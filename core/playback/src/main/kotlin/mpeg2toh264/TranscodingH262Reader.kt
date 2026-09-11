package net.rokoucha.visiomata.playback.mpeg2toh264

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.lang.ref.Cleaner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * JNI boundary for the MPEG-2 to H.264 bridge, separated for testing.
 *
 * The default implementation owns one native session handle and must be used from a single
 * thread. [TranscodingH262Reader] confines all calls to its worker thread.
 */
internal interface H262Transcoder {
    /**
     * Returns the native output allocation without copying. The caller passes it to
     * [releaseOutput] exactly once. Null means the bridge itself failed.
     */
    fun push(
        data: ByteArray,
        ptsUs: Long,
        hasPts: Boolean,
        finish: Boolean,
    ): ByteBuffer?

    fun releaseOutput(output: ByteBuffer)

    fun reset()
}

internal class NativeH262Transcoder : H262Transcoder {
    private class NativeState(
        val handle: Long,
    ) : Runnable {
        override fun run() = Mpeg2ToH264Native.destroy(handle)
    }

    private val nativeState = NativeState(Mpeg2ToH264Native.create())

    @Suppress("unused")
    private val cleanable = cleaner.register(this, nativeState)

    override fun push(
        data: ByteArray,
        ptsUs: Long,
        hasPts: Boolean,
        finish: Boolean,
    ): ByteBuffer? = Mpeg2ToH264Native.pushDirect(nativeState.handle, data, ptsUs, hasPts, finish)

    override fun releaseOutput(output: ByteBuffer) = Mpeg2ToH264Native.freeDirect(output)

    override fun reset() = Mpeg2ToH264Native.reset(nativeState.handle)

    private companion object {
        val cleaner: Cleaner = Cleaner.create()
    }
}

/**
 * Transcodes MPEG-2 video to H.264 off the extractor (loader) thread.
 *
 * [consume] only copies the caller's buffer and enqueues it; the native decode/encode and the
 * [TrackOutput] writes run on a dedicated worker thread. This keeps network intake flowing
 * while a GOP is being transcoded. [TrackOutput] writes are confined to that single worker
 * thread, matching the single-writer threading the Media3 sample queues expect.
 */
