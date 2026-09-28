package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

internal class FakeExtractorInput(
    private val data: ByteArray,
) : ExtractorInput {
    private var readPosition = 0
    private var peekPosition = 0

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (readPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - readPosition)
        data.copyInto(buffer, offset, readPosition, readPosition + count)
        readPosition += count
        peekPosition = readPosition
        return count
    }

    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (data.size - readPosition < length) {
            if (allowEndOfInput && readPosition == data.size) return false
            throw EOFException()
        }
        data.copyInto(target, offset, readPosition, readPosition + length)
        readPosition += length
        peekPosition = readPosition
        return true
    }

    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
    ) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val count = minOf(length, data.size - readPosition)
        readPosition += count
        peekPosition = readPosition
        return count
    }

    override fun skipFully(
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (data.size - readPosition < length) {
            if (allowEndOfInput && readPosition == data.size) return false
            throw EOFException()
        }
        readPosition += length
        peekPosition = readPosition
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (peekPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - peekPosition)
        data.copyInto(target, offset, peekPosition, peekPosition + count)
        peekPosition += count
        return count
    }

    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (data.size - peekPosition < length) {
            if (allowEndOfInput && peekPosition == data.size) return false
            throw EOFException()
        }
        data.copyInto(target, offset, peekPosition, peekPosition + length)
        peekPosition += length
        return true
    }

    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
    ) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (data.size - peekPosition < length) {
            if (allowEndOfInput && peekPosition == data.size) return false
            throw EOFException()
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekPosition = readPosition
    }

    override fun getPeekPosition(): Long = peekPosition.toLong()

    override fun getPosition(): Long = readPosition.toLong()

    override fun getLength(): Long = data.size.toLong()

    override fun <E : Throwable> setRetryPosition(
        position: Long,
        e: E,
    ): Unit = throw e
}

internal class FakeExtractorOutput : ExtractorOutput {
    val tracks = mutableMapOf<Int, RecordingTrackOutput>()
    var seekMap: SeekMap? = null
        private set
    var endTracksCalled = false
        private set

    override fun track(
        id: Int,
        type: Int,
    ): TrackOutput = RecordingTrackOutput().also { tracks[id] = it }

    override fun endTracks() {
        endTracksCalled = true
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }
}

class TlvMmtExtractorTest {
    private val videoPid = 0xf300
    private val audioPid = 0xf310
    private val videoNtp = 0xee5e4279L shl 32
    private val audioNtp = (0xee5e4279L shl 32) or 0x40000000L
    private val videoBaseUs = ntpToUs(videoNtp)
    private val audioBaseUs = ntpToUs(audioNtp)
    private val audioPayload = bytes(0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c)

    private fun videoAsset() =
        TestAsset(
            packetId = videoPid,
            assetType = MmtAssetType.HEVC_VIDEO,
            presentationTimes = mapOf(100L to videoNtp),
            timescale = 90000,
            defaultInterval = 3000,
            mpuTimings =
                mapOf(
                    100L to TestMpuTiming(decodingOffset = 6000, dtsPtsOffsets = intArrayOf(6000)),
                ),
        )

    private fun audioAsset() =
        TestAsset(
            packetId = audioPid,
            assetType = MmtAssetType.AAC_AUDIO,
            presentationTimes = mapOf(50L to audioNtp),
            timescale = 48000,
            defaultInterval = 1024,
            mpuTimings =
                mapOf(
                    50L to TestMpuTiming(decodingOffset = 0, dtsPtsOffsets = intArrayOf(0, 0)),
                ),
        )

    private fun packageTlv(vararg assets: TestAsset): ByteArray {
        val message = paMessage(assets.toList())
        val payload = signalingPayload(MmtFragmentationIndicator.NOT_FRAGMENTED, message)
        return compressedIpTlv(mmtPacket(MmtPayloadType.SIGNALING, 0x0000, 77L, payload))
    }

    private fun videoTlv(
        sequenceNumber: Long,
        payload: ByteArray,
    ): ByteArray = compressedIpTlv(mmtPacket(MmtPayloadType.MPU, videoPid, sequenceNumber, payload))

    private fun audioTlv(
        sequenceNumber: Long,
        payload: ByteArray,
    ): ByteArray = compressedIpTlv(mmtPacket(MmtPayloadType.MPU, audioPid, sequenceNumber, payload))

