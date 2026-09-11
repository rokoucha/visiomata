package net.rokoucha.visiomata.playback.mpeg2toh264

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

object Mpeg2DecoderCapabilities {
    private val mpeg2Decoders: List<MediaCodecInfo> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
                !codec.isEncoder &&
                    codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_MPEG2, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Whether any software or hardware MPEG-2 decoder is visible to the app. Forced direct
     * playback shows audio only when this is false.
     */
    val hasAnyDecoder: Boolean by lazy { mpeg2Decoders.isNotEmpty() }

    val hasHardwareDecoder: Boolean by lazy {
        runCatching { mpeg2Decoders.any { it.isHardwareAccelerated } }.getOrDefault(false)
    }
}
