package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinterlaceEffectsGateTest {
    @Test
    fun unlatchedInitially() {
        assertNull(DeinterlaceEffectsGate(effectsAvailable = true).latchedKind)
    }

    @Test
    fun restartsWithEffectsForFirstTsSniff() {
        val gate = DeinterlaceEffectsGate(effectsAvailable = true)

        assertTrue(gate.onSniffed(StreamKind.TS))
        assertEquals(StreamKind.TS, gate.latchedKind)
    }

    @Test
    fun noRestartForTlvSniff() {
        val gate = DeinterlaceEffectsGate(effectsAvailable = true)

        assertFalse(gate.onSniffed(StreamKind.TLV))
        assertEquals(StreamKind.TLV, gate.latchedKind)
    }

    @Test
    fun noRestartWhenEffectsUnavailable() {
        val gate = DeinterlaceEffectsGate(effectsAvailable = false)

        assertFalse(gate.onSniffed(StreamKind.TS))
        assertEquals(StreamKind.TS, gate.latchedKind)
    }

    @Test
    fun ignoresSniffsAfterFirst() {
        val tsFirst = DeinterlaceEffectsGate(effectsAvailable = true)
        assertTrue(tsFirst.onSniffed(StreamKind.TS))
        assertFalse(tsFirst.onSniffed(StreamKind.TLV))
        assertFalse(tsFirst.onSniffed(StreamKind.TS))
        assertEquals(StreamKind.TS, tsFirst.latchedKind)

        val tlvFirst = DeinterlaceEffectsGate(effectsAvailable = true)
        assertFalse(tlvFirst.onSniffed(StreamKind.TLV))
        assertFalse(tlvFirst.onSniffed(StreamKind.TS))
        assertEquals(StreamKind.TLV, tlvFirst.latchedKind)
    }
}
