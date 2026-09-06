package net.rokoucha.visiomata.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ProgramGuideTest {
    private val now = Instant.parse("2026-08-29T12:00:00Z")

    @Test
    fun `detects dual mono from the audio component type`() {
        assertEquals(true, ProgramAudio(0x02, 0x10, true).isDualMono)
        assertEquals(true, ProgramAudio(0x42, 0x10, true).isDualMono)
        assertEquals(false, ProgramAudio(0x03, 0x10, true).isDualMono)
    }

    @Test
    fun `identifies services and programs using ARIB identifiers`() {
        val service = service(10, 1, "Custom", 100)
        val program = program(service, eventId = 42)
        assertEquals(ServiceKey(1, 100, 1), service.key)
        assertEquals(ProgramKey(service.key, 42), program.key)
    }

    @Test
    fun `hides simulcast subchannels even when their logos differ`() {
        val first = service(10, 1, "arbitrary-a", 100, logoId = 7, remoteControlKeyId = 5)
        val second = service(20, 2, "arbitrary-b", 100, logoId = 8, remoteControlKeyId = 5)
        val guide =
            ProgramGuide(
                listOf(second, first),
                listOf(program(first, title = "Simulcast"), program(second, title = "Simulcast")),
            )
        val group = guide.serviceGroups(now).single()
        assertEquals("10", group.id)
        assertEquals(emptyList<ServiceVariant>(), group.variants)
    }

    @Test
    fun `does not group services merely because their channel type matches`() {
        val first = service(10, 1, "anything", 100, logoId = 7)
        val second = service(20, 2, "anything", 200, logoId = 7)
        val guide = ProgramGuide(listOf(first, second), listOf(program(first), program(second)))
        assertEquals(2, guide.serviceGroups(now).size)
    }

    @Test
    fun `shows subchannel when its current program differs`() {
        val main = service(10, 8, "GR", 100, remoteControlKeyId = 9)
        val sub = service(20, 9, "GR", 100, remoteControlKeyId = 9)
        val guide =
            ProgramGuide(
                listOf(main, sub),
                listOf(program(main, title = "Regular"), program(sub, title = "Baseball live")),
            )

        assertEquals(listOf(10L, 20L), guide.serviceGroups(now).map { it.primaryServiceId })
    }

    @Test
    fun `shows only ARIB primary service in station lists`() {
        val sub = service(20, 17, "GR", 100, remoteControlKeyId = 9)
        val main = service(10, 16, "GR", 100, remoteControlKeyId = 9)
        val guide = ProgramGuide(listOf(sub, main), emptyList())

        assertEquals(listOf(main), guide.primaryServices)
    }

    @Test
    fun `does not collapse satellite services without shared station identity`() {
        val first = service(10, 251, "BS", 100, name = "BS釣りビジョン")
        val second = service(20, 252, "BS", 100, name = "WOWOWプラス")

        assertEquals(listOf(first, second), ProgramGuide(listOf(first, second), emptyList()).primaryServices)
    }

    @Test
    fun `groups BS member services by normalized station name`() {
        val first = service(10, 151, "BS", 100, name = "BS朝日1")
        val second = service(20, 152, "BS", 100, name = "BS朝日2")
        val third = service(30, 153, "BS", 100, name = "BS朝日3")
        val guide = ProgramGuide(listOf(first, second, third), emptyList())

        assertEquals(listOf(first), guide.primaryServices)
    }

    @Test
    fun `groups BS member services with identical names despite different logos`() {
        val first = service(10, 161, "BS", 100, logoId = 1, name = "BS-TBS")
        val second = service(20, 162, "BS", 100, logoId = 2, name = "BS-TBS")

        assertEquals(listOf(first), ProgramGuide(listOf(first, second), emptyList()).primaryServices)
    }

    @Test
    fun `guide exposes a subchannel only when its schedule diverges`() {
        val main = service(10, 16, "GR", 100, remoteControlKeyId = 9)
        val sub = service(20, 17, "GR", 100, remoteControlKeyId = 9)
        val simulcast =
            ProgramGuide(
                listOf(main, sub),
                listOf(program(main, title = "News"), program(sub, title = "News")),
            )
        val multichannel =
            ProgramGuide(
                listOf(main, sub),
                listOf(program(main, title = "News"), program(sub, title = "Sports")),
            )

        assertEquals(listOf(main), simulcast.servicesWithDistinctProgramming)
        assertEquals(listOf(main, sub), multichannel.servicesWithDistinctProgramming)
        assertEquals(emptyList<Program>(), simulcast.displaySchedule(sub))
        assertEquals(listOf(program(sub, title = "Sports")), multichannel.displaySchedule(sub))
    }

    @Test
    fun `small EIT timing differences do not expose a simulcast subchannel`() {
        val main = service(10, 16, "GR", 100, remoteControlKeyId = 9)
        val sub = service(20, 17, "GR", 100, remoteControlKeyId = 9)
        val mainProgram = program(main, title = "News")
        val shiftedSubProgram =
            program(sub, title = "News").copy(
                startAt = mainProgram.startAt.plusSeconds(30),
                endAt = mainProgram.endAt.plusSeconds(30),
            )
        val guide = ProgramGuide(listOf(main, sub), listOf(mainProgram, shiftedSubProgram))

        assertEquals(emptyList<Program>(), guide.displaySchedule(sub))
    }

    @Test
    fun `ARIB event common relation hides inherited subchannel event`() {
        val main = service(10, 16, "GR", 100, remoteControlKeyId = 9)
        val sub = service(20, 17, "GR", 100, remoteControlKeyId = 9)
        val mainProgram = program(main, eventId = 100, title = "News")
        val subProgram =
            program(sub, eventId = 200, title = "番組情報なし").copy(
                relatedPrograms = listOf(RelatedProgram("shared", 1, 100, 16, 100)),
            )
        val guide = ProgramGuide(listOf(main, sub), listOf(mainProgram, subProgram))

        assertEquals(emptyList<Program>(), guide.displaySchedule(sub))
    }

    @Test
    fun `uses service id as satellite logical channel when remote control key is zero`() {
        val service = service(211, 211, "BS", 100, remoteControlKeyId = 0)
        val guide = ProgramGuide(listOf(service), listOf(program(service)))
        val group = guide.serviceGroups(now).single()
        assertEquals("211", group.logicalChannelNumber)
        assertEquals("BS  211", group.channelIdentityLabel)
    }

    @Test
    fun `derives terrestrial three digit number from ARIB service id fields`() {
        val main = service(10, 0x0410, "GR", 100, remoteControlKeyId = 9)
        val sub = service(20, 0x0412, "GR", 100, remoteControlKeyId = 9)

        assertEquals("091", main.logicalChannelNumber)
        assertEquals("093", sub.logicalChannelNumber)
        assertEquals("GR  091", main.channelIdentityLabel)
    }

    @Test
    fun `keeps logo identity without carrying image bytes in the guide`() {
        val service = service(10, 1, "GR", 100, logoId = 7)
        val group = ProgramGuide(listOf(service), listOf(program(service))).serviceGroups(now).single()
        assertEquals(7, group.logoId)
    }

    @Test
    fun `orders known channel types first and skips absent types`() {
        val custom = service(50, 5, "CUSTOM", 500)
        val sky = service(40, 4, "SKY", 400)
        val gr = service(10, 1, "GR", 100)
        val guide =
            ProgramGuide(
                services = listOf(custom, sky, gr),
                programs = listOf(program(custom), program(sky), program(gr)),
            )

        assertEquals(listOf("GR", "SKY", "CUSTOM"), guide.serviceGroups(now).map { it.channelType.value })
    }

    @Test
    fun `always returns schedules in time order`() {
        val service = service(10, 1, "Custom", 100)
        val later = program(service, offsetMinutes = 60)
        val earlier = program(service)
        val guide = ProgramGuide(listOf(service), listOf(later, earlier))
        assertEquals(listOf(earlier, later), guide.schedule(service))
        assertEquals(later, guide.serviceGroups(now).single().next)
    }

    @Test
    fun `retains overlapping cards while the next guide window is loading`() {
        val service = service(10, 1, "GR", 100)
        val expired = program(service, eventId = 1, offsetMinutes = -120)
        val overlapping = program(service, eventId = 2, offsetMinutes = 0)
        val incoming = ProgramGuide(listOf(service), emptyList())

        val snapshot =
            incoming.retainingWindow(
                previous = ProgramGuide(listOf(service), listOf(expired, overlapping)),
                startAt = now.minusSeconds(30 * 60),
                endAt = now.plusSeconds(2 * 60 * 60),
            )

        assertEquals(listOf(overlapping), snapshot.programs)
    }

    private fun service(
        id: Long,
        serviceId: Int,
        channelType: String,
        transportStreamId: Int,
        logoId: Int? = null,
        remoteControlKeyId: Int? = null,
        name: String = "Service $serviceId",
    ) = Service(
        id = id,
        networkId = 1,
        transportStreamId = transportStreamId,
        serviceId = serviceId,
        name = name,
        channelType = ChannelType(channelType),
        channel = "channel",
        remoteControlKeyId = remoteControlKeyId,
        logoId = logoId,
    )

    private fun program(
        service: Service,
        eventId: Int = service.serviceId,
        title: String = "Program",
        offsetMinutes: Long = 0,
    ): Program {
        val start = now.plusSeconds(offsetMinutes * 60 - if (offsetMinutes == 0L) 600 else 0)
        return Program(
            id = eventId.toLong(),
            eventId = eventId,
            networkId = service.networkId,
            transportStreamId = service.transportStreamId,
            serviceId = service.serviceId,
            title = title,
            description = "",
            startAt = start,
            endAt = start.plusSeconds(3600),
        )
    }
}
