package net.rokoucha.visiomata.playback.media3

/** Signaling message identifiers (ARIB STD-B60 Table 7-2). Only PA carries the MPT needed here. */
internal object MmtMessageId {
    const val PA = 0x0000
}

/** Table identifiers relevant to package signaling (ARIB STD-B60 Table 7-5). */
internal object MmtTableId {
    const val MPT = 0x20
}

/** Asset descriptor tags used for A/V timing (ARIB STD-B60 Table 7-12). */
internal object MmtDescriptorTag {
    const val MPU_TIMESTAMP = 0x0001
    const val MPU_EXTENDED_TIMESTAMP = 0x8026
}

/** Location types in MMT_general_location_info (ARIB STD-B60 Table 7-10). */
internal object MmtLocationType {
    const val PACKET_ID = 0x00
    const val IPV4 = 0x01
    const val IPV6 = 0x02
}

/** Asset type codes for the A/V assets (ARIB STD-B60 7.3.2). */
internal object MmtAssetType {
    const val HEVC_VIDEO = "hev1"
    const val AAC_AUDIO = "mp4a"
}

/** PTS offset types in the MPU extended timestamp descriptor (ARIB STD-B60 7.4.3.35). */
internal object MmtPtsOffsetType {
    const val FIXED_PRESCRIBED = 0
    const val DEFAULT_VALUE = 1
    const val PER_AU = 2
}

/**
 * Reassembles signaling messages (payload type 0x02) of one packet_id
 * (ARIB STD-B60 6.3.2). Fragmented messages are joined in arrival order;
 * aggregated payloads yield one message per length-prefixed entry. A packet
 * sequence gap discards the in-progress message, since a hole cannot be
 * filled. Returns the complete message bytes, each starting at message_id.
 */
internal class MmtSignalingReassembler {
    val messagesEmitted: Long
        get() = emittedMessages
    val messagesDropped: Long
        get() = droppedMessages
    val sequenceGaps: Long
        get() = observedGaps

    private var emittedMessages = 0L
    private var droppedMessages = 0L
    private var observedGaps = 0L
    private var lastSequenceNumber: Long? = null
    private var fragment: MutableList<ByteArray>? = null
    private var fragmentLength = 0

    fun push(
        payload: ByteArray,
        offset: Int,
        length: Int,
        packetSequenceNumber: Long,
    ): List<ByteArray> {
        if (length < SIGNALING_HEADER_SIZE) {
            droppedMessages++
            return emptyList()
        }
        val last = lastSequenceNumber
        lastSequenceNumber = packetSequenceNumber
        if (last != null && packetSequenceNumber != (last + 1) and SEQUENCE_MASK && fragment != null) {
            fragment = null
            fragmentLength = 0
            observedGaps++
        }
        val b0 = payload[offset].toInt() and 0xff
        val fragmentationIndicator = (b0 shr 6) and 0x03
        val lengthExtensionFlag = (b0 shr 1) and 0x01
        val aggregationFlag = b0 and 0x01
        val bodyOffset = offset + SIGNALING_HEADER_SIZE
        val bodyLength = length - SIGNALING_HEADER_SIZE
        if (aggregationFlag == 1) {
            if (fragmentationIndicator != MmtFragmentationIndicator.NOT_FRAGMENTED) {
                droppedMessages++
                return emptyList()
            }
            return splitAggregated(payload, bodyOffset, bodyLength, lengthExtensionFlag == 1)
        }
        val bytes = payload.copyOfRange(bodyOffset, bodyOffset + bodyLength)
        when (fragmentationIndicator) {
            MmtFragmentationIndicator.NOT_FRAGMENTED -> {
                emittedMessages++
                return listOf(bytes)
            }

            MmtFragmentationIndicator.FIRST -> {
                fragment = mutableListOf(bytes)
                fragmentLength = bytes.size
                return emptyList()
            }

            MmtFragmentationIndicator.MIDDLE, MmtFragmentationIndicator.LAST -> {
                return appendFragment(bytes, fragmentationIndicator)
            }

            else -> {
                droppedMessages++
                return emptyList()
            }
        }
    }

