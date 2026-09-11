package net.rokoucha.visiomata.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderRetryPolicyTest {
    @Test
    fun givesUpAfterThreeConsecutiveDecoderErrors() {
        val policy = DecoderRetryPolicy()

        assertFalse(policy.onDecoderError())
        assertFalse(policy.onDecoderError())
        assertTrue(policy.onDecoderError())
        assertTrue(policy.gaveUp)
    }

    @Test
    fun staysGaveUpUntilReset() {
        val policy = DecoderRetryPolicy()
        repeat(3) { policy.onDecoderError() }

        assertTrue(policy.onDecoderError())
        assertTrue(policy.gaveUp)

        policy.reset()

        assertFalse(policy.gaveUp)
        assertFalse(policy.onDecoderError())
    }

    @Test
    fun otherErrorBreaksConsecutiveStreak() {
        val policy = DecoderRetryPolicy()
        repeat(2) { policy.onDecoderError() }
        policy.onOtherError()
        repeat(2) { assertFalse(policy.onDecoderError()) }

        assertTrue(policy.onDecoderError())
    }

    @Test
    fun detectsDecoderErrorCodes() {
        assertTrue(isDecoderErrorCode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isDecoderErrorCode(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isDecoderErrorCode(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertTrue(isDecoderErrorCode(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    @Test
    fun ignoresNonDecoderErrorCodes() {
        assertFalse(isDecoderErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isDecoderErrorCode(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun honorsCustomThreshold() {
        val policy = DecoderRetryPolicy(maxConsecutiveDecoderErrors = 2)

        assertFalse(policy.onDecoderError())
        assertTrue(policy.onDecoderError())
        assertEquals(DecoderRetryPolicy.MAX_CONSECUTIVE_DECODER_ERRORS, 3)
    }
}
