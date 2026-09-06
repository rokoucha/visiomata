package net.rokoucha.visiomata.guide

import net.rokoucha.visiomata.model.Program
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

object GuideTimeline {
    const val broadcastDayStartHour = 4
    const val minutesPerDay = 24 * 60
    const val windowDays = 3
    const val leadingDays = 1

    fun windowStart(
        anchorDate: LocalDate,
        zone: ZoneId,
    ): Instant =
        anchorDate
            .minusDays(leadingDays.toLong())
            .atTime(broadcastDayStartHour, 0)
            .atZone(zone)
            .toInstant()

    fun windowEnd(
        anchorDate: LocalDate,
        zone: ZoneId,
    ): Instant = windowStart(anchorDate, zone).plus(Duration.ofDays(windowDays.toLong()))

    fun broadcastDateAt(
        instant: Instant,
        zone: ZoneId,
    ): LocalDate {
        val local = instant.atZone(zone)
        return if (local.hour < broadcastDayStartHour) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    fun displayDateAt(
        instant: Instant,
        zone: ZoneId,
    ): LocalDate = instant.atZone(zone).toLocalDate()

    fun clampDate(
        date: LocalDate,
        earliest: LocalDate?,
        latest: LocalDate?,
    ): LocalDate =
        when {
            earliest != null && date < earliest -> earliest
            latest != null && date > latest -> latest
            else -> date
        }

    fun instantAtOffset(
        windowStart: Instant,
        offsetPx: Float,
        minuteHeightPx: Float,
    ): Instant = windowStart.plusMillis((offsetPx / minuteHeightPx * 60_000).toLong())

    fun offsetForInstant(
        windowStart: Instant,
        instant: Instant,
        minuteHeightPx: Float,
    ): Float = Duration.between(windowStart, instant).toMillis() / 60_000f * minuteHeightPx

    fun rebaseOffset(
        oldWindowStart: Instant,
        newWindowStart: Instant,
        oldOffsetPx: Float,
        minuteHeightPx: Float,
    ): Float =
        offsetForInstant(
            newWindowStart,
            instantAtOffset(oldWindowStart, oldOffsetPx, minuteHeightPx),
            minuteHeightPx,
        )

    fun programIndexAt(
        schedule: List<Program>,
        instant: Instant,
    ): Int {
        val containing = schedule.indexOfFirst { instant >= it.startAt && instant < it.endAt }
        if (containing >= 0) return containing
        return schedule.indices.minByOrNull {
            minOf(
                abs(Duration.between(instant, schedule[it].startAt).toMillis()),
                abs(Duration.between(instant, schedule[it].endAt).toMillis()),
            )
        } ?: 0
    }
}
