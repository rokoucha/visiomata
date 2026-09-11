package net.rokoucha.visiomata.playback

import androidx.media3.common.PlaybackException

internal fun isDecoderErrorCode(errorCode: Int): Boolean =
    when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> true

        else -> false
    }

/**
 * Abandons automatic retries after decoder errors repeat consecutively. Decoder failures
 * (missing codec, corrupt stream for the device decoder) never heal by reconnecting to the
 * same live stream, so retrying forever only loops the error overlay. Other errors keep the
 * existing unbounded retry behavior.
 */
internal class DecoderRetryPolicy(
    private val maxConsecutiveDecoderErrors: Int = MAX_CONSECUTIVE_DECODER_ERRORS,
) {
    private var consecutiveDecoderErrors = 0
    var gaveUp = false
        private set

    /**
     * Records one decoder error. Returns true when automatic retry should be abandoned.
     * Once given up, keeps returning true until [reset].
     */
    fun onDecoderError(): Boolean {
        if (gaveUp) return true
        consecutiveDecoderErrors++
        if (consecutiveDecoderErrors >= maxConsecutiveDecoderErrors) {
            gaveUp = true
            return true
        }
        return false
    }

    /** A non-decoder error breaks the consecutive streak. */
    fun onOtherError() {
        consecutiveDecoderErrors = 0
    }

    fun reset() {
        consecutiveDecoderErrors = 0
        gaveUp = false
    }

    companion object {
        const val MAX_CONSECUTIVE_DECODER_ERRORS = 3
    }
}