    fun reset() {
        fragment = null
        fragmentLength = 0
        lastSequenceNumber = null
    }

    private fun appendFragment(
        bytes: ByteArray,
        fragmentationIndicator: Int,
    ): List<ByteArray> {
        val current = fragment
        if (current == null) {
            droppedMessages++
            return emptyList()
        }
        if (fragmentLength + bytes.size > MAX_MESSAGE_BYTES) {
            fragment = null
            fragmentLength = 0
            droppedMessages++
            return emptyList()
        }
        current.add(bytes)
        fragmentLength += bytes.size
        if (fragmentationIndicator == MmtFragmentationIndicator.LAST) {
            val complete = concat(current, fragmentLength)
            fragment = null
            fragmentLength = 0
            emittedMessages++
            return listOf(complete)
        }
        return emptyList()
    }

    private fun splitAggregated(
        payload: ByteArray,
        offset: Int,
        length: Int,
        lengthExtended: Boolean,
    ): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var position = offset
        val end = offset + length
        val lengthSize = if (lengthExtended) 4 else 2
        while (position < end) {
            if (end - position < lengthSize) {
                droppedMessages++
                return emptyList()
            }
            val messageLength =
                if (lengthExtended) {
                    u32(payload, position).toInt()
                } else {
                    u16(payload, position)
                }
            position += lengthSize
            if (messageLength <= 0 || end - position < messageLength) {
                droppedMessages++
                return emptyList()
            }
            messages.add(payload.copyOfRange(position, position + messageLength))
            position += messageLength
        }
        emittedMessages += messages.size.toLong()
        return messages
    }

    private companion object {
        const val SIGNALING_HEADER_SIZE = 2
        const val SEQUENCE_MASK = 0xffffffffL
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

        fun concat(
            chunks: List<ByteArray>,
            totalLength: Int,
        ): ByteArray {
            val out = ByteArray(totalLength)
            var position = 0
            chunks.forEach { chunk ->
                chunk.copyInto(out, position)
                position += chunk.size
            }
            return out
        }
    }
}

/**
 * Timing of one MPU: presentation NTP time of the first access unit in
 * presentation order (from the MPU timestamp descriptor), the decoding time
 * offset, and the per-access-unit DTS-to-PTS offsets in decode order (from
 * the MPU extended timestamp descriptor). [ptsOffsets] is present only for
 * per-access-unit PTS offset mode.
 */
internal class MmtMpuTiming(
    val presentationNtp: Long?,
    val decodingOffset: Int,
    val dtsPtsOffsets: IntArray,
    val ptsOffsets: IntArray?,
)

/**
 * Presentation timing of one asset, merged from its MPU timestamp
 * descriptor (0x0001) and MPU extended timestamp descriptor (0x8026).
 * All offsets count in [timescale] ticks per second.
 */
