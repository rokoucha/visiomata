package net.rokoucha.visiomata.playback.media3

/**
 * TLV packet type assignments (ARIB STD-B32 Part 3, Table No.11), as used by the
 * ARIB STD-B60 MMT/TLV broadcast system.
 */
internal object TlvPacketType {
    const val IPV4 = 0x01
    const val IPV6 = 0x02
    const val COMPRESSED_IP = 0x03
    const val CONTROL = 0xFE
    const val NULL = 0xFF

    fun isKnown(packetType: Int): Boolean =
        packetType == IPV4 ||
            packetType == IPV6 ||
            packetType == COMPRESSED_IP ||
            packetType == CONTROL ||
            packetType == NULL
}

/**
 * One parsed TLV packet. [payload] is a copy of the packet's data bytes and stays
 * valid after the demuxer advances.
 */
internal class TlvPacket(
    val packetType: Int,
    val payload: ByteArray,
)

/**
 * Streaming TLV packet demuxer.
 *
 * Each TLV packet is a 4-byte header (sync byte `0x7F`, packet type, 16-bit big-endian
 * data length) followed by the data bytes. This demuxer scans pushed bytes for the sync
 * byte, parses complete packets, and emits every known packet type except Null through
 * [onPacket]. Null packets carry only `0xFF` stuffing, so they are counted in
 * [nullPacketsSkipped] instead of being emitted. Bytes that cannot frame a packet
 * (garbage before sync, or a sync byte followed by an unassigned packet type) are
 * discarded and counted in [bytesSkipped].
 *
 * Incomplete trailing packets are buffered until the rest arrives; [pendingByteCount]
 * reports how many bytes are held. All counters are cumulative and survive [reset],
 * which only drops the buffered partial packet.
 */
internal class TlvDemuxer(
    private val onPacket: (TlvPacket) -> Unit = {},
) {
    val totalBytesReceived: Long
        get() = receivedBytes
    val packetsEmitted: Long
        get() = emittedPackets
    val nullPacketsSkipped: Long
        get() = skippedNullPackets
    val bytesSkipped: Long
        get() = skippedBytes
    val pendingByteCount: Int
        get() = pendingSize

    private var receivedBytes = 0L
    private var emittedPackets = 0L
    private var skippedNullPackets = 0L
    private var skippedBytes = 0L

    private var pending = ByteArray(INITIAL_BUFFER_SIZE)
    private var pendingSize = 0

    fun push(
        source: ByteArray,
        offset: Int = 0,
        length: Int = source.size - offset,
    ) {
        if (length == 0) return
        if (pending.size < pendingSize + length) {
            pending = pending.copyOf(maxOf(pendingSize + length, pending.size * 2))
        }
        source.copyInto(pending, pendingSize, offset, offset + length)
        pendingSize += length
        receivedBytes += length
        var position = 0
        try {
            while (position < pendingSize) {
                val sync = indexOfSync(position)
                skippedBytes += (sync - position).toLong()
                position = sync
                if (pendingSize - position < HEADER_SIZE) break
                val packetType = pending[position + 1].toInt() and 0xff
                if (!TlvPacketType.isKnown(packetType)) {
                    skippedBytes++
                    position++
                    continue
                }
                val dataLength = u16(position + 2)
                if (pendingSize - position < HEADER_SIZE + dataLength) break
                position += HEADER_SIZE + dataLength
                if (packetType == TlvPacketType.NULL) {
                    skippedNullPackets++
                } else {
                    emittedPackets++
                    onPacket(TlvPacket(packetType, pending.copyOfRange(position - dataLength, position)))
                }
            }
        } finally {
            if (position > 0) {
                pending.copyInto(pending, 0, position, pendingSize)
                pendingSize -= position
            }
            if (pending.size > MAX_BUFFERED_SIZE) {
                pending = pending.copyOf(MAX_BUFFERED_SIZE)
            }
        }
    }

    fun reset() {
        pendingSize = 0
    }

    private fun indexOfSync(from: Int): Int {
        var position = from
        while (position < pendingSize && (pending[position].toInt() and 0xff) != SYNC_BYTE) {
            position++
        }
        return position
    }

    private fun u16(offset: Int): Int =
        ((pending[offset].toInt() and 0xff) shl 8) or (pending[offset + 1].toInt() and 0xff)

    private companion object {
        const val SYNC_BYTE = 0x7F
        const val HEADER_SIZE = 4
        const val MAX_DATA_LENGTH = 0xFFFF
        const val MAX_BUFFERED_SIZE = HEADER_SIZE + MAX_DATA_LENGTH
        const val INITIAL_BUFFER_SIZE = 8192
    }
}
