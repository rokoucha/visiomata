package net.rokoucha.visiomata.settings.data

import org.junit.Assert.assertFalse
import org.junit.Test

class MirakurunSettingsTest {
    @Test
    fun `data broadcasting internet access is disabled by default`() {
        assertFalse(MirakurunSettings().dataBroadcastingInternetEnabled)
    }
}
