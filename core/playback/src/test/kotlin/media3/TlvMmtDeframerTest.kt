package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlvMmtDeframerTest {
    @Test
    fun deframesFullyCompressedPacket() {
        val mmt = bytes(0x06, 0xc0, 0xf3, 0x00, 0x01, 0x02)

        val datagram =
            requireNotNull(
                TlvMmtDeframer.deframe(
                    TlvPacket(
                        TlvPacketType.COMPRESSED_IP,
                        bytes(0x00, 0x10, 0x61) + mmt,
                    ),
                ),
            )

        assertEquals(3, datagram.offset)
        assertEquals(mmt.size, datagram.length)
        assertArrayEquals(mmt, datagram.bytes.copyOfRange(datagram.offset, datagram.offset + datagram.length))
    }

    @Test
    fun deframesPartialIpv6Packet() {
        val mmt = bytes(0x06, 0xc0, 0x00, 0x01)
        val payload = bytes(0x00, 0x15, 0x60) + ByteArray(42) { 0x11 } + mmt

        val datagram = requireNotNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.COMPRESSED_IP, payload)))

        assertEquals(45, datagram.offset)
        assertArrayEquals(mmt, datagram.bytes.copyOfRange(datagram.offset, datagram.offset + datagram.length))
    }

    @Test
    fun deframesIpv6UdpPacket() {
        val mmt = bytes(0x04, 0xc2, 0x00, 0x00, 0x09)
        val header =
            bytes(0x60, 0x00, 0x00, 0x00, 0x00, 0x10, 17, 32) +
                ByteArray(16) { 0x20.toByte() } +
                ByteArray(16) { 0xfd.toByte() }
        val udp = bytes(0x01, 0xc8, 0x17, 0x70) + u16Bytes(8 + mmt.size) + bytes(0x00, 0x00)

        val datagram = requireNotNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.IPV6, header + udp + mmt)))

        assertEquals(48, datagram.offset)
        assertEquals(mmt.size, datagram.length)
        assertArrayEquals(mmt, datagram.bytes.copyOfRange(datagram.offset, datagram.offset + datagram.length))
    }

    @Test
    fun deframesIpv4UdpPacket() {
        val mmt = bytes(0x06, 0xc0, 0x00, 0x02, 0x03)
        val ip =
            bytes(0x45, 0x00, 0x00, 0x21, 0x00, 0x00, 0x00, 0x00, 0x40, 17, 0x00, 0x00) +
                bytes(192, 168, 0, 1) + bytes(239, 0, 0, 1)
        val udp = bytes(0x08, 0x00, 0x08, 0x01) + u16Bytes(8 + mmt.size) + bytes(0x00, 0x00)

        val datagram = requireNotNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.IPV4, ip + udp + mmt)))

        assertEquals(28, datagram.offset)
        assertArrayEquals(mmt, datagram.bytes.copyOfRange(datagram.offset, datagram.offset + datagram.length))
    }

    @Test
    fun rejectsUnsupportedOrTruncatedInputs() {
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.NULL, bytes(0xff))))
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.CONTROL, bytes(0x01, 0x02))))
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x10, 0x62, 0x06))))
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x10))))
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x10, 0x60, 0x01))))

        val nonUdpV6 = ByteArray(48) { 0 }.also { it[6] = 6 }
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.IPV6, nonUdpV6)))
        assertNull(TlvMmtDeframer.deframe(TlvPacket(TlvPacketType.IPV4, ByteArray(10))))
    }
}
