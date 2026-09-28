package net.rokoucha.visiomata.playback.media3

/** Header-compressed IP context header types (ARIB STD-B32 Part 3, Table No.10 No.2). 0x21 (IPv4 identifier) carries no header to skip; ROHC packets are rejected. */
internal object HeaderCompressionType {
    const val PARTIAL_IPV4 = 0x20
    const val IPV4_IDENTIFIER = 0x21
    const val PARTIAL_IPV6 = 0x60
    const val NO_HEADER = 0x61
}

/**
 * One MMTP datagram inside a TLV payload. The bytes are a view of the TLV
 * packet payload owned by the caller and stay valid as long as it does.
 */
internal class MmtDatagram(
    val bytes: ByteArray,
    val offset: Int,
    val length: Int,
)

/**
 * Extracts the MMTP datagram from an IP-carrying TLV packet (ARIB STD-B32
 * Part 3, 3.7): plain IPv4/UDP, IPv6/UDP, or header-compressed IP (HCfB).
 * Returns null for TLV types without IP content, for non-UDP IP packets, for
 * unknown compression types, and for truncated headers.
 */
internal object TlvMmtDeframer {
    fun deframe(packet: TlvPacket): MmtDatagram? {
        val payload = packet.payload
        return when (packet.packetType) {
            TlvPacketType.IPV4 -> deframeIpv4(payload)
            TlvPacketType.IPV6 -> deframeIpv6(payload)
            TlvPacketType.COMPRESSED_IP -> deframeCompressed(payload)
            else -> null
        }
    }

    private fun deframeIpv4(payload: ByteArray): MmtDatagram? {
        if (payload.size < IPV4_MIN_HEADER_SIZE + UDP_HEADER_SIZE) return null
        val headerLength = (payload[0].toInt() and 0x0f) * 4
        if (headerLength < IPV4_MIN_HEADER_SIZE || payload.size < headerLength + UDP_HEADER_SIZE) return null
        if ((payload[9].toInt() and 0xff) != IP_PROTOCOL_UDP) return null
        val udpLength = u16(payload, headerLength + 4)
        if (udpLength < UDP_HEADER_SIZE || payload.size < headerLength + udpLength) return null
        val offset = headerLength + UDP_HEADER_SIZE
        return MmtDatagram(payload, offset, udpLength - UDP_HEADER_SIZE)
    }

    private fun deframeIpv6(payload: ByteArray): MmtDatagram? {
        if (payload.size < IPV6_HEADER_SIZE + UDP_HEADER_SIZE) return null
        if ((payload[6].toInt() and 0xff) != IP_PROTOCOL_UDP) return null
        val udpLength = u16(payload, IPV6_HEADER_SIZE + 4)
        if (udpLength < UDP_HEADER_SIZE || payload.size < IPV6_HEADER_SIZE + udpLength) return null
        val offset = IPV6_HEADER_SIZE + UDP_HEADER_SIZE
        return MmtDatagram(payload, offset, udpLength - UDP_HEADER_SIZE)
    }

    private fun deframeCompressed(payload: ByteArray): MmtDatagram? {
        if (payload.size < COMPRESSED_HEADER_SIZE) return null
        return when (payload[2].toInt() and 0xff) {
            HeaderCompressionType.NO_HEADER -> {
                MmtDatagram(payload, COMPRESSED_HEADER_SIZE, payload.size - COMPRESSED_HEADER_SIZE)
            }

            HeaderCompressionType.PARTIAL_IPV6 -> {
                // Partial IPv6 header (without payload length: 38 bytes) plus
                // partial UDP header (without length and checksum: 4 bytes).
                val offset = COMPRESSED_HEADER_SIZE + PARTIAL_IPV6_BYTES + PARTIAL_UDP_BYTES
                if (payload.size < offset) return null
                MmtDatagram(payload, offset, payload.size - offset)
            }

            HeaderCompressionType.PARTIAL_IPV4 -> {
                // Partial IPv4 header (without length, checksum, options: 12
                // bytes) plus partial UDP header (4 bytes).
                val offset = COMPRESSED_HEADER_SIZE + PARTIAL_IPV4_BYTES + PARTIAL_UDP_BYTES
                if (payload.size < offset) return null
                MmtDatagram(payload, offset, payload.size - offset)
            }

            else -> {
                null
            }
        }
    }

    private const val IP_PROTOCOL_UDP = 17
    private const val IPV4_MIN_HEADER_SIZE = 20
    private const val IPV6_HEADER_SIZE = 40
    private const val UDP_HEADER_SIZE = 8
    private const val COMPRESSED_HEADER_SIZE = 3
    private const val PARTIAL_IPV4_BYTES = 12
    private const val PARTIAL_IPV6_BYTES = 38
    private const val PARTIAL_UDP_BYTES = 4
}
