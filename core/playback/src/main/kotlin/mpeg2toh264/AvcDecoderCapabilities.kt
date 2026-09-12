package net.rokoucha.visiomata.playback.mpeg2toh264

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

object AvcDecoderCapabilities {
    private val avcDecoders: List<MediaCodecInfo> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
                !codec.isEncoder &&
                    codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Whether a software H.264 decoder is visible to the app. Forcing software decoding shows
     * no video without one, so the option stays unselectable when this is false.
     */
    val hasSoftwareDecoder: Boolean by lazy {
        runCatching { avcDecoders.any { it.isSoftwareOnly } }.getOrDefault(false)
    }
}
