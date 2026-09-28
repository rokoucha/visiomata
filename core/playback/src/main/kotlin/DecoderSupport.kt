@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Decides whether an installed decoder claims to handle a renderer input format.
 *
 * MediaCodec accepts configurations its decoder cannot actually decode (the emulator's HEVC
 * decoder takes 4K Main 10 and then outputs nothing), and ExoPlayer only logs
 * "Format exceeds selected codec's capabilities" before hanging on a black screen. Aborting
 * early turns that silent hang into an explicit terminal error.
 *
 * The check is deliberately conservative: playback proceeds unless *no* decoder reports
 * support, so devices that play beyond their advertised caps are unaffected.
 */
internal object DecoderSupport {
    fun isSupported(
        context: Context,
        format: Format,
    ): Boolean {
        val mimeType = format.sampleMimeType ?: return true
        val decoders =
            try {
                MediaCodecUtil.getDecoderInfos(mimeType, false, false)
            } catch (queryFailure: MediaCodecUtil.DecoderQueryException) {
                return false
            }
        return decoders.any { decoder ->
            try {
                decoder.isFormatSupported(context, format)
            } catch (runtime: RuntimeException) {
                false
            }
        }
    }

    fun unsupportedMessage(): String = "この端末では再生できません"
}
