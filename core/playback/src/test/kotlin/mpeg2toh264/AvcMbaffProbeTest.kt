package net.rokoucha.visiomata.playback.mpeg2toh264

import org.junit.Assert.assertEquals
import org.junit.Test

class AvcMbaffProbeTest {
    @Test
    fun `returns hardware ok when every sample came back`() {
        assertEquals(
            MbaffProbeResult.HardwareOk,
            AvcMbaffProbe.decide(inputSamples = 7, outputFrames = 7, reachedEos = true),
        )
    }

    @Test
    fun `returns hardware failed when no frame came back`() {
        assertEquals(
            MbaffProbeResult.HardwareFailed,
            AvcMbaffProbe.decide(inputSamples = 7, outputFrames = 0, reachedEos = true),
        )
    }

    @Test
    fun `returns hardware failed when only the leading IDR came back`() {
        assertEquals(
            MbaffProbeResult.HardwareFailed,
            AvcMbaffProbe.decide(inputSamples = 7, outputFrames = 1, reachedEos = true),
        )
    }

    @Test
    fun `returns inconclusive when the deadline hit before the end of stream`() {
        assertEquals(
            MbaffProbeResult.Inconclusive,
            AvcMbaffProbe.decide(inputSamples = 7, outputFrames = 7, reachedEos = false),
        )
    }

    @Test
    fun `returns inconclusive when no sample was fed at all`() {
        assertEquals(
            MbaffProbeResult.Inconclusive,
            AvcMbaffProbe.decide(inputSamples = 0, outputFrames = 0, reachedEos = true),
        )
    }
}
