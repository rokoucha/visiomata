package net.rokoucha.visiomata.guide

import net.rokoucha.visiomata.model.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class GuideTileContentTest {
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val end = start.plusSeconds(7200)
    private val program =
        Program(
            id = 1,
            eventId = 1,
            networkId = 1,
            transportStreamId = 1,
            serviceId = 1,
            title = "番組",
            description = "説明",
            startAt = start,
            endAt = end,
        )

    @Test
    fun updatesOutsideTileKeepCachedContent() {
        val outside = program.copy(id = 2, startAt = end, endAt = end.plusSeconds(3600))
        assertEquals(
            guideTileContent(listOf(program, outside), start, end),
            guideTileContent(listOf(program, outside.copy(title = "更新")), start, end),
        )
    }

    @Test
    fun updatesAndRemovalInsideTileInvalidateCachedContent() {
        val original = guideTileContent(listOf(program), start, end)
        assertNotEquals(original, guideTileContent(listOf(program.copy(title = "更新")), start, end))
        assertNotEquals(original, guideTileContent(emptyList(), start, end))
    }

    @Test
    fun crossingProgramsInvalidateBothTilesButBoundaryProgramsDoNotOverlap() {
        val crossing = program.copy(startAt = start.minusSeconds(60), endAt = end.plusSeconds(60))
        val schedule = listOf(program.copy(endAt = start), crossing, program.copy(startAt = end))
        assertEquals(listOf(crossing), guideTileContent(schedule, start, end).programs)
        val updated = crossing.copy(description = "更新")
        for (tileStart in listOf(start, end)) {
            assertNotEquals(
                guideTileContent(listOf(crossing), tileStart, tileStart.plusSeconds(7200)),
                guideTileContent(listOf(updated), tileStart, tileStart.plusSeconds(7200)),
            )
        }
    }
}
