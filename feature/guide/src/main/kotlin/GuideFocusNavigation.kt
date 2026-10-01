package net.rokoucha.visiomata.guide

import net.rokoucha.visiomata.model.Program
import java.time.Duration
import java.time.Instant

internal data class GuideFocusRange(
    val start: Instant,
    val end: Instant,
) {
    fun intersecting(program: Program): GuideFocusRange {
        val intersectionStart = maxOf(start, program.startAt)
        val intersectionEnd = minOf(end, program.endAt)
        return if (intersectionStart < intersectionEnd) {
            GuideFocusRange(intersectionStart, intersectionEnd)
        } else {
            of(program)
        }
    }

    companion object {
        fun of(program: Program) = GuideFocusRange(program.startAt, program.endAt)
    }
}

internal fun horizontalProgramIndex(
    schedule: List<Program>,
    focusRange: GuideFocusRange,
): Int? {
    if (schedule.isEmpty()) return null

    schedule
        .indexOfFirst { it.startAt <= focusRange.start && it.endAt >= focusRange.end }
        .takeIf { it >= 0 }
        ?.let { return it }

    val fullyOverlapping =
        schedule.indices.filter {
            schedule[it].startAt >= focusRange.start && schedule[it].endAt <= focusRange.end
        }
    if (fullyOverlapping.isNotEmpty()) {
        return fullyOverlapping.maxBy { Duration.between(schedule[it].startAt, schedule[it].endAt) }
    }

    schedule.indices
        .filter { schedule[it].startAt < focusRange.end && schedule[it].endAt > focusRange.start }
        .maxByOrNull {
            Duration.between(
                maxOf(schedule[it].startAt, focusRange.start),
                minOf(schedule[it].endAt, focusRange.end),
            )
        }?.let { return it }

    return null
}

internal fun horizontalProgramSelection(
    schedules: List<List<Program>>,
    currentStation: Int,
    direction: Int,
    focusRange: GuideFocusRange,
): Pair<Int, Int>? {
    require(direction == -1 || direction == 1)
    var station = currentStation + direction
    while (station in schedules.indices) {
        horizontalProgramIndex(schedules[station], focusRange)?.let { program ->
            return station to program
        }
        station += direction
    }
    return null
}

internal fun revealProgramOffset(
    viewportOffset: Float,
    viewportHeight: Float,
    programTop: Float,
    programBottom: Float,
    preserveWhenVisible: Boolean = false,
): Float =
    when {
        preserveWhenVisible && programTop < viewportOffset + viewportHeight && programBottom > viewportOffset -> {
            viewportOffset
        }

        programBottom <= viewportOffset -> {
            programTop
        }

        programTop >= viewportOffset + viewportHeight -> {
            programTop
        }

        programBottom - programTop <= viewportHeight && programTop < viewportOffset -> {
            programTop
        }

        programBottom - programTop <= viewportHeight && programBottom > viewportOffset + viewportHeight -> {
            programBottom - viewportHeight
        }

        else -> {
            viewportOffset
        }
    }
