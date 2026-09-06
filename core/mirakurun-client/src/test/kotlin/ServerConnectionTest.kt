package net.rokoucha.visiomata.mirakurun

import net.rokoucha.visiomata.mahiron.model.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionTest {
    @Test
    fun `server field enables Mahiron extensions`() {
        assertTrue(MirakurunConnector.isMahiron(Version("5.1.1", "", "mahiron")))
    }

    @Test
    fun `missing server field remains Mirakurun`() {
        assertFalse(MirakurunConnector.isMahiron(Version("4.1.3-master", "4.1.3")))
    }
}
