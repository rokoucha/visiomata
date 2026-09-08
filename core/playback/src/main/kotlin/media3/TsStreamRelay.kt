

@file:Suppress("ktlint:standard:max-line-length")

package net.rokoucha.visiomata.playback.media3

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import net.rokoucha.visiomata.playback.bml.BmlTsDemuxer
import java.util.concurrent.atomic.AtomicLong

internal class TsStreamRelay(
    private val demuxer: BmlTsDemuxer? = null,
    private val onAudioPidsAdded: (Long) -> Unit = {},
) {
    private val generationCounter = AtomicLong()
    val generation: Long get() = generationCounter.get()
    private var streamGeneration = 0L

    private val audioMonitor = TsAudioPmtMonitor { onAudioPidsAdded(streamGeneration) }
    private val partialPacket = ByteArray(packetSize)
    private var partialSize = 0
    private var lastPublishedPcrBase = unsetPcrBase
    private val psiAssemblers = mutableMapOf<Int, PsiSectionAssembler>()

    fun publish(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        try {
            scanTransportStream(source, offset, length)
        } catch (error: Exception) {
            // Side-channel monitoring must never make Media3's upstream read fail.
            Log.e("TsStreamRelay", "Dropping a failed transport stream monitoring chunk", error)
        }
    }

    fun reset() {
        streamGeneration = generationCounter.incrementAndGet()
        audioMonitor.reset()
        partialSize = 0
        lastPublishedPcrBase = unsetPcrBase
        psiAssemblers.clear()
        demuxer?.reset()
    }

    /** Invalidates queued notifications immediately, before the loader closes the old source. */
    fun invalidate() {
        generationCounter.incrementAndGet()
    }

    private fun scanTransportStream(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        var position = offset
        val limit = offset + length
        while (position < limit) {
            if (partialSize == 0 && (source[position].toInt() and 0xff) != syncByte) {
                position++
                continue
            }
            val count = minOf(packetSize - partialSize, limit - position)
            source.copyInto(partialPacket, partialSize, position, position + count)
            partialSize += count
            position += count
            if (partialSize == packetSize) {
                consumePacket(partialPacket, 0)
                partialSize = 0
            }
        }
    }

    private fun consumePacket(
        packet: ByteArray,
        start: Int,
    ) {
        if ((packet[start].toInt() and 0xff) != syncByte) return
        val payloadUnitStart = packet[start + 1].toInt() and 0x40 != 0
        val pid = ((packet[start + 1].toInt() and 0x1f) shl 8) or (packet[start + 2].toInt() and 0xff)
        if (packet[start + 1].toInt() and 0x80 != 0 || packet[start + 3].toInt() and 0xc0 != 0) {
            psiAssemblers[pid]?.reset()
            return
        }
        val adaptationControl = (packet[start + 3].toInt() ushr 4) and 3
        var payload = start + 4
        if (adaptationControl == 2 || adaptationControl == 3) {
            val adaptationLength = packet[payload].toInt() and 0xff
            if (payload + 1 + adaptationLength > start + packetSize) return
            if (adaptationLength > 0 && packet[payload + 1].toInt() and 0x80 != 0) {
                psiAssemblers[pid]?.reset()
            }
            if (adaptationLength >= 7 && packet[payload + 1].toInt() and 0x10 != 0) {
                publishPcr(packet, payload + 2)
            }
            payload += adaptationLength + 1
        }
        if ((adaptationControl != 1 && adaptationControl != 3) || payload >= start + packetSize) return
        if (audioMonitor.acceptsPid(pid) || (demuxer != null && (pid == nitPid || pid == sdtPid))) {
            val continuityCounter = packet[start + 3].toInt() and 0x0f
            psiAssemblers
                .getOrPut(pid) { PsiSectionAssembler() }
                .push(packet, payload, start + packetSize, payloadUnitStart, continuityCounter)
                .forEach { section ->
                    audioMonitor.consume(pid, section)
                    if (pid == nitPid || pid == sdtPid) consumePsiSection(section)
                }
        }
    }

    private fun publishPcr(
        packet: ByteArray,
        p: Int,
    ) {
        val demuxer = demuxer ?: return
        val base =
            ((packet[p].toLong() and 0xff) shl 25) or
                ((packet[p + 1].toLong() and 0xff) shl 17) or
                ((packet[p + 2].toLong() and 0xff) shl 9) or
                ((packet[p + 3].toLong() and 0xff) shl 1) or
                ((packet[p + 4].toLong() ushr 7) and 1)
        val extension = ((packet[p + 4].toInt() and 1) shl 8) or (packet[p + 5].toInt() and 0xff)
        val previous = lastPublishedPcrBase
        if (previous != unsetPcrBase && ((base - previous) and pcrBaseMask) < pcrPublishIntervalTicks) return
        lastPublishedPcrBase = base
        demuxer.pushPcr(base, extension)
    }

    private fun consumePsiSection(section: ByteArray) {
        when (section.firstOrNull()?.toInt()?.and(0xff)) {
            0x40 -> {
                if (section.size >= 8) demuxer?.updateProgramIds(null, null, u16(section, 3))
            }

            0x42 -> {
                if (section.size >= 12) demuxer?.updateProgramIds(u16(section, 8), u16(section, 3), null)
            }
        }
    }

    private fun u16(
        data: ByteArray,
        offset: Int,
    ) = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private companion object {
        const val packetSize = 188
        const val syncByte = 0x47
        const val nitPid = 0x10
        const val sdtPid = 0x11
        const val unsetPcrBase = -1L
        const val pcrBaseMask = (1L shl 33) - 1L
        const val pcrPublishIntervalTicks = 9_000L
    }
}

