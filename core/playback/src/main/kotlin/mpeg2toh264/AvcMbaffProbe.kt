@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback.mpeg2toh264

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** What [AvcMbaffProbe] concluded about the hardware decoder. */
internal enum class MbaffProbeResult {
    HardwareOk,
    HardwareFailed,

    /** No decoder, the deadline hit, or the probe threw. Must not be cached. */
    Inconclusive,
}

/**
 * Whether the device's hardware H.264 decoder can decode the MBAFF inter pictures mpeg2toh264
 * emits for interlaced broadcasts. Some decoders return no frame at all and report no error,
 * which leaves playback buffering forever, so probe once with a small clip instead of keeping a
 * device blocklist.
 *
 * The verdict is cached under [Build.FINGERPRINT], so an OS update measures again instead of
 * pinning the device forever.
 */
object AvcMbaffProbe {
    private const val TAG = "VisiomataPlayer"
    private const val ASSET_PATH = "probe/mbaff.mp4"
    private const val PREFERENCES_NAME = "playback.decoder_probe"
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 5_000L

    @Volatile
    private var memoized: Boolean? = null

    /**
     * Returns false when the probe could not decide, because falling back to the software
     * decoder is the safe direction. Blocks, so call it off the main thread.
     */
    fun hardwareDecodesMbaff(context: Context): Boolean {
        memoized?.let { return it }
        synchronized(this) {
            memoized?.let { return it }

            val preferences =
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (preferences.contains(Build.FINGERPRINT)) {
                val cached = preferences.getBoolean(Build.FINGERPRINT, false)
                Log.i(TAG, "MBAFF probe: cached hardwareOk=$cached")
                memoized = cached
                return cached
            }

            val decoderName = hardwareDecoderName()
            if (decoderName == null) {
                Log.i(TAG, "MBAFF probe: no hardware AVC decoder, leaving the choice to MediaCodec")
                return true
            }

            val startedAt = System.nanoTime()
            val result = runProbe(context, decoderName)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            Log.i(TAG, "MBAFF probe: $result on $decoderName in ${elapsedMs}ms")
            if (result == MbaffProbeResult.Inconclusive) {
                return false
            }

            val hardwareOk = result == MbaffProbeResult.HardwareOk
            preferences.edit().putBoolean(Build.FINGERPRINT, hardwareOk).apply()
            memoized = hardwareOk
            return hardwareOk
        }
    }

    private fun hardwareDecoderName(): String? =
        runCatching {
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                .firstOrNull { it.hardwareAccelerated }
                ?.name
        }.getOrNull()

    private fun runProbe(
        context: Context,
        decoderName: String,
    ): MbaffProbeResult {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            context.assets.openFd(ASSET_PATH).use { asset ->
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            val format = extractor.videoTrackFormat() ?: return MbaffProbeResult.Inconclusive

            codec = MediaCodec.createByCodecName(decoderName)
            codec.configure(format, null, null, 0)
            codec.start()
            decode(codec, extractor)
        } catch (error: Exception) {
            Log.w(TAG, "MBAFF probe failed to run", error)
            MbaffProbeResult.Inconclusive
        } finally {
            // A leaked probe instance would starve playback of the few hardware decoders a device has.
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun decode(
        codec: MediaCodec,
        extractor: MediaExtractor,
    ): MbaffProbeResult {
        val info = MediaCodec.BufferInfo()
        var inputSamples = 0
        var outputFrames = 0
        var inputDone = false
        var reachedEos = false
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000

        while (!reachedEos && System.nanoTime() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        inputSamples++
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                if (info.size > 0) outputFrames++
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) reachedEos = true
            }
        }
        return decide(inputSamples = inputSamples, outputFrames = outputFrames, reachedEos = reachedEos)
    }

    /**
     * Fewer output frames than input samples counts as a failure. Some decoders return the
     * leading IDR and drop everything after it, so "any frame at all" would miss them.
     */
    internal fun decide(
        inputSamples: Int,
        outputFrames: Int,
        reachedEos: Boolean,
    ): MbaffProbeResult =
        when {
            !reachedEos || inputSamples == 0 -> MbaffProbeResult.Inconclusive
            outputFrames >= inputSamples -> MbaffProbeResult.HardwareOk
            else -> MbaffProbeResult.HardwareFailed
        }
}

private fun MediaExtractor.videoTrackFormat(): MediaFormat? {
    for (index in 0 until trackCount) {
        val format = getTrackFormat(index)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
            selectTrack(index)
            return format
        }
    }
    return null
}
