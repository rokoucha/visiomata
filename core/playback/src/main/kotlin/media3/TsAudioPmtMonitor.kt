package net.rokoucha.visiomata.playback.media3

/** Watches the same first program map as MODE_SINGLE_PMT, including when BML is disabled. */
internal class TsAudioPmtMonitor(
    private val onAudioPidsAdded: () -> Unit,
) {
    private var programs: Map<Int, Int>? = null
    private var selectedPmtPid: Int? = null
    private var initialAudioPids: Set<Int>? = null
    private var notified = false

    fun reset() {
        programs = null
        selectedPmtPid = null
        initialAudioPids = null
        notified = false
    }

    fun acceptsPid(pid: Int): Boolean = pid == 0 || pid in programs.orEmpty()

    fun consume(
        pid: Int,
        section: ByteArray,
    ) {
        if (notified || !isCurrentSection(section)) return
        val end = section.size - 4 // CRC
        if (pid == 0 && section[0].toInt() == 0 && programs == null) {
            if ((end - 8) % 4 != 0) return
            programs =
                buildMap {
                    for (offset in 8 until end step 4) {
                        val program = u16(section, offset)
                        if (program != 0) put(u16(section, offset + 2) and 0x1fff, program)
                    }
                }
            return
        }
        if (section[0].toInt() != 2 || section.size < 16 ||
            programs?.get(pid) != u16(section, 3) ||
            (selectedPmtPid != null && selectedPmtPid != pid)
        ) {
            return
        }
        // A program map is carried in a single section, unlike a PAT.
        if (section[6].toInt() != 0 || section[7].toInt() != 0) return
        var offset = 12 + (u16(section, 10) and 0x0fff)
        if (offset > end) return
        val audioPids = mutableSetOf<Int>()
        while (offset < end) {
            if (offset + 5 > end) return
            val next = offset + 5 + (u16(section, offset + 3) and 0x0fff)
            if (next > end) return
            if (section[offset].toInt() == 0x0f) audioPids += u16(section, offset + 1) and 0x1fff
            offset = next
        }
        val initial = initialAudioPids
        if (initial == null) {
            selectedPmtPid = pid
            initialAudioPids = audioPids
        } else if (audioPids.any { it !in initial }) {
            // Repeated PMTs must not enqueue multiple source replacements for one connection.
            notified = true
            onAudioPidsAdded()
        }
    }

    private fun isCurrentSection(section: ByteArray): Boolean {
        if (section.size < 12 || section[1].toInt() and 0x80 == 0 || section[5].toInt() and 1 == 0) {
            return false
        }
        if (3 + (u16(section, 1) and 0x0fff) != section.size) return false
        var crc = -1
        for (byte in section) {
            crc = crc xor ((byte.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0
            }
        }
        return crc == 0
    }

    private fun u16(
        data: ByteArray,
        offset: Int,
    ): Int = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}
