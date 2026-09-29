package net.rokoucha.visiomata.playback

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFullscreenTest {
    @Test
    fun `entering fullscreen locks orientation to sensor landscape`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            fullscreenLockedOrientation,
        )
    }

    @Test
    fun `exiting fullscreen releases the orientation lock`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            fullscreenReleasedOrientation,
        )
    }

    @Test
    fun `enter button shows only on portrait phones`() {
        assertTrue(shouldShowEnterFullscreen(isTv = false, portrait = true))
        assertFalse(shouldShowEnterFullscreen(isTv = false, portrait = false))
        assertFalse(shouldShowEnterFullscreen(isTv = true, portrait = true))
        assertFalse(shouldShowEnterFullscreen(isTv = true, portrait = false))
    }

    @Test
    fun `exit button shows only in button-driven fullscreen`() {
        assertTrue(shouldShowExitFullscreen(isTv = false, portrait = false, isFullscreen = true))
        assertFalse(shouldShowExitFullscreen(isTv = false, portrait = false, isFullscreen = false))
        assertFalse(shouldShowExitFullscreen(isTv = false, portrait = true, isFullscreen = true))
        assertFalse(shouldShowExitFullscreen(isTv = true, portrait = false, isFullscreen = true))
    }
}
