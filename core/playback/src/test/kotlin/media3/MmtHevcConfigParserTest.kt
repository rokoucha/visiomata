package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmtHevcConfigParserTest {
    @Test
    fun builds4kFormatFromCompleteAccessUnit() {
        val parser = MmtHevcConfigParser("tlv-video/61696")
        val accessUnit = annexB(nal(HevcNalType.AUD, 0x50), HEVC_VPS, HEVC_SPS, HEVC_PPS, nal(19, 0x01))

        val format = parser.consume(accessUnit) ?: error("format must be built")

        // Dimensions verified against ffprobe on the dumped Annex-B stream.
        assertEquals("tlv-video/61696", format.id)
        assertEquals(MimeTypes.VIDEO_H265, format.sampleMimeType)
        assertEquals(3840, format.width)
        assertEquals(2160, format.height)
        assertTrue(format.codecs.orEmpty().startsWith("hvc1"))
        assertEquals(1, format.initializationData.size)
        assertArrayEquals(
            bytes(0, 0, 1) + HEVC_VPS + bytes(0, 0, 1) + HEVC_SPS + bytes(0, 0, 1) + HEVC_PPS,
            format.initializationData[0],
        )
        assertEquals(format, parser.format)
        // The format is fixed once built.
        assertNull(parser.consume(accessUnit))
    }

    @Test
    fun collectsParameterSetsAcrossAccessUnits() {
        val parser = MmtHevcConfigParser("tlv-video/1")

        assertNull(parser.consume(annexB(HEVC_VPS)))
        val format = parser.consume(annexB(HEVC_SPS, HEVC_PPS)) ?: error("format must be built")

        assertEquals(3840, format.width)
        assertEquals(2160, format.height)
    }

    @Test
    fun corruptParameterSetReturnsNullAndRetries() {
        val parser = MmtHevcConfigParser("tlv-video/1")
        val corrupt = annexB(HEVC_VPS, HEVC_SPS.copyOfRange(0, 12), HEVC_PPS)

        assertNull(parser.consume(corrupt))
        assertEquals(null, parser.format)

        val format =
            parser.consume(annexB(HEVC_VPS, HEVC_SPS, HEVC_PPS)) ?: error("retry must succeed")
        assertEquals(3840, format.width)
        assertEquals(2160, format.height)
    }

    @Test
    fun resetClearsFormat() {
        val parser = MmtHevcConfigParser("tlv-video/1")
        parser.consume(annexB(HEVC_VPS, HEVC_SPS, HEVC_PPS)) ?: error("format must be built")

        parser.reset()

        assertEquals(null, parser.format)
        val format =
            parser.consume(annexB(HEVC_VPS, HEVC_SPS, HEVC_PPS)) ?: error("format must be rebuilt")
        assertEquals(2160, format.height)
    }
}
