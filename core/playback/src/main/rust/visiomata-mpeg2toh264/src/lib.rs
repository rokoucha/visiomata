use mpeg2toh264::container::adts::{AacConfig, AdtsStream};
use mpeg2toh264::container::fmp4::{mpeg2_sample_timing, UnitLeadIn};
use mpeg2toh264::mpeg2::gop_stream::{Mpeg2Gop, Mpeg2GopStream};
use mpeg2toh264::mpeg2::headers::stream_sequence_description;
use mpeg2toh264::transcode::Step;
use mpeg2toh264::{
    mpeg2_video_timeline, DualMono, IncrementalTranscoder, PictureEncoder, PictureOutput,
    TranscodeOptions, TranscodeResult,
};
use std::ffi::c_void;
use std::sync::{mpsc, Arc, Mutex};

const OUTPUT_VERSION: u32 = 2;
const TIMESCALE: i64 = 90_000;
const FLAG_KEY_FRAME: u32 = 1;
const FLAG_INTERLACED: u32 = 1 << 1;
const FLAG_TOP_FIELD_FIRST: u32 = 1 << 2;
const FLAG_SINGLE_FIELD: u32 = 1 << 3;
const AAC_OUTPUT_VERSION: u32 = 1;

struct AacSession {
    main: AdtsStream,
    sub: AdtsStream,
}

impl AacSession {
    fn new() -> Self {
        let main = AdtsStream::new();
        let mut sub = AdtsStream::new();
        sub.select_dual_mono(DualMono::Sub);
        Self { main, sub }
    }

    fn push(&mut self, input: &[u8], finish: bool) -> Result<Vec<NormalizedAacFrame>, String> {
        let main = if finish {
            let mut frames = self.main.push(input).map_err(|error| error.to_string())?;
            frames.extend(self.main.finish().map_err(|error| error.to_string())?);
            frames
        } else {
            self.main.push(input).map_err(|error| error.to_string())?
        };
        let sub = if finish {
            let mut frames = self.sub.push(input).map_err(|error| error.to_string())?;
            frames.extend(self.sub.finish().map_err(|error| error.to_string())?);
            frames
        } else {
            self.sub.push(input).map_err(|error| error.to_string())?
        };
        if main.len() != sub.len() {
            return Err("AAC main/sub normalizers produced different frame counts".into());
        }
        Ok(main
            .into_iter()
            .zip(sub)
            .map(|(main, sub)| NormalizedAacFrame {
                config: main.config,
                main: main.data,
                sub: main.is_dual_mono.then_some(sub.data),
            })
            .collect())
    }
}

struct NormalizedAacFrame {
    config: AacConfig,
    main: Vec<u8>,
    sub: Option<Vec<u8>>,
}

struct Session {
    gops: Mpeg2GopStream,
    transcoder: IncrementalTranscoder,
    encoders: EncoderPool,
    next_pts_us: Option<i64>,
}

impl Session {
    fn new() -> Self {
        Self {
            gops: Mpeg2GopStream::new(),
            transcoder: IncrementalTranscoder::new(TranscodeOptions::default()),
            encoders: EncoderPool::new(),
            next_pts_us: None,
        }
    }

    fn push(
        &mut self,
        input: &[u8],
        pts_us: Option<i64>,
        finish: bool,
    ) -> Result<Vec<AccessUnit>, String> {
        let pts_mark = pts_us.and_then(|value| u64::try_from(value).ok());
        let mut gops = self.gops.push(input, pts_mark);
        if finish {
            gops.extend(self.gops.finish());
        }
        let mut output = Vec::new();
        for gop in gops {
            output.extend(self.transcode_gop(gop)?);
        }
        Ok(output)
    }