internal class MmtAssetTiming(
    val timescale: Long,
    val ptsOffsetType: Int,
    val defaultInterval: Long?,
    val mpus: Map<Long, MmtMpuTiming>,
) {
    /**
     * Presentation timestamp of the [auIndex]-th access unit in decode order
     * of [mpuSequenceNumber], in microseconds since the NTP epoch. MPUs
     * covered by the descriptors use their exact entries; MPUs outside the
     * signaled window (the descriptors list current and future MPUs, so media
     * routinely arrives ahead of its entry) extrapolate from the nearest
     * entry: the anchor follows the NTP grid spanned by consecutive
     * presentation times, and the access-unit structure repeats the nearest
     * template, which broadcast MPUs keep uniform. Returns null only when no
     * entry can anchor the unit at all.
     */
    fun ptsUs(
        mpuSequenceNumber: Long,
        auIndex: Int,
    ): Long? {
        val exact = mpus[mpuSequenceNumber]
        if (exact != null && exact.presentationNtp != null && auIndex in exact.dtsPtsOffsets.indices) {
            val interval = decodeInterval(mpuSequenceNumber, exact) ?: return null
            val ticks = auIndex.toLong() * interval + exact.dtsPtsOffsets[auIndex] - exact.decodingOffset
            return ntpToUs(exact.presentationNtp) + ticks * MICROSECONDS_PER_SECOND / timescale
        }
        return extrapolatedUs(mpuSequenceNumber, auIndex, includeDtsOffset = true)
    }

    /** Decoding timestamp of the same unit, for containers that need it. */
    fun dtsUs(
        mpuSequenceNumber: Long,
        auIndex: Int,
    ): Long? {
        val exact = mpus[mpuSequenceNumber]
        if (exact != null && exact.presentationNtp != null && auIndex in exact.dtsPtsOffsets.indices) {
            val interval = decodeInterval(mpuSequenceNumber, exact) ?: return null
            val ticks = auIndex.toLong() * interval - exact.decodingOffset
            return ntpToUs(exact.presentationNtp) + ticks * MICROSECONDS_PER_SECOND / timescale
        }
        return extrapolatedUs(mpuSequenceNumber, auIndex, includeDtsOffset = false)
    }

    private fun extrapolatedUs(
        mpuSequenceNumber: Long,
        auIndex: Int,
        includeDtsOffset: Boolean,
    ): Long? {
        if (auIndex < 0) return null
        val templateEntry = nearestTemplate(mpuSequenceNumber) ?: return null
        val template = templateEntry.second
        if (auIndex >= template.dtsPtsOffsets.size) return null
        val interval = decodeInterval(templateEntry.first, template) ?: return null
        val anchor = extrapolatedNtp(mpuSequenceNumber, template, interval) ?: return null
        var ticks = auIndex.toLong() * interval - template.decodingOffset
        if (includeDtsOffset) ticks += template.dtsPtsOffsets[auIndex]
        return ntpToUs(anchor) + ticks * MICROSECONDS_PER_SECOND / timescale
    }

    /** Nearest MPU entry carrying access-unit offsets, with its sequence number. */
    private fun nearestTemplate(mpuSequenceNumber: Long): Pair<Long, MmtMpuTiming>? {
        var best: Pair<Long, MmtMpuTiming>? = null
        var bestDistance = Long.MAX_VALUE
        mpus.forEach { (sequenceNumber, timing) ->
            if (timing.dtsPtsOffsets.isEmpty()) return@forEach
            val distance =
                if (sequenceNumber >= mpuSequenceNumber) {
                    sequenceNumber - mpuSequenceNumber
                } else {
                    mpuSequenceNumber - sequenceNumber
                }
            if (distance < bestDistance) {
                bestDistance = distance
                best = sequenceNumber to timing
            }
        }
        return best
    }

    /**
     * NTP anchor for [mpuSequenceNumber]: its own signaled presentation time
     * when present, otherwise the nearest signaled time shifted by whole MPU
     * spacings measured on the NTP grid (or the nominal MPU duration when the
     * grid has a single point).
     */
    private fun extrapolatedNtp(
        mpuSequenceNumber: Long,
        template: MmtMpuTiming,
        interval: Long,
    ): Long? {
        mpus[mpuSequenceNumber]?.presentationNtp?.let { return it }
        var nearestSequence = 0L
        var nearestNtp: Long? = null
        var nearestDistance = Long.MAX_VALUE
        mpus.forEach { (sequenceNumber, timing) ->
            val ntp = timing.presentationNtp ?: return@forEach
            val distance =
                if (sequenceNumber >= mpuSequenceNumber) {
                    sequenceNumber - mpuSequenceNumber
                } else {
                    mpuSequenceNumber - sequenceNumber
                }
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestSequence = sequenceNumber
                nearestNtp = ntp
            }
        }
        val anchor = nearestNtp ?: return null
        if (nearestDistance == 0L) return anchor
        val spacing = ntpSpacing(nearestSequence) ?: nominalMpuSpacingNtp(template, interval)
        return anchor + (mpuSequenceNumber - nearestSequence) * spacing
    }

    /** Nominal MPU duration in NTP units: one interval per access unit of the template. */
    private fun nominalMpuSpacingNtp(
        template: MmtMpuTiming,
        interval: Long,
    ): Long = interval * NTP_FRACTION_PER_SECOND / timescale * template.dtsPtsOffsets.size

    /** NTP spacing per MPU measured from consecutive signaled presentation times, preferring neighbors of [sequenceNumber]. */
    private fun ntpSpacing(sequenceNumber: Long): Long? {
        val keys = mpus.keys.filter { mpus[it]?.presentationNtp != null }.sorted()
        if (keys.size < 2) return null
        val neighbors = listOf(sequenceNumber - 1, sequenceNumber)
        neighbors.forEach { lower ->
            val upper = lower + 1
            val first = if (lower in mpus) mpus[lower]?.presentationNtp else null
            val second = if (upper in mpus) mpus[upper]?.presentationNtp else null
            if (first != null && second != null) return second - first
        }
        for (index in 0 until keys.size - 1) {
            val first = mpus[keys[index]]?.presentationNtp ?: continue
            val second = mpus[keys[index + 1]]?.presentationNtp ?: continue
            if (keys[index + 1] == keys[index] + 1) return second - first
        }
        return null
    }

    /** Nominal access-unit interval in microseconds, for timestamp fallback. Null when the descriptors give no interval. */
    fun nominalStepUs(): Long? {
        val interval = defaultInterval ?: return null
        return interval * MICROSECONDS_PER_SECOND / timescale
    }

    private fun decodeInterval(
        mpuSequenceNumber: Long,
        mpu: MmtMpuTiming,
    ): Long? {
        if (defaultInterval != null && ptsOffsetType == MmtPtsOffsetType.DEFAULT_VALUE) return defaultInterval
        if (ptsOffsetType == MmtPtsOffsetType.PER_AU) {
            // Best effort: the descriptor gives per-unit presentation
            // intervals, not decode spacing, so assume uniform decoding at
            // the mean presentation interval.
            val offsets = mpu.ptsOffsets
            if (offsets == null || offsets.isEmpty()) return null
            return offsets.sumOf { it.toLong() } / offsets.size
        }
        // Prescribed fixed value: derive it from consecutive MPU
        // presentation times spread over this MPU's access units.
        val next = mpus[mpuSequenceNumber + 1]?.presentationNtp
        val base = mpu.presentationNtp
        if (next == null || base == null || mpu.dtsPtsOffsets.isEmpty()) return null
        val ticks = ntpToTicks(next, timescale) - ntpToTicks(base, timescale)
        if (ticks <= 0) return null
        return ticks / mpu.dtsPtsOffsets.size
    }
}

