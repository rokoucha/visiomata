package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderSupportTest {
    @Test
    fun unsupportedMessage() {
        assertEquals("この端末では再生できません", DecoderSupport.unsupportedMessage())
    }
}