    fn transcode_gop(&mut self, gop: Mpeg2Gop) -> Result<Vec<AccessUnit>, String> {
        let has_references = !self.transcoder.awaiting_random_access();
        let result = transcode_parallel(&mut self.transcoder, &self.encoders, &gop.data)?;
        let mut timeline = mpeg2_video_timeline(&gop.data, has_references, &result.undecodable)
            .map_err(|error| error.to_string())?;
        timeline.split_field_samples = self.transcoder.split_field_samples();

        let samples = split_access_units(&result.bitstream)?;
        let content_count = timeline.presentation_indices.len()
            + if timeline.split_field_samples {
                timeline.field_pairs.iter().filter(|&&pair| pair).count()
            } else {
                0
            };
        let lead_in = match samples.len().checked_sub(content_count) {
            Some(0) => UnitLeadIn::None,
            Some(1) => UnitLeadIn::IdrClone,
            _ => {
                return Err(format!(
                    "H.264 access-unit count {} does not match MPEG-2 timeline {}",
                    samples.len(),
                    content_count
                ))
            }
        };
        let timing = mpeg2_sample_timing(&timeline, lead_in);
        if timing.durations.len() != samples.len() || timing.compositions.len() != samples.len() {
            return Err("H.264 timing count does not match access units".into());
        }

        let first_display_tick = timeline
            .presentation_times
            .iter()
            .copied()
            .min()
            .unwrap_or(0) as i64;
        let first_coded_tick = timeline.presentation_times.first().copied().unwrap_or(0) as i64;
        let source_pts_us = gop
            .pts
            .map(|value| value as i64)
            .or(self.next_pts_us)
            .unwrap_or(0);
        let presentation_start_us =
            source_pts_us + ticks_to_us(first_display_tick - first_coded_tick);
        let aspect = timeline.sample_aspect_ratio.unwrap_or(
            mpeg2toh264::mpeg2::headers::SampleAspectRatio {
                width: 1,
                height: 1,
            },
        );
        // A 1080i service can carry telecined or standards-converted archive material whose
        // picture-level progressive_frame bit is set even though the decoded frame is visibly
        // woven.  The H.264 stream is MBAFF whenever the MPEG-2 sequence is interlaced, so use
        // that sequence-level signal as the deinterlacing floor.  Truly progressive sequences
        // still retain the finer per-picture decision below.
        let interlaced_sequence =
            stream_sequence_description(&gop.data).is_some_and(|description| description.mbaff);

        let mut field_flags = Vec::with_capacity(samples.len());
        for (index, scan) in timeline.sample_scans.iter().enumerate() {
            let mut flags = if interlaced_sequence || scan.interlaced {
                FLAG_INTERLACED
            } else {
                0
            };
            if scan.top_field_first {
                flags |= FLAG_TOP_FIELD_FIRST;
            }
            if timeline.field_pairs.get(index).copied().unwrap_or(false)
                && timeline.split_field_samples
            {
                field_flags.push(flags | FLAG_SINGLE_FIELD);
                field_flags.push((flags ^ FLAG_TOP_FIELD_FIRST) | FLAG_SINGLE_FIELD);
            } else {
                field_flags.push(flags);
            }
        }
        if matches!(lead_in, UnitLeadIn::IdrClone) {
            field_flags.insert(0, field_flags.first().copied().unwrap_or(0));
        }
        if field_flags.len() != samples.len() {
            return Err("H.264 field metadata count does not match access units".into());
        }

        let mut decode_tick = -timing.reorder_delay;
        let mut output = Vec::with_capacity(samples.len());
        for (index, data) in samples.into_iter().enumerate() {
            let presentation_tick = decode_tick + i64::from(timing.compositions[index]);
            output.push(AccessUnit {
                pts_us: presentation_start_us + ticks_to_us(presentation_tick),
                duration_us: ticks_to_us(i64::from(timing.durations[index])),
                flags: field_flags[index]
                    | if contains_nal_type(&data, 5) {
                        FLAG_KEY_FRAME
                    } else {
                        0
                    },
                width: timeline.width,
                height: timeline.height,
                pixel_aspect_width: aspect.width,
                pixel_aspect_height: aspect.height,
                data,
            });
            decode_tick += i64::from(timing.durations[index]);
        }
        self.next_pts_us =
            Some(presentation_start_us + ticks_to_us(decode_tick + timing.reorder_delay));
        Ok(output)
    }
}

type Work = (usize, Vec<u8>);
type Completed = (usize, mpeg2toh264::Result<PictureOutput>);

/// A shared queue balances uneven picture jobs within a broadcast GOP.
struct EncoderPool {
    send_work: Option<mpsc::Sender<Work>>,
    take_done: mpsc::Receiver<Completed>,
    threads: Vec<std::thread::JoinHandle<()>>,
}