/** One asset location usable by the depacketizer: a packet_id carrying timed MFUs. */
internal class MmtAssetInfo(
    val packetId: Int,
    val assetType: String,
    val timing: MmtAssetTiming,
)

internal class MmtPackageInfo(
    val assets: List<MmtAssetInfo>,
    val mptVersion: Int,
)

/**
 * Parses a reassembled PA message into the package's asset map (ARIB STD-B60
 * 7.2.2, 7.3.2). Returns null when the message is not a PA message, when it
 * carries no complete MPT, or when any framing check fails; callers keep
 * their previous package on null. Only packet_id locations are collected;
 * IP locations cannot feed this depacketizer.
 */
internal object MmtPackageParser {
    fun parsePaMessage(message: ByteArray): MmtPackageInfo? {
        if (message.size < PA_HEADER_SIZE) return null
        if (u16(message, 0) != MmtMessageId.PA) return null
        val length = u32(message, 3).toInt()
        if (length < 1 || message.size < PA_HEADER_SIZE + length) return null
        val end = PA_HEADER_SIZE + length
        val tableCount = message[7].toInt() and 0xff
        var position = 8 + tableCount * PA_TABLE_ENTRY_SIZE
        if (position > end) return null
        while (position < end) {
            if (end - position < TABLE_HEADER_SIZE) return null
            val tableId = message[position].toInt() and 0xff
            val tableVersion = message[position + 1].toInt() and 0xff
            val tableLength = u16(message, position + 2)
            position += TABLE_HEADER_SIZE
            if (end - position < tableLength) return null
            if (tableId == MmtTableId.MPT) {
                val assets = parseMpt(message, position, tableLength) ?: return null
                return MmtPackageInfo(assets, tableVersion)
            }
            position += tableLength
        }
        return null
    }