    private fun drain(
        extractor: TlvMmtExtractor,
        input: FakeExtractorInput,
    ) {
        val holder = PositionHolder()
        repeat(100) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) return
            assertEquals(Extractor.RESULT_CONTINUE, result)
        }
        error("extractor did not consume the input")
    }

    private fun audioFrameTlv(
        sequenceNumber: Long,
        useSameStreamMux: Boolean,
    ): ByteArray =
        audioTlv(
            sequenceNumber,
            singleMfuPayload(
                50L,
                MmtFragmentationIndicator.NOT_FRAGMENTED,
                latmAudioMuxElement(audioPayload, useSameStreamMux = useSameStreamMux),
            ),
        )

    @Test
    fun sniffAcceptsTlvStreamStartingMidPacket() {
        val garbage = ByteArray(500)
        garbage[100] = 0x7f // Decoy sync followed by an unassigned packet type.
        garbage[101] = 0x99.toByte()
        val stream =
            garbage +
                tlvPacket(TlvPacketType.NULL, ByteArray(0)) +
                tlvPacket(TlvPacketType.COMPRESSED_IP, ByteArray(16)) +
                tlvPacket(TlvPacketType.CONTROL, ByteArray(8)) +
                tlvPacket(TlvPacketType.IPV4, ByteArray(32))
        val input = FakeExtractorInput(stream)

        assertTrue(TlvMmtExtractor().sniff(input))
        assertEquals(garbage.size.toLong(), input.position)
    }

    @Test
    fun sniffRejectsMpeg2TsStream() {
        val stream =
            ByteArray(188 * 6) { index ->
                if (index % 188 == 0) {
                    0x47.toByte()
                } else {
                    val value = index % 255
                    (if (value >= 0x7f) value + 1 else value).toByte()
                }
            }
        val input = FakeExtractorInput(stream)

        assertFalse(TlvMmtExtractor().sniff(input))
        assertEquals(0L, input.position)
    }

    @Test
    fun sniffRejectsGarbageAndShortInput() {
        assertFalse(TlvMmtExtractor().sniff(FakeExtractorInput(ByteArray(0))))
        assertFalse(TlvMmtExtractor().sniff(FakeExtractorInput(bytes(0x7f, 0x03, 0x00))))
        assertFalse(
            TlvMmtExtractor().sniff(
                FakeExtractorInput(tlvPacket(TlvPacketType.COMPRESSED_IP, ByteArray(16))),
            ),
        )
    }

    @Test
    fun sniffRequiresThreeChainedPackets() {
        val twoPackets =
            tlvPacket(TlvPacketType.NULL, ByteArray(0)) +
                tlvPacket(TlvPacketType.CONTROL, ByteArray(8))
        assertFalse(TlvMmtExtractor().sniff(FakeExtractorInput(twoPackets)))

        val brokenChain =
            tlvPacket(TlvPacketType.NULL, ByteArray(0)) +
                bytes(0x7f, 0x99, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04) +
                tlvPacket(TlvPacketType.CONTROL, ByteArray(8))
        assertFalse(TlvMmtExtractor().sniff(FakeExtractorInput(brokenChain)))
    }

    @Test
    fun readExtractsVideoAndAudioSamples() {
        val aud = nal(HevcNalType.AUD, 0x50)
        val cra = nal(21, 0x01, 0x02, 0x03)
        val stream =
            packageTlv(videoAsset(), audioAsset()) +
                tlvPacket(TlvPacketType.NULL, ByteArray(0)) +
                videoTlv(
                    1000L,
                    aggregatedMfuPayload(
                        100L,
                        lengthPrefixed(aud),
                        *hevcParamSetMfus(),
                        lengthPrefixed(cra),
                    ),
                ) +
                videoTlv(1001L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud))) +
                audioFrameTlv(500L, useSameStreamMux = false) +
                audioFrameTlv(501L, useSameStreamMux = true)
        val extractor = TlvMmtExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        drain(extractor, FakeExtractorInput(stream))

        val seekMap = output.seekMap ?: error("seekMap must be emitted")
        assertFalse(seekMap.isSeekable)
        assertTrue(output.endTracksCalled)
        assertEquals(setOf(videoPid, audioPid), output.tracks.keys)

        val videoFormat =
            output.tracks
                .getValue(videoPid)
                .formats
                .single()
        assertEquals("tlv-video/$videoPid", videoFormat.id)
        assertEquals(MimeTypes.VIDEO_H265, videoFormat.sampleMimeType)
        assertEquals(3840, videoFormat.width)
        assertEquals(2160, videoFormat.height)
        assertEquals(1, videoFormat.initializationData.size)

        val audioTrack = output.tracks.getValue(audioPid)
        val audioFormat = audioTrack.formats.single()
        assertEquals("tlv-audio/$audioPid", audioFormat.id)
        assertEquals(MimeTypes.AUDIO_AAC, audioFormat.sampleMimeType)
        assertEquals(48000, audioFormat.sampleRate)
        assertEquals(2, audioFormat.channelCount)
        assertArrayEquals(bytes(0x11, 0x90), audioFormat.initializationData[0])

        val videoSamples = output.tracks.getValue(videoPid).samples
        assertEquals(1, videoSamples.size)
        assertEquals(0L, videoSamples[0].timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, videoSamples[0].flags)
        assertArrayEquals(annexB(aud, HEVC_VPS, HEVC_SPS, HEVC_PPS, cra), videoSamples[0].bytes)

        // The video access unit anchors the timeline; audio follows 250ms later.
        assertEquals(audioBaseUs - videoBaseUs, 250_000L)
        assertEquals(2, audioTrack.samples.size)
        assertEquals(250_000L, audioTrack.samples[0].timeUs)
        assertEquals(250_000L + 1024L * 1_000_000 / 48000, audioTrack.samples[1].timeUs)
        assertArrayEquals(audioPayload, audioTrack.samples[0].bytes)
        assertArrayEquals(audioPayload, audioTrack.samples[1].bytes)

        assertEquals(1L, extractor.videoSamplesWritten)
        assertEquals(2L, extractor.audioSamplesWritten)
        assertEquals(0L, extractor.samplesDropped)
        assertEquals(1L, extractor.packagesSeen)
    }

    @Test
    fun withholdsVideoFormatUntilParameterSets() {
        val aud = nal(HevcNalType.AUD, 0x50)
        val stream =
            packageTlv(videoAsset()) +
                videoTlv(1000L, aggregatedMfuPayload(100L, lengthPrefixed(aud))) +
                videoTlv(1001L, aggregatedMfuPayload(100L, lengthPrefixed(aud), *hevcParamSetMfus())) +
                videoTlv(1002L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud)))
        val extractor = TlvMmtExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        drain(extractor, FakeExtractorInput(stream))

        // The first access unit carries no VPS/SPS/PPS, so it is dropped and the
        // second one both registers the format and is written.
        val videoTrack = output.tracks.getValue(videoPid)
        assertEquals(3840, videoTrack.formats.single().width)
        assertEquals(1, videoTrack.samples.size)
        assertArrayEquals(
            annexB(aud, HEVC_VPS, HEVC_SPS, HEVC_PPS),
            videoTrack.samples.single().bytes,
        )
        assertEquals(1L, extractor.videoSamplesWritten)
        assertEquals(1L, extractor.samplesDropped)
    }

    @Test
    fun clampsOutOfOrderTimestampsToZero() {
        val aud = nal(HevcNalType.AUD, 0x50)
        val stream =
            packageTlv(videoAsset(), audioAsset()) +
                audioFrameTlv(500L, useSameStreamMux = false) +
                videoTlv(1000L, aggregatedMfuPayload(100L, lengthPrefixed(aud), *hevcParamSetMfus())) +
                videoTlv(1001L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud)))
        val extractor = TlvMmtExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        drain(extractor, FakeExtractorInput(stream))

        // Audio arrived first and anchored the timeline; the earlier video PTS clamps to zero.
        assertEquals(
            0L,
            output.tracks
                .getValue(audioPid)
                .samples
                .single()
                .timeUs,
        )
        assertEquals(
            0L,
            output.tracks
                .getValue(videoPid)
                .samples
                .single()
                .timeUs,
        )
    }

    @Test
    fun ignoresAssetsAddedAfterEndTracks() {
        val aud = nal(HevcNalType.AUD, 0x50)
        val openUnit = singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud))
        val stream =
            packageTlv(videoAsset()) +
                videoTlv(1000L, aggregatedMfuPayload(100L, lengthPrefixed(aud), *hevcParamSetMfus())) +
                videoTlv(1001L, openUnit) +
                packageTlv(videoAsset(), audioAsset()) +
                audioFrameTlv(500L, useSameStreamMux = false) +
                videoTlv(1002L, openUnit)
        val extractor = TlvMmtExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        drain(extractor, FakeExtractorInput(stream))

        assertEquals(setOf(videoPid), output.tracks.keys)
        assertEquals(
            2,
            output.tracks
                .getValue(videoPid)
                .samples.size,
        )
        assertEquals(2L, extractor.videoSamplesWritten)
        assertEquals(0L, extractor.audioSamplesWritten)
        assertEquals(1L, extractor.samplesDropped)
        assertEquals(2L, extractor.packagesSeen)
    }

    @Test
    fun seekRestartsTheTimeline() {
        val aud = nal(HevcNalType.AUD, 0x50)
        val openUnit = singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud))
        val prefix =
            packageTlv(videoAsset()) +
                videoTlv(1000L, aggregatedMfuPayload(100L, lengthPrefixed(aud), *hevcParamSetMfus())) +
                videoTlv(1001L, openUnit)
        val extractor = TlvMmtExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        drain(extractor, FakeExtractorInput(prefix))
        assertEquals(
            0L,
            output.tracks
                .getValue(videoPid)
                .samples
                .single()
                .timeUs,
        )

        extractor.seek(0L, 0L)
        drain(extractor, FakeExtractorInput(prefix))

        val samples = output.tracks.getValue(videoPid).samples
        assertEquals(2, samples.size)
        assertEquals(0L, samples[1].timeUs)
        assertEquals(2L, extractor.videoSamplesWritten)
    }
}
