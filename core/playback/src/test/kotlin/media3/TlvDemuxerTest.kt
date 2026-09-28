package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlvDemuxerTest {
    private fun packet(
        packetType: Int,
        payload: ByteArray,
    ): ByteArray =
        byteArrayOf(
            0x7F.toByte(),
            packetType.toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
        ) + payload

    private fun collect(bytes: ByteArray): List<TlvPacket> {
        val packets = mutableListOf<TlvPacket>()
        TlvDemuxer(onPacket = packets::add).push(bytes)
        return packets
    }

    @Test
    fun emitsSinglePacketWithTypeAndPayload() {
        val payload = byteArrayOf(0x60, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04)

        val packets = collect(packet(TlvPacketType.IPV6, payload))

        assertEquals(1, packets.size)
        assertEquals(TlvPacketType.IPV6, packets[0].packetType)
        assertArrayEquals(payload, packets[0].payload)
    }

    @Test
    fun emitsTypedPacketsAndSkipsNullPackets() {
        val demuxerPackets = mutableListOf<TlvPacket>()
        val demuxer = TlvDemuxer(onPacket = demuxerPackets::add)
        val stream =
            packet(TlvPacketType.IPV4, byteArrayOf(0x45)) +
                packet(TlvPacketType.NULL, ByteArray(3) { 0xFF.toByte() }) +
                packet(TlvPacketType.COMPRESSED_IP, byteArrayOf(0x61, 0x00)) +
                packet(TlvPacketType.CONTROL, byteArrayOf(0x40.toByte())) +
                packet(TlvPacketType.NULL, ByteArray(0))

        demuxer.push(stream)

        assertEquals(
            listOf(TlvPacketType.IPV4, TlvPacketType.COMPRESSED_IP, TlvPacketType.CONTROL),
            demuxerPackets.map { it.packetType },
        )
        assertEquals(3L, demuxer.packetsEmitted)
        assertEquals(2L, demuxer.nullPacketsSkipped)
        assertEquals(0L, demuxer.bytesSkipped)
    }

    @Test
    fun emitsZeroLengthPayload() {
        val packets = collect(packet(TlvPacketType.IPV6, ByteArray(0)))

        assertEquals(1, packets.size)
        assertTrue(packets[0].payload.isEmpty())
    }

    @Test
    fun holdsPartialHeaderAndPayloadAcrossPushes() {
        val packets = mutableListOf<TlvPacket>()
        val demuxer = TlvDemuxer(onPacket = packets::add)
        val first = packet(TlvPacketType.IPV6, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val second = packet(TlvPacketType.IPV4, byteArrayOf(0x06, 0x07))

        demuxer.push(first, 0, 2)
        assertTrue(packets.isEmpty())
        assertEquals(2, demuxer.pendingByteCount)

        demuxer.push(first, 2, 4)
        assertTrue(packets.isEmpty())

        demuxer.push(first, 6, first.size - 6)
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05), packets[0].payload)
        assertEquals(0, demuxer.pendingByteCount)

        second.forEach { demuxer.push(byteArrayOf(it)) }
        assertEquals(2, packets.size)
        assertEquals(TlvPacketType.IPV4, packets[1].packetType)
        assertEquals((first.size + second.size).toLong(), demuxer.totalBytesReceived)
    }

    @Test
    fun resyncsAfterGarbageAndUnknownPacketTypes() {
        val packets = mutableListOf<TlvPacket>()
        val demuxer = TlvDemuxer(onPacket = packets::add)
        val garbage = byteArrayOf(0x00, 0x47, 0x7F, 0x04, 0x00, 0x10)
        val valid = packet(TlvPacketType.COMPRESSED_IP, byteArrayOf(0x61))

        demuxer.push(garbage + valid)

        assertEquals(1, packets.size)
        assertEquals(TlvPacketType.COMPRESSED_IP, packets[0].packetType)
        assertEquals(garbage.size.toLong(), demuxer.bytesSkipped)
    }

    @Test
    fun resetDropsPartialPacketButKeepsCounters() {
        val packets = mutableListOf<TlvPacket>()
        val demuxer = TlvDemuxer(onPacket = packets::add)
        val partial = packet(TlvPacketType.IPV6, byteArrayOf(0x01, 0x02))[0].let { byteArrayOf(it) }

        demuxer.push(partial)
        assertEquals(1, demuxer.pendingByteCount)
        demuxer.reset()
        assertEquals(0, demuxer.pendingByteCount)

        demuxer.push(packet(TlvPacketType.IPV4, byteArrayOf(0x09)))

        assertEquals(1, packets.size)
        assertEquals(TlvPacketType.IPV4, packets[0].packetType)
        assertEquals(1L + 5L, demuxer.totalBytesReceived)
    }

    @Test
    fun keepsIncompleteTrailingPacketBuffered() {
        val packets = mutableListOf<TlvPacket>()
        val demuxer = TlvDemuxer(onPacket = packets::add)
        val complete = packet(TlvPacketType.IPV6, byteArrayOf(0x01))
        val next = packet(TlvPacketType.IPV4, byteArrayOf(0x02, 0x03, 0x04))

        demuxer.push(complete + next.copyOf(5))
        assertEquals(1, packets.size)
        assertEquals(5, demuxer.pendingByteCount)

        demuxer.push(next, 5, next.size - 5)
        assertEquals(2, packets.size)
        assertEquals(TlvPacketType.IPV4, packets[1].packetType)
        assertEquals(0, demuxer.pendingByteCount)
    }
}
