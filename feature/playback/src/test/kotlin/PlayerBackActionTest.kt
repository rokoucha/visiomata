package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBackActionTest {
    @Test
    fun `back opens a hidden overlay`() {
        assertEquals(TvPlayerBackAction.ShowOverlay, tvPlayerBackAction(overlayVisible = false))
    }

    @Test
    fun `back navigates away when the overlay is visible`() {
        assertEquals(TvPlayerBackAction.NavigateBack, tvPlayerBackAction(overlayVisible = true))
    }
}
