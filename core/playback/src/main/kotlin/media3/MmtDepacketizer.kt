package net.rokoucha.visiomata.playback.media3

/** HEVC NAL unit types needed for access-unit framing (ITU-T H.265 Table 7-1). */
internal object HevcNalType {
    const val AUD = 35
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val PREFIX_SEI = 39
    const val SUFFIX_SEI = 40
    const val IRAP_FIRST = 16
    const val IRAP_LAST = 23

    fun of(firstByte: Int): Int = (firstByte shr 1) and 0x3f

    fun isSync(type: Int): Boolean = type in IRAP_FIRST..IRAP_LAST
}

internal enum class MmtTrackKind {
    VIDEO_HEVC,
    AUDIO_AAC,
}

/**
 * One depacketized media sample.
 *
 * Video samples hold one Annex-B access unit (4-byte start codes), audio
 * samples one LATM AudioMuxElement exactly as carried in its MFU. Timestamps
 * are microseconds since the NTP epoch; video PTS may run backwards in
 * decode order wherever B-frames reorder presentation.
 */
internal class MmtSample(
    val packetId: Int,
    val kind: MmtTrackKind,
    val ptsUs: Long,
    val dtsUs: Long,
    val data: ByteArray,
    val isSyncFrame: Boolean,
    val mpuSequenceNumber: Long,
    val accessUnitIndex: Int,
)

/** AAC stream parameters sniffed from a LATM AudioSpecificConfig. */
internal class MmtAudioStreamInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
)

/**
 * MMT depacketizer for broadcast A/V assets.
 *
 * Consumes TLV packets from [TlvDemuxer] (or raw MMTP datagrams), reassembles
 * MFUs per packet_id, follows the MPT to map packet_ids to `hev1`/`mp4a`
 * assets, and emits video access units and AAC frames with presentation
 * timestamps taken from the MPU (extended) timestamp descriptors. Caption,
 * data-broadcast and download assets are ignored, as are MPU/movie-fragment
 * metadata fragments, which broadcasting omits by design (ARIB STD-B60 6.2).
 *
 * Access-unit indexes run per MPU from the first unit seen; after a mid-MPU
 * tune-in the first partial MPU is indexed optimistically and indexing
 * self-corrects at the next MPU boundary. Sample [MmtSample.data] arrays are
 * copies owned by the sample.
 */
