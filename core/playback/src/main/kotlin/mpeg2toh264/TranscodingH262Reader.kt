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
            if (format == lastFormat) return
            require(format.width > 0 && format.height > 0) { "Invalid transcoded video dimensions" }
            val pixelRatio =
                if (format.pixelAspectWidth > 0 && format.pixelAspectHeight > 0) {
                    format.pixelAspectWidth.toFloat() / format.pixelAspectHeight
                } else {
                    1f
                }
            output.format(
                Format
                    .Builder()
                    .setId(formatId)
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                    .setWidth(format.width)
                    .setHeight(format.height)
                    .setPixelWidthHeightRatio(pixelRatio)
                    .build(),
            )
            lastFormat = format
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

    private companion object {
        val shutdownCleaner: Cleaner = Cleaner.create()
        val EMPTY = ByteArray(0)
        const val workerName = "TranscodeH262"

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
