package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class TsStreamRelayTest {
    @Test
    fun publishCountsEveryByteForThroughputStats() {
        val relay = TsStreamRelay()
        val first = Random.nextBytes(1_879)
        val second = Random.nextBytes(188 * 7)

        relay.publish(first, 0, first.size)
        relay.publish(second, 0, second.size)

        assertEquals((first.size + second.size).toLong(), relay.totalBytesPublished)
    }

    @Test
    fun byteCounterSurvivesStreamReset() {
        val relay = TsStreamRelay()
        val chunk = Random.nextBytes(188)

        relay.publish(chunk, 0, chunk.size)
        relay.reset()
        relay.publish(chunk, 0, chunk.size)

        // Reconnects must not zero the intake totals the stats log derives rates from.
        assertEquals((chunk.size * 2).toLong(), relay.totalBytesPublished)
    }
}