    private fun parseMpt(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<MmtAssetInfo>? {
        val end = offset + length
        var position = offset + 1
        if (position >= end) return null
        val packageIdLength = bytes[position].toInt() and 0xff
        position += 1 + packageIdLength
        if (position + 2 > end) return null
        val descriptorsLength = u16(bytes, position)
        position += 2 + descriptorsLength
        if (position + 1 > end) return null
        val assetCount = bytes[position].toInt() and 0xff
        position += 1
        val assets = mutableListOf<MmtAssetInfo>()
        repeat(assetCount) {
            if (position + 9 > end) return null
            position += 1 + 4
            val assetIdLength = bytes[position].toInt() and 0xff
            position += 1
            if (position + assetIdLength + 7 > end) return null
            position += assetIdLength
            val assetType =
                String(bytes, position, 4, Charsets.US_ASCII)
            position += 4 + 1
            val locationCount = bytes[position].toInt() and 0xff
            position += 1
            val packetIds = mutableListOf<Int>()
            repeat(locationCount) {
                if (position + 1 > end) return null
                when (bytes[position].toInt() and 0xff) {
                    MmtLocationType.PACKET_ID -> {
                        if (position + 3 > end) return null
                        packetIds.add(u16(bytes, position + 1))
                        position += 3
                    }

                    MmtLocationType.IPV4 -> {
                        position += 11
                        if (position > end) return null
                    }

                    MmtLocationType.IPV6 -> {
                        position += 35
                        if (position > end) return null
                    }

                    else -> {
                        return null
                    }
                }
            }
            if (position + 2 > end) return null
            val assetDescriptorsLength = u16(bytes, position)
            position += 2
            if (position + assetDescriptorsLength > end) return null
            val timing = parseAssetTiming(bytes, position, assetDescriptorsLength) ?: return null
            position += assetDescriptorsLength
            packetIds.forEach { packetId ->
                assets.add(MmtAssetInfo(packetId, assetType, timing))
            }
        }
        if (position != end) return null
        return assets
    }

    private fun parseAssetTiming(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): MmtAssetTiming? {
        val end = offset + length
        var position = offset
        var presentationTimes: Map<Long, Long>? = null
        var extended: MmtAssetTiming? = null
        while (position < end) {
            if (end - position < DESCRIPTOR_HEADER_SIZE) return null
            val tag = u16(bytes, position)
            val descriptorLength = bytes[position + 2].toInt() and 0xff
            position += DESCRIPTOR_HEADER_SIZE
            if (end - position < descriptorLength) return null
            when (tag) {
                MmtDescriptorTag.MPU_TIMESTAMP -> {
                    presentationTimes = parseMpuTimestamp(bytes, position, descriptorLength) ?: return null
                }

                MmtDescriptorTag.MPU_EXTENDED_TIMESTAMP -> {
                    extended = parseExtendedTimestamp(bytes, position, descriptorLength) ?: return null
                }
            }
            position += descriptorLength
        }
        if (position != end) return null
        val base =
            extended ?: return MmtAssetTiming(DEFAULT_TIMESCALE, MmtPtsOffsetType.FIXED_PRESCRIBED, null, emptyMap())
        if (presentationTimes == null) return base
        val merged =
            base.mpus.mapValues { (mpuSequenceNumber, timing) ->
                MmtMpuTiming(
                    presentationTimes[mpuSequenceNumber] ?: timing.presentationNtp,
                    timing.decodingOffset,
                    timing.dtsPtsOffsets,
                    timing.ptsOffsets,
                )
            } +
                presentationTimes
                    .filterKeys { it !in base.mpus }
                    .mapValues { (_, ntp) -> MmtMpuTiming(ntp, 0, IntArray(0), null) }
        return MmtAssetTiming(base.timescale, base.ptsOffsetType, base.defaultInterval, merged)
    }

    private fun parseMpuTimestamp(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Map<Long, Long>? {
        if (length % MPU_TIMESTAMP_ENTRY_SIZE != 0) return null
        val entries = mutableMapOf<Long, Long>()
        var position = offset
        repeat(length / MPU_TIMESTAMP_ENTRY_SIZE) {
            val mpuSequenceNumber = u32(bytes, position)
            val presentationNtp = u64(bytes, position + 4)
            entries[mpuSequenceNumber] = presentationNtp
            position += MPU_TIMESTAMP_ENTRY_SIZE
        }
        return entries
    }

    private fun parseExtendedTimestamp(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): MmtAssetTiming? {
        if (length < 1) return null
        val end = offset + length
        val b0 = bytes[offset].toInt() and 0xff
        val ptsOffsetType = (b0 shr 1) and 0x03
        if (ptsOffsetType == 3) return null
        val timescaleFlag = b0 and 0x01
        var position = offset + 1
        var timescale = DEFAULT_TIMESCALE
        if (timescaleFlag == 1) {
            if (position + 4 > end) return null
            timescale = u32(bytes, position)
            if (timescale <= 0) return null
            position += 4
        }
        var defaultInterval: Long? = null
        if (ptsOffsetType == MmtPtsOffsetType.DEFAULT_VALUE) {
            if (position + 2 > end) return null
            defaultInterval = u16(bytes, position).toLong()
            if (defaultInterval == 0L) return null
            position += 2
        }
        val mpus = mutableMapOf<Long, MmtMpuTiming>()
        while (position < end) {
            if (position + MPU_TIMING_HEADER_SIZE > end) return null
            val mpuSequenceNumber = u32(bytes, position)
            val decodingOffset = u16(bytes, position + 5)
            val accessUnitCount = bytes[position + 7].toInt() and 0xff
            position += MPU_TIMING_HEADER_SIZE
            val entrySize = if (ptsOffsetType == MmtPtsOffsetType.PER_AU) 4 else 2
            if (end - position < accessUnitCount * entrySize) return null
            val dtsPtsOffsets = IntArray(accessUnitCount)
            val ptsOffsets =
                if (ptsOffsetType == MmtPtsOffsetType.PER_AU) IntArray(accessUnitCount) else null
            repeat(accessUnitCount) { index ->
                dtsPtsOffsets[index] = u16(bytes, position)
                position += 2
                if (ptsOffsets != null) {
                    ptsOffsets[index] = u16(bytes, position)
                    position += 2
                }
            }
            mpus[mpuSequenceNumber] = MmtMpuTiming(null, decodingOffset, dtsPtsOffsets, ptsOffsets)
        }
        return MmtAssetTiming(timescale, ptsOffsetType, defaultInterval, mpus)
    }

    private const val PA_HEADER_SIZE = 7
    private const val PA_TABLE_ENTRY_SIZE = 4
    private const val TABLE_HEADER_SIZE = 4
    private const val DESCRIPTOR_HEADER_SIZE = 3
    private const val MPU_TIMESTAMP_ENTRY_SIZE = 12
    private const val MPU_TIMING_HEADER_SIZE = 8
    const val DEFAULT_TIMESCALE = 90000L
}

internal const val MICROSECONDS_PER_SECOND = 1_000_000L

private const val NTP_FRACTION_PER_SECOND = 4294967296L

/** Converts a 64-bit NTP timestamp to microseconds since the NTP epoch. */
internal fun ntpToUs(ntp: Long): Long {
    val seconds = (ntp ushr 32) and 0xffffffffL
    val fraction = ntp and 0xffffffffL
    return seconds * MICROSECONDS_PER_SECOND + (fraction * MICROSECONDS_PER_SECOND ushr 32)
}

private fun ntpToTicks(
    ntp: Long,
    timescale: Long,
): Long {
    val seconds = (ntp ushr 32) and 0xffffffffL
    val fraction = ntp and 0xffffffffL
    return seconds * timescale + (fraction * timescale ushr 32)
}
