package net.rokoucha.visiomata.playback.mpeg2toh264

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeinterlaceMetadataQueueTest {
    @Test
    fun takeReturnsExactTimestampAndPrunesOlderEntries() {
        val queue = DeinterlaceMetadataQueue()
        val older = DeinterlaceFrameInfo(interlaced = true, topFieldFirst = true)
        val matched = DeinterlaceFrameInfo(interlaced = true, topFieldFirst = false)
        queue.record(1_000, older)
        queue.record(2_000, matched)

        assertEquals(matched, queue.take(2_000))
        assertNull(queue.take(1_000))
    }

    @Test
    fun takeDoesNotConsumeNewerEntries() {
        val queue = DeinterlaceMetadataQueue()
        val first = DeinterlaceFrameInfo(interlaced = false, topFieldFirst = false)
        val second = DeinterlaceFrameInfo(interlaced = true, topFieldFirst = true)
        queue.record(1_000, first)
        queue.record(2_000, second)

        assertEquals(first, queue.take(1_000))
        assertEquals(second, queue.take(2_000))
    }

    @Test
    fun recordDropsOldestEntryAtCapacity() {
        val queue = DeinterlaceMetadataQueue(maxEntries = 2)
        val info = DeinterlaceFrameInfo.InterlacedTopFieldFirst
        queue.record(1_000, info)
        queue.record(2_000, info)
        queue.record(3_000, info)

        assertNull(queue.take(1_000))
        assertEquals(info, queue.take(2_000))
        assertEquals(info, queue.take(3_000))
    }

    @Test
    fun clearRemovesEveryEntry() {
        val queue = DeinterlaceMetadataQueue()
        queue.record(1_000, DeinterlaceFrameInfo.InterlacedTopFieldFirst)

        queue.clear()

        assertNull(queue.take(1_000))
    }
}
