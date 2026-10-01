package net.rokoucha.visiomata.guide

import net.rokoucha.visiomata.model.Program
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GuideFocusNavigationTest {
    @Test
    fun `horizontal traversal keeps original time through a long programme`() {
        val original = program("10:00", "10:30")
        val long = program("06:00", "12:00")
        val beforeOriginalTime = program("09:30", "10:00")
        val atOriginalTime = program("10:00", "10:30")
        val schedule = listOf(beforeOriginalTime, atOriginalTime)

        val range = GuideFocusRange.of(original).intersecting(long)

        assertEquals(1, horizontalProgramIndex(schedule, range))
    }

    @Test
    fun `largest programme fully inside focus range is preferred`() {
        val schedule =
            listOf(
                program("10:00", "10:05"),
                program("10:05", "10:25"),
                program("10:25", "10:30"),
            )

        assertEquals(
            1,
            horizontalProgramIndex(schedule, GuideFocusRange(at("10:00"), at("10:30"))),
        )
    }

    @Test
    fun `programme with greatest partial overlap is preferred`() {
        val schedule = listOf(program("09:30", "10:10"), program("10:10", "10:40"))

        assertEquals(
            1,
            horizontalProgramIndex(schedule, GuideFocusRange(at("10:00"), at("10:30"))),
        )
    }

    @Test
    fun `schedule gap has no horizontal focus target`() {
        val schedule = listOf(program("09:00", "10:00"), program("11:00", "12:00"))

        assertEquals(
            null,
            horizontalProgramIndex(schedule, GuideFocusRange(at("10:15"), at("10:45"))),
        )
    }

    @Test
    fun `horizontal traversal skips a subchannel with no programme at the focus time`() {
        val schedules =
            listOf(
                listOf(program("10:00", "10:30")),
                listOf(program("09:00", "10:00"), program("11:00", "12:00")),
                listOf(program("10:15", "10:45")),
            )

        assertEquals(
            2 to 0,
            horizontalProgramSelection(
                schedules,
                currentStation = 0,
                direction = 1,
                focusRange = GuideFocusRange(at("10:00"), at("10:30")),
            ),
        )
    }

    @Test
    fun `long visible programme does not move viewport`() {
        assertEquals(1_000f, revealProgramOffset(1_000f, 500f, 100f, 2_000f))
    }

    @Test
    fun `horizontal traversal does not reveal whole programme when it overlaps viewport`() {
        assertEquals(
            1_000f,
            revealProgramOffset(
                viewportOffset = 1_000f,
                viewportHeight = 500f,
                programTop = 900f,
                programBottom = 1_200f,
                preserveWhenVisible = true,
            ),
        )
    }

    @Test
    fun `offscreen programme is brought into view`() {
        assertEquals(1_600f, revealProgramOffset(1_000f, 500f, 1_600f, 1_800f))
    }

    private fun program(
        start: String,
        end: String,
    ) = Program(
        id = start.hashCode().toLong(),
        eventId = start.hashCode(),
        networkId = 1,
        transportStreamId = 1,
        serviceId = 1,
        title = start,
        description = "",
        startAt = at(start),
        endAt = at(end),
    )

    private fun at(time: String) = Instant.parse("2026-10-01T$time:00Z")
}