impl EncoderPool {
    fn new() -> Self {
        let parallelism = std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(2)
            .clamp(2, 4);
        let (send_work, take_work) = mpsc::channel::<Work>();
        let (send_done, take_done) = mpsc::channel::<Completed>();
        let take_work = Arc::new(Mutex::new(take_work));
        let threads = (0..parallelism)
            .map(|index| {
                let take_work = Arc::clone(&take_work);
                let send_done = send_done.clone();
                std::thread::Builder::new()
                    .name(format!("mpeg2toh264-{index}"))
                    .spawn(move || {
                        let mut encoder = PictureEncoder::new();
                        loop {
                            let job = take_work.lock().expect("work queue poisoned").recv();
                            let Ok((job_index, job)) = job else { return };
                            if send_done.send((job_index, encoder.encode(&job))).is_err() {
                                return;
                            }
                        }
                    })
                    .expect("create MPEG-2 transcoder worker")
            })
            .collect();
        Self {
            send_work: Some(send_work),
            take_done,
            threads,
        }
    }

    fn run(&self, jobs: Vec<Vec<u8>>) -> Result<Vec<PictureOutput>, String> {
        let count = jobs.len();
        let sender = self
            .send_work
            .as_ref()
            .ok_or_else(|| "picture encoder pool is closed".to_string())?;
        for (index, job) in jobs.into_iter().enumerate() {
            sender
                .send((index, job))
                .map_err(|_| "picture encoder worker stopped".to_string())?;
        }
        let mut ordered: Vec<Option<PictureOutput>> = (0..count).map(|_| None).collect();
        for _ in 0..count {
            let (index, result) = self
                .take_done
                .recv()
                .map_err(|_| "picture encoder worker stopped".to_string())?;
            ordered[index] = Some(result.map_err(|error| error.to_string())?);
        }
        ordered
            .into_iter()
            .map(|item| item.ok_or_else(|| "picture encoder result is missing".to_string()))
            .collect()
    }
}

impl Drop for EncoderPool {
    fn drop(&mut self) {
        self.send_work = None;
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
    }
}

fn transcode_parallel(
    transcoder: &mut IncrementalTranscoder,
    encoders: &EncoderPool,
    data: &[u8],
) -> Result<TranscodeResult, String> {
    let mut jobs = transcoder.begin(data).map_err(|error| error.to_string())?;
    loop {
        let outputs = encoders.run(jobs)?;
        match transcoder
            .complete(data, &outputs)
            .map_err(|error| error.to_string())?
        {
            Step::Done(result) => return Ok(*result),
            Step::Again(next_jobs) => jobs = next_jobs,
        }
    }
}

struct AccessUnit {
    pts_us: i64,
    duration_us: i64,
    flags: u32,
    width: u32,
    height: u32,
    pixel_aspect_width: u32,
    pixel_aspect_height: u32,
    data: Vec<u8>,
}

fn ticks_to_us(ticks: i64) -> i64 {
    ticks.saturating_mul(1_000_000) / TIMESCALE
}

fn annex_b_nals(data: &[u8]) -> Vec<(usize, usize, u8)> {
    let mut starts = Vec::new();
    let mut index = 0;
    while index + 3 < data.len() {
        let start_code_len = if data[index..].starts_with(&[0, 0, 1]) {
            3
        } else if data[index..].starts_with(&[0, 0, 0, 1]) {
            4
        } else {
            index += 1;
            continue;
        };
        let header = index + start_code_len;
        if header < data.len() {
            starts.push((index, start_code_len, data[header] & 0x1f));
        }
        index = header + 1;
    }
    starts
}

fn split_access_units(data: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    let nals = annex_b_nals(data);
    let auds: Vec<usize> = nals
        .iter()
        .filter(|nal| nal.2 == 9)
        .map(|nal| nal.0)
        .collect();
    let boundaries = if auds.is_empty() {
        // Older/upstream-compatible output may omit AUDs and emits one VCL NAL per picture.
        // Keep parameter sets and SEI with the VCL unit immediately following them.
        let vcl_indices: Vec<usize> = nals
            .iter()
            .enumerate()
            .filter(|(_, nal)| matches!(nal.2, 1 | 5))
            .map(|(index, _)| index)
            .collect();
        if vcl_indices.is_empty() {
            return Err("transcoder output contains no H.264 pictures".into());
        }
        let mut boundaries = vec![0];
        for pair in vcl_indices.windows(2) {
            boundaries.push(nals[pair[0] + 1].0);
        }
        boundaries
    } else {
        auds
    };
    let mut output = Vec::with_capacity(boundaries.len());
    for (index, &start) in boundaries.iter().enumerate() {
        let end = boundaries.get(index + 1).copied().unwrap_or(data.len());
        if end > start {
            output.push(data[start..end].to_vec());
        }
    }
    Ok(output)
}

