package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmtDepacketizerTest {
    private val videoNtp = (0xee5e4279L shl 32)
    private val videoBaseUs = ntpToUs(videoNtp)
    private val audioNtp = (0xee5e4279L shl 32) or 0x40000000L
    private val audioBaseUs = ntpToUs(audioNtp)

    private fun videoAsset() =
        TestAsset(
            packetId = 0xf300,
            assetType = MmtAssetType.HEVC_VIDEO,
            presentationTimes = mapOf(100L to videoNtp),
            timescale = 90000,
            defaultInterval = 3000,
            mpuTimings =
                mapOf(
                    100L to
                        TestMpuTiming(
                            decodingOffset = 6000,
                            dtsPtsOffsets = intArrayOf(6000, 0),
                        ),
                ),
        )

    private fun audioAsset() =
        TestAsset(
            packetId = 0xf310,
            assetType = MmtAssetType.AAC_AUDIO,
            presentationTimes = mapOf(50L to audioNtp),
            timescale = 48000,
            defaultInterval = 1024,
            mpuTimings =
                mapOf(
                    50L to
                        TestMpuTiming(
                            decodingOffset = 0,
                            dtsPtsOffsets = intArrayOf(0, 0),
                        ),
                ),
        )

    private fun feedPackage(depacketizer: MmtDepacketizer) {
        val message = paMessage(listOf(videoAsset(), audioAsset()))
        val payload = signalingPayload(MmtFragmentationIndicator.NOT_FRAGMENTED, message)
        depacketizer.pushMmt(mmtPacket(MmtPayloadType.SIGNALING, 0xff02, 77L, payload))
    }

    private fun videoPacket(
        depacketizer: MmtDepacketizer,
        sequenceNumber: Long,
        payload: ByteArray,
        rap: Boolean = false,
    ) {
        depacketizer.pushMmt(mmtPacket(MmtPayloadType.MPU, 0xf300, sequenceNumber, payload, rap = rap))
    }

    private fun audioPacket(
        depacketizer: MmtDepacketizer,
        sequenceNumber: Long,
        payload: ByteArray,
    ) {
        depacketizer.pushMmt(mmtPacket(MmtPayloadType.MPU, 0xf310, sequenceNumber, payload))
    }

    @Test
    fun emitsVideoAccessUnitsWithExactReorderedTimestamps() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud = nal(HevcNalType.AUD, 0x50)
        val cra = nal(21, 0x01, 0x02, 0x03)
        val trail = nal(1, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e)
        val fragmented = lengthPrefixed(trail)

        videoPacket(depacketizer, 1000L, aggregatedMfuPayload(100L, lengthPrefixed(aud), lengthPrefixed(cra)))
        videoPacket(
            depacketizer,
            1001L,
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud)),
        )
        videoPacket(
            depacketizer,
            1002L,
            singleMfuPayload(100L, MmtFragmentationIndicator.FIRST, fragmented.copyOfRange(0, 6)),
        )
        videoPacket(
            depacketizer,
            1003L,
            singleMfuPayload(100L, MmtFragmentationIndicator.MIDDLE, fragmented.copyOfRange(6, 9)),
        )
        videoPacket(
            depacketizer,
            1004L,
            singleMfuPayload(100L, MmtFragmentationIndicator.LAST, fragmented.copyOfRange(9, fragmented.size)),
        )
        videoPacket(
            depacketizer,
            1005L,
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(aud)),
        )

        assertEquals(2, samples.size)
        assertEquals(MmtTrackKind.VIDEO_HEVC, samples[0].kind)
        assertEquals(videoBaseUs, samples[0].ptsUs)
        assertEquals(videoBaseUs - 6000L * 1_000_000 / 90000, samples[0].dtsUs)
        assertTrue(samples[0].isSyncFrame)
        assertEquals(0, samples[0].accessUnitIndex)
        assertArrayEquals(
            bytes(0, 0, 0, 1) + aud + bytes(0, 0, 0, 1) + cra,
            samples[0].data,
        )
        // Second unit in decode order presents one interval earlier.
        assertEquals(videoBaseUs - 3000L * 1_000_000 / 90000, samples[1].ptsUs)
        assertFalse(samples[1].isSyncFrame)
        assertEquals(1, samples[1].accessUnitIndex)
        assertArrayEquals(bytes(0, 0, 0, 1) + aud + bytes(0, 0, 0, 1) + trail, samples[1].data)
    }

    @Test
    fun emitsAudioFramesWithExactTimestamps() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val frame = bytes(0x20, 0x00, 0x11, 0x90, 0x0d, 0x48, 0x0f, 0xff)

        audioPacket(depacketizer, 500L, singleMfuPayload(50L, MmtFragmentationIndicator.NOT_FRAGMENTED, frame))
        audioPacket(depacketizer, 501L, singleMfuPayload(50L, MmtFragmentationIndicator.NOT_FRAGMENTED, frame))

        assertEquals(2, samples.size)
        assertEquals(MmtTrackKind.AUDIO_AAC, samples[0].kind)
        assertEquals(audioBaseUs, samples[0].ptsUs)
        assertEquals(audioBaseUs, samples[0].dtsUs)
        assertTrue(samples[0].isSyncFrame)
        assertArrayEquals(frame, samples[0].data)
        assertEquals(audioBaseUs + 1024L * 1_000_000 / 48000, samples[1].ptsUs)
        assertEquals(1, samples[1].accessUnitIndex)
        val info = requireNotNull(depacketizer.audioStreamInfo(0xf310))
        assertEquals(48000, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(2L, depacketizer.audioSamplesEmitted)
    }

    @Test
    fun resetsAccessUnitIndexOnMpuChange() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud = lengthPrefixed(nal(HevcNalType.AUD, 0x50))

        videoPacket(depacketizer, 1000L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1001L, singleMfuPayload(101L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1002L, singleMfuPayload(101L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))

        assertEquals(2, samples.size)
        assertEquals(100L, samples[0].mpuSequenceNumber)
        assertEquals(0, samples[0].accessUnitIndex)
        assertEquals(videoBaseUs, samples[0].ptsUs)
        assertEquals(101L, samples[1].mpuSequenceNumber)
        assertEquals(0, samples[1].accessUnitIndex)
        // MPU 101 sits outside the signaled window, so its anchor extrapolates
        // by the nominal MPU duration (2 units of 3000 ticks at 90 kHz).
        val nominalSpacingNtp = 3000L * 4294967296L / 90000 * 2
        assertEquals(ntpToUs(videoNtp + nominalSpacingNtp), samples[1].ptsUs)
        assertEquals(0L, depacketizer.samplesWithFallbackPts)
    }

    @Test
    fun fallsBackForUnitsBeyondTheDescriptor() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud =
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(nal(HevcNalType.AUD, 0x50)))

        videoPacket(depacketizer, 1000L, aud)
        videoPacket(depacketizer, 1001L, aud)
        videoPacket(depacketizer, 1002L, aud)
        videoPacket(depacketizer, 1003L, aud)

        // The descriptor covers 2 units; the third carries the clock forward nominally.
        assertEquals(3, samples.size)
        assertEquals(2, samples[2].accessUnitIndex)
        assertEquals(samples[1].ptsUs + 3000L * 1_000_000 / 90000, samples[2].ptsUs)
        assertEquals(1L, depacketizer.samplesWithFallbackPts)
    }

    @Test
    fun dropsAccessUnitWithDroppedFragment() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud =
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(nal(HevcNalType.AUD, 0x50)))
        val orphan = singleMfuPayload(100L, MmtFragmentationIndicator.MIDDLE, bytes(0x09, 0x09, 0x09))

        videoPacket(depacketizer, 1000L, aud)
        // Continuation without a head: the open unit has a hole.
        videoPacket(depacketizer, 1001L, orphan)
        videoPacket(depacketizer, 1002L, aud)
        videoPacket(depacketizer, 1003L, aud)

        assertEquals(1, samples.size)
        assertEquals(1, samples[0].accessUnitIndex)
        assertEquals(videoBaseUs - 3000L * 1_000_000 / 90000, samples[0].ptsUs)
        assertEquals(1L, depacketizer.mfusDropped)
        assertEquals(1L, depacketizer.corruptAusDropped)
    }

    @Test
    fun dropsSamplesWithoutAnyTimingAnchor() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        val message = paMessage(listOf(TestAsset(0xf300, MmtAssetType.HEVC_VIDEO)))
        depacketizer.pushMmt(
            mmtPacket(
                MmtPayloadType.SIGNALING,
                0xff02,
                1L,
                signalingPayload(MmtFragmentationIndicator.NOT_FRAGMENTED, message),
            ),
        )
        val aud = lengthPrefixed(nal(HevcNalType.AUD, 0x50))

        videoPacket(depacketizer, 1000L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1001L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))

        assertTrue(samples.isEmpty())
        assertEquals(1L, depacketizer.samplesDroppedNoTiming)
    }

    @Test
    fun resyncsAfterSequenceGap() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud = lengthPrefixed(nal(HevcNalType.AUD, 0x50))
        val slice = lengthPrefixed(nal(1, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))

        videoPacket(
            depacketizer,
            1000L,
            singleMfuPayload(100L, MmtFragmentationIndicator.FIRST, slice.copyOfRange(0, 5)),
        )
        // Sequence 1001 lost: the partial MFU cannot be completed.
        videoPacket(
            depacketizer,
            1002L,
            singleMfuPayload(100L, MmtFragmentationIndicator.LAST, slice.copyOfRange(5, slice.size)),
        )
        videoPacket(depacketizer, 1003L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1004L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))

        assertEquals(1, samples.size)
        assertEquals(1L, depacketizer.mediaSequenceGaps)
        assertTrue(depacketizer.mfusDropped >= 1L)
    }

    @Test
    fun dropsScrambledUnmappedAndNonMediaPackets() {
        val depacketizer = MmtDepacketizer()
        feedPackage(depacketizer)
        val aud = singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(nal(HevcNalType.AUD)))

        depacketizer.pushMmt(
            mmtPacket(
                MmtPayloadType.MPU,
                0xf300,
                1000L,
                aud,
                extensionEntries = listOf(scrambleEntry(MmtScramblingControl.SCRAMBLED_EVEN)),
            ),
        )
        depacketizer.pushMmt(mmtPacket(MmtPayloadType.MPU, 0xf999, 1001L, aud))
        depacketizer.pushMmt(
            mmtPacket(
                MmtPayloadType.MPU,
                0xf300,
                1002L,
                mpuPayload(
                    MmtFragmentType.MFU,
                    false,
                    MmtFragmentationIndicator.NOT_FRAGMENTED,
                    false,
                    100L,
                    mfuUnitHeader(),
                ),
            ),
        )
        depacketizer.pushMmt(
            mmtPacket(
                MmtPayloadType.MPU,
                0xf300,
                1003L,
                mpuPayload(
                    MmtFragmentType.MPU_METADATA,
                    true,
                    MmtFragmentationIndicator.NOT_FRAGMENTED,
                    false,
                    100L,
                    bytes(1, 2),
                ),
            ),
        )

        assertEquals(1L, depacketizer.scrambledPacketsDropped)
        assertEquals(1L, depacketizer.unmappedPacketsDropped)
        assertEquals(2L, depacketizer.nonAvPacketsSkipped)
    }

    @Test
    fun marksRapPacketUnitsAsSync() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud = lengthPrefixed(nal(HevcNalType.AUD, 0x50))
        val slice = lengthPrefixed(nal(1, 0x09))

        videoPacket(
            depacketizer,
            1000L,
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud),
            rap = true,
        )
        videoPacket(depacketizer, 1001L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, slice))
        videoPacket(depacketizer, 1002L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))

        assertEquals(1, samples.size)
        assertTrue(samples[0].isSyncFrame)
    }

    @Test
    fun dropsCorruptAccessUnitButKeepsDecodeIndex() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud = lengthPrefixed(nal(HevcNalType.AUD, 0x50))
        // Declared NAL length does not match the carried bytes.
        val corrupt = u32Bytes(99L) + nal(1, 0x01)

        videoPacket(depacketizer, 1000L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1001L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, corrupt))
        videoPacket(depacketizer, 1002L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))
        videoPacket(depacketizer, 1003L, singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, aud))

        assertEquals(1, samples.size)
        assertEquals(1, samples[0].accessUnitIndex)
        assertEquals(videoBaseUs - 3000L * 1_000_000 / 90000, samples[0].ptsUs)
    }

    @Test
    fun acceptsTlvPacketsAndIgnoresNonIpTypes() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        val message = paMessage(listOf(videoAsset()))
        val signaling =
            mmtPacket(
                MmtPayloadType.SIGNALING,
                0xff02,
                1L,
                signalingPayload(MmtFragmentationIndicator.NOT_FRAGMENTED, message),
            )
        val aud =
            mmtPacket(
                MmtPayloadType.MPU,
                0xf300,
                1000L,
                singleMfuPayload(
                    100L,
                    MmtFragmentationIndicator.NOT_FRAGMENTED,
                    lengthPrefixed(nal(HevcNalType.AUD, 0x50)),
                ),
            )
        val aud2 =
            mmtPacket(
                MmtPayloadType.MPU,
                0xf300,
                1001L,
                singleMfuPayload(
                    100L,
                    MmtFragmentationIndicator.NOT_FRAGMENTED,
                    lengthPrefixed(nal(HevcNalType.AUD, 0x50)),
                ),
            )

        depacketizer.pushTlv(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x10, 0x61) + signaling))
        depacketizer.pushTlv(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x11, 0x61) + aud))
        depacketizer.pushTlv(TlvPacket(TlvPacketType.NULL, bytes(0xff, 0xff)))
        depacketizer.pushTlv(TlvPacket(TlvPacketType.COMPRESSED_IP, bytes(0x00, 0x12, 0x61) + aud2))

        assertEquals(1, samples.size)
        assertEquals(3L, depacketizer.tlvPacketsAccepted)
        assertEquals(1L, depacketizer.tlvPacketsIgnored)
        assertEquals(1L, depacketizer.mptUpdatesApplied)
    }

    @Test
    fun resetClearsPackageAndTracks() {
        val samples = mutableListOf<MmtSample>()
        val depacketizer = MmtDepacketizer(onSample = samples::add)
        feedPackage(depacketizer)
        val aud =
            singleMfuPayload(100L, MmtFragmentationIndicator.NOT_FRAGMENTED, lengthPrefixed(nal(HevcNalType.AUD, 0x50)))

        videoPacket(depacketizer, 1000L, aud)
        videoPacket(depacketizer, 1001L, aud)
        assertEquals(1, samples.size)

        depacketizer.reset()
        videoPacket(depacketizer, 1002L, aud)

        assertEquals(1, samples.size)
        assertEquals(1L, depacketizer.unmappedPacketsDropped)
    }
}
