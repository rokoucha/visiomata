package net.rokoucha.visiomata.playback.mpeg2toh264

import androidx.media3.common.C
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

@UnstableApi
internal class TranscodingH262Reader(
    private val deinterlaceMetadata: DeinterlaceMetadataQueue,
) : ElementaryStreamReader {
    private class NativeState(
        val handle: Long,
    ) : Runnable {
        override fun run() = Mpeg2ToH264Native.destroy(handle)
    }

    private val nativeState = NativeState(Mpeg2ToH264Native.create())

    @Suppress("unused")
    private val cleanable = cleaner.register(this, nativeState)
    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private var pendingPesTimeUs = C.TIME_UNSET
    private var timestampPending = false
    private var lastFormat: VideoFormat? = null

    override fun seek() {
        Mpeg2ToH264Native.reset(nativeState.handle)
        deinterlaceMetadata.clear()
        pendingPesTimeUs = C.TIME_UNSET
        timestampPending = false
    }

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_VIDEO)
    }

    override fun packetStarted(
        pesTimeUs: Long,
        flags: Int,
    ) {
        pendingPesTimeUs = pesTimeUs
        timestampPending = pesTimeUs != C.TIME_UNSET
    }

    override fun consume(data: ParsableByteArray) {
        val bytes = ByteArray(data.bytesLeft())
        data.readBytes(bytes, 0, bytes.size)
        emit(
            Mpeg2ToH264Native.push(
                nativeState.handle,
                bytes,
                pendingPesTimeUs,
                timestampPending,
                false,
            ),
        )
        timestampPending = false
    }

    override fun packetFinished() = Unit

    override fun endOfInputReached() {
        emit(Mpeg2ToH264Native.push(nativeState.handle, EMPTY, 0, false, true))
    }

    private fun emit(encoded: ByteArray) {
        try {
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            require(input.remaining() >= HEADER_SIZE && input.int == OUTPUT_VERSION) {
                "Unsupported MPEG-2 transcoder output"
            }
            when (input.int) {
                STATUS_OK -> {
                    val count = input.readSize("access-unit count")
                    repeat(count) {
                        require(input.remaining() >= ACCESS_UNIT_HEADER_SIZE) { "Truncated H.264 access unit" }
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
                        val sample = ByteArray(size)
                        input.get(sample)
                        output.sampleData(ParsableByteArray(sample), size)
                        output.sampleMetadata(
                            ptsUs,
                            if ((flags and NATIVE_FLAG_KEY_FRAME) != 0) C.BUFFER_FLAG_KEY_FRAME else 0,
                            size,
                            0,
                            null,
                        )
                    }
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

    private data class VideoFormat(
        val width: Int,
        val height: Int,
        val pixelAspectWidth: Int,
        val pixelAspectHeight: Int,
    )

    private companion object {
        val cleaner: Cleaner = Cleaner.create()
        val EMPTY = ByteArray(0)
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