internal class MmtDepacketizer(
    private val onSample: (MmtSample) -> Unit = {},
) {
    val tlvPacketsAccepted: Long
        get() = acceptedTlv
    val tlvPacketsIgnored: Long
        get() = ignoredTlv
    val datagramsRejected: Long
        get() = rejectedDatagrams
    val scrambledPacketsDropped: Long
        get() = droppedScrambled
    val unmappedPacketsDropped: Long
        get() = droppedUnmapped
    val nonAvPacketsSkipped: Long
        get() = skippedNonAv
    val signalingMessagesSeen: Long
        get() = seenSignalingMessages
    val mptUpdatesApplied: Long
        get() = appliedMptUpdates

    /** PA messages carrying no usable MPT: PLT-only package announcements as well as corrupt ones. Callers keep the previous package. */
    val paMessagesWithoutMpt: Long
        get() = messagesWithoutMpt
    val mfusCompleted: Long
        get() = completedMfus
    val mfusDropped: Long
        get() = droppedMfus
    val corruptAusDropped: Long
        get() = droppedCorruptAus
    val videoSamplesEmitted: Long
        get() = emittedVideoSamples
    val audioSamplesEmitted: Long
        get() = emittedAudioSamples
    val samplesWithFallbackPts: Long
        get() = fallbackPtsSamples
    val samplesDroppedNoTiming: Long
        get() = droppedNoTiming
    val mediaSequenceGaps: Long
        get() = observedMediaGaps

    /** Latest package map; null until the first PA message with an MPT arrives. */
    var currentPackage: MmtPackageInfo? = null
        private set

    private var acceptedTlv = 0L
    private var ignoredTlv = 0L
    private var rejectedDatagrams = 0L
    private var droppedScrambled = 0L
    private var droppedUnmapped = 0L
    private var skippedNonAv = 0L
    private var seenSignalingMessages = 0L
    private var appliedMptUpdates = 0L
    private var messagesWithoutMpt = 0L
    private var completedMfus = 0L
    private var droppedMfus = 0L
    private var droppedCorruptAus = 0L
    private var emittedVideoSamples = 0L
    private var emittedAudioSamples = 0L
    private var fallbackPtsSamples = 0L
    private var droppedNoTiming = 0L
    private var observedMediaGaps = 0L

    private val signalingTracks = mutableMapOf<Int, MmtSignalingReassembler>()
    private val mediaTracks = mutableMapOf<Int, MediaTrack>()
    private val packageAssets = mutableMapOf<Int, MmtAssetInfo>()

    fun audioStreamInfo(packetId: Int): MmtAudioStreamInfo? = mediaTracks[packetId]?.audioInfo

    fun pushTlv(packet: TlvPacket) {
        if (
            packet.packetType != TlvPacketType.IPV4 &&
            packet.packetType != TlvPacketType.IPV6 &&
            packet.packetType != TlvPacketType.COMPRESSED_IP
        ) {
            ignoredTlv++
            return
        }
        val datagram = TlvMmtDeframer.deframe(packet)
        if (datagram == null) {
            rejectedDatagrams++
            return
        }
        acceptedTlv++
        route(datagram.bytes, datagram.offset, datagram.length)
    }

    fun pushMmt(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        route(bytes, offset, length)
    }

    /** Drops all buffered fragments, tracks and the package map; counters are cumulative and survive. */
    fun reset() {
        signalingTracks.clear()
        mediaTracks.clear()
        packageAssets.clear()
        currentPackage = null
    }

    private fun route(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val packet = parseMmtPacket(bytes, offset, length)
        if (packet == null) {
            rejectedDatagrams++
            return
        }
        if (packet.scramblingControl() != MmtScramblingControl.UNSCRAMBLED) {
            droppedScrambled++
            return
        }
        when (packet.payloadType) {
            MmtPayloadType.SIGNALING -> pushSignaling(packet)
            MmtPayloadType.MPU -> pushMedia(packet)
            else -> skippedNonAv++
        }
    }

    private fun pushSignaling(packet: MmtPacket) {
        val track =
            signalingTracks.getOrPut(packet.packetId) {
                if (signalingTracks.size >= MAX_SIGNALING_TRACKS) {
                    droppedUnmapped++
                    return
                }
                MmtSignalingReassembler()
            }
        track
            .push(
                packet.payloadBytes,
                packet.payloadOffset,
                packet.payloadLength,
                packet.packetSequenceNumber,
            ).forEach { message ->
                seenSignalingMessages++
                if (message.size >= 2 && u16(message, 0) == MmtMessageId.PA) {
                    val info = MmtPackageParser.parsePaMessage(message)
                    if (info == null) {
                        messagesWithoutMpt++
                    } else {
                        appliedMptUpdates++
                        currentPackage = info
                        packageAssets.clear()
                        info.assets.forEach { asset -> packageAssets[asset.packetId] = asset }
                    }
                }
            }
    }

    private fun pushMedia(packet: MmtPacket) {
        val asset = packageAssets[packet.packetId]
        val kind =
            when (asset?.assetType) {
                MmtAssetType.HEVC_VIDEO -> MmtTrackKind.VIDEO_HEVC
                MmtAssetType.AAC_AUDIO -> MmtTrackKind.AUDIO_AAC
                else -> null
            }
        if (asset == null || kind == null) {
            droppedUnmapped++
            return
        }
        val header =
            parseMpuPayload(packet.payloadBytes, packet.payloadOffset, packet.payloadLength)
        if (header == null || header.fragmentType != MmtFragmentType.MFU || !header.timed) {
            skippedNonAv++
            return
        }
        val track =
            mediaTracks.getOrPut(packet.packetId) {
                if (mediaTracks.size >= MAX_MEDIA_TRACKS) {
                    droppedUnmapped++
                    return
                }
                MediaTrack(packet.packetId, kind)
            }
        track.kind = kind
        track.timing = asset.timing
        track.push(packet, header)
    }

    private inner class MediaTrack(
        val packetId: Int,
        var kind: MmtTrackKind,
    ) {
        var timing: MmtAssetTiming? = null
        var audioInfo: MmtAudioStreamInfo? = null
        private var lastSequenceNumber: Long? = null
        private var mpuSequenceNumber: Long? = null
        private var accessUnitIndex = 0
        private var mfuChunks: MutableList<ByteArray>? = null
        private var mfuLength = 0
        private var mfuMpuSequenceNumber = 0L
        private var auNals: MutableList<ByteArray>? = null
        private var auLength = 0
        private var auMpuSequenceNumber = 0L
        private var auRap = false
        private var auCorrupt = false
        private var lastPtsUs: Long? = null

        fun push(
            packet: MmtPacket,
            header: MpuPayloadHeader,
        ) {
            val last = lastSequenceNumber
            lastSequenceNumber = packet.packetSequenceNumber
            if (last != null && packet.packetSequenceNumber != (last + 1) and SEQUENCE_MASK) {
                observedMediaGaps++
                dropPartialMfu()
            }
            if (mpuSequenceNumber != null && mpuSequenceNumber != header.mpuSequenceNumber) {
                dropPartialMfu()
                if (kind == MmtTrackKind.VIDEO_HEVC) finishAccessUnit()
                accessUnitIndex = 0
            }
            mpuSequenceNumber = header.mpuSequenceNumber
            if (header.aggregation) {
                val units = splitAggregated(packet.payloadBytes, header.unitsOffset, header.unitsEnd)
                if (units == null || header.fragmentationIndicator != MmtFragmentationIndicator.NOT_FRAGMENTED) {
                    markAccessUnitCorrupt(header.mpuSequenceNumber)
                    droppedMfus++
                    return
                }
                units.forEach { unit ->
                    completeMfu(unit, header.mpuSequenceNumber, packet.rapFlag)
                }
            } else {
                val unit =
                    parseSingleUnit(packet.payloadBytes, header.unitsOffset, header.unitsEnd) ?: run {
                        dropPartialMfu()
                        markAccessUnitCorrupt(header.mpuSequenceNumber)
                        droppedMfus++
                        return
                    }
                when (header.fragmentationIndicator) {
                    MmtFragmentationIndicator.NOT_FRAGMENTED -> {
                        dropPartialMfu()
                        completeMfu(unit, header.mpuSequenceNumber, packet.rapFlag)
                    }

                    MmtFragmentationIndicator.FIRST -> {
                        dropPartialMfu()
                        mfuChunks = mutableListOf(unit)
                        mfuLength = unit.size
                        mfuMpuSequenceNumber = header.mpuSequenceNumber
                    }

                    MmtFragmentationIndicator.MIDDLE, MmtFragmentationIndicator.LAST -> {
                        appendFragment(header, unit, packet.rapFlag)
                    }
                }
            }
        }

        private fun appendFragment(
            header: MpuPayloadHeader,
            unit: ByteArray,
            rap: Boolean,
        ) {
            val current = mfuChunks
            if (current == null || mfuMpuSequenceNumber != header.mpuSequenceNumber) {
                dropPartialMfu()
                markAccessUnitCorrupt(header.mpuSequenceNumber)
                droppedMfus++
                return
            }
            if (mfuLength + unit.size > MAX_MFU_BYTES) {
                dropPartialMfu()
                droppedMfus++
                return
            }
            current.add(unit)
            mfuLength += unit.size
            if (header.fragmentationIndicator == MmtFragmentationIndicator.LAST) {
                mfuChunks = null
                completeMfu(concatChunks(current, mfuLength), header.mpuSequenceNumber, rap)
                mfuLength = 0
            }
        }

        private fun dropPartialMfu() {
            if (mfuChunks != null) {
                markAccessUnitCorrupt(mfuMpuSequenceNumber)
                mfuChunks = null
                mfuLength = 0
                droppedMfus++
            }
        }

        /** A dropped MFU leaves a hole in its access unit, so the open unit of the same MPU is no longer emittable. */
        private fun markAccessUnitCorrupt(mpuSequenceNumber: Long) {
            if (kind == MmtTrackKind.VIDEO_HEVC && auNals != null && mpuSequenceNumber == auMpuSequenceNumber) {
                auCorrupt = true
            }
        }

        private fun completeMfu(
            data: ByteArray,
            mpu: Long,
            rap: Boolean,
        ) {
            completedMfus++
            if (kind == MmtTrackKind.VIDEO_HEVC) {
                pushVideoMfu(data, mpu, rap)
            } else {
                pushAudioMfu(data, mpu)
            }
        }

        private fun pushVideoMfu(
            data: ByteArray,
            mpu: Long,
            rap: Boolean,
        ) {
            if (data.size < NAL_LENGTH_SIZE || u32(data, 0).toInt() != data.size - NAL_LENGTH_SIZE) {
                auCorrupt = true
                droppedMfus++
                return
            }
            val nal = data.copyOfRange(NAL_LENGTH_SIZE, data.size)
            val type = HevcNalType.of(nal[0].toInt() and 0xff)
            if (type == HevcNalType.AUD && auNals != null) {
                finishAccessUnit()
            }
            if (auNals == null) {
                auNals = mutableListOf()
                auLength = 0
                auMpuSequenceNumber = mpu
                auRap = false
                auCorrupt = false
            }
            if (auLength + nal.size > MAX_AU_BYTES) {
                discardAccessUnit()
                droppedMfus++
                return
            }
            auNals!!.add(nal)
            auLength += nal.size
            auRap = auRap || rap
        }

        private fun finishAccessUnit() {
            val nals = auNals ?: return
            auNals = null
            val index = accessUnitIndex
            accessUnitIndex++
            if (nals.isEmpty() || auCorrupt) {
                if (auCorrupt) droppedCorruptAus++
                auLength = 0
                return
            }
            val timing = timing
            val pts = timing?.ptsUs(auMpuSequenceNumber, index)
            if (pts == null) {
                emitWithFallback(nals, auMpuSequenceNumber, index, isSync(nals))
                auLength = 0
                return
            }
            lastPtsUs = pts
            emittedVideoSamples++
            onSample(
                MmtSample(
                    packetId,
                    MmtTrackKind.VIDEO_HEVC,
                    pts,
                    timing.dtsUs(auMpuSequenceNumber, index) ?: pts,
                    annexB(nals, auLength),
                    auRap || isSync(nals),
                    auMpuSequenceNumber,
                    index,
                ),
            )
            auLength = 0
        }

        private fun discardAccessUnit() {
            if (auNals != null) {
                // The discarded unit consumed its decode-order index.
                accessUnitIndex++
            }
            auNals = null
            auLength = 0
        }

        private fun pushAudioMfu(
            data: ByteArray,
            mpu: Long,
        ) {
            if (data.isEmpty()) {
                droppedMfus++
                return
            }
            if (audioInfo == null) {
                sniffLatmConfig(data)?.let { audioInfo = it }
            }
            val index = accessUnitIndex
            accessUnitIndex++
            val timing = timing
            val pts = timing?.ptsUs(mpu, index)
            if (pts == null) {
                emitAudioWithFallback(data, mpu, index)
                return
            }
            lastPtsUs = pts
            emittedAudioSamples++
            onSample(
                MmtSample(
                    packetId,
                    MmtTrackKind.AUDIO_AAC,
                    pts,
                    pts,
                    data,
                    true,
                    mpu,
                    index,
                ),
            )
        }

        private fun emitWithFallback(
            nals: List<ByteArray>,
            mpu: Long,
            index: Int,
            sync: Boolean,
        ) {
            val step = timing?.nominalStepUs()
            val previous = lastPtsUs
            if (step == null || previous == null) {
                droppedNoTiming++
                return
            }
            val pts = previous + step
            lastPtsUs = pts
            fallbackPtsSamples++
            emittedVideoSamples++
            onSample(
                MmtSample(
                    packetId,
                    MmtTrackKind.VIDEO_HEVC,
                    pts,
                    pts,
                    annexB(nals, auLength),
                    auRap || sync,
                    mpu,
                    index,
                ),
            )
        }

        private fun emitAudioWithFallback(
            data: ByteArray,
            mpu: Long,
            index: Int,
        ) {
            val previous = lastPtsUs
            val step = timing?.nominalStepUs() ?: audioFrameStepUs()
            if (step == null || previous == null) {
                droppedNoTiming++
                return
            }
            val pts = previous + step
            lastPtsUs = pts
            fallbackPtsSamples++
            emittedAudioSamples++
            onSample(MmtSample(packetId, MmtTrackKind.AUDIO_AAC, pts, pts, data, true, mpu, index))
        }

        private fun audioFrameStepUs(): Long? {
            val rate = audioInfo?.sampleRateHz ?: return null
            return AAC_FRAME_SAMPLES * MICROSECONDS_PER_SECOND / rate
        }

        private fun isSync(nals: List<ByteArray>): Boolean =
            nals.any { HevcNalType.isSync(HevcNalType.of(it[0].toInt() and 0xff)) }
    }

    private companion object {
        const val SEQUENCE_MASK = 0xffffffffL
        const val MAX_MEDIA_TRACKS = 16
        const val MAX_SIGNALING_TRACKS = 32
        const val MAX_MFU_BYTES = 8 * 1024 * 1024
        const val MAX_AU_BYTES = 16 * 1024 * 1024
        const val NAL_LENGTH_SIZE = 4
        const val START_CODE_SIZE = 4
        const val AAC_FRAME_SAMPLES = 1024L

        fun concatChunks(
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

        fun annexB(
            nals: List<ByteArray>,
            totalLength: Int,
        ): ByteArray {
            val out = ByteArray(totalLength + nals.size * START_CODE_SIZE)
            var position = 0
            nals.forEach { nal ->
                out[position++] = 0
                out[position++] = 0
                out[position++] = 0
                out[position++] = 1
                nal.copyInto(out, position)
                position += nal.size
            }
            return out
        }
    }
}

private class MpuPayloadHeader(
    val fragmentType: Int,
    val timed: Boolean,
    val fragmentationIndicator: Int,
    val aggregation: Boolean,
    val mpuSequenceNumber: Long,
    val unitsOffset: Int,
    val unitsEnd: Int,
)

/**
 * Parses an MPU payload header (ARIB STD-B60 6.3.2). [unitsOffset]..[unitsEnd]
 * bound the data-unit area as absolute offsets into the payload bytes.
 */
private fun parseMpuPayload(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): MpuPayloadHeader? {
    if (length < MPU_HEADER_SIZE) return null
    val payloadLength = u16(bytes, offset)
    if (payloadLength > length - 2) return null
    val b2 = bytes[offset + 2].toInt() and 0xff
    val end = offset + 2 + payloadLength
    return MpuPayloadHeader(
        fragmentType = (b2 shr 4) and 0x0f,
        timed = (b2 shr 3) and 0x01 == 1,
        fragmentationIndicator = (b2 shr 1) and 0x03,
        aggregation = b2 and 0x01 == 1,
        mpuSequenceNumber = u32(bytes, offset + 4),
        unitsOffset = offset + MPU_HEADER_SIZE,
        unitsEnd = end,
    )
}

private const val MPU_HEADER_SIZE = 8
private const val MFU_UNIT_HEADER_SIZE = 14

/** Extracts the single MFU fragment of a non-aggregated payload. */
private fun parseSingleUnit(
    bytes: ByteArray,
    offset: Int,
    end: Int,
): ByteArray? {
    if (end - offset < MFU_UNIT_HEADER_SIZE) return null
    return bytes.copyOfRange(offset + MFU_UNIT_HEADER_SIZE, end)
}

/** Splits the data units of an aggregated payload; each unit is one complete MFU. Null on malformed framing. */
private fun splitAggregated(
    bytes: ByteArray,
    offset: Int,
    end: Int,
): List<ByteArray>? {
    val units = mutableListOf<ByteArray>()
    var position = offset
    while (position < end) {
        if (end - position < 2 + MFU_UNIT_HEADER_SIZE) return null
        val unitLength = u16(bytes, position)
        if (unitLength < MFU_UNIT_HEADER_SIZE || end - position < 2 + unitLength) return null
        units.add(bytes.copyOfRange(position + 2 + MFU_UNIT_HEADER_SIZE, position + 2 + unitLength))
        position += 2 + unitLength
    }
    return units
}

private val AAC_SAMPLE_RATES =
    intArrayOf(
        96000,
        88200,
        64000,
        48000,
        44100,
        32000,
        24000,
        22050,
        16000,
        12000,
        11025,
        8000,
        7350,
        0,
        0,
        0,
    )

/**
 * Sniffs the AudioSpecificConfig of a LATM AudioMuxElement with an in-band
 * StreamMuxConfig (ISO/IEC 14496-3). Returns null unless the element starts
 * a version-0 config for one AAC-LC program, so later elements reusing the
 * mux (`useSameStreamMux`) never poison the cached parameters.
 */
internal fun sniffLatmConfig(element: ByteArray): MmtAudioStreamInfo? {
    if (element.size < 4) return null
    if (element[0].toInt() and 0x80 != 0x00) return null
    if (element[0].toInt() and 0x40 != 0x00) return null
    if (element[1].toInt() and 0xff != 0x00) return null
    val audioObjectType = (element[2].toInt() and 0xff) shr 3
    if (audioObjectType != 2) return null
    val samplingIndex = ((element[2].toInt() and 0x07) shl 1) or ((element[3].toInt() and 0x80) shr 7)
    if (samplingIndex >= AAC_SAMPLE_RATES.size || AAC_SAMPLE_RATES[samplingIndex] == 0) return null
    val channels = (element[3].toInt() and 0x78) shr 3
    if (channels == 0 || channels > 7) return null
    return MmtAudioStreamInfo(AAC_SAMPLE_RATES[samplingIndex], channels)
}
