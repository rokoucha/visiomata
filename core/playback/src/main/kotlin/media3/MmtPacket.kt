package net.rokoucha.visiomata.playback.media3

/**
 * MMTP payload types (ARIB STD-B60 Table 6-6). Generic object (0x01) is defined
 * by MMT but unused in broadcasting; repair symbols (0x03) are AL-FEC data.
 */
internal object MmtPayloadType {
    const val MPU = 0x00
    const val GENERIC_OBJECT = 0x01
    const val SIGNALING = 0x02
    const val REPAIR_SYMBOL = 0x03
}

/** MPU fragment types carried in an MPU payload (ARIB STD-B60 Table 6-2). */
internal object MmtFragmentType {
    const val MPU_METADATA = 0
    const val MOVIE_FRAGMENT_METADATA = 1
    const val MFU = 2
}

/** Fragmentation indicator shared by MPU and signaling payloads. */
internal object MmtFragmentationIndicator {
    const val NOT_FRAGMENTED = 0
    const val FIRST = 1
    const val MIDDLE = 2
    const val LAST = 3
}

/** Multi-type header extension assignments (ARIB STD-B60 Table 6-9). */
internal object MmtExtensionType {
    const val MULTI_TYPE = 0x0000
    const val SCRAMBLING = 0x0001
    const val DOWNLOAD_ID = 0x0002
    const val FILE_DIVISION = 0x0003
}

/**
 * MMT scrambling control values in the first byte of the 0x0001 extension
 * (ARIB STD-B61 Fig. 3-1 / Table 3-1). The control sits at bits 4-3 of the
 * first extension byte: reserved(3), control(2), subsystem(1), MAC(1), SICV(1).
 */
internal object MmtScramblingControl {
    const val UNSCRAMBLED = 0
    const val SCRAMBLED_EVEN = 2
    const val SCRAMBLED_ODD = 3

    fun of(firstByte: Int): Int = (firstByte shr 3) and 0x03
}

/** One header extension entry. [bytes] is a copy owned by the packet. */
internal class MmtExtensionEntry(
    val type: Int,
    val bytes: ByteArray,
)

/**
 * Parsed MMTP packet header plus a view of its payload.
 *
 * [payloadBytes], [payloadOffset] and [payloadLength] describe the payload in
 * the caller's datagram; the bytes are not copied. [timestamp] is the raw
 * 32-bit short-format NTP distribution timestamp; it measures packet emission,
 * not presentation, so the depacketizer only carries it for diagnostics.
 */
@Suppress("LongParameterList")
internal class MmtPacket(
    val payloadType: Int,
    val packetId: Int,
    val timestamp: Long,
    val packetSequenceNumber: Long,
    val rapFlag: Boolean,
    val extensionEntries: List<MmtExtensionEntry>,
    val payloadBytes: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    /** MMT scrambling control, or [MmtScramblingControl.UNSCRAMBLED] when the packet carries no scrambling extension. */
    fun scramblingControl(): Int {
        val entry = extensionEntries.firstOrNull { it.type == MmtExtensionType.SCRAMBLING }
        if (entry == null || entry.bytes.isEmpty()) return MmtScramblingControl.UNSCRAMBLED
        return MmtScramblingControl.of(entry.bytes[0].toInt() and 0xff)
    }
}

/**
 * Parses one MMTP packet header (ARIB STD-B60 6.4). Returns null when the
 * bytes cannot be an MMTP packet: wrong version, unsupported FEC type, unknown
 * payload type, or truncated header/extension. Packets with the packet counter
 * flag set are accepted and the counter is skipped.
 */
internal fun parseMmtPacket(
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
): MmtPacket? {
    if (length < MMTP_MIN_HEADER_SIZE) return null
    val b0 = bytes[offset].toInt() and 0xff
    val version = (b0 shr 6) and 0x03
    val packetCounterFlag = (b0 shr 5) and 0x01
    val fecType = (b0 shr 3) and 0x03
    val extensionFlag = (b0 shr 1) and 0x01
    val rapFlag = b0 and 0x01
    if (version != 0 || fecType != 0) return null
    val payloadType = bytes[offset + 1].toInt() and 0x3f
    if (
        payloadType != MmtPayloadType.MPU &&
        payloadType != MmtPayloadType.GENERIC_OBJECT &&
        payloadType != MmtPayloadType.SIGNALING &&
        payloadType != MmtPayloadType.REPAIR_SYMBOL
    ) {
        return null
    }
    val packetId = u16(bytes, offset + 2)
    val timestamp = u32(bytes, offset + 4)
    val packetSequenceNumber = u32(bytes, offset + 8)
    var position = offset + MMTP_MIN_HEADER_SIZE
    if (packetCounterFlag == 1) {
        if (length < MMTP_MIN_HEADER_SIZE + 4) return null
        position += 4
    }
    val extension =
        if (extensionFlag == 1) {
            parseExtensionEntries(bytes, position, offset + length) ?: return null
        } else {
            emptyList<MmtExtensionEntry>() to position
        }
    val entries = extension.first
    position = extension.second
    return MmtPacket(
        payloadType = payloadType,
        packetId = packetId,
        timestamp = timestamp,
        packetSequenceNumber = packetSequenceNumber,
        rapFlag = rapFlag == 1,
        extensionEntries = entries,
        payloadBytes = bytes,
        payloadOffset = position,
        payloadLength = offset + length - position,
    )
}

/** Parses the header extension area at [offset] (bounded by [end]); returns the entries and the payload offset. */
private fun parseExtensionEntries(
    bytes: ByteArray,
    offset: Int,
    end: Int,
): Pair<List<MmtExtensionEntry>, Int>? {
    if (end < offset + 4) return null
    val extensionType = u16(bytes, offset)
    val extensionLength = u16(bytes, offset + 2)
    val entriesOffset = offset + 4
    if (end < entriesOffset + extensionLength) return null
    if (extensionType != MmtExtensionType.MULTI_TYPE) {
        return listOf(
            MmtExtensionEntry(
                extensionType,
                bytes.copyOfRange(entriesOffset, entriesOffset + extensionLength),
            ),
        ) to entriesOffset + extensionLength
    }
    val entries = mutableListOf<MmtExtensionEntry>()
    var entryOffset = entriesOffset
    val entriesEnd = entriesOffset + extensionLength
    while (entryOffset < entriesEnd) {
        if (entriesEnd - entryOffset < 4) return null
        val endAndType = u16(bytes, entryOffset)
        val entryLength = u16(bytes, entryOffset + 2)
        entryOffset += 4
        if (entriesEnd - entryOffset < entryLength) return null
        entries.add(
            MmtExtensionEntry(
                endAndType and 0x7fff,
                bytes.copyOfRange(entryOffset, entryOffset + entryLength),
            ),
        )
        entryOffset += entryLength
        if (endAndType and 0x8000 != 0) break
    }
    return entries to entriesEnd
}

private const val MMTP_MIN_HEADER_SIZE = 12

internal fun u16(
    bytes: ByteArray,
    offset: Int,
): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

internal fun u32(
    bytes: ByteArray,
    offset: Int,
): Long =
    ((bytes[offset].toLong() and 0xff) shl 24) or
        ((bytes[offset + 1].toLong() and 0xff) shl 16) or
        ((bytes[offset + 2].toLong() and 0xff) shl 8) or
        (bytes[offset + 3].toLong() and 0xff)

internal fun u64(
    bytes: ByteArray,
    offset: Int,
): Long = (u32(bytes, offset) shl 32) or u32(bytes, offset + 4)
