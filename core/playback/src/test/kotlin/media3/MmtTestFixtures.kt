package net.rokoucha.visiomata.playback.media3

import java.io.ByteArrayOutputStream

internal fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun u16Bytes(value: Int): ByteArray = bytes((value shr 8) and 0xff, value and 0xff)

internal fun u32Bytes(value: Long): ByteArray =
    bytes(
        ((value shr 24) and 0xff).toInt(),
        ((value shr 16) and 0xff).toInt(),
        ((value shr 8) and 0xff).toInt(),
        (value and 0xff).toInt(),
    )

internal fun u64Bytes(value: Long): ByteArray =
    u32Bytes((value ushr 32) and 0xffffffffL) + u32Bytes(value and 0xffffffffL)

internal fun ntpBytes(
    seconds: Long,
    fraction: Long,
): ByteArray = u32Bytes(seconds) + u32Bytes(fraction)

internal fun mmtPacket(
    payloadType: Int,
    packetId: Int,
    sequenceNumber: Long,
    payload: ByteArray,
    rap: Boolean = false,
    extensionEntries: List<Pair<Int, ByteArray>> = emptyList(),
    packetCounter: Long? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    var b0 = 0
    if (packetCounter != null) b0 = b0 or 0x20
    if (extensionEntries.isNotEmpty()) b0 = b0 or 0x02
    if (rap) b0 = b0 or 0x01
    // Top two bits of the second byte are unused here; receivers mask them off.
    val b1 = 0xc0 or payloadType
    out.write(bytes(b0, b1) + u16Bytes(packetId) + u32Bytes(0x4279_0000L) + u32Bytes(sequenceNumber))
    if (packetCounter != null) out.write(u32Bytes(packetCounter))
    if (extensionEntries.isNotEmpty()) {
        val body = ByteArrayOutputStream()
        extensionEntries.forEachIndexed { index, (type, entryBytes) ->
            val endFlag = if (index == extensionEntries.lastIndex) 0x8000 else 0x0000
            body.write(u16Bytes(endFlag or type) + u16Bytes(entryBytes.size) + entryBytes)
        }
        val bodyBytes = body.toByteArray()
        out.write(u16Bytes(0x0000) + u16Bytes(bodyBytes.size) + bodyBytes)
    }
    out.write(payload)
    return out.toByteArray()
}

/** Scramble extension entry value: reserved(3)=7, control(2), subsystem(1)=0, MAC(1)=0, SICV(1)=0. */
internal fun scrambleEntry(control: Int): Pair<Int, ByteArray> =
    MmtExtensionType.SCRAMBLING to bytes(0xe0 or (control shl 3))

internal fun mfuUnitHeader(
    movieFragmentSequence: Long = 0,
    sampleNumber: Long = 0,
    offset: Long = 0,
): ByteArray = u32Bytes(movieFragmentSequence) + u32Bytes(sampleNumber) + u32Bytes(offset) + bytes(0, 0)

internal fun mpuPayload(
    fragmentType: Int,
    timed: Boolean,
    fragmentationIndicator: Int,
    aggregation: Boolean,
    mpuSequenceNumber: Long,
    body: ByteArray,
): ByteArray {
    var b2 = (fragmentType shl 4) or (fragmentationIndicator shl 1)
    if (timed) b2 = b2 or 0x08
    if (aggregation) b2 = b2 or 0x01
    val rest = bytes(b2, 0) + u32Bytes(mpuSequenceNumber) + body
    return u16Bytes(rest.size) + rest
}

internal fun singleMfuPayload(
    mpuSequenceNumber: Long,
    fragmentationIndicator: Int,
    data: ByteArray,
): ByteArray =
    mpuPayload(
        MmtFragmentType.MFU,
        true,
        fragmentationIndicator,
        false,
        mpuSequenceNumber,
        mfuUnitHeader() + data,
    )

internal fun aggregatedMfuPayload(
    mpuSequenceNumber: Long,
    vararg datas: ByteArray,
): ByteArray {
    val out = ByteArrayOutputStream()
    datas.forEach { data ->
        out.write(u16Bytes(14 + data.size) + mfuUnitHeader() + data)
    }
    return mpuPayload(
        MmtFragmentType.MFU,
        true,
        MmtFragmentationIndicator.NOT_FRAGMENTED,
        true,
        mpuSequenceNumber,
        out.toByteArray(),
    )
}

internal fun nal(
    type: Int,
    vararg body: Int,
): ByteArray = bytes((type shl 1) or 1, 0x01) + bytes(*body)

internal fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
    val total = parts.sumOf { it.size }
    return u32Bytes(total.toLong()) + parts.reduce { acc, bytes -> acc + bytes }
}

