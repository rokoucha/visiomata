@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Media3 [Extractor] for ARIB STD-B60 MMT/TLV broadcast streams (4K/8K satellite).
 *
 * Bytes flow through the streaming stages: [TlvDemuxer] frames TLV packets, [MmtDepacketizer]
 * reassembles MFUs and emits timestamped access units, and this extractor publishes them as
 * Media3 tracks. Video access units are Annex-B HEVC (passed through like the TS path passes
 * Annex-B AVC); audio MFUs are LATM AudioMuxElements unwrapped to raw AAC by [MmtLatmParser].
 *
 * Tracks are registered from the first package map (MPT): like `TsExtractor.MODE_SINGLE_PMT`,
 * assets that appear only in later package versions are ignored, since tracks cannot be added
 * after [ExtractorOutput.endTracks]. Sample timestamps count microseconds since the NTP epoch, so
 * the first sample's PTS anchors the Media3 timeline at zero.
 */
internal class TlvMmtExtractor : Extractor {
    val videoSamplesWritten: Long
        get() = writtenVideoSamples
    val audioSamplesWritten: Long
        get() = writtenAudioSamples
    val samplesDropped: Long
        get() = droppedSamples
    val packagesSeen: Long
        get() = seenPackages

    private var writtenVideoSamples = 0L
    private var writtenAudioSamples = 0L
    private var droppedSamples = 0L
    private var seenPackages = 0L

