package net.rokoucha.visiomata.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideEventStreamTest {
    private val adapter =
        Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(GuideEventDto::class.java)

    @Test
    fun `parses Mirakurun's never-ending JSON array framing`() {
        assertNull(adapter.parseEventStreamLine("["))
        assertNull(adapter.parseEventStreamLine(","))

        val event =
            adapter.parseEventStreamLine(
                """{"resource":"program","type":"remove","data":{"id":123},"time":456}""",
            )

        assertEquals("program", event?.resource)
        assertEquals("remove", event?.type)
        assertEquals(123L, (event?.data?.get("id") as Number).toLong())
        assertEquals(456L, event.time)
    }

    @Test
    fun `parses same-transport related item without network id`() {
        val programAdapter =
            Moshi
                .Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
                .adapter(ProgramDto::class.java)

        val program =
            programAdapter.fromJson(
                """{"id":1,"eventId":10,"serviceId":102,"networkId":4,"startAt":0,"duration":60,"relatedItems":[{"type":"shared","serviceId":101,"eventId":20}]}""",
            )!!

        assertEquals(null, program.relatedItems?.single()?.networkId)
        assertEquals(101, program.relatedItems?.single()?.serviceId)
        assertEquals(20, program.relatedItems?.single()?.eventId)
    }

    @Test
    fun `parses ARIB dual-mono component metadata`() {
        val programAdapter =
            Moshi
                .Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
                .adapter(ProgramDto::class.java)

        val program =
            programAdapter.fromJson(
                """{"id":1,"eventId":10,"serviceId":102,"networkId":4,"startAt":0,"duration":60,"audios":[{"componentType":2,"componentTag":16,"isMain":true,"langs":["jpn","eng"]}]}""",
            )!!
        val audio = program.audios?.single()!!

        assertEquals(0x02, audio.componentType)
        assertEquals(0x10, audio.componentTag)
        assertEquals(true, audio.isMain)
        assertEquals(listOf("jpn", "eng"), audio.langs)
    }
}