internal fun signalingPayload(
    fragmentationIndicator: Int,
    message: ByteArray,
    aggregation: Boolean = false,
    lengthExtended: Boolean = false,
): ByteArray {
    var b0 = fragmentationIndicator shl 6
    if (lengthExtended) b0 = b0 or 0x02
    if (aggregation) b0 = b0 or 0x01
    if (!aggregation) return bytes(b0, 0) + message
    val lengthBytes = if (lengthExtended) u32Bytes(message.size.toLong()) else u16Bytes(message.size)
    return bytes(b0, 0) + lengthBytes + message
}

internal fun tlvPacket(
    packetType: Int,
    payload: ByteArray,
): ByteArray = bytes(0x7f, packetType) + u16Bytes(payload.size) + payload

internal fun compressedIpTlv(
    mmtp: ByteArray,
    contextId: Int = 0,
): ByteArray =
    tlvPacket(
        TlvPacketType.COMPRESSED_IP,
        u16Bytes(contextId) + bytes(HeaderCompressionType.NO_HEADER) + mmtp,
    )

internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var filled = 0

    fun writeBits(
        value: Long,
        count: Int,
    ) {
        repeat(count) { index ->
            current = (current shl 1) or (((value shr (count - 1 - index)) and 1).toInt())
            if (++filled == 8) {
                out.write(current)
                current = 0
                filled = 0
            }
        }
    }

    fun writeBytes(data: ByteArray) = data.forEach { writeBits((it.toInt() and 0xff).toLong(), 8) }

    fun toByteArray(): ByteArray {
        if (filled > 0) out.write(current shl (8 - filled))
        return out.toByteArray()
    }
}

/**
 * Builds one LATM AudioMuxElement unwrapping to [payload]: AAC-LC, 48kHz stereo (`0x11 0x90`
 * AudioSpecificConfig) unless [useSameStreamMux] reuses a previous config. [otherData] signals two
 * trailing other-data bits with a three-byte length, which also lands the payload byte-aligned;
 * otherwise the payload starts mid-byte.
 */
internal fun latmAudioMuxElement(
    payload: ByteArray,
    useSameStreamMux: Boolean = false,
    otherData: Boolean = false,
    numProgram: Int = 0,
    audioMuxVersionA: Int = 0,
): ByteArray {
    val writer = BitWriter()
    writer.writeBits(if (useSameStreamMux) 1 else 0, 1)
    if (!useSameStreamMux) {
        writer.writeBits(if (audioMuxVersionA == 0) 0 else 1, 1)
        if (audioMuxVersionA != 0) writer.writeBits(audioMuxVersionA.toLong(), 1)
        writer.writeBits(1, 1) // sameTimeFraming
        writer.writeBits(0, 6) // numSubframes
        writer.writeBits(numProgram.toLong(), 4)
        writer.writeBits(0, 3) // numLayer
        writer.writeBits(2, 5) // AAC-LC
        writer.writeBits(3, 4) // 48kHz
        writer.writeBits(2, 4) // stereo
        writer.writeBits(0, 1) // frameLengthFlag
        writer.writeBits(0, 1) // dependsOnCoreCoder
        writer.writeBits(0, 1) // extensionFlag
        writer.writeBits(0, 3) // frameLengthType
        writer.writeBits(0, 8) // latmBufferFullness
        writer.writeBits(if (otherData) 1 else 0, 1)
        if (otherData) {
            writer.writeBits(1, 1)
            writer.writeBits(0, 8)
            writer.writeBits(1, 1)
            writer.writeBits(0, 8)
            writer.writeBits(0, 1)
            writer.writeBits(2, 8)
        }
        writer.writeBits(0, 1) // crcCheckPresent
    }
    var remaining = payload.size
    while (remaining >= 255) {
        writer.writeBits(255, 8)
        remaining -= 255
    }
    writer.writeBits(remaining.toLong(), 8)
    writer.writeBytes(payload)
    if (otherData) writer.writeBits(0, 2)
    return writer.toByteArray()
}

internal class TestAsset(
    val packetId: Int,
    val assetType: String,
    val presentationTimes: Map<Long, Long> = emptyMap(),
    val timescale: Long = 90000,
    val ptsOffsetType: Int = MmtPtsOffsetType.DEFAULT_VALUE,
    val defaultInterval: Long = 3000,
    val mpuTimings: Map<Long, TestMpuTiming> = emptyMap(),
)

internal class TestMpuTiming(
    val decodingOffset: Int = 0,
    val dtsPtsOffsets: IntArray = intArrayOf(),
    val ptsOffsets: IntArray? = null,
)

