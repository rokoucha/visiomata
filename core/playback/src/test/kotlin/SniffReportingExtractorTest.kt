package net.rokoucha.visiomata.playback

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import net.rokoucha.visiomata.playback.media3.FakeExtractorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffReportingExtractorTest {
    private class FakeExtractor(
        var sniffResult: Boolean,
    ) : Extractor {
        var sniffedInput: ExtractorInput? = null
        var released = false

        override fun sniff(input: ExtractorInput): Boolean {
            sniffedInput = input
            return sniffResult
        }

        override fun init(output: ExtractorOutput) = Unit

        override fun read(
            input: ExtractorInput,
            seekPosition: PositionHolder,
        ): Int = Extractor.RESULT_END_OF_INPUT

        override fun seek(
            position: Long,
            timeUs: Long,
        ) = Unit

        override fun release() {
            released = true
        }
    }

    @Test
    fun reportsKindWhenDelegateSniffMatches() {
        val delegate = FakeExtractor(sniffResult = true)
        var reported: StreamKind? = null
        val extractor = SniffReportingExtractor(delegate, StreamKind.TS) { reported = it }
        val input = FakeExtractorInput(ByteArray(0))

        assertTrue(extractor.sniff(input))
        assertEquals(StreamKind.TS, reported)
        assertSame(input, delegate.sniffedInput)
    }

    @Test
    fun doesNotReportWhenDelegateSniffRejects() {
        val delegate = FakeExtractor(sniffResult = false)
        var reported: StreamKind? = null
        val extractor = SniffReportingExtractor(delegate, StreamKind.TLV) { reported = it }

        assertFalse(extractor.sniff(FakeExtractorInput(ByteArray(0))))
        assertNull(reported)
    }

    @Test
    fun delegatesRelease() {
        val delegate = FakeExtractor(sniffResult = true)
        val extractor = SniffReportingExtractor(delegate, StreamKind.TS) {}

        extractor.release()

        assertTrue(delegate.released)
    }
}
