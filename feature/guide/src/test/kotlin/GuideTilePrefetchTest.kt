package net.rokoucha.visiomata.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTilePrefetchTest {
    @Test
    fun prefetchFitsCacheAndKeepsEveryVisibleTile() {
        val requests = guideTilesToPrepare(2..6, 10L..12L, 20, 96, 24, 1)
        val visible = (2..6).flatMap { station -> (10L..12L).map { station to it } }.toSet()
        assertEquals(24, requests.size)
        assertEquals(visible, requests.take(visible.size).toSet())
        assertEquals(requests.size, requests.distinct().size)
    }

    @Test
    fun prefetchStaysInsideGuideAtEdges() {
        val requests = guideTilesToPrepare(0..1, 0L..1L, 2, 2, 24, 1)
        assertEquals(setOf(0 to 0L, 0 to 1L, 1 to 0L, 1 to 1L), requests.toSet())
    }

    @Test
    fun smallCacheNeverPrefetchesHiddenTilesBeforeVisibleTiles() {
        val requests = guideTilesToPrepare(2..6, 10L..12L, 20, 96, 4, 1)
        assertEquals(4, requests.size)
        assertTrue(requests.all { (station, tile) -> station in 2..6 && tile in 10L..12L })
    }
}
