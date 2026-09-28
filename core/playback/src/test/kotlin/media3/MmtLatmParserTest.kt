package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.EOFException

internal class RecordingTrackOutput : TrackOutput {
    val formats = mutableListOf<Format>()
    val samples = mutableListOf<RecordedSample>()

    private var pending = ByteArray(0)

    override fun format(format: Format) {
        formats.add(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val chunk = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(chunk, read, length - read)
            if (count == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) break
                throw EOFException()
            }
            read += count
        }
        pending += chunk.copyOf(read)
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int,
    ) {
        // readBytes/skipBytes are unusable in JVM unit tests (their limit assertion touches
        // android.os.Build from a static initializer), so copy positionally instead.
        pending += data.data.copyOfRange(data.position, data.position + length)
        data.position = data.position + length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val bytes = pending.copyOfRange(pending.size - size, pending.size)
        pending = ByteArray(0)
        samples.add(RecordedSample(timeUs, flags, bytes))
    }
}

internal class RecordedSample(
    val timeUs: Long,
    val flags: Int,
    val bytes: ByteArray,
)

class MmtLatmParserTest {
    private val payload = bytes(0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c, 0x85, 0xe4)

    @Test
    fun parsesConfigAndUnwrapsNonAlignedPayload() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        val element = latmAudioMuxElement(payload)

        val frame = parser.parse(element)

        assertArrayEquals(payload, frame)
        assertEquals(1, output.formats.size)
        val format = output.formats.single()
        assertEquals("tlv-audio/62256", format.id)
        assertEquals(MimeTypes.AUDIO_AAC, format.sampleMimeType)
        assertEquals("mp4a.40.2", format.codecs)
        assertEquals(48000, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals(1, format.initializationData.size)
        assertArrayEquals(bytes(0x11, 0x90), format.initializationData[0])
        assertEquals(1L, parser.framesEmitted)
        assertEquals(0L, parser.framesDropped)
    }

    @Test
    fun reusesConfigForSameStreamMuxElements() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        parser.parse(latmAudioMuxElement(payload))
        val second = bytes(0xde, 0xad, 0xbe, 0xef)

        val frame = parser.parse(latmAudioMuxElement(second, useSameStreamMux = true))

        assertArrayEquals(second, frame)
        assertEquals(1, output.formats.size)
        assertEquals(2L, parser.framesEmitted)
    }

    @Test
    fun dropsElementBeforeAnyConfig() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")

        assertNull(parser.parse(latmAudioMuxElement(payload, useSameStreamMux = true)))

        assertEquals(0, output.formats.size)
        assertEquals(0L, parser.framesEmitted)
        assertEquals(1L, parser.framesDropped)
    }

    @Test
    fun dropsTruncatedElementsWithoutThrowing() {
        val element = latmAudioMuxElement(payload)
        val cutPoints = (1 until element.size).toList()

        cutPoints.forEach { cut ->
            val output = RecordingTrackOutput()
            val parser = MmtLatmParser(output, "tlv-audio/62256")

            assertNull("cut at $cut", parser.parse(element.copyOf(cut)))
            assertEquals(1L, parser.framesDropped)
        }
    }

    @Test
    fun dropsTruncatedPayloadWithoutThrowing() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        val element = latmAudioMuxElement(payload).dropLast(4).toByteArray()

        assertNull(parser.parse(element))

        assertEquals(1L, parser.framesDropped)
    }

    @Test
    fun dropsUnsupportedProfilesWithoutThrowing() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")

        assertNull(parser.parse(latmAudioMuxElement(payload, numProgram = 1)))
        assertNull(parser.parse(latmAudioMuxElement(payload, audioMuxVersionA = 1)))

        assertEquals(0, output.formats.size)
        assertEquals(2L, parser.framesDropped)
    }

    @Test
    fun unwrapsByteAlignedPayloadWithOtherData() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")

        val frame = parser.parse(latmAudioMuxElement(payload, otherData = true))

        assertArrayEquals(payload, frame)
        assertEquals(1, output.formats.size)
    }

    @Test
    fun handlesMultibytePayloadLength() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        val longPayload = ByteArray(300) { (it * 31).toByte() }

        val frame = parser.parse(latmAudioMuxElement(longPayload))

        assertArrayEquals(longPayload, frame)
    }

    @Test
    fun resetClearsConfig() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        parser.parse(latmAudioMuxElement(payload))
        parser.reset()

        assertNull(parser.parse(latmAudioMuxElement(payload, useSameStreamMux = true)))
        assertArrayEquals(payload, parser.parse(latmAudioMuxElement(payload)))
        assertEquals(2, output.formats.size)
    }

    @Test
    fun writeSampleWritesKeyframeSample() {
        val output = RecordingTrackOutput()
        val parser = MmtLatmParser(output, "tlv-audio/62256")
        val frame = parser.parse(latmAudioMuxElement(payload)) ?: error("fixture must parse")

        parser.writeSample(frame, 21_333L)

        val sample = output.samples.single()
        assertEquals(21_333L, sample.timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, sample.flags)
        assertArrayEquals(payload, sample.bytes)
    }
}
