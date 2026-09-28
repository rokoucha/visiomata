package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmtSignalingTest {
    private fun pushFull(
        reassembler: MmtSignalingReassembler,
        payload: ByteArray,
        sequenceNumber: Long,
    ): List<ByteArray> = reassembler.push(payload, 0, payload.size, sequenceNumber)

    @Test
    fun reassemblesFragmentedMessageInOrder() {
        val reassembler = MmtSignalingReassembler()
        val head = bytes(0x00, 0x00, 0x07, 0xaa)
        val middle = bytes(0xbb, 0xcc)
        val tail = bytes(0xdd)

        assertTrue(pushFull(reassembler, signalingPayload(MmtFragmentationIndicator.FIRST, head), 10L).isEmpty())
        assertTrue(pushFull(reassembler, signalingPayload(MmtFragmentationIndicator.MIDDLE, middle), 11L).isEmpty())
        val done = pushFull(reassembler, signalingPayload(MmtFragmentationIndicator.LAST, tail), 12L)

        assertEquals(1, done.size)
        assertArrayEquals(head + middle + tail, done[0])
        assertEquals(1L, reassembler.messagesEmitted)
    }

    @Test
    fun splitsAggregatedMessages() {
        val reassembler = MmtSignalingReassembler()
        val first = bytes(0x01, 0x02, 0x03)
        val second = bytes(0x04, 0x05)
        val payload =
            signalingPayload(MmtFragmentationIndicator.NOT_FRAGMENTED, first, aggregation = true) +
                u16Bytes(second.size) + second

        val messages = pushFull(reassembler, payload, 3L)

        assertEquals(2, messages.size)
        assertArrayEquals(first, messages[0])
        assertArrayEquals(second, messages[1])
    }

    @Test
    fun dropsPartialMessageOnSequenceGap() {
        val reassembler = MmtSignalingReassembler()

        assertTrue(pushFull(reassembler, signalingPayload(MmtFragmentationIndicator.FIRST, bytes(1, 2)), 20L).isEmpty())
        val done = pushFull(reassembler, signalingPayload(MmtFragmentationIndicator.LAST, bytes(3)), 22L)

        assert(done.isEmpty())
        assertEquals(1L, reassembler.sequenceGaps)
        assertEquals(1L, reassembler.messagesDropped)
    }

    @Test
    fun parsesPaMessageIntoAssetTimings() {
        val videoNtp = (0xee5e4279L shl 32) or 0xb127c65eL
        val audioNtp = (0xee5e4279L shl 32) or 0xb60ac8c8L
        val message =
            paMessage(
                listOf(
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
                    ),
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
                    ),
                ),
            )

        val info = requireNotNull(MmtPackageParser.parsePaMessage(message))

        assertEquals(2, info.assets.size)
        val video = info.assets.first { it.packetId == 0xf300 }
        assertEquals(MmtAssetType.HEVC_VIDEO, video.assetType)
        // AU0: base - 6000 + 0*3000 + 6000 = base; AU1 presents one interval earlier (B-frame reorder).
        assertEquals(ntpToUs(videoNtp), video.timing.ptsUs(100L, 0))
        assertEquals(ntpToUs(videoNtp) - 3000L * 1_000_000 / 90000, video.timing.ptsUs(100L, 1))
        assertEquals(ntpToUs(videoNtp) - 6000L * 1_000_000 / 90000, video.timing.dtsUs(100L, 0))
        assertNull(video.timing.ptsUs(100L, 2))
        // Far outside the window, anchors advance by the nominal MPU duration (2 units of 3000 ticks).
        val extrapolatedBase = requireNotNull(video.timing.ptsUs(999L, 0))
        assertEquals(extrapolatedBase + 6000L * 1_000_000 / 90000, video.timing.ptsUs(1000L, 0))
        assertEquals(extrapolatedBase - 3000L * 1_000_000 / 90000, video.timing.ptsUs(999L, 1))
        val audio = info.assets.first { it.packetId == 0xf310 }
        assertEquals(ntpToUs(audioNtp) + 1024L * 1_000_000 / 48000, audio.timing.ptsUs(50L, 1))
    }

    @Test
    fun convertsNtpToMicroseconds() {
        assertEquals(0L, ntpToUs(0L))
        assertEquals(1_500_000L, ntpToUs((1L shl 32) or 0x80000000L))
        assertEquals(2_000_000L, ntpToUs(2L shl 32))
    }

    @Test
    fun rejectsMalformedPaMessages() {
        val valid =
            paMessage(
                listOf(
                    TestAsset(0xf300, MmtAssetType.HEVC_VIDEO, mapOf(1L to (5L shl 32))),
                ),
            )
        assertTrue(MmtPackageParser.parsePaMessage(valid) != null)
        // Truncated message.
        assertNull(MmtPackageParser.parsePaMessage(valid.copyOf(valid.size - 1)))
        // Wrong message id.
        assertNull(MmtPackageParser.parsePaMessage(valid.copyOf().also { it[1] = 0x01 }))
        // Corrupt table length.
        assertNull(MmtPackageParser.parsePaMessage(valid.copyOf().also { it[11] = 0x7f }))
        // Message without an MPT (table id changed to PLT).
        assertNull(MmtPackageParser.parsePaMessage(valid.copyOf().also { it[8] = 0x80.toByte() }))
    }

    @Test
    fun extrapolatesAnchorsAlongTheNtpGrid() {
        val base = 100L shl 32
        val message =
            paMessage(
                listOf(
                    TestAsset(
                        packetId = 0xf300,
                        assetType = MmtAssetType.HEVC_VIDEO,
                        presentationTimes =
                            mapOf(
                                10L to base,
                                11L to base + 0x80000000L,
                            ),
                        timescale = 90000,
                        defaultInterval = 22500,
                        mpuTimings =
                            mapOf(
                                10L to TestMpuTiming(0, intArrayOf(0, 0)),
                                11L to TestMpuTiming(0, intArrayOf(0, 0)),
                            ),
                    ),
                ),
            )

        val timing = requireNotNull(MmtPackageParser.parsePaMessage(message)).assets[0].timing

        // Half-second MPU grid: MPU 8 anchors a full second before MPU 10.
        assertEquals(ntpToUs(base) - 1_000_000L, timing.ptsUs(8L, 0))
        assertEquals(ntpToUs(base) - 1_000_000L + 250_000L, timing.ptsUs(8L, 1))
        assertEquals(ntpToUs(base) + 1_000_000L, timing.ptsUs(12L, 0))
        assertNull(timing.ptsUs(8L, 2))
    }

    @Test
    fun extrapolatesWithTemplateOffsets() {
        val base = 50L shl 32
        val message =
            paMessage(
                listOf(
                    TestAsset(
                        packetId = 0xf300,
                        assetType = MmtAssetType.HEVC_VIDEO,
                        presentationTimes = mapOf(20L to base, 21L to base + 0x1_00000000L),
                        timescale = 90000,
                        defaultInterval = 3000,
                        mpuTimings =
                            mapOf(
                                20L to TestMpuTiming(6000, IntArray(30).also { it[0] = 6000 }),
                                21L to TestMpuTiming(6000, IntArray(30).also { it[0] = 6000 }),
                            ),
                    ),
                ),
            )

        val timing = requireNotNull(MmtPackageParser.parsePaMessage(message)).assets[0].timing

        // MPU 19 reuses MPU 20's structure one grid second earlier.
        assertEquals(ntpToUs(base) - 1_000_000L, timing.ptsUs(19L, 0))
        assertEquals(ntpToUs(base) - 1_000_000L - 3000L * 1_000_000 / 90000, timing.ptsUs(19L, 1))
        assertEquals(ntpToUs(base) - 1_000_000L - 6000L * 1_000_000 / 90000, timing.dtsUs(19L, 0))
    }

    @Test
    fun derivesIntervalForPrescribedOffsetType() {
        val base = 10L shl 32
        val message =
            paMessage(
                listOf(
                    TestAsset(
                        packetId = 0xf300,
                        assetType = MmtAssetType.HEVC_VIDEO,
                        presentationTimes = mapOf(7L to base, 8L to base + (1L shl 32)),
                        timescale = 90000,
                        ptsOffsetType = MmtPtsOffsetType.FIXED_PRESCRIBED,
                        mpuTimings =
                            mapOf(
                                7L to TestMpuTiming(0, intArrayOf(0, 0)),
                            ),
                    ),
                ),
            )

        val info = requireNotNull(MmtPackageParser.parsePaMessage(message))

        // One second spread over two units at 90 kHz.
        assertEquals(ntpToUs(base), info.assets[0].timing.ptsUs(7L, 0))
        assertEquals(ntpToUs(base) + 500_000L, info.assets[0].timing.ptsUs(7L, 1))
    }
}
