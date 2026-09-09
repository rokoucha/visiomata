package net.rokoucha.visiomata.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSnapshotTest {
    private val now = 1_000_000L

    private fun program(
        id: Long,
        networkId: Int = 1,
        serviceId: Int = 100,
        startAt: Long = now,
        duration: Long = 1_800_000L,
    ) = ProgramDto(
        id = id,
        eventId = id.toInt(),
        serviceId = serviceId,
        networkId = networkId,
        startAt = startAt,
        duration = duration,
    )

    @Test
    fun `keeps current and next programmes per service`() {
        val key = 1 to 100
        val expired = program(1, startAt = now - 3_600_000L, duration = 1_800_000L)
        val current = program(2, startAt = now - 600_000L)
        val next = program(3, startAt = now + 1_200_000L)
        val later = program(4, startAt = now + 3_000_000L)
        val farFuture = program(5, startAt = now + 5_000_000L)

        val selected = selectHomePrograms(mapOf(key to listOf(farFuture, expired, later, next, current)), now)

        assertEquals(mapOf(key to listOf(current, next)), selected)
    }

    @Test
    fun `drops programmes ending exactly now and services without future programmes`() {
        val endingNow = program(1, startAt = now - 1_800_000L, duration = 1_800_000L)
        val upcoming = program(2, startAt = now + 600_000L)

        val selected =
            selectHomePrograms(
                mapOf(
                    (1 to 100) to listOf(endingNow),
                    (1 to 200) to listOf(upcoming),
                ),
                now,
            )

        assertEquals(mapOf((1 to 200) to listOf(upcoming)), selected)
    }

    @Test
    fun `sorts out-of-order batches by start time`() {
        val key = 2 to 300
        val first = program(1, networkId = 2, serviceId = 300, startAt = now - 100_000L)
        val second = program(2, networkId = 2, serviceId = 300, startAt = now + 1_000_000L)

        val selected = selectHomePrograms(mapOf(key to listOf(second, first)), now)

        assertEquals(mapOf(key to listOf(first, second)), selected)
    }
}