fn contains_nal_type(data: &[u8], expected: u8) -> bool {
    annex_b_nals(data).iter().any(|nal| nal.2 == expected)
}

fn put_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_i64(output: &mut Vec<u8>, value: i64) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn encode_result(result: Result<Vec<AccessUnit>, String>) -> Vec<u8> {
    let mut output = Vec::new();
    put_u32(&mut output, OUTPUT_VERSION);
    match result {
        Ok(units) => {
            put_u32(&mut output, 0);
            put_u32(&mut output, units.len() as u32);
            for unit in units {
                put_i64(&mut output, unit.pts_us);
                put_i64(&mut output, unit.duration_us);
                put_u32(&mut output, unit.flags);
                put_u32(&mut output, unit.width);
                put_u32(&mut output, unit.height);
                put_u32(&mut output, unit.pixel_aspect_width);
                put_u32(&mut output, unit.pixel_aspect_height);
                put_u32(&mut output, unit.data.len() as u32);
                output.extend_from_slice(&unit.data);
            }
        }
        Err(error) => {
            put_u32(&mut output, 1);
            put_u32(&mut output, error.len() as u32);
            output.extend_from_slice(error.as_bytes());
        }
    }
    output
}

fn encode_aac_result(result: Result<Vec<NormalizedAacFrame>, String>) -> Vec<u8> {
    let mut output = Vec::new();
    put_u32(&mut output, AAC_OUTPUT_VERSION);
    match result {
        Ok(frames) => {
            put_u32(&mut output, 0);
            put_u32(&mut output, frames.len() as u32);
            for frame in frames {
                put_u32(&mut output, frame.config.sample_rate);
                put_u32(&mut output, frame.config.channel_count as u32);
                put_u32(&mut output, frame.config.audio_specific_config.len() as u32);
                output.extend_from_slice(&frame.config.audio_specific_config);
                put_u32(&mut output, frame.main.len() as u32);
                output.extend_from_slice(&frame.main);
                if let Some(sub) = frame.sub {
                    put_u32(&mut output, sub.len() as u32);
                    output.extend_from_slice(&sub);
                } else {
                    put_u32(&mut output, 0);
                }
            }
        }
        Err(error) => {
            put_u32(&mut output, 1);
            put_u32(&mut output, error.len() as u32);
            output.extend_from_slice(error.as_bytes());
        }
    }
    output
}

#[repr(C)]
pub struct NativeBuffer {
    data: *mut u8,
    len: usize,
}

impl NativeBuffer {
    fn from_vec(value: Vec<u8>) -> Self {
        let mut value = value.into_boxed_slice();
        let result = Self {
            data: value.as_mut_ptr(),
            len: value.len(),
        };
        std::mem::forget(value);
        result
    }
}