    private val depacketizer = MmtDepacketizer(::onSample)
    private val demuxer = TlvDemuxer(depacketizer::pushTlv)
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)
    private val videoTracks = mutableMapOf<Int, TrackOutput>()
    private val videoConfigParsers = mutableMapOf<Int, MmtHevcConfigParser>()
    private val audioParsers = mutableMapOf<Int, MmtLatmParser>()
    private var output: ExtractorOutput? = null
    private var tracksEnded = false
    private var lastPackage: MmtPackageInfo? = null
    private var firstPtsUs: Long? = null

    override fun sniff(input: ExtractorInput): Boolean {
        var buffer = ByteArray(INITIAL_SNIFF_BYTES)
        var peeked = ExtractorUtil.peekToLength(input, buffer, 0, buffer.size)
        while (true) {
            val sync = findTlvChain(buffer, peeked)
            if (sync >= 0) {
                input.skipFully(sync)
                return true
            }
            if (peeked < buffer.size || buffer.size >= MAX_SNIFF_BYTES) return false
            val grown = buffer.copyOf(buffer.size * 2)
            val extra = ExtractorUtil.peekToLength(input, grown, peeked, grown.size - peeked)
            if (extra == 0) return false
            buffer = grown
            peeked += extra
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int {
        val bytesRead = input.read(readBuffer, 0, readBuffer.size)
        if (bytesRead == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        if (bytesRead > 0) {
            demuxer.push(readBuffer, 0, bytesRead)
            pollPackage()
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(
        position: Long,
        timeUs: Long,
    ) {
        demuxer.reset()
        depacketizer.reset()
        videoConfigParsers.values.forEach(MmtHevcConfigParser::reset)
        audioParsers.values.forEach(MmtLatmParser::reset)
        lastPackage = null
        firstPtsUs = null
    }

    override fun release() = Unit

    private fun pollPackage() {
        val packageInfo = depacketizer.currentPackage ?: return
        if (packageInfo === lastPackage) return
        lastPackage = packageInfo
        seenPackages++
        if (tracksEnded) return
        val output = checkNotNull(output)
        packageInfo.assets.forEach { asset ->
            when (asset.assetType) {
                MmtAssetType.HEVC_VIDEO -> {
                    if (asset.packetId !in videoTracks) {
                        // The format is withheld until VPS/SPS/PPS arrive (see onSample):
                        // MediaCodec.configure rejects a video format without width, height
                        // and codec-specific data. Like TsExtractor's H265Reader, samples
                        // before the first parameter sets are dropped.
                        videoTracks[asset.packetId] = output.track(asset.packetId, C.TRACK_TYPE_VIDEO)
                        videoConfigParsers[asset.packetId] =
                            MmtHevcConfigParser(videoFormatId(asset.packetId))
                    }
                }

                MmtAssetType.AAC_AUDIO -> {
                    if (asset.packetId !in audioParsers) {
                        val track = output.track(asset.packetId, C.TRACK_TYPE_AUDIO)
                        audioParsers[asset.packetId] = MmtLatmParser(track, audioFormatId(asset.packetId))
                    }
                }

                else -> {
                    // Caption, data-broadcast and download assets have no Media3 track.
                }
            }
        }
        if (videoTracks.isNotEmpty() || audioParsers.isNotEmpty()) {
            tracksEnded = true
            output.endTracks()
        }
    }

    private fun onSample(sample: MmtSample) {
        // Samples arrive synchronously inside demuxer.push, potentially many per read, so the
        // package must be polled per sample: polling only per read would register tracks after
        // the first samples were already (mis)routed.
        pollPackage()
        when (sample.kind) {
            MmtTrackKind.VIDEO_HEVC -> {
                val track = videoTracks[sample.packetId] ?: return dropSample()
                val config = videoConfigParsers[sample.packetId] ?: return dropSample()
                if (config.format == null) {
                    val format = config.consume(sample.data) ?: return dropSample()
                    track.format(format)
                }
                val timeUs = normalize(sample.ptsUs)
                track.sampleData(ParsableByteArray(sample.data), sample.data.size)
                val flags = if (sample.isSyncFrame) C.BUFFER_FLAG_KEY_FRAME else 0
                track.sampleMetadata(timeUs, flags, sample.data.size, 0, null)
                writtenVideoSamples++
            }

            MmtTrackKind.AUDIO_AAC -> {
                val parser = audioParsers[sample.packetId] ?: return dropSample()
                val frame = parser.parse(sample.data) ?: return dropSample()
                parser.writeSample(frame, normalize(sample.ptsUs))
                writtenAudioSamples++
            }
        }
    }

    private fun normalize(ptsUs: Long): Long {
        val anchor = firstPtsUs ?: ptsUs.also { firstPtsUs = it }
        return (ptsUs - anchor).coerceAtLeast(0)
    }

    private fun dropSample() {
        droppedSamples++
    }

    private companion object {
        const val READ_BUFFER_SIZE = 16 * 1024
        const val INITIAL_SNIFF_BYTES = 16 * 1024
        const val MAX_SNIFF_BYTES = 256 * 1024
        const val SNIFF_CHAIN_PACKETS = 3
        const val TLV_SYNC_BYTE = 0x7F
        const val TLV_HEADER_SIZE = 4

        fun videoFormatId(packetId: Int): String = "tlv-video/$packetId"

        fun audioFormatId(packetId: Int): String = "tlv-audio/$packetId"

        /**
         * Returns the offset of the first TLV packet starting a chain of [SNIFF_CHAIN_PACKETS]
         * consecutive fully-framed packets, or -1. Every link must carry a known packet type and
         * land its successor exactly on a sync byte, which random TS bytes cannot do.
         */
        fun findTlvChain(
            buffer: ByteArray,
            length: Int,
        ): Int {
            var candidate = 0
            while (candidate + TLV_HEADER_SIZE <= length) {
                if ((buffer[candidate].toInt() and 0xff) != TLV_SYNC_BYTE) {
                    candidate++
                    continue
                }
                if (isChained(buffer, length, candidate)) return candidate
                candidate++
            }
            return -1
        }

        private fun isChained(
            buffer: ByteArray,
            length: Int,
            start: Int,
        ): Boolean {
            var position = start
            repeat(SNIFF_CHAIN_PACKETS) {
                if (position + TLV_HEADER_SIZE > length) return false
                if ((buffer[position].toInt() and 0xff) != TLV_SYNC_BYTE) return false
                val packetType = buffer[position + 1].toInt() and 0xff
                if (!TlvPacketType.isKnown(packetType)) return false
                val dataLength = u16(buffer, position + 2)
                if (position + TLV_HEADER_SIZE + dataLength > length) return false
                position += TLV_HEADER_SIZE + dataLength
            }
            return true
        }
    }
}
