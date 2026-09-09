package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMemoryPolicyTest {
    @Test
    fun bothTiersShareTheSameLiveWindow() {
        val lowRam = PlaybackMemoryPolicy.forDevice(true)
        val standard = PlaybackMemoryPolicy.forDevice(false)

        assertEquals(PlaybackMemoryPolicy.LIVE_MIN_BUFFER_MS, lowRam.minBufferMs)
        assertEquals(PlaybackMemoryPolicy.LIVE_MIN_BUFFER_MS, standard.minBufferMs)
        assertEquals(PlaybackMemoryPolicy.LIVE_MAX_BUFFER_MS, lowRam.maxBufferMs)
        assertEquals(PlaybackMemoryPolicy.LIVE_MAX_BUFFER_MS, standard.maxBufferMs)
    }

    @Test
    fun liveWindowStaysCloseToLive() {
        // The stream is unbounded and non-timeshifted: buffering far ahead only pins playback
        // behind live and forces burst-idle reads that the server must absorb. Guard the live
        // window so a future bump cannot silently reintroduce a tens-of-seconds delay.
        forDeviceEach { policy ->
            assertTrue(policy.minBufferMs < policy.maxBufferMs)
            assertTrue(policy.minBufferMs >= 500)
            assertTrue(policy.maxBufferMs <= 10_000)
        }
    }

    @Test
    fun memoryTiersOnlyTuneBmlCaps() {
        val lowRam = PlaybackMemoryPolicy.forDevice(true)
        val standard = PlaybackMemoryPolicy.forDevice(false)

        assertTrue(lowRam.isLowRamDevice)
        assertFalse(standard.isLowRamDevice)
        assertTrue(lowRam.bmlMaxModuleBytes in 1..standard.bmlMaxModuleBytes)
        assertTrue(lowRam.bmlMaxCarouselBytes in 1..standard.bmlMaxCarouselBytes)
        assertTrue(lowRam.bmlQueueCapacity in 1..standard.bmlQueueCapacity)
    }

    private fun forDeviceEach(block: (PlaybackMemoryPolicy) -> Unit) {
        block(PlaybackMemoryPolicy.forDevice(true))
        block(PlaybackMemoryPolicy.forDevice(false))
    }
}