internal class PsiSectionAssembler {
    private var data = ByteArray(4096)
    private var size = 0
    private var expectedSize = -1
    private var continuityCounter = -1
    private var waitingForStart = true

    fun push(
        packet: ByteArray,
        payloadStart: Int,
        payloadEnd: Int,
        unitStart: Boolean,
        counter: Int,
    ): List<ByteArray> {
        if (counter == continuityCounter) return emptyList()
        if (continuityCounter >= 0 && counter != ((continuityCounter + 1) and 0x0f)) reset()
        continuityCounter = counter
        if (waitingForStart && !unitStart) return emptyList()
        val sections = mutableListOf<ByteArray>()
        var position = payloadStart
        if (unitStart) {
            if (position >= payloadEnd) return sections
            val pointer = packet[position].toInt() and 0xff
            position++
            val previousEnd = position + pointer
            if (previousEnd > payloadEnd) {
                reset()
                return sections
            }
            if (!waitingForStart && size > 0) append(packet, position, previousEnd, sections)
            size = 0
            expectedSize = -1
            waitingForStart = false
            position = previousEnd
        }
        append(packet, position, payloadEnd, sections)
        return sections
    }

    private fun append(
        source: ByteArray,
        start: Int,
        end: Int,
        sections: MutableList<ByteArray>,
    ) {
        var position = start
        while (position < end) {
            if (size == 0 && (source[position].toInt() and 0xff) == 0xff) return
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = source[position++]
            if (size == 3) {
                expectedSize = 3 + (((data[1].toInt() and 0x0f) shl 8) or (data[2].toInt() and 0xff))
                if (expectedSize !in 3..maxSectionSize) {
                    reset()
                    return
                }
                if (expectedSize > data.size) data = data.copyOf(expectedSize)
            }
            if (expectedSize > 0 && size == expectedSize) {
                sections.add(data.copyOf(size))
                size = 0
                expectedSize = -1
            }
        }
    }

    fun reset() {
        waitingForStart = true
        continuityCounter = -1
        size = 0
        expectedSize = -1
    }

    private companion object {
        const val maxSectionSize = 4096
    }
}

@UnstableApi
internal class RelayingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val relay: TsStreamRelay,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RelayingDataSource(upstreamFactory.createDataSource(), relay)
}

@UnstableApi
private class RelayingDataSource(
    private val upstream: DataSource,
    private val relay: TsStreamRelay,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        relay.reset()
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0) relay.publish(buffer, offset, bytesRead)
        return bytesRead
    }

    override fun getUri() = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            relay.invalidate()
        }
    }
}
