package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsAudioPmtMonitorTest {
    @Test
    fun addedAacPidRequestsOneRefreshWithoutBmlAcrossFragmentedReads() {
        val notifications = mutableListOf<Long>()
        val relay = TsStreamRelay(onAudioPidsAdded = notifications::add)
        relay.reset()
        val initialGeneration = relay.generation
        val stream =
            packet(0, pat()) +
                packet(100, pmt(listOf(200))) +
                packet(100, pmt(listOf(200, 201)), counter = 1) +
                packet(100, pmt(listOf(200, 201)), counter = 2)
        // Network reads can split both TS headers and section bodies at any byte.
        stream.forEach { relay.publish(byteArrayOf(it), 0, 1) }
        assertEquals(listOf(initialGeneration), notifications)
        relay.invalidate()
        assertTrue(relay.generation != initialGeneration)

        relay.reset()
        val newGeneration = relay.generation
        val nextStream =
            packet(0, pat()) + packet(100, pmt(listOf(200, 201))) +
                packet(100, pmt(listOf(200, 201, 202)), counter = 1)
        relay.publish(nextStream, 0, nextStream.size)
        assertEquals(listOf(initialGeneration, newGeneration), notifications)
    }

    @Test
    fun repeatedRemovedAndReturningInitialPidsDoNotRefresh() {
        var refreshes = 0
        val monitor = TsAudioPmtMonitor { refreshes++ }
        monitor.consume(0, pat())
        monitor.consume(100, pmt(listOf(200, 201)))
        monitor.consume(100, pmt(listOf(201, 200), version = 31))
        monitor.consume(100, pmt(listOf(200), version = 0))
        monitor.consume(100, pmt(listOf(200, 201), version = 1))
        assertEquals(0, refreshes)
        monitor.consume(100, pmt(listOf(202), version = 2))
        assertEquals(1, refreshes)
    }

    @Test
    fun nonAacAdditionsDoNotRefresh() {
        var refreshes = 0
        val monitor = TsAudioPmtMonitor { refreshes++ }
        monitor.consume(0, pat())
        monitor.consume(100, pmt(listOf(200)))
        val videoAdded = pmt(listOf(200, 201)).dropLast(4).toByteArray().apply { this[17] = 0x02 }
        monitor.consume(100, withCrc(videoAdded))
        assertEquals(0, refreshes)
    }

    @Test
    fun notificationFromInvalidatedConnectionKeepsItsOldGeneration() {
        val notifications = mutableListOf<Long>()
        val relay = TsStreamRelay(onAudioPidsAdded = notifications::add)
        relay.reset()
        val oldGeneration = relay.generation
        val initial = packet(0, pat()) + packet(100, pmt(listOf(200)))
        relay.publish(initial, 0, initial.size)
        relay.invalidate()
        val added = packet(100, pmt(listOf(200, 201)), counter = 1)
        relay.publish(added, 0, added.size)
        assertEquals(listOf(oldGeneration), notifications)
        assertTrue(notifications.single() != relay.generation)
    }

    @Test
    fun firstPmtWinsAndOtherProgramsAreIgnored() {
        var refreshes = 0
        val monitor = TsAudioPmtMonitor { refreshes++ }
        monitor.consume(0, pat(100 to 1, 101 to 2))
        monitor.consume(101, pmt(listOf(300), program = 2))
        monitor.consume(100, pmt(listOf(200), program = 1))
        monitor.consume(100, pmt(listOf(200, 201), program = 1))
        monitor.consume(101, pmt(listOf(300, 301), program = 1))
        assertEquals(0, refreshes)
        monitor.consume(101, pmt(listOf(300, 301), program = 2))
        assertEquals(1, refreshes)
    }

    @Test
    fun emptyInitialAudioAndVersionWrapAreSupported() {
        var refreshes = 0
        val monitor = TsAudioPmtMonitor { refreshes++ }
        monitor.consume(0, pat())
        monitor.consume(100, pmt(emptyList(), version = 31))
        monitor.consume(100, pmt(listOf(200), version = 0))
        assertEquals(1, refreshes)
    }

    @Test
    fun invalidCrcFutureTablesAndMalformedDescriptorsDoNotRefresh() {
        var refreshes = 0
        val monitor = TsAudioPmtMonitor { refreshes++ }
        monitor.consume(0, pat())
        monitor.consume(100, pmt(listOf(200)))
        val added = pmt(listOf(200, 201))
        monitor.consume(100, added.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        monitor.consume(100, pmt(listOf(200, 201), current = false))
        // A valid CRC cannot make an out-of-bounds ES descriptor length valid.
        monitor.consume(100, withCrc(added.dropLast(4).toByteArray().apply { this[16] = 0x7f }))
        assertEquals(0, refreshes)
        monitor.consume(100, added)
        assertEquals(1, refreshes)
    }

    @Test
    fun sectionSpanningPacketsSurvivesDuplicatesButNotLoss() {
        val notifications = mutableListOf<Long>()
        val relay = TsStreamRelay(onAudioPidsAdded = notifications::add)
        relay.reset()
        val initial = packet(0, pat()) + packet(100, pmt(listOf(200)))
        relay.publish(initial, 0, initial.size)
        val large = pmt((200..240).toList())
        val first = packet(100, large.copyOfRange(0, 183), counter = 1)
        val lost = packet(100, large.copyOfRange(183, large.size), counter = 3, unitStart = false)
        val broken = first + first + lost
        relay.publish(broken, 0, broken.size)
        assertTrue(notifications.isEmpty())
        val validStart = packet(100, large.copyOfRange(0, 183), counter = 4)
        val valid =
            validStart + validStart +
                packet(100, large.copyOfRange(183, large.size), counter = 5, unitStart = false)
        relay.publish(valid, 0, valid.size)
        assertEquals(listOf(relay.generation), notifications)
    }

    @Test
    fun assemblerCompletesPreviousSectionBeforePointerAndRejectsBadPointer() {
        val assembler = PsiSectionAssembler()
        val section = pmt(listOf(200))
        assertTrue(assembler.push(byteArrayOf(0) + section.take(10), 0, 11, true, 0).isEmpty())
        val remaining = section.size - 10
        val payload = byteArrayOf(remaining.toByte()) + section.drop(10) + section
        val sections = assembler.push(payload, 0, payload.size, true, 1)
        assertEquals(2, sections.size)
        assertTrue(sections.all { it.contentEquals(section) })
        assertTrue(assembler.push(byteArrayOf(100), 0, 1, true, 2).isEmpty())
        assertTrue(assembler.push(section, 0, section.size, false, 3).isEmpty())
    }

    private fun packet(
        pid: Int,
        section: ByteArray,
        counter: Int = 0,
        unitStart: Boolean = true,
    ): ByteArray =
        ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = ((pid shr 8) or if (unitStart) 0x40 else 0).toByte()
            this[2] = pid.toByte()
            this[3] = (0x10 or counter).toByte()
            if (unitStart) this[4] = 0
            section.copyInto(this, if (unitStart) 5 else 4)
        }

    private fun pat(vararg programs: Pair<Int, Int> = arrayOf(100 to 1)): ByteArray =
        section(
            0,
            1,
            0,
            true,
            programs.flatMap { (pid, program) ->
                listOf(program shr 8, program, 0xe0 or (pid shr 8), pid)
            },
        )

    private fun pmt(
        audioPids: List<Int>,
        program: Int = 1,
        version: Int = 0,
        current: Boolean = true,
    ): ByteArray =
        section(
            2,
            program,
            version,
            current,
            listOf(0xe0, 200, 0xf0, 0) + audioPids.flatMap { listOf(0x0f, 0xe0 or (it shr 8), it, 0xf0, 0) },
        )

    private fun section(
        table: Int,
        program: Int,
        version: Int,
        current: Boolean,
        body: List<Int>,
    ): ByteArray {
        val length = body.size + 9
        return withCrc(
            (
                listOf(
                    table,
                    0xb0 or (length shr 8),
                    length,
                    program shr 8,
                    program,
                    0xc0 or (version shl 1) or if (current) 1 else 0,
                    0,
                    0,
                ) + body
            ).map(Int::toByte).toByteArray(),
        )
    }

    private fun withCrc(bytes: ByteArray): ByteArray {
        var crc = 0xffffffffL
        bytes.forEach { byte ->
            crc = crc xor ((byte.toLong() and 0xff) shl 24)
            repeat(8) {
                crc = ((crc shl 1) xor if (crc and 0x80000000L != 0L) 0x04c11db7 else 0) and 0xffffffffL
            }
        }
        return bytes + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }
}