#[no_mangle]
pub extern "C" fn visiomata_mpeg2_create() -> *mut c_void {
    Box::into_raw(Box::new(Session::new())).cast()
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_mpeg2_destroy(handle: *mut c_void) {
    if !handle.is_null() {
        drop(Box::from_raw(handle.cast::<Session>()));
    }
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_mpeg2_reset(handle: *mut c_void) {
    if let Some(session) = handle.cast::<Session>().as_mut() {
        *session = Session::new();
    }
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_mpeg2_push(
    handle: *mut c_void,
    data: *const u8,
    len: usize,
    pts_us: i64,
    has_pts: bool,
    finish: bool,
) -> NativeBuffer {
    let result = match handle.cast::<Session>().as_mut() {
        Some(session) => {
            let input = if len == 0 {
                &[]
            } else {
                std::slice::from_raw_parts(data, len)
            };
            session.push(input, has_pts.then_some(pts_us), finish)
        }
        None => Err("native transcoder session is null".into()),
    };
    NativeBuffer::from_vec(encode_result(result))
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_mpeg2_free(buffer: NativeBuffer) {
    if !buffer.data.is_null() {
        drop(Box::from_raw(std::slice::from_raw_parts_mut(
            buffer.data,
            buffer.len,
        )));
    }
}

#[no_mangle]
pub extern "C" fn visiomata_aac_create() -> *mut c_void {
    Box::into_raw(Box::new(AacSession::new())).cast()
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_aac_destroy(handle: *mut c_void) {
    if !handle.is_null() {
        drop(Box::from_raw(handle.cast::<AacSession>()));
    }
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_aac_reset(handle: *mut c_void) {
    if let Some(session) = handle.cast::<AacSession>().as_mut() {
        *session = AacSession::new();
    }
}

#[no_mangle]
pub unsafe extern "C" fn visiomata_aac_push(
    handle: *mut c_void,
    data: *const u8,
    len: usize,
    finish: bool,
) -> NativeBuffer {
    let result = match handle.cast::<AacSession>().as_mut() {
        Some(session) => {
            let input = if len == 0 {
                &[]
            } else {
                std::slice::from_raw_parts(data, len)
            };
            session.push(input, finish)
        }
        None => Err("native AAC normalizer session is null".into()),
    };
    NativeBuffer::from_vec(encode_aac_result(result))
}

#[cfg(test)]
mod tests {
    use super::*;
    use mpeg2toh264::container::adts::silent_frame;

    fn adts_frame(config: &AacConfig, payload: &[u8]) -> Vec<u8> {
        let length = 7 + payload.len();
        let channels = config.channel_count;
        let mut frame = vec![
            0xff,
            0xf1,
            (1 << 6) | (config.sampling_frequency_index << 2) | (channels >> 2),
            ((channels & 3) << 6) | ((length >> 11) as u8 & 3),
            (length >> 3) as u8,
            ((length & 7) as u8) << 5 | 0x1f,
            0xfc,
        ];
        frame.extend_from_slice(payload);
        frame
    }

    #[test]
    fn aac_bridge_turns_mono_into_one_stereo_track() {
        let config = AacConfig {
            audio_object_type: 2,
            sample_rate: 48_000,
            sampling_frequency_index: 3,
            channel_count: 1,
            audio_specific_config: vec![0x11, 0x88],
        };
        let source = silent_frame(&config).expect("make silent mono frame");
        let mut session = AacSession::new();
        let frames = session
            .push(&adts_frame(&config, &source.data), true)
            .expect("normalize mono frame");

        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].config.channel_count, 2);
        assert_eq!(frames[0].config.audio_specific_config, [0x11, 0x90]);
        assert!(!frames[0].main.is_empty());
        assert!(frames[0].sub.is_none());
    }

    #[test]
    fn incremental_bridge_emits_timed_h264_access_units() {
        let fixtures: &[&[u8]] = &[
            include_bytes!("../../testdata/i_only.m2v"),
            include_bytes!("../../testdata/ibbp.m2v"),
            include_bytes!("../../testdata/hd1080i.m2v"),
            include_bytes!("../../testdata/open_gop_leading_bb.m2v"),
        ];
        for input in fixtures {
            let mut session = Session::new();
            let mut units = Vec::new();
            for (index, chunk) in input.chunks(4096).enumerate() {
                units.extend(
                    session
                        .push(chunk, (index == 0).then_some(1_000_000), false)
                        .expect("transcode chunk"),
                );
            }
            units.extend(session.push(&[], None, true).expect("finish transcode"));

            assert!(!units.is_empty());
            assert!(units.iter().any(|unit| unit.flags & FLAG_KEY_FRAME != 0));
            assert!(units.iter().all(|unit| unit.duration_us > 0));
            assert!(units.iter().all(|unit| unit.width > 0 && unit.height > 0));
            assert!(units
                .windows(2)
                .any(|pair| pair[0].pts_us != pair[1].pts_us));
        }
    }

    #[test]
    fn bridge_preserves_interlaced_field_metadata_for_deinterlacing() {
        let mut session = Session::new();
        let units = session
            .push(
                include_bytes!("../../testdata/hd1080i.m2v"),
                Some(1_000_000),
                true,
            )
            .expect("transcode interlaced fixture");

        assert!(!units.is_empty());
        assert!(units.iter().all(|unit| unit.flags & FLAG_INTERLACED != 0));
        assert!(units
            .iter()
            .any(|unit| unit.flags & FLAG_TOP_FIELD_FIRST != 0));
    }
}
