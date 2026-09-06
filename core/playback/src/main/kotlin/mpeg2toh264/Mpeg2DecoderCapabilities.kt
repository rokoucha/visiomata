package net.rokoucha.visiomata.playback.mpeg2toh264

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

internal object Mpeg2DecoderCapabilities {
    val hasHardwareDecoder: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                !codec.isEncoder &&
                    codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_MPEG2, ignoreCase = true) } &&
                    codec.isHardwareAccelerated
            }
        }.getOrDefault(false)
    }
}