internal fun paMessage(
    assets: List<TestAsset>,
    mptVersion: Int = 7,
): ByteArray {
    val mpt = ByteArrayOutputStream()
    mpt.write(bytes(0))
    mpt.write(bytes(2) + bytes(0x00, 0x65))
    mpt.write(u16Bytes(0))
    mpt.write(bytes(assets.size))
    assets.forEach { asset ->
        mpt.write(bytes(0) + u32Bytes(0) + bytes(2) + u16Bytes(asset.packetId))
        mpt.write(asset.assetType.toByteArray(Charsets.US_ASCII))
        mpt.write(bytes(0, 1, MmtLocationType.PACKET_ID) + u16Bytes(asset.packetId))
        val descriptors = ByteArrayOutputStream()
        if (asset.presentationTimes.isNotEmpty()) {
            val body = ByteArrayOutputStream()
            asset.presentationTimes.toSortedMap().forEach { (mpu, ntp) ->
                body.write(u32Bytes(mpu) + u64Bytes(ntp))
            }
            val bodyBytes = body.toByteArray()
            descriptors.write(u16Bytes(MmtDescriptorTag.MPU_TIMESTAMP) + bytes(bodyBytes.size) + bodyBytes)
        }
        if (asset.mpuTimings.isNotEmpty()) {
            val body = ByteArrayOutputStream()
            val b0 = 0xf8 or (asset.ptsOffsetType shl 1) or 1
            body.write(bytes(b0))
            body.write(u32Bytes(asset.timescale))
            if (asset.ptsOffsetType == MmtPtsOffsetType.DEFAULT_VALUE) {
                body.write(u16Bytes(asset.defaultInterval.toInt()))
            }
            asset.mpuTimings.toSortedMap().forEach { (mpu, timing) ->
                body.write(
                    u32Bytes(mpu) + bytes(0x3f) + u16Bytes(timing.decodingOffset) + bytes(timing.dtsPtsOffsets.size),
                )
                timing.dtsPtsOffsets.forEachIndexed { index, offset ->
                    body.write(u16Bytes(offset))
                    timing.ptsOffsets?.let { body.write(u16Bytes(it[index])) }
                }
            }
            val bodyBytes = body.toByteArray()
            descriptors.write(u16Bytes(MmtDescriptorTag.MPU_EXTENDED_TIMESTAMP) + bytes(bodyBytes.size) + bodyBytes)
        }
        val descriptorBytes = descriptors.toByteArray()
        mpt.write(u16Bytes(descriptorBytes.size) + descriptorBytes)
    }
    val mptBody = mpt.toByteArray()
    val table = bytes(MmtTableId.MPT, mptVersion) + u16Bytes(mptBody.size) + mptBody
    // PA with no extension table entries: payload tables are self-framing.
    val length = 1 + table.size
    return u16Bytes(MmtMessageId.PA) + bytes(0) + u32Bytes(length.toLong()) + bytes(0) + table
}

internal fun hexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Real VPS/SPS/PPS from the NHK BS4K sample (3840x2160, Main 10); see ffprobe oracle in history. */
internal val HEVC_VPS: ByteArray by lazy {
    hexBytes(
        "40010c06ffff022000000300b00000030000030099000018824090",
    )
}

internal val HEVC_SPS: ByteArray by lazy {
    hexBytes(
        "420106022000000300b000000300000300990000a001e020021c4d8d18826490" +
            "a594e0284243826ca500000303e90000ea60803e9c00814bc00004c4b4000004" +
            "c5ec5c00004c4b4000004c5ec5c00004c4b4000004c5ec5c00004c4b4000004c" +
            "5ec440",
    )
}

internal val HEVC_PPS: ByteArray by lazy {
    hexBytes(
        "4401c076f02c2014873041c2185214843498ce3860d055027142018863087198" +
            "c84210c98ae2b0e41d1421ffffe9ada4eaeba7aab9126c8494526323210ffa69" +
            "a4d5a31526c862c92c8a431cb104111251c28c716161d1e09050b05d06c5087f" +
            "fffafd7ffebebc9cd888a843febd7c9f88febf37c5784386b045051087fd35a2" +
            "9390488f1011087c2212f9be2bc21c35822828843ffffe9ada4eaeba7aab9126" +
            "c8494526323210ffd34d26ad18a936431649645218e5882088928e14638b0b0e" +
            "8f04828582e8362843ffffebf5fffafaf2736222a10ffd7af93f11fd7e6f8af0" +
            "870d608a0a0a10fffffa6b693abae9eaae449b21251498c8c843ffffebf5fffa" +
            "faf273622292",
    )
}

internal fun annexB(vararg nals: ByteArray): ByteArray =
    nals.fold(ByteArray(0)) { acc, nal -> acc + bytes(0, 0, 0, 1) + nal }

/** MFUs carrying real VPS/SPS/PPS so extractors can build the HEVC video format. */
internal fun hevcParamSetMfus(): Array<ByteArray> =
    arrayOf(lengthPrefixed(HEVC_VPS), lengthPrefixed(HEVC_SPS), lengthPrefixed(HEVC_PPS))
