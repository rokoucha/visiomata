package net.rokoucha.visiomata.playback

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Decoder(
    val name: String,
    val softwareOnly: Boolean,
    val hardwareAccelerated: Boolean,
)

class VideoDecoderPreferenceTest {
    private val avcDecoders =
        listOf(
            Decoder("hardware.avc", softwareOnly = false, hardwareAccelerated = true),
            Decoder("software.avc", softwareOnly = true, hardwareAccelerated = false),
        )

    private fun VideoDecoderPreference.apply(
        mimeType: String,
        decoders: List<Decoder>,
    ): List<Decoder> =
        apply(
            mimeType = mimeType,
            decoders = decoders,
            softwareOnly = { it.softwareOnly },
            hardwareAccelerated = { it.hardwareAccelerated },
        )

    @Test
    fun keepsOnlySoftwareAvcDecodersWhenForced() {
        val preference = VideoDecoderPreference(forceHardwareMpeg2Decoder = null, useSoftwareAvcDecoder = true)

        assertEquals(listOf(avcDecoders[1]), preference.apply(MimeTypes.VIDEO_H264, avcDecoders))
    }

    @Test
    fun leavesAvcDecodersAloneWhenHardwareIsForced() {
        val preference = VideoDecoderPreference(forceHardwareMpeg2Decoder = null, useSoftwareAvcDecoder = false)

        assertFalse(preference.filtersAnything)
        assertEquals(avcDecoders, preference.apply(MimeTypes.VIDEO_H264, avcDecoders))
    }

    @Test
    fun leavesOtherMimeTypesAlone() {
        val preference = VideoDecoderPreference(forceHardwareMpeg2Decoder = null, useSoftwareAvcDecoder = true)

        assertEquals(avcDecoders, preference.apply(MimeTypes.VIDEO_H265, avcDecoders))
    }

    @Test
    fun fallsBackToEveryDecoderWhenTheFilterMatchesNothing() {
        val hardwareOnly = listOf(avcDecoders[0])
        var fallbackMimeType: String? = null
        val preference =
            VideoDecoderPreference(
                forceHardwareMpeg2Decoder = null,
                useSoftwareAvcDecoder = true,
                onEmptyFallback = { fallbackMimeType = it },
            )

        assertEquals(hardwareOnly, preference.apply(MimeTypes.VIDEO_H264, hardwareOnly))
        assertEquals(MimeTypes.VIDEO_H264, fallbackMimeType)
    }

    @Test
    fun reportsNoFallbackWhenMediaCodecOffersNoDecoderAtAll() {
        var fallbackCalled = false
        val preference =
            VideoDecoderPreference(
                forceHardwareMpeg2Decoder = null,
                useSoftwareAvcDecoder = true,
                onEmptyFallback = { fallbackCalled = true },
            )

        assertTrue(preference.apply(MimeTypes.VIDEO_H264, emptyList()).isEmpty())
        assertFalse(fallbackCalled)
    }

    @Test
    fun filtersMpeg2DecodersIndependentlyOfTheAvcChoice() {
        val mpeg2Decoders =
            listOf(
                Decoder("hardware.mpeg2", softwareOnly = false, hardwareAccelerated = true),
                Decoder("software.mpeg2", softwareOnly = true, hardwareAccelerated = false),
            )
        val preference = VideoDecoderPreference(forceHardwareMpeg2Decoder = true, useSoftwareAvcDecoder = true)

        assertEquals(listOf(mpeg2Decoders[0]), preference.apply(MimeTypes.VIDEO_MPEG2, mpeg2Decoders))
        assertEquals(listOf(avcDecoders[1]), preference.apply(MimeTypes.VIDEO_H264, avcDecoders))
    }
}
