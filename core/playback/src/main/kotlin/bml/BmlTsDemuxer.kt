@file:Suppress("ktlint:standard:max-line-length")

package net.rokoucha.visiomata.playback.bml

import android.os.Process
import android.os.Trace
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater

/**
 * Small, streaming ARIB MPEG-TS demultiplexer for the BML data carousel.
 *
 * Media3 remains responsible for A/V. This class only reads PSI, PCR and the DSM-CC sections that
 * web-bml consumes, and emits the same messages as web-bml's former browser-side TS decoder.
 */
internal data class BmlResourceLimits(
    val maxModuleBytes: Long = 16L * 1024 * 1024,
    val maxCarouselBytes: Long = 32L * 1024 * 1024,
    val queueCapacity: Int = 128,
)

internal class BmlTsDemuxer(
    private val resourceLimits: BmlResourceLimits = BmlResourceLimits(),
) : BmlMessageSource {
    private data class SectionChunk(
        val pid: Int,
        val bytes: ByteArray,
        val length: Int,
        val generation: Int,
    )

    private data class CompletedModuleKey(
        val pid: Int,
        val downloadId: Long,
        val moduleId: Int,
        val version: Int,
    )

    private data class PendingComponent(
        val pid: Int,
        val componentId: Int,
        val streamType: Int,
        val dataComponentId: Int?,
        val additionalInfo: ByteArray?,
        val serviceId: Int?,
        val generation: Int,
    )

    private data class Component(
        val pid: Int,
        val id: Int,
        val streamType: Int,
        val dataComponentId: Int?,
        val additionalInfo: ByteArray?,
    )

    private data class Module(
        val id: Int,
        val version: Int,
        val size: Int,
        val blockSize: Int,
        val dataEventId: Int,
        val contentType: String?,
        val compressed: Boolean,
        val blocks: Array<ByteArray?>,
        var queuedForProcessing: Boolean = false,
    )

    private data class FilePart(
        val location: String?,
        val type: String,
        val start: Int,
        val end: Int,
    )

    private data class PendingMessage(
        var json: String?,
    )

    private enum class ModulePhase { ASSEMBLE, INFLATE, MIME_INIT, MIME_SCAN, JSON_INIT, JSON_FILE, JSON_BASE64, DONE }

    private enum class MimePhase { FIND_FIRST_BOUNDARY, READ_BOUNDARY, FIND_HEADER_END, FIND_NEXT_BOUNDARY }

    private data class ModuleWork(
        val componentId: Int,
        val module: Module,
        val message: PendingMessage,
        val assembled: ByteArray = ByteArray(module.size),
        var phase: ModulePhase = ModulePhase.ASSEMBLE,
        var blockIndex: Int = 0,
        var blockOffset: Int = 0,
        var outputOffset: Int = 0,
        var inflater: Inflater? = null,
        var inflateOutput: ByteArrayOutputStream? = null,
        var data: ByteArray? = null,
        var effectiveType: String? = module.contentType,
        var bodyStart: Int = 0,
        var boundary: ByteArray? = null,
        var mimePosition: Int = -1,
        var mimePhase: MimePhase = MimePhase.FIND_FIRST_BOUNDARY,
        var scanCursor: Int = 0,
        var headerStart: Int = 0,
        var headerEnd: Int = 0,
        val files: MutableList<FilePart> = mutableListOf(),
        var json: StringBuilder? = null,
        var fileIndex: Int = 0,
        var base64Offset: Int = 0,
    )

    private val components = mutableMapOf<Int, Component>()
    private val modules = mutableMapOf<Pair<Int, Int>, Module>()
    private val transactions = mutableMapOf<Int, Long>()
    private var selectedServiceId: Int? = null
    private var originalNetworkId: Int? = null
    private var transportStreamId: Int? = null
    private var networkId: Int? = null

    @Volatile private var consumer: ((String) -> Unit)? = null
    private val waitingMessages = ArrayDeque<PendingMessage>()
    private val moduleWorks = ArrayDeque<ModuleWork>()
    private val batchBuilder = StringBuilder(initialBatchCapacity)
    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "BmlTsDemuxer",
            ).apply { isDaemon = true }
        }
    private val inputLock = Any()
    private val byteArrayPool = ArrayDeque<ByteArray>()
    private val queuedPrioritySections = ArrayDeque<SectionChunk>()
    private val queuedSections = ArrayDeque<SectionChunk>()
    private val entryPointPids = ConcurrentHashMap.newKeySet<Int>()
    private val queuedComponents = ArrayDeque<PendingComponent>()
    private val queuedPcrBase = LongArray(maxQueuedPcr)
    private val queuedPcrExtension = IntArray(maxQueuedPcr)
    private var queuedPcrRead = 0
    private var queuedPcrSize = 0
    private val drainScheduled = AtomicBoolean()
    private val active = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val completedModules = ConcurrentHashMap.newKeySet<CompletedModuleKey>()
    private var workerGeneration = 0

    override fun start() {
        if (active.compareAndSet(false, true) && consumer != null) {
            worker.execute(::flushMessagesSafely)
        }
    }

    override fun stop() {
        if (active.compareAndSet(true, false)) reset()
    }

    fun pushSection(
        pid: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0 || !active.get()) return
        Trace.beginSection("BML enqueue section")
        try {
            val copy = acquireInputBuffer(length)
            source.copyInto(copy, 0, offset, offset + length)
            synchronized(inputLock) {
                val target = if (isPrioritySection(pid, copy, length)) queuedPrioritySections else queuedSections
                val capacity = resourceLimits.queueCapacity
                if (target.size >= capacity) recycleInputBuffer(target.removeFirst().bytes)
                target.addLast(SectionChunk(pid, copy, length, generation.get()))
            }
        } finally {
            Trace.endSection()
        }
        scheduleDrain()
    }

    /**
     * Loader-thread fast path for repeated DDB sections. A completed generation is immutable, so its
     * later carousel cycles can be discarded before allocating and copying a section buffer.
     */
    fun shouldConsumeSection(
        pid: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (!active.get()) return false
        if (length < 26 || (source[offset].toInt() and 0xff) != 0x3c) return true
        val messageStart = offset + 8
        if (messageStart + 12 > offset + length || u16(source, messageStart + 2) != 0x1003) return true
        val downloadId = u32(source, messageStart + 4)
        val adaptationLength = source[messageStart + 9].toInt() and 0xff
        val moduleStart = messageStart + 12 + adaptationLength
        if (moduleStart + 3 > offset + length) return true
        val key =
            CompletedModuleKey(
                pid = pid,
                downloadId = downloadId,
                moduleId = u16(source, moduleStart),
                version = source[moduleStart + 2].toInt() and 0xff,
            )
        return key !in completedModules
    }

    fun pushPcr(
        pcrBase: Long,
        pcrExtension: Int,
    ) {
        if (!active.get()) return
        synchronized(inputLock) {
            if (queuedPcrSize == maxQueuedPcr) {
                queuedPcrRead = (queuedPcrRead + 1) % maxQueuedPcr
                queuedPcrSize--
            }
            val write = (queuedPcrRead + queuedPcrSize) % maxQueuedPcr
            queuedPcrBase[write] = pcrBase
            queuedPcrExtension[write] = pcrExtension
            queuedPcrSize++
        }
        scheduleDrain()
    }

    fun registerComponent(
        pid: Int,
        componentId: Int,
        streamType: Int,
        dataComponentId: Int?,
        additionalInfo: ByteArray?,
        serviceId: Int?,
    ) {
        if (!active.get()) return
        if ((additionalInfo?.firstOrNull()?.toInt()?.and(0x20) ?: 0) != 0) entryPointPids.add(pid)
        synchronized(inputLock) {
            queuedComponents.addLast(
                PendingComponent(
                    pid,
                    componentId,
                    streamType,
                    dataComponentId,
                    additionalInfo,
                    serviceId,
                    generation.get(),
                ),
            )
        }
        scheduleDrain()
    }

    override fun setConsumer(consumer: ((String) -> Unit)?) {
        this.consumer = consumer
        if (consumer != null && active.get()) worker.execute(::flushMessagesSafely)
    }

    fun updateProgramIds(
        originalNetworkId: Int?,
        transportStreamId: Int?,
        networkId: Int?,
    ) {
        if (worker.isShutdown || !active.get()) return
        worker.execute {
            var changed = false
            if (originalNetworkId != null && originalNetworkId != this.originalNetworkId) {
                this.originalNetworkId = originalNetworkId
                changed = true
            }
            if (transportStreamId != null && transportStreamId != this.transportStreamId) {
                this.transportStreamId = transportStreamId
                changed = true
            }
            if (networkId != null && networkId != this.networkId) {
                this.networkId = networkId
                changed = true
            }
            if (changed) emitProgramInfo()
            flushMessagesSafely()
        }
    }

    override fun reset() {
        val nextGeneration = generation.incrementAndGet()
        synchronized(inputLock) {
            entryPointPids.clear()
            while (queuedPrioritySections.isNotEmpty()) recycleInputBuffer(queuedPrioritySections.removeFirst().bytes)
            while (queuedSections.isNotEmpty()) recycleInputBuffer(queuedSections.removeFirst().bytes)
            queuedComponents.clear()
            queuedPcrRead = 0
            queuedPcrSize = 0
        }
        worker.execute {
            if (workerGeneration != nextGeneration) {
                resetOnWorker()
                workerGeneration = nextGeneration
            }
        }
    }

    private fun resetOnWorker() {
        components.clear()
        modules.clear()
        transactions.clear()
        completedModules.clear()
        moduleWorks.forEach { it.inflater?.end() }
        moduleWorks.clear()
        selectedServiceId = null
        originalNetworkId = null
        transportStreamId = null
        networkId = null
        waitingMessages.clear()
    }

    override fun release() {
        active.set(false)
        consumer = null
        worker.shutdownNow()
        moduleWorks.forEach { it.inflater?.end() }
        moduleWorks.clear()
        waitingMessages.clear()
        synchronized(inputLock) {
            queuedPrioritySections.clear()
            queuedSections.clear()
            queuedComponents.clear()
            byteArrayPool.clear()
        }
        entryPointPids.clear()
        completedModules.clear()
    }

    private fun acquireInputBuffer(length: Int): ByteArray =
        synchronized(inputLock) {
            val reusable = byteArrayPool.firstOrNull { it.size >= length }
            if (reusable != null) {
                byteArrayPool.remove(reusable)
                reusable
            } else {
                ByteArray(length)
            }
        }

    private fun recycleInputBuffer(bytes: ByteArray) {
        if (bytes.size <= maxPooledInputBufferBytes && byteArrayPool.size < maxPooledInputBuffers) {
            byteArrayPool.addLast(bytes)
        }
    }

    private fun drainAndFlushSafely() {
        try {
            Trace.beginSection("BML drain batch")
            try {
                drainExtractedData()
            } finally {
                Trace.endSection()
            }
            Trace.beginSection("BML build JS batch")
            try {
                flushMessages()
            } finally {
                Trace.endSection()
            }
        } catch (error: Exception) {
            Log.e(logTag, "Failed to process a BML batch", error)
        }
    }

    private fun scheduleDrain(delayMillis: Long = batchIntervalMillis) {
        if (!active.get() || worker.isShutdown || !drainScheduled.compareAndSet(false, true)) return
        try {
            worker.schedule(::runScheduledDrain, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            drainScheduled.set(false)
        }
    }

    private fun runScheduledDrain() {
        try {
            if (active.get()) drainAndFlushSafely()
        } finally {
            drainScheduled.set(false)
            if (hasQueuedInput()) scheduleDrain(if (hasBoundedWork()) moduleContinuationMillis else batchIntervalMillis)
        }
    }

    private fun hasBoundedWork(): Boolean =
        synchronized(inputLock) {
            queuedPrioritySections.isNotEmpty() || queuedSections.isNotEmpty() || moduleWorks.isNotEmpty()
        }

    private fun hasQueuedInput(): Boolean =
        synchronized(inputLock) {
            queuedComponents.isNotEmpty() || queuedPrioritySections.isNotEmpty() || queuedSections.isNotEmpty() ||
                queuedPcrSize > 0 || moduleWorks.isNotEmpty()
        }

    // Keep the ordered protocol parsing steps together for comparison with the wire format.
    @Suppress("LongMethod")
    private fun drainExtractedData() {
        var componentsChanged = false
        repeat(maxItemsPerBatch) {
            val pending = synchronized(inputLock) { queuedComponents.removeFirstOrNull() } ?: return@repeat
            if (workerGeneration != pending.generation) {
                resetOnWorker()
                workerGeneration = pending.generation
            }
            selectedServiceId = pending.serviceId ?: selectedServiceId
            val component =
                Component(
                    pending.pid,
                    pending.componentId,
                    pending.streamType,
                    pending.dataComponentId,
                    pending.additionalInfo,
                )
            val previous = components.put(pending.pid, component)
            componentsChanged = componentsChanged || previous == null || !sameComponent(previous, component)
        }
        if (componentsChanged) emitProgramMap()

        val sectionDeadline = System.nanoTime() + sectionWorkBudgetNanos
        var sectionsProcessed = 0
        var sectionBytesProcessed = 0
        while (
            sectionsProcessed < maxSectionsPerDrain &&
            sectionBytesProcessed < maxSectionBytesPerDrain &&
            (sectionsProcessed == 0 || System.nanoTime() < sectionDeadline)
        ) {
            val section =
                synchronized(inputLock) {
                    queuedPrioritySections.removeFirstOrNull() ?: queuedSections.removeFirstOrNull()
                } ?: break
            try {
                if (workerGeneration != section.generation) {
                    resetOnWorker()
                    workerGeneration = section.generation
                }
                val tableId =
                    section.bytes
                        .firstOrNull()
                        ?.toInt()
                        ?.and(0xff) ?: -1
                Trace.beginSection("BML input section pid=${section.pid} table=$tableId bytes=${section.length}")
                try {
                    consumeExtractedSection(section.pid, section.bytes, section.length)
                } finally {
                    Trace.endSection()
                }
            } finally {
                synchronized(inputLock) { recycleInputBuffer(section.bytes) }
            }
            sectionsProcessed++
            sectionBytesProcessed += section.length
        }

        advanceModuleWork()

        var latestPcrBase: Long? = null
        var latestPcrExtension = 0
        synchronized(inputLock) {
            while (queuedPcrSize > 0) {
                latestPcrBase = queuedPcrBase[queuedPcrRead]
                latestPcrExtension = queuedPcrExtension[queuedPcrRead]
                queuedPcrRead = (queuedPcrRead + 1) % maxQueuedPcr
                queuedPcrSize--
            }
        }
        latestPcrBase?.let {
            emit(JSONObject().put("type", "pcr").put("pcrBase", it).put("pcrExtension", latestPcrExtension))
        }
    }

    private fun consumeExtractedSection(
        pid: Int,
        section: ByteArray,
        length: Int,
    ) {
        when (section.firstOrNull()?.toInt()?.and(0xff)) {
            0x3b -> parseDii(pid, section, hasCrc = false, sectionLimit = length)
            0x3c -> parseDdb(pid, section, hasCrc = false, sectionLimit = length)
        }
    }

    /** Protect entry-point carousels and module 0 from high-bandwidth auxiliary carousel bursts. */
    private fun isPrioritySection(
        pid: Int,
        section: ByteArray,
        length: Int,
    ): Boolean {
        if (length < 1) return false
        val tableId = section[0].toInt() and 0xff
        if (tableId == 0x3b || pid in entryPointPids) return true
        if (tableId != 0x3c || length < 23) return false
        val messageStart = 8
        if (u16(section, messageStart + 2) != 0x1003) return false
        val moduleStart = messageStart + 12 + (section[messageStart + 9].toInt() and 0xff)
        return moduleStart + 2 <= length && u16(section, moduleStart) == 0
    }

    private fun sameComponent(
        a: Component,
        b: Component,
    ): Boolean =
        a.pid == b.pid &&
            a.id == b.id &&
            a.streamType == b.streamType &&
            a.dataComponentId == b.dataComponentId &&
            (a.additionalInfo?.contentEquals(b.additionalInfo) ?: (b.additionalInfo == null))

    private fun emitProgramMap() {
        val items = JSONArray()
        for (component in components.values) {
            val item =
                JSONObject()
                    .put("pid", component.pid)
                    .put("componentId", component.id)
                    .put("streamType", component.streamType)
            component.dataComponentId?.let { item.put("dataComponentId", it) }
            component.additionalInfo?.let { item.put("bxmlInfo", parseBxmlInfo(it)) }
            items.put(item)
        }
        emit(JSONObject().put("type", "pmt").put("components", items))
        emitProgramInfo()
    }

    private fun emitProgramInfo() {
        emit(
            JSONObject()
                .put("type", "programInfo")
                .put("originalNetworkId", originalNetworkId)
                .put("transportStreamId", transportStreamId)
                .put("serviceId", selectedServiceId)
                .put("eventId", JSONObject.NULL)
                .put("eventName", JSONObject.NULL)
                .put("startTimeUnixMillis", JSONObject.NULL)
                .put("durationSeconds", JSONObject.NULL)
                .put("indefiniteDuration", JSONObject.NULL)
                .put("networkId", networkId),
        )
    }

  /*
   * The former full-TS parsing path is intentionally disabled. DSM-CC sections now arrive from
   * Media3's SectionReader and PCR is filtered directly from the Loader buffer in TsStreamRelay.
  private fun consumeInput(source: ByteArray, length: Int) {
    ensureInputCapacity(inputSize + length)
    source.copyInto(input, inputSize, 0, length)
    inputSize += length
    var position = findSync(input, inputSize)
    if (position < 0) {
      val retained = minOf(inputSize, packetSize * 2)
      input.copyInto(input, 0, inputSize - retained, inputSize)
      inputSize = retained
      return
    }
    while (position + packetSize <= inputSize) {
      if ((input[position].toInt() and 0xff) != syncByte) {
        position++
        continue
      }
      consumePacket(input, position)
      position += packetSize
    }
    input.copyInto(input, 0, position, inputSize)
    inputSize -= position
  }

  private fun ensureInputCapacity(required: Int) {
    if (required > input.size) input = input.copyOf(maxOf(required, input.size * 2))
  }

  private fun findSync(data: ByteArray, size: Int): Int {
    for (i in 0 until minOf(packetSize, size)) {
      if ((data[i].toInt() and 0xff) != syncByte) continue
      if (i + packetSize >= size || (data[i + packetSize].toInt() and 0xff) == syncByte) return i
    }
    return -1
  }

  private fun consumePacket(packet: ByteArray, start: Int) {
    if (packet[start + 1].toInt() and 0x80 != 0) return
    val unitStart = packet[start + 1].toInt() and 0x40 != 0
    val pid = ((packet[start + 1].toInt() and 0x1f) shl 8) or (packet[start + 2].toInt() and 0xff)
    val adaptationControl = (packet[start + 3].toInt() ushr 4) and 3
    val continuityCounter = packet[start + 3].toInt() and 0x0f
    var payload = start + 4
    if (adaptationControl == 2 || adaptationControl == 3) {
      val adaptationLength = packet[payload].toInt() and 0xff
      if (pid == pcrPid && adaptationLength >= 7 && packet[payload + 1].toInt() and 0x10 != 0) {
        val p = payload + 2
        val base =
          ((packet[p].toLong() and 0xff) shl 25) or
            ((packet[p + 1].toLong() and 0xff) shl 17) or
            ((packet[p + 2].toLong() and 0xff) shl 9) or
            ((packet[p + 3].toLong() and 0xff) shl 1) or
            ((packet[p + 4].toLong() ushr 7) and 1)
        val extension = ((packet[p + 4].toInt() and 1) shl 8) or (packet[p + 5].toInt() and 0xff)
        emit(JSONObject().put("type", "pcr").put("pcrBase", base).put("pcrExtension", extension))
      }
      payload += adaptationLength + 1
    }
    if ((adaptationControl != 1 && adaptationControl != 3) || payload >= start + packetSize) return
    val interesting = pid == 0 || pid == pmtPid || components.containsKey(pid)
    if (!interesting) return
    val assembler = assemblers.getOrPut(pid) { SectionAssembler() }
    val previousCounter = continuityCounters.put(pid, continuityCounter)
    if (previousCounter == continuityCounter) return
    if (previousCounter != null && continuityCounter != ((previousCounter + 1) and 0x0f)) {
      assembler.reset()
    }
    for (section in assembler.push(packet, payload, start + packetSize, unitStart)) consumeSection(pid, section)
  }

  private fun consumeSection(pid: Int, section: ByteArray) {
    if (!hasValidCrc(section)) return
    when (section[0].toInt() and 0xff) {
      0x00 -> parsePat(section)
      0x02 -> parsePmt(section)
      0x3b -> parseDii(pid, section, hasCrc = true)
      0x3c -> parseDdb(pid, section, hasCrc = true)
    }
  }

  private fun parsePat(section: ByteArray) {
    if (section.size < 12) return
    var p = 8
    while (p + 4 <= section.size - 4) {
      val service = u16(section, p)
      val pid = ((section[p + 2].toInt() and 0x1f) shl 8) or (section[p + 3].toInt() and 0xff)
      if (service != 0 && selectedServiceId == null) {
        selectedServiceId = service
        pmtPid = pid
        Log.i(logTag, "PAT selected service=$service pmtPid=0x${pid.toString(16)}")
      }
      p += 4
    }
  }

  private fun parsePmt(section: ByteArray) {
    if (section.size < 16 || u16(section, 3) != selectedServiceId) return
    val version = (section[5].toInt() ushr 1) and 0x1f
    if (section[5].toInt() and 1 == 0 || version == pmtVersion) return
    pmtVersion = version
    pcrPid = ((section[8].toInt() and 0x1f) shl 8) or (section[9].toInt() and 0xff)
    var p = 12 + (((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff))
    val found = mutableMapOf<Int, Component>()
    while (p + 5 <= section.size - 4) {
      val streamType = section[p].toInt() and 0xff
      val elementaryPid = ((section[p + 1].toInt() and 0x1f) shl 8) or (section[p + 2].toInt() and 0xff)
      val infoLength = ((section[p + 3].toInt() and 0x0f) shl 8) or (section[p + 4].toInt() and 0xff)
      val end = minOf(p + 5 + infoLength, section.size - 4)
      var d = p + 5
      var componentId: Int? = null
      var dataComponentId: Int? = null
      var additionalInfo: ByteArray? = null
      while (d + 2 <= end) {
        val tag = section[d].toInt() and 0xff
        val length = section[d + 1].toInt() and 0xff
        if (d + 2 + length > end) break
        if (tag == 0x52 && length >= 1) componentId = section[d + 2].toInt() and 0xff
        if (tag == 0xfd && length >= 2) {
          dataComponentId = u16(section, d + 2)
          additionalInfo = section.copyOfRange(d + 4, d + 2 + length)
        }
        d += 2 + length
      }
      if (componentId != null) {
        found[elementaryPid] = Component(elementaryPid, componentId, streamType, dataComponentId, additionalInfo)
      }
      p = end
    }
    if (found == components) return
    components.clear()
    components.putAll(found)
    Log.i(
      logTag,
      "PMT pcrPid=0x${pcrPid?.toString(16)} components=" +
        found.values.joinToString {
          "0x${it.pid.toString(16)}:0x${it.id.toString(16)}/0x${it.streamType.toString(16)}"
        },
    )
    val items = JSONArray()
    for (component in found.values) {
      val item =
        JSONObject()
          .put("pid", component.pid)
          .put("componentId", component.id)
          .put("streamType", component.streamType)
      component.dataComponentId?.let { item.put("dataComponentId", it) }
      component.additionalInfo?.let { item.put("bxmlInfo", parseBxmlInfo(it)) }
      items.put(item)
    }
    emit(JSONObject().put("type", "pmt").put("components", items))
    emit(
      JSONObject()
        .put("type", "programInfo")
        .put("originalNetworkId", JSONObject.NULL)
        .put("transportStreamId", JSONObject.NULL)
        .put("serviceId", selectedServiceId)
        .put("eventId", JSONObject.NULL)
        .put("eventName", JSONObject.NULL)
        .put("startTimeUnixMillis", JSONObject.NULL)
        .put("durationSeconds", JSONObject.NULL)
        .put("indefiniteDuration", JSONObject.NULL)
        .put("networkId", JSONObject.NULL)
    )
  }

   */

    // Keep the ordered protocol parsing steps together for comparison with the wire format.
    @Suppress("LongMethod")
    private fun parseDii(
        pid: Int,
        section: ByteArray,
        hasCrc: Boolean,
        sectionLimit: Int = section.size,
    ) {
        val component = components[pid] ?: return
        if (section.size < 32) return
        val sectionEnd = sectionLimit - if (hasCrc) 4 else 0
        var p = 8
        if (p + 12 > sectionEnd) return
        if (u16(section, p + 2) != 0x1002) return
        val transactionId = u32(section, p + 4)
        if (transactions[component.id] == transactionId) return
        val adaptationLength = section[p + 9].toInt() and 0xff
        p += 12 + adaptationLength
        if (p + 16 > sectionEnd) return
        val downloadId = u32(section, p)
        val dataEventId = ((downloadId ushr 28) and 0xf).toInt()
        val blockSize = u16(section, p + 4)
        if (blockSize <= 0) return
        p += 16
        if (p + 2 > sectionEnd) return
        val compatibilityLength = u16(section, p)
        val compatibilityEnd = p + 2 + compatibilityLength
        if (compatibilityEnd > sectionEnd) return
        p = compatibilityEnd
        if (p + 2 > sectionEnd) return
        val count = u16(section, p)
        p += 2
        val list = JSONArray()
        val stagedModules = ArrayList<Pair<Int, Module>>(count)
        var stagedBytes = 0L
        var parsedCount = 0
        while (parsedCount < count) {
            if (p + 8 > sectionEnd) return
            val id = u16(section, p)
            val sizeLong = u32(section, p + 2)
            if (sizeLong > Int.MAX_VALUE) return
            val size = sizeLong.toInt()
            val version = section[p + 6].toInt() and 0xff
            val infoLength = section[p + 7].toInt() and 0xff
            val infoEnd = p + 8 + infoLength
            if (infoEnd > sectionEnd) return
            var d = p + 8
            var contentType: String? = null
            var compressed = false
            while (d + 2 <= infoEnd) {
                val tag = section[d].toInt() and 0xff
                val length = section[d + 1].toInt() and 0xff
                if (d + 2 + length > infoEnd) break
                if (tag == 0x01) contentType = section.copyOfRange(d + 2, d + 2 + length).toString(Charsets.UTF_8)
                if (tag == 0xc2 && length >= 5) compressed = (section[d + 2].toInt() and 0xff) == 0
                d += 2 + length
            }
            val blockCount = maxOf(1L, (sizeLong + blockSize - 1L) / blockSize)
            if (
                sizeLong > resourceLimits.maxModuleBytes ||
                stagedBytes + sizeLong > resourceLimits.maxCarouselBytes ||
                blockCount > maxModuleBlocks
            ) {
                Log.w(logTag, "Ignoring oversized module component=${component.id} id=$id bytes=$sizeLong")
                p = infoEnd
                parsedCount++
                continue
            }
            stagedBytes += sizeLong
            val blocks = arrayOfNulls<ByteArray>(blockCount.toInt())
            val previous = modules[component.id to id]
            val module =
                previous?.takeIf {
                    it.version == version &&
                        it.size == size &&
                        it.blockSize == blockSize &&
                        it.dataEventId == dataEventId &&
                        it.contentType == contentType &&
                        it.compressed == compressed
                } ?: Module(id, version, size, blockSize, dataEventId, contentType, compressed, blocks)
            if (module !== previous) completedModules.removeIf { it.pid == component.pid && it.moduleId == id }
            stagedModules.add(id to module)
            list.put(JSONObject().put("id", id).put("version", version).put("size", size))
            p = infoEnd
            parsedCount++
        }
        val retainedModules = stagedModules.map { it.second }
        val obsoleteWorks =
            moduleWorks.filter { work ->
                work.componentId == component.id && retainedModules.none { it === work.module }
            }
        obsoleteWorks.forEach { work ->
            work.inflater?.end()
            waitingMessages.remove(work.message)
            moduleWorks.remove(work)
        }
        modules.keys.removeAll { key ->
            key.first == component.id && stagedModules.none { staged -> staged.first == key.second }
        }
        stagedModules.forEach { (id, module) -> modules[component.id to id] = module }
        transactions[component.id] = transactionId
        emit(
            JSONObject()
                .put("type", "moduleListUpdated")
                .put("componentId", component.id)
                .put("modules", list)
                .put("dataEventId", dataEventId),
        )
        Log.i(logTag, "DII component=0x${component.id.toString(16)} transaction=$transactionId modules=$count")
    }

    private fun parseDdb(
        pid: Int,
        section: ByteArray,
        hasCrc: Boolean,
        sectionLimit: Int = section.size,
    ) {
        val component = components[pid] ?: return
        if (section.size < 26) return
        val sectionEnd = sectionLimit - if (hasCrc) 4 else 0
        var p = 8
        if (u16(section, p + 2) != 0x1003) return
        val downloadId = u32(section, p + 4)
        val dataEventId = ((downloadId ushr 28) and 0xf).toInt()
        val adaptationLength = section[p + 9].toInt() and 0xff
        p += 12 + adaptationLength
        if (p + 6 > sectionEnd) return
        val moduleId = u16(section, p)
        val version = section[p + 2].toInt() and 0xff
        val blockNumber = u16(section, p + 4)
        p += 6
        val module = modules[component.id to moduleId] ?: return
        if (module.version != version || module.dataEventId != dataEventId ||
            blockNumber !in module.blocks.indices
        ) {
            return
        }
        // A completed/queued module is immutable for this generation. Reject carousel repeats before
        // copying their payload; otherwise every cycle creates garbage even though no work is needed.
        if (module.queuedForProcessing) return
        if (module.blocks[blockNumber] != null) return
        module.blocks[blockNumber] = section.copyOfRange(p, sectionEnd)
        if (module.queuedForProcessing || module.blocks.any { it == null }) return
        module.queuedForProcessing = true
        completedModules.add(CompletedModuleKey(pid, downloadId, moduleId, version))
        val placeholder = PendingMessage(null)
        enqueueMessage(placeholder)
        moduleWorks.addLast(ModuleWork(component.id, module, placeholder))
    }

    private fun advanceModuleWork() {
        val work = moduleWorks.firstOrNull() ?: return
        val deadline = System.nanoTime() + moduleWorkBudgetNanos
        try {
            while (work.phase != ModulePhase.DONE && System.nanoTime() < deadline) advanceModulePhase(work)
            if (work.phase == ModulePhase.DONE) {
                moduleWorks.removeFirst()
                Log.i(
                    logTag,
                    "module component=0x${work.componentId.toString(16)} id=0x${work.module.id.toString(16)} " +
                        "version=${work.module.version} bytes=${work.data?.size ?: 0} files=${work.files.size} " +
                        "type=${work.module.contentType}",
                )
            }
        } catch (error: Exception) {
            work.inflater?.end()
            moduleWorks.removeFirst()
            waitingMessages.remove(work.message)
            Log.w(
                logTag,
                "Discarding corrupt module component=0x${work.componentId.toString(16)} " +
                    "id=0x${work.module.id.toString(16)}; waiting for the next carousel",
                error,
            )
            transactions.remove(work.componentId)
            modules.keys.removeAll { it.first == work.componentId }
        }
    }

    private fun advanceModulePhase(work: ModuleWork) {
        Trace.beginSection("BML module ${work.phase.name}")
        try {
            when (work.phase) {
                ModulePhase.ASSEMBLE -> advanceAssembly(work)
                ModulePhase.INFLATE -> advanceInflate(work)
                ModulePhase.MIME_INIT -> initializeMime(work)
                ModulePhase.MIME_SCAN -> advanceMime(work)
                ModulePhase.JSON_INIT -> initializeModuleJson(work)
                ModulePhase.JSON_FILE -> initializeFileJson(work)
                ModulePhase.JSON_BASE64 -> advanceBase64(work)
                ModulePhase.DONE -> Unit
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun advanceAssembly(work: ModuleWork) {
        var remaining = moduleChunkBytes
        while (remaining > 0 && work.blockIndex < work.module.blocks.size && work.outputOffset < work.assembled.size) {
            val block = requireNotNull(work.module.blocks[work.blockIndex])
            val count = minOf(remaining, block.size - work.blockOffset, work.assembled.size - work.outputOffset)
            block.copyInto(work.assembled, work.outputOffset, work.blockOffset, work.blockOffset + count)
            remaining -= count
            work.blockOffset += count
            work.outputOffset += count
            if (work.blockOffset == block.size) {
                // The assembled buffer now owns these bytes. Release the DDB block immediately so peak
                // memory does not include every block plus the complete module for the whole pipeline.
                work.module.blocks[work.blockIndex] = null
                work.blockIndex++
                work.blockOffset = 0
            }
        }
        if (work.outputOffset == work.assembled.size) {
            if (work.module.compressed) {
                work.inflater = Inflater().apply { setInput(work.assembled) }
                work.inflateOutput = ByteArrayOutputStream()
                work.phase = ModulePhase.INFLATE
            } else {
                work.data = work.assembled
                work.phase = ModulePhase.MIME_INIT
            }
        }
    }

    private fun advanceInflate(work: ModuleWork) {
        val inflater = requireNotNull(work.inflater)
        val output = requireNotNull(work.inflateOutput)
        val buffer = ByteArray(inflateChunkBytes)
        var produced = 0
        while (!inflater.finished() && produced < moduleChunkBytes) {
            val count = inflater.inflate(buffer, 0, minOf(buffer.size, moduleChunkBytes - produced))
            if (count == 0) {
                check(!inflater.needsDictionary() && !inflater.needsInput()) { "Incomplete compressed module" }
                break
            }
            output.write(buffer, 0, count)
            produced += count
        }
        if (inflater.finished()) {
            inflater.end()
            work.inflater = null
            work.data = output.toByteArray()
            work.inflateOutput = null
            work.phase = ModulePhase.MIME_INIT
        }
    }

    private fun initializeMime(work: ModuleWork) {
        val data = requireNotNull(work.data)
        if (work.effectiveType == null || work.effectiveType!!.startsWith("multipart/", true)) {
            val outerHeaderEnd = indexOf(data, crlfCrlf, 0)
            if (outerHeaderEnd >= 0) {
                val headers = data.copyOfRange(0, outerHeaderEnd).toString(Charsets.ISO_8859_1)
                work.effectiveType = header(headers, "Content-Type") ?: work.effectiveType
                work.bodyStart = outerHeaderEnd + 4
            }
        }
        val match = boundaryRegex.find(work.effectiveType.orEmpty())
        if (!work.effectiveType.orEmpty().startsWith("multipart/", true) || match == null) {
            work.files.add(FilePart(null, work.effectiveType ?: "application/octet-stream", work.bodyStart, data.size))
            work.phase = ModulePhase.JSON_INIT
            return
        }
        work.boundary = "--${match.groupValues[1]}".toByteArray(Charsets.ISO_8859_1)
        work.scanCursor = work.bodyStart
        work.mimePhase = MimePhase.FIND_FIRST_BOUNDARY
        work.phase = ModulePhase.MIME_SCAN
    }

    private fun advanceMime(work: ModuleWork) {
        val data = requireNotNull(work.data)
        val marker = requireNotNull(work.boundary)
        when (work.mimePhase) {
            MimePhase.FIND_FIRST_BOUNDARY -> {
                val found = boundedIndexOf(data, marker, work.scanCursor)
                if (found >= 0) {
                    work.mimePosition = found
                    work.mimePhase = MimePhase.READ_BOUNDARY
                } else if (!advanceScanCursor(work, data.size, marker.size)) {
                    work.phase = ModulePhase.JSON_INIT
                }
            }

            MimePhase.READ_BOUNDARY -> {
                var position = work.mimePosition + marker.size
                if (
                    position + 2 <= data.size &&
                    data[position] == '-'.code.toByte() &&
                    data[position + 1] == '-'.code.toByte()
                ) {
                    work.phase = ModulePhase.JSON_INIT
                    return
                }
                if (position + 2 <= data.size && data[position] == '\r'.code.toByte()) position += 2
                work.headerStart = position
                work.scanCursor = position
                work.mimePhase = MimePhase.FIND_HEADER_END
            }

            MimePhase.FIND_HEADER_END -> {
                val found = boundedIndexOf(data, crlfCrlf, work.scanCursor)
                if (found >= 0) {
                    work.headerEnd = found
                    work.scanCursor = found + crlfCrlf.size
                    work.mimePhase = MimePhase.FIND_NEXT_BOUNDARY
                } else if (!advanceScanCursor(work, data.size, crlfCrlf.size)) {
                    work.phase = ModulePhase.JSON_INIT
                }
            }

            MimePhase.FIND_NEXT_BOUNDARY -> {
                val found = boundedIndexOf(data, marker, work.scanCursor)
                if (found >= 0) {
                    val headers = data.copyOfRange(work.headerStart, work.headerEnd).toString(Charsets.ISO_8859_1)
                    val bodyEnd = if (found >= 2 && data[found - 2] == 13.toByte()) found - 2 else found
                    work.files.add(
                        FilePart(
                            header(headers, "Content-Location"),
                            header(headers, "Content-Type") ?: "application/octet-stream",
                            work.headerEnd + crlfCrlf.size,
                            bodyEnd,
                        ),
                    )
                    work.mimePosition = found
                    work.mimePhase = MimePhase.READ_BOUNDARY
                } else if (!advanceScanCursor(work, data.size, marker.size)) {
                    work.phase = ModulePhase.JSON_INIT
                }
            }
        }
    }

    private fun boundedIndexOf(
        data: ByteArray,
        needle: ByteArray,
        start: Int,
    ): Int {
        if (needle.isEmpty() || start > data.size - needle.size) return -1
        val last = minOf(data.size - needle.size, start + mimeScanChunkBytes - 1)
        outer@ for (i in start..last) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun advanceScanCursor(
        work: ModuleWork,
        dataSize: Int,
        needleSize: Int,
    ): Boolean {
        if (work.scanCursor > dataSize - needleSize) return false
        work.scanCursor = minOf(dataSize, work.scanCursor + mimeScanChunkBytes)
        return work.scanCursor <= dataSize - needleSize
    }

    private fun initializeModuleJson(work: ModuleWork) {
        work.json =
            StringBuilder(initialBatchCapacity)
                .append("{\"type\":\"moduleDownloaded\",\"componentId\":")
                .append(work.componentId)
                .append(",\"moduleId\":")
                .append(work.module.id)
                .append(",\"files\":[")
        work.phase = ModulePhase.JSON_FILE
    }

    private fun initializeFileJson(work: ModuleWork) {
        val builder = requireNotNull(work.json)
        if (work.fileIndex >= work.files.size) {
            builder
                .append("],\"version\":")
                .append(work.module.version)
                .append(",\"dataEventId\":")
                .append(work.module.dataEventId)
                .append('}')
            work.message.json = builder.toString()
            work.phase = ModulePhase.DONE
            return
        }
        if (work.fileIndex > 0) builder.append(',')
        val file = work.files[work.fileIndex]
        val media =
            file.type
                .substringBefore(';')
                .trim()
                .split('/', limit = 2)
        val major = media.getOrElse(0) { "application" }
        val subtype = media.getOrElse(1) { "octet-stream" }
        builder
            .append("{\"contentLocation\":")
            .append(file.location?.let(JSONObject::quote) ?: "null")
            .append(",\"contentType\":{\"type\":")
            .append(JSONObject.quote(major))
            .append(",\"originalType\":")
            .append(JSONObject.quote(major))
            .append(",\"subtype\":")
            .append(JSONObject.quote(subtype))
            .append(",\"originalSubtype\":")
            .append(JSONObject.quote(subtype))
            .append(",\"parameters\":[]},\"dataBase64\":\"")
        work.base64Offset = file.start
        work.phase = ModulePhase.JSON_BASE64
    }

    private fun advanceBase64(work: ModuleWork) {
        val data = requireNotNull(work.data)
        val file = work.files[work.fileIndex]
        val count = minOf(base64ChunkBytes, file.end - work.base64Offset)
        if (count > 0) {
            requireNotNull(work.json).append(Base64.encodeToString(data, work.base64Offset, count, Base64.NO_WRAP))
            work.base64Offset += count
        }
        if (work.base64Offset >= file.end) {
            requireNotNull(work.json).append("\"}")
            work.fileIndex++
            work.phase = ModulePhase.JSON_FILE
        }
    }

    private fun parseBxmlInfo(data: ByteArray): JSONObject {
        if (data.isEmpty()) return JSONObject()
        var p = 0
        val first = data[p].toInt() and 0xff
        val transmissionFormat = first ushr 6
        val entryPoint = first and 0x20 != 0
        val result = JSONObject().put("transmissionFormat", transmissionFormat).put("entryPointFlag", entryPoint)
        if (entryPoint && data.size >= 2) {
            val second = data[1].toInt() and 0xff
            result.put(
                "entryPointInfo",
                JSONObject()
                    .put("autoStartFlag", first and 0x10 != 0)
                    .put("documentResolution", first and 0x0f)
                    .put("useXML", second and 0x80 != 0)
                    .put("defaultVersionFlag", second and 0x40 != 0)
                    .put("independentFlag", second and 0x20 != 0)
                    .put("styleForTVFlag", second and 0x10 != 0)
                    .put("bmlMajorVersion", 1)
                    .put("bmlMinorVersion", 0),
            )
            p = 2
            if (second and 0x40 == 0) {
                p += 4
                if (second and 0x80 != 0) p += 4
            }
        } else {
            p = 1
        }
        if (transmissionFormat == 0 && p + 1 < data.size) {
            result.put(
                "additionalAribCarouselInfo",
                JSONObject()
                    .put("dataEventId", (data[p].toInt() ushr 4) and 0xf)
                    .put("eventSectionFlag", data[p].toInt() and 8 != 0)
                    .put("ondemandRetrievalFlag", data[p + 1].toInt() and 0x80 != 0)
                    .put("fileStorableFlag", data[p + 1].toInt() and 0x40 != 0)
                    .put("startPriority", (data[p + 1].toInt() ushr 5) and 1),
            )
        }
        return result
    }

    private fun emit(message: JSONObject) {
        if (!active.get()) return
        enqueueMessage(PendingMessage(message.toString()))
    }

    private fun enqueueMessage(message: PendingMessage) {
        if (waitingMessages.size >= maxWaitingMessages && waitingMessages.firstOrNull()?.json != null) {
            waitingMessages.removeFirst()
        }
        waitingMessages.addLast(message)
    }

    private fun flushMessagesSafely() {
        try {
            flushMessages()
        } catch (error: Exception) {
            Log.e(logTag, "Failed to deliver a BML message batch", error)
        }
    }

    private fun flushMessages() {
        if (!active.get()) return
        val target = consumer ?: return
        if (waitingMessages.isEmpty()) return
        batchBuilder.setLength(0)
        batchBuilder.append('[')
        var first = true
        while (waitingMessages.isNotEmpty()) {
            val json = waitingMessages.first().json ?: break
            if (!first && batchBuilder.length + json.length > maxJsBatchChars) break
            waitingMessages.removeFirst()
            if (!first) batchBuilder.append(',')
            batchBuilder.append(json)
            first = false
        }
        if (first) return
        batchBuilder.append(']')
        target(batchBuilder.toString())
    }

    private fun header(
        headers: String,
        name: String,
    ): String? =
        headers
            .lineSequence()
            .firstOrNull { it.startsWith("$name:", true) }
            ?.substringAfter(':')
            ?.trim()

    private fun indexOf(
        data: ByteArray,
        needle: ByteArray,
        start: Int,
    ): Int {
        outer@ for (i in start..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u16(
        data: ByteArray,
        p: Int,
    ) = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)

    private fun u32(
        data: ByteArray,
        p: Int,
    ) = ((data[p].toLong() and 0xff) shl 24) or
        ((data[p + 1].toLong() and 0xff) shl 16) or
        ((data[p + 2].toLong() and 0xff) shl 8) or
        (data[p + 3].toLong() and 0xff)

    private companion object {
        const val packetSize = 188
        const val syncByte = 0x47
        const val maxWaitingMessages = 256
        const val maxPooledInputBuffers = 8
        const val maxPooledInputBufferBytes = 8 * 1024
        const val maxQueuedPcr = 256
        const val maxModuleBlocks = 65_536L
        const val maxItemsPerBatch = 32
        const val maxSectionsPerDrain = 4
        const val maxSectionBytesPerDrain = 32 * 1024
        const val sectionWorkBudgetNanos = 1_000_000L
        const val initialInputBufferSize = 32 * 1024
        const val initialBatchCapacity = 8 * 1024
        const val batchIntervalMillis = 32L
        const val moduleContinuationMillis = 4L
        const val moduleWorkBudgetNanos = 1_000_000L
        const val moduleChunkBytes = 32 * 1024
        const val mimeScanChunkBytes = 4 * 1024
        const val base64ChunkBytes = 3 * 1024 // Divisible by 3, so concatenated chunks are canonical Base64.
        const val inflateChunkBytes = 8 * 1024
        const val maxJsBatchChars = 384 * 1024
        const val logTag = "BmlTsDemuxer"
        val crlfCrlf = byteArrayOf(13, 10, 13, 10)
        val boundaryRegex = Regex("boundary=\\\"?([^\\\";\\r\\n]+)", RegexOption.IGNORE_CASE)
    }
}
