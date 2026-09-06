package net.rokoucha.visiomata.guide

import net.rokoucha.visiomata.model.Program
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GuideTimelineTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val anchor = LocalDate.of(2026, 9, 1)

    @Test
    fun `broadcast day changes at 4am`() {
        val before =
            LocalDate
                .of(2026, 9, 2)
                .atTime(3, 59)
                .atZone(zone)
                .toInstant()
        val boundary =
            LocalDate
                .of(2026, 9, 2)
                .atTime(4, 0)
                .atZone(zone)
                .toInstant()

        assertEquals(LocalDate.of(2026, 9, 1), GuideTimeline.broadcastDateAt(before, zone))
        assertEquals(LocalDate.of(2026, 9, 2), GuideTimeline.broadcastDateAt(boundary, zone))
    }

    @Test
    fun `display date changes at midnight independently of broadcast day`() {
        val before =
            LocalDate
                .of(2026, 9, 1)
                .atTime(23, 59)
                .atZone(zone)
                .toInstant()
        val boundary = LocalDate.of(2026, 9, 2).atStartOfDay(zone).toInstant()
        val earlyMorning =
            LocalDate
                .of(2026, 9, 2)
                .atTime(3, 59)
                .atZone(zone)
                .toInstant()

        assertEquals(LocalDate.of(2026, 9, 1), GuideTimeline.displayDateAt(before, zone))
        assertEquals(LocalDate.of(2026, 9, 2), GuideTimeline.displayDateAt(boundary, zone))
        assertEquals(LocalDate.of(2026, 9, 2), GuideTimeline.displayDateAt(earlyMorning, zone))
        assertEquals(LocalDate.of(2026, 9, 1), GuideTimeline.broadcastDateAt(earlyMorning, zone))
    }

    @Test
    fun `date jumps are clamped to available programme dates`() {
        val earliest = LocalDate.of(2026, 8, 30)
        val latest = LocalDate.of(2026, 9, 7)

        assertEquals(earliest, GuideTimeline.clampDate(earliest.minusDays(1), earliest, latest))
        assertEquals(anchor, GuideTimeline.clampDate(anchor, earliest, latest))
        assertEquals(latest, GuideTimeline.clampDate(latest.plusDays(1), earliest, latest))
    }

    @Test
    fun `three day window surrounds anchor day`() {
        assertEquals(
            LocalDate
                .of(2026, 8, 31)
                .atTime(4, 0)
                .atZone(zone)
                .toInstant(),
            GuideTimeline.windowStart(anchor, zone),
        )
        assertEquals(
            LocalDate
                .of(2026, 9, 3)
                .atTime(4, 0)
                .atZone(zone)
                .toInstant(),
            GuideTimeline.windowEnd(anchor, zone),
        )
    }

    @Test
    fun `forward rebase preserves visible instant`() {
        val oldStart = GuideTimeline.windowStart(anchor, zone)
        val newStart = GuideTimeline.windowStart(anchor.plusDays(1), zone)
        val minuteHeight = 7.5f
        val oldOffset =
            GuideTimeline.offsetForInstant(
                oldStart,
                LocalDate
                    .of(2026, 9, 2)
                    .atTime(8, 30)
                    .atZone(zone)
                    .toInstant(),
                minuteHeight,
            )

        val newOffset = GuideTimeline.rebaseOffset(oldStart, newStart, oldOffset, minuteHeight)

        assertEquals(
            GuideTimeline.instantAtOffset(oldStart, oldOffset, minuteHeight),
            GuideTimeline.instantAtOffset(newStart, newOffset, minuteHeight),
        )
    }

    @Test
    fun `backward rebase preserves visible instant`() {
        val oldStart = GuideTimeline.windowStart(anchor, zone)
        val newStart = GuideTimeline.windowStart(anchor.minusDays(1), zone)
        val minuteHeight = 7.5f
        val oldOffset =
            GuideTimeline.offsetForInstant(
                oldStart,
                LocalDate
                    .of(2026, 8, 31)
                    .atTime(20, 0)
                    .atZone(zone)
                    .toInstant(),
                minuteHeight,
            )

        val newOffset = GuideTimeline.rebaseOffset(oldStart, newStart, oldOffset, minuteHeight)

        assertEquals(
            GuideTimeline.instantAtOffset(oldStart, oldOffset, minuteHeight),
            GuideTimeline.instantAtOffset(newStart, newOffset, minuteHeight),
        )
    }

    @Test
    fun `initial selection follows the visible time instead of the first programme`() {
        val first = program("2026-09-01T04:00:00Z", "2026-09-01T05:00:00Z")
        val current = program("2026-09-01T12:00:00Z", "2026-09-01T13:00:00Z")

        assertEquals(
            1,
            GuideTimeline.programIndexAt(
                listOf(first, current),
                Instant.parse("2026-09-01T12:30:00Z"),
            ),
        )
    }

    @Test
    fun `initial selection uses the nearest programme across a schedule gap`() {
        val first = program("2026-09-01T04:00:00Z", "2026-09-01T05:00:00Z")
        val next = program("2026-09-01T08:00:00Z", "2026-09-01T09:00:00Z")

        assertEquals(
            1,
            GuideTimeline.programIndexAt(
                listOf(first, next),
                Instant.parse("2026-09-01T07:30:00Z"),
            ),
        )
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
        title = "Program",
        description = "",
        startAt = Instant.parse(start),
        endAt = Instant.parse(end),
    )
}
