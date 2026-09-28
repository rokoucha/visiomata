package net.rokoucha.visiomata.playback.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmtPacketTest {
    @Test
    fun parsesMediaHeaderWithScrambleExtension() {
        val payload = bytes(0x00, 0x10, 0x25, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02)

        val packet =
            requireNotNull(
                parseMmtPacket(
                    mmtPacket(
                        MmtPayloadType.MPU,
                        0xf300,
                        1976158374L,
                        payload,
                        extensionEntries = listOf(scrambleEntry(MmtScramblingControl.UNSCRAMBLED)),
                    ),
                ),
            )

        assertEquals(MmtPayloadType.MPU, packet.payloadType)
        assertEquals(0xf300, packet.packetId)
        assertEquals(1976158374L, packet.packetSequenceNumber)
        assertFalse(packet.rapFlag)
        assertEquals(1, packet.extensionEntries.size)
        assertEquals(MmtExtensionType.SCRAMBLING, packet.extensionEntries[0].type)
        assertEquals(MmtScramblingControl.UNSCRAMBLED, packet.scramblingControl())
        assertArrayEquals(
            payload,
            packet.payloadBytes.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.payloadLength),
        )
    }

    @Test
    fun reportsScrambledControlAndRapFlag() {
        val packet =
            requireNotNull(
                parseMmtPacket(
                    mmtPacket(
                        MmtPayloadType.MPU,
                        0xf300,
                        1L,
                        bytes(0x01, 0x02),
                        rap = true,
                        extensionEntries = listOf(scrambleEntry(MmtScramblingControl.SCRAMBLED_ODD)),
                    ),
                ),
            )

        assertTrue(packet.rapFlag)
        assertEquals(MmtScramblingControl.SCRAMBLED_ODD, packet.scramblingControl())
    }

    @Test
    fun assumesUnscrambledWithoutExtension() {
        val packet = requireNotNull(parseMmtPacket(mmtPacket(MmtPayloadType.SIGNALING, 0xff02, 9L, bytes(0x00, 0x00))))

        assertEquals(MmtPayloadType.SIGNALING, packet.payloadType)
        assertEquals(MmtScramblingControl.UNSCRAMBLED, packet.scramblingControl())
        assertTrue(packet.extensionEntries.isEmpty())
    }

    @Test
    fun skipsPacketCounterWhenFlagged() {
        val payload = bytes(0x0a, 0x0b, 0x0c)

        val packet =
            requireNotNull(
                parseMmtPacket(
                    mmtPacket(MmtPayloadType.MPU, 0x10, 5L, payload, packetCounter = 99L),
                ),
            )

        assertEquals(3, packet.payloadLength)
        assertArrayEquals(
            payload,
            packet.payloadBytes.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.payloadLength),
        )
    }

    @Test
    fun rejectsInvalidHeaders() {
        val valid = mmtPacket(MmtPayloadType.MPU, 0x10, 5L, bytes(0x01))

        assertNull(parseMmtPacket(valid.copyOf(11)))
        // Bad version.
        assertNull(parseMmtPacket(valid.copyOf().also { it[0] = 0x40.toByte() }))
        // FEC type must be zero.
        assertNull(parseMmtPacket(valid.copyOf().also { it[0] = 0x08.toByte() }))
        // Unknown payload type 0x05.
        assertNull(parseMmtPacket(valid.copyOf().also { it[1] = 0xc5.toByte() }))
        // Truncated extension.
        val withExt =
            mmtPacket(
                MmtPayloadType.MPU,
                0x10,
                5L,
                bytes(0x01),
                extensionEntries = listOf(scrambleEntry(0)),
            )
        assertNull(parseMmtPacket(withExt.copyOf(withExt.size - 3)))
    }

    @Test
    fun parsesMultipleExtensionEntries() {
        val packet =
            requireNotNull(
                parseMmtPacket(
                    mmtPacket(
                        MmtPayloadType.MPU,
                        0xf340,
                        3L,
                        bytes(0x07),
                        extensionEntries =
                            listOf(
                                scrambleEntry(0),
                                MmtExtensionType.DOWNLOAD_ID to bytes(0x01, 0x02, 0x03, 0x04),
                            ),
                    ),
                ),
            )

        assertEquals(2, packet.extensionEntries.size)
        assertArrayEquals(
            bytes(0x01, 0x02, 0x03, 0x04),
            packet.extensionEntries[1].bytes,
        )
        assertEquals(1, packet.payloadLength)
    }
}
