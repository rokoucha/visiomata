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
    fun `retains every future programme instead of only current and next`() {
        val expired = program(1, startAt = now - 3_600_000L, duration = 1_800_000L)
        val current = program(2, startAt = now - 600_000L)
        val next = program(3, startAt = now + 1_200_000L)
        val later = program(4, startAt = now + 3_000_000L)
        val farFuture = program(5, startAt = now + 5_000_000L)

        val retained = listOf(farFuture, expired, later, next, current).filterFuturePrograms(now)

        // Truncating here to current plus next used to wipe the guide cache on
        // every home refresh; the home query itself limits display to two.
        assertEquals(listOf(farFuture, later, next, current), retained)
    }

    @Test
    fun `drops programmes ending exactly now`() {
        val endingNow = program(1, startAt = now - 1_800_000L, duration = 1_800_000L)
        val upcoming = program(2, startAt = now + 600_000L)

        val retained = listOf(endingNow, upcoming).filterFuturePrograms(now)

        assertEquals(listOf(upcoming), retained)
    }
}
