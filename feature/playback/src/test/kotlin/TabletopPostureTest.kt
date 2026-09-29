package net.rokoucha.visiomata.playback

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletopPostureTest {
    @Test
    fun `tabletop layout used only on non-TV devices with a hinge split`() {
        val split = TabletopSplit(videoHeight = 400.dp, hingeHeight = 0.dp)
        assertTrue(shouldUseTabletopLayout(isTv = false, tabletopSplit = split))
        assertFalse(shouldUseTabletopLayout(isTv = true, tabletopSplit = split))
        assertFalse(shouldUseTabletopLayout(isTv = false, tabletopSplit = null))
        assertFalse(shouldUseTabletopLayout(isTv = true, tabletopSplit = null))
    }
}
