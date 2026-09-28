package net.rokoucha.visiomata.mirakurun

import net.rokoucha.visiomata.mahiron.infrastructure.Serializer
import net.rokoucha.visiomata.mahiron.model.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedMoshiAdaptersTest {
    private val adapter = Serializer.moshi.adapter(Version::class.java)

    @Test
    fun `parses Mahiron version response`() {
        val version = adapter.fromJson("""{"current":"5.1.1","latest":"","server":"mahiron"}""")

        assertEquals(Version("5.1.1", "", "mahiron"), version)
    }

    @Test
    fun `parses Mirakurun version response missing server field`() {
        val version = adapter.fromJson("""{"current":"4.1.3-master","latest":"4.1.3"}""")

        assertEquals("4.1.3-master", version?.current)
        assertNull(version?.server)
    }
}
