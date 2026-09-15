package net.rokoucha.visiomata.playback

import androidx.media3.common.MimeTypes

/**
 * Narrows the decoder candidates media3 offers for a video track to the decoder the user
 * forced. Media3 fails playback outright on an empty list, so a filter that matches nothing
 * falls back to every candidate and reports it through [onEmptyFallback].
 *
 * [forceHardwareMpeg2Decoder] is null unless MPEG-2 plays back directly, and
 * [useSoftwareAvcDecoder] covers the H.264 that mpeg2toh264 produces: hardware AVC decoders on
 * some devices cannot decode the MBAFF pictures it emits for interlaced broadcasts. Forcing the
 * hardware AVC decoder needs no filter because that is already the default choice.
 */
internal class VideoDecoderPreference(
    private val forceHardwareMpeg2Decoder: Boolean?,
    private val useSoftwareAvcDecoder: Boolean,
    private val onEmptyFallback: (String) -> Unit = {},
) {
    val filtersAnything: Boolean
        get() = forceHardwareMpeg2Decoder != null || useSoftwareAvcDecoder

    fun <T> apply(
        mimeType: String,
        decoders: List<T>,
        softwareOnly: (T) -> Boolean,
        hardwareAccelerated: (T) -> Boolean,
    ): List<T> {
        val filtered =
            when {
                mimeType == MimeTypes.VIDEO_MPEG2 && forceHardwareMpeg2Decoder != null -> {
                    decoders.filter { codec ->
                        if (forceHardwareMpeg2Decoder) hardwareAccelerated(codec) else softwareOnly(codec)
                    }
                }

                mimeType == MimeTypes.VIDEO_H264 && useSoftwareAvcDecoder -> {
                    decoders.filter { codec -> softwareOnly(codec) }
                }

                else -> {
                    decoders
                }
            }
        if (filtered.isEmpty() && decoders.isNotEmpty()) {
            onEmptyFallback(mimeType)
            return decoders
        }
        return filtered
    }
}
