package net.rokoucha.visiomata.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOverlayProgramInfoTest {
    @Test
    fun `portrait phones use compact overlay program info`() {
        assertTrue(shouldUseCompactOverlayProgramInfo(portrait = true, useTabletopLayout = false))
    }

    @Test
    fun `tabletop and landscape use full overlay program info`() {
        assertFalse(shouldUseCompactOverlayProgramInfo(portrait = true, useTabletopLayout = true))
        assertFalse(shouldUseCompactOverlayProgramInfo(portrait = false, useTabletopLayout = true))
        assertFalse(shouldUseCompactOverlayProgramInfo(portrait = false, useTabletopLayout = false))
    }
}