@UnstableApi
internal class TranscodingH262Reader(
    private val deinterlaceMetadata: DeinterlaceMetadataQueue,
    transcoderFactory: () -> H262Transcoder = ::NativeH262Transcoder,
) : ElementaryStreamReader {
    private sealed interface WorkItem {
        data class Input(
            val bytes: ByteArray,
            val ptsUs: Long,
            val hasPts: Boolean,
            val finish: Boolean,
        ) : WorkItem

        data class Seek(
            val acknowledged: CountDownLatch,
        ) : WorkItem

        data object Poison : WorkItem
    }

    // Stops the worker without retaining the reader: the worker thread must never hold a
    // strong reference to the reader, or the Cleaner below would never fire.
    private class PoisonOnClean(
        private val queue: LinkedBlockingQueue<WorkItem>,
    ) : Runnable {
        override fun run() {
            queue.offer(WorkItem.Poison)
        }
    }

    private val queue = LinkedBlockingQueue<WorkItem>(maxQueuedInputs)
    private val workerError = AtomicReference<Exception?>()
    private val workerState = WorkerState(queue, deinterlaceMetadata, workerError, transcoderFactory)
    private val worker = Thread(workerState, workerName).apply { isDaemon = true }
    private val workerStarted = AtomicBoolean(false)

    @Suppress("unused")
    private val shutdownCleanable = shutdownCleaner.register(this, PoisonOnClean(queue))

    // Loader-thread only PES reassembly state, captured into each queued item.
    private var pendingPesTimeUs = C.TIME_UNSET
    private var timestampPending = false

    override fun seek() {
        throwIfWorkerError()
        if (workerStarted.get()) {
            // Process every queued input through the pre-seek native state first so the reset
            // below cannot reorder post-seek bytes ahead of pre-seek ones.
            val acknowledged = CountDownLatch(1)
            queue.put(WorkItem.Seek(acknowledged))
            acknowledged.await()
            throwIfWorkerError()
        }
        deinterlaceMetadata.clear()
        pendingPesTimeUs = C.TIME_UNSET
        timestampPending = false
    }

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        workerState.formatId = idGenerator.formatId
        workerState.output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_VIDEO)
        if (workerStarted.compareAndSet(false, true)) worker.start()
    }

    override fun packetStarted(
        pesTimeUs: Long,
        flags: Int,
    ) {
        pendingPesTimeUs = pesTimeUs
        timestampPending = pesTimeUs != C.TIME_UNSET
    }

    override fun consume(data: ParsableByteArray) {
        throwIfWorkerError()
        val bytes = ByteArray(data.bytesLeft())
        data.readBytes(bytes, 0, bytes.size)
        consumeBytes(bytes)
    }

    /**
     * Test seam for [consume] without Media3's buffer type. Media3's [ParsableByteArray] read
     * paths probe `android.os.Build` and cannot run in plain JVM unit tests.
     */
    internal fun consumeBytes(bytes: ByteArray) {
        throwIfWorkerError()
        val item =
            WorkItem.Input(
                bytes = bytes,
                ptsUs = pendingPesTimeUs,
                hasPts = timestampPending,
                finish = false,
            )
        timestampPending = false
        // Blocks only when the transcoder falls behind the network; unavoidable backpressure
        // that previously stalled inside the native call on every single input.
        queue.put(item)
    }

    override fun packetFinished() = Unit

    override fun endOfInputReached() {
        throwIfWorkerError()
        queue.put(WorkItem.Input(EMPTY, 0, false, true))
    }

    private fun throwIfWorkerError() {
        workerError.get()?.let { error ->
            if (error is ParserException) throw error
            throw ParserException.createForMalformedContainer(error.message, error)
        }
    }

    private class WorkerState(
        private val queue: LinkedBlockingQueue<WorkItem>,
        private val deinterlaceMetadata: DeinterlaceMetadataQueue,
        private val workerError: AtomicReference<Exception?>,
        private val transcoderFactory: () -> H262Transcoder,
    ) : Runnable {
        // Written on the loader thread before the worker starts; read by the worker afterwards.
        @Volatile lateinit var output: TrackOutput

        @Volatile lateinit var formatId: String

        private var lastFormat: VideoFormat? = null

        // Latest SPS/PPS seen in the Annex-B payloads, and the pair baked into the last
        // emitted Format. The transcoder emits parameter sets once at the stream head, so
        // they must be retained here: without csd-0/csd-1 the hardware AVC decoder rejects
        // the very first queueInputBuffer with C2_CORRUPTED.
        private var currentSps: ByteArray? = null
        private var currentPps: ByteArray? = null
        private var emittedSps: ByteArray? = null
        private var emittedPps: ByteArray? = null

        override fun run() {
            val transcoder = transcoderFactory()
            try {
                while (true) {
                    when (val item = queue.take()) {
                        is WorkItem.Poison -> {
                            return
                        }

                        is WorkItem.Seek -> {
                            try {
                                transcoder.reset()
                                lastFormat = null
                                currentSps = null
                                currentPps = null
                                emittedSps = null
                                emittedPps = null
                            } catch (error: Exception) {
                                workerError.compareAndSet(null, error)
                            } finally {
                                item.acknowledged.countDown()
                            }
                        }

                        is WorkItem.Input -> {
                            var encoded: ByteBuffer? = null
                            try {
                                encoded =
                                    transcoder.push(item.bytes, item.ptsUs, item.hasPts, item.finish)
                                        ?: error("Empty MPEG-2 transcoder output")
                                emit(encoded)
                            } catch (error: Exception) {
                                workerError.compareAndSet(null, error)
                            } finally {
                                try {
                                    encoded?.let(transcoder::releaseOutput)
                                } catch (error: Exception) {
                                    workerError.compareAndSet(null, error)
                                }
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun emit(encoded: ByteBuffer) {
            try {
                val input = encoded.order(ByteOrder.LITTLE_ENDIAN)
                require(input.remaining() >= HEADER_SIZE && input.int == OUTPUT_VERSION) {
                    "Unsupported MPEG-2 transcoder output"
                }
                when (input.int) {
                    STATUS_OK -> {
                        emitAccessUnits(input)
                    }

                    STATUS_ERROR -> {
                        val length = input.readSize("error length")
                        require(length <= input.remaining()) { "Truncated MPEG-2 transcoder error" }
                        val message = ByteArray(length).also(input::get).toString(Charsets.UTF_8)
                        throw IllegalStateException(message)
                    }

                    else -> {
                        error("Unknown MPEG-2 transcoder status")
                    }
                }
            } catch (error: Exception) {
                if (error is ParserException) throw error
                throw ParserException.createForMalformedContainer(error.message, error)
            }
        }

        private fun emitAccessUnits(input: ByteBuffer) {
            val count = input.readSize("access-unit count")
            repeat(count) {
                require(input.remaining() >= ACCESS_UNIT_HEADER_SIZE) {
                    "Truncated H.264 access unit"
                }
                val ptsUs = input.long
                input.long
                val flags = input.int
                deinterlaceMetadata.record(
                    ptsUs,
                    DeinterlaceFrameInfo(
                        interlaced = (flags and NATIVE_FLAG_INTERLACED) != 0,
                        topFieldFirst = (flags and NATIVE_FLAG_TOP_FIELD_FIRST) != 0,
                    ),
                )
                val format =
                    VideoFormat(
                        width = input.int,
                        height = input.int,
                        pixelAspectWidth = input.int,
                        pixelAspectHeight = input.int,
                    )
                val size = input.readSize("access-unit size")
                require(size <= input.remaining()) { "Truncated H.264 access-unit payload" }
                val (sps, pps) = extractParameterSets(input, input.position(), size)
                if (sps != null) currentSps = sps
                if (pps != null) currentPps = pps
                updateFormat(format)
                // Feed the sample straight from the native buffer into the sample queue's own
                // allocation; the only remaining copy is the unavoidable final store. The
                // DataReader overload writes a single allocation chunk per call, so loop until
                // the full sample is stored, mirroring the ParsableByteArray overload.
                val sampleReader = ByteBufferSampleReader(input, size)
                var bytesStored = 0
                while (bytesStored < size) {
                    val written = output.sampleData(sampleReader, size - bytesStored, false)
                    require(written > 0) { "Sample write made no progress" }
                    bytesStored += written
                }
                output.sampleMetadata(
                    ptsUs,
                    if ((flags and NATIVE_FLAG_KEY_FRAME) != 0) {
                        C.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    },
                    size,
                    0,
                    null,
                )
            }
        }

        private fun updateFormat(format: VideoFormat) {
            val sps = currentSps
            val pps = currentPps
            if (format == lastFormat && sps contentEquals emittedSps && pps contentEquals emittedPps) return
            require(format.width > 0 && format.height > 0) { "Invalid transcoded video dimensions" }
            val pixelRatio =
                if (format.pixelAspectWidth > 0 && format.pixelAspectHeight > 0) {
                    format.pixelAspectWidth.toFloat() / format.pixelAspectHeight
                } else {
                    1f
                }
            val builder =
                Format
                    .Builder()
                    .setId(formatId)
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                    .setWidth(format.width)
                    .setHeight(format.height)
                    .setPixelWidthHeightRatio(pixelRatio)
            if (sps != null && pps != null) {
                builder.setInitializationData(listOf(sps, pps))
                avcCodecString(sps)?.let(builder::setCodecs)
            }
            output.format(builder.build())
            lastFormat = format
            emittedSps = sps
            emittedPps = pps
        }

        private fun ByteBuffer.readSize(label: String): Int {
            require(remaining() >= Int.SIZE_BYTES) { "Missing $label" }
            val value = int
            require(value >= 0) { "Invalid $label" }
            return value
        }
    }

    private data class VideoFormat(
        val width: Int,
        val height: Int,
        val pixelAspectWidth: Int,
        val pixelAspectHeight: Int,
    )

    /**
     * Streams one access-unit payload out of the shared native buffer. The reader is
     * single-use and confined to the worker thread that owns both the buffer and the
     * [TrackOutput] write.
     */
    private class ByteBufferSampleReader(
        private val source: ByteBuffer,
        private var remaining: Int,
    ) : DataReader {
        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            val count = min(length, min(remaining, source.remaining()))
            if (count <= 0) return C.RESULT_END_OF_INPUT
            source.get(target, offset, count)
            remaining -= count
            return count
        }
    }

    internal companion object {
        val shutdownCleaner: Cleaner = Cleaner.create()
        val EMPTY = ByteArray(0)
        const val workerName = "TranscodeH262"

        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        /**
         * Extracts the latest SPS/PPS NAL units from one Annex-B access-unit payload without
         * copying the payload itself. Returned units carry a 3-byte start-code prefix, matching
         * Media3's `H264Reader` csd entries. Either side of the pair is null when absent.
         */
        internal fun extractParameterSets(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
        ): Pair<ByteArray?, ByteArray?> {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val end = offset + size
            var nalStart = -1
            var nalHeader = -1
            var position = offset
            while (position + 3 <= end) {
                val startCodeLength =
                    when {
                        buffer.get(position) == 0.toByte() &&
                            buffer.get(position + 1) == 0.toByte() &&
                            buffer.get(position + 2) == 1.toByte() -> {
                            3
                        }

                        position + 4 <= end &&
                            buffer.get(position) == 0.toByte() &&
                            buffer.get(position + 1) == 0.toByte() &&
                            buffer.get(position + 2) == 0.toByte() &&
                            buffer.get(position + 3) == 1.toByte() -> {
                            4
                        }

                        else -> {
                            position++
                            continue
                        }
                    }
                if (nalStart >= 0) {
                    when (buffer.get(nalHeader).toInt() and 0x1F) {
                        NAL_TYPE_SPS -> sps = startPrefixedNal(buffer, nalHeader, position)
                        NAL_TYPE_PPS -> pps = startPrefixedNal(buffer, nalHeader, position)
                    }
                }
                nalStart = position
                nalHeader = position + startCodeLength
                // Emulation prevention guarantees no accidental start code inside a NAL unit,
                // so resuming the scan at the header cannot split the unit being opened.
                position = nalHeader
            }
            if (nalStart >= 0 && nalHeader < end) {
                when (buffer.get(nalHeader).toInt() and 0x1F) {
                    NAL_TYPE_SPS -> sps = startPrefixedNal(buffer, nalHeader, end)
                    NAL_TYPE_PPS -> pps = startPrefixedNal(buffer, nalHeader, end)
                }
            }
            return sps to pps
        }

        private fun startPrefixedNal(
            buffer: ByteBuffer,
            header: Int,
            end: Int,
        ): ByteArray {
            val unit = ByteArray(3 + end - header)
            unit[0] = 0
            unit[1] = 0
            unit[2] = 1
            var index = 3
            for (position in header until end) {
                unit[index++] = buffer.get(position)
            }
            return unit
        }

        /**
         * Builds the `avc1.PPCCLL` codec string from a start-code-prefixed SPS NAL, mirroring
         * `CodecSpecificDataUtil.buildAvcCodecString`. Null when the unit is too short to hold
         * the profile/constraint/level bytes.
         */
        internal fun avcCodecString(sps: ByteArray): String? {
            if (sps.size < 7 || sps[0] != 0.toByte() || sps[1] != 0.toByte() || sps[2] != 1.toByte()) return null
            if (sps[3].toInt() and 0x1F != NAL_TYPE_SPS) return null
            return buildString {
                append("avc1.")
                for (index in 4..6) {
                    append(((sps[index].toInt() and 0xFF) ushr 4).toString(16).uppercase())
                    append(((sps[index].toInt() and 0xFF) and 0x0F).toString(16).uppercase())
                }
            }
        }

        /**
         * Bounds queued PES payloads so a transcoder that falls behind cannot accumulate
         * unbounded memory; the loader then blocks, which is the same backpressure as before.
         */
        const val maxQueuedInputs = 32
        const val OUTPUT_VERSION = 2
        const val STATUS_OK = 0
        const val STATUS_ERROR = 1
        const val NATIVE_FLAG_KEY_FRAME = 1
        const val NATIVE_FLAG_INTERLACED = 1 shl 1
        const val NATIVE_FLAG_TOP_FIELD_FIRST = 1 shl 2
        const val HEADER_SIZE = 12
        const val ACCESS_UNIT_HEADER_SIZE = 16 + 6 * 4
    }
}
