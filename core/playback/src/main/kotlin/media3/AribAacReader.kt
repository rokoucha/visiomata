package net.rokoucha.visiomata.playback.media3

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.lang.ref.Cleaner
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AribAacNative {
    init {
        System.loadLibrary("visiomata_aribcaption")
    }

    external fun create(): Long

    external fun destroy(handle: Long)

    external fun reset(handle: Long)

    external fun push(
        handle: Long,
        data: ByteArray,
        finish: Boolean,
    ): ByteArray
}

@UnstableApi
internal class AribAacReader(
    private val mainLanguage: String?,
    private val subLanguage: String?,
    private val roleFlags: Int,
    private val exposeSubTrack: Boolean,
    private val isMainComponent: Boolean?,
) : ElementaryStreamReader {
    private class NativeState(
        val handle: Long,
    ) : Runnable {
        override fun run() = AribAacNative.destroy(handle)
    }

    private val nativeState = NativeState(AribAacNative.create())

    @Suppress("unused")
    private val cleanable = cleaner.register(this, nativeState)
    private lateinit var mainOutput: TrackOutput
    private var subOutput: TrackOutput? = null
    private lateinit var mainFormatId: String
    private var subFormatId: String? = null
    private var nextTimeUs = C.TIME_UNSET
    private var lastConfig: AacConfig? = null

    override fun seek() {
        AribAacNative.reset(nativeState.handle)
        nextTimeUs = C.TIME_UNSET
        lastConfig = null
    }

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        mainFormatId = idGenerator.formatId
        mainOutput = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
        if (exposeSubTrack) {
            idGenerator.generateNewId()
            subFormatId = idGenerator.formatId
            subOutput = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
        }
    }

    override fun packetStarted(
        pesTimeUs: Long,
        flags: Int,
    ) {
        if (pesTimeUs != C.TIME_UNSET) nextTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val bytes = ByteArray(data.bytesLeft())
        data.readBytes(bytes, 0, bytes.size)
        try {
            emit(AribAacNative.push(nativeState.handle, bytes, false))
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Discarding malformed AAC while resynchronizing", error)
            AribAacNative.reset(nativeState.handle)
            nextTimeUs = C.TIME_UNSET
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Discarding unsupported AAC while resynchronizing", error)
            AribAacNative.reset(nativeState.handle)
            nextTimeUs = C.TIME_UNSET
        }
    }

    override fun packetFinished() = Unit

    override fun endOfInputReached() {
        emit(AribAacNative.push(nativeState.handle, EMPTY, true))
    }

    private fun emit(encoded: ByteArray) {
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= HEADER_SIZE && input.int == OUTPUT_VERSION) {
            "Unsupported ARIB AAC normalizer output"
        }
        when (input.int) {
            STATUS_OK -> {
                repeat(input.readSize("frame count")) {
                    require(input.remaining() >= FRAME_HEADER_SIZE) { "Truncated normalized AAC frame" }
                    val sampleRate = input.int
                    val channelCount = input.int
                    val configBytes = input.readBytes("AudioSpecificConfig")
                    val main = input.readBytes("main AAC access unit")
                    val sub = input.readBytes("sub AAC access unit")
                    val config = AacConfig(sampleRate, channelCount, configBytes)
                    updateFormats(config)
                    if (nextTimeUs != C.TIME_UNSET) {
                        writeSample(mainOutput, main, nextTimeUs)
                        if (sub.isNotEmpty()) subOutput?.let { writeSample(it, sub, nextTimeUs) }
                        nextTimeUs += C.MICROS_PER_SECOND * 1024L / sampleRate
                    }
                }
            }

            STATUS_ERROR -> {
                error(input.readBytes("error").toString(Charsets.UTF_8))
            }

            else -> {
                error("Unknown ARIB AAC normalizer status")
            }
        }
        require(!input.hasRemaining()) { "Trailing ARIB AAC normalizer output" }
    }

    private fun updateFormats(config: AacConfig) {
        if (lastConfig == config) return
        val mainLabel =
            when {
                exposeSubTrack -> "第一音声"
                isMainComponent == true -> "主音声"
                isMainComponent == false -> "副音声"
                else -> "音声"
            }
        mainOutput.format(audioFormat(mainFormatId, mainLanguage, mainLabel, config, true))
        subOutput?.format(audioFormat(checkNotNull(subFormatId), subLanguage, "第二音声", config, false))
        lastConfig = config
    }

    private fun audioFormat(
        id: String,
        language: String?,
        label: String,
        config: AacConfig,
        isDefault: Boolean,
    ) = Format
        .Builder()
        .setId(id)
        .setContainerMimeType(MimeTypes.VIDEO_MP2T)
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setChannelCount(config.channelCount)
        .setSampleRate(config.sampleRate)
        .setInitializationData(listOf(config.initializationData))
        .setLanguage(language)
        .setLabel(label)
        .setRoleFlags(roleFlags)
        .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
        .build()

    private fun writeSample(
        output: TrackOutput,
        bytes: ByteArray,
        timeUs: Long,
    ) {
        output.sampleData(ParsableByteArray(bytes), bytes.size)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    private fun ByteBuffer.readSize(name: String): Int {
        require(remaining() >= Int.SIZE_BYTES) { "Missing $name" }
        return int.also { require(it >= 0) { "Invalid $name" } }
    }

    private fun ByteBuffer.readBytes(name: String): ByteArray {
        val size = readSize("$name size")
        require(size <= remaining()) { "Truncated $name" }
        return ByteArray(size).also(::get)
    }

    private data class AacConfig(
        val sampleRate: Int,
        val channelCount: Int,
        val initializationData: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is AacConfig &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                initializationData.contentEquals(other.initializationData)

        override fun hashCode(): Int = 31 * (31 * sampleRate + channelCount) + initializationData.contentHashCode()
    }

    private companion object {
        const val TAG = "AribAacReader"
        val cleaner: Cleaner = Cleaner.create()
        val EMPTY = ByteArray(0)
        const val OUTPUT_VERSION = 1
        const val STATUS_OK = 0
        const val STATUS_ERROR = 1
        const val HEADER_SIZE = 2 * Int.SIZE_BYTES
        const val FRAME_HEADER_SIZE = 5 * Int.SIZE_BYTES
    }
}
