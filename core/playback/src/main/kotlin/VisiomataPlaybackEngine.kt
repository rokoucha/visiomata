@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import net.rokoucha.visiomata.playback.bml.BmlMessageSource
import net.rokoucha.visiomata.playback.bml.BmlResourceLimits
import net.rokoucha.visiomata.playback.bml.BmlTsDemuxer
import net.rokoucha.visiomata.playback.bml.MahironBmlMessageSource
import net.rokoucha.visiomata.playback.media3.AribTsPayloadReaderFactory
import net.rokoucha.visiomata.playback.media3.RelayingDataSourceFactory
import net.rokoucha.visiomata.playback.media3.TsStreamRelay
import net.rokoucha.visiomata.playback.media3.VisiomataSubtitleParserFactory
import net.rokoucha.visiomata.playback.mpeg2toh264.DeinterlaceMetadataQueue
import net.rokoucha.visiomata.playback.mpeg2toh264.LinearDeinterlaceEffect
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

internal data class VisiomataPlaybackEngineConfig(
    val url: String,
    val basicAuthUsername: String,
    val basicAuthPassword: String,
    val bearerToken: String,
    val forceMpeg2Transcoding: Boolean?,
    val forceHardwareMpeg2Decoder: Boolean?,
    val transcodeMpeg2Video: Boolean,
    val deinterlaceEnabled: Boolean,
    val dataBroadcastingEnabled: Boolean,
    val mahironApiRoot: String?,
    val serviceId: Long?,
    val mediaTitle: String?,
    val mediaSubtitle: String?,
    val mediaArtworkData: ByteArray?,
    val aribFontFiles: List<String>,
    val memoryPolicy: PlaybackMemoryPolicy,
)

/** Owns playback and transport resources without retaining an Activity or any View. */
internal class VisiomataPlaybackEngine(
    context: Context,
    private val config: VisiomataPlaybackEngineConfig,
    initialAudioComponents: List<BroadcastAudioComponent> = emptyList(),
) {
    private val applicationContext = context.applicationContext
    private val retryHandler = Handler(Looper.getMainLooper())
    private val released = AtomicBoolean(false)
    private var streaming = false
    private var retryAttempt = 0
    private var retryScheduled = false
    private var mediaSession: MediaSession? = null
    internal val audioComponentState = AudioComponentState(initialAudioComponents)

    val bmlMessageSource: BmlMessageSource? = createBmlMessageSource(config)
    private val bmlTsDemuxer = bmlMessageSource as? BmlTsDemuxer
    private val tsStreamRelay = TsStreamRelay(bmlTsDemuxer, ::onAudioPidsAdded)
    private val deinterlaceMetadata =
        if (config.transcodeMpeg2Video) DeinterlaceMetadataQueue() else null
    private val deinterlaceEffect =
        deinterlaceMetadata
            ?.takeIf { config.deinterlaceEnabled }
            ?.let(::LinearDeinterlaceEffect)

    val player: ExoPlayer = createPlayer()
    private val sessionPlayer = LiveBroadcastPlayer(player)

    private val retryPlayback =
        Runnable {
            retryScheduled = false
            if (streaming && !released.get()) reconnectInternal()
        }

    private val recoveryListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = scheduleRetry(error)

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        retryHandler.removeCallbacks(retryPlayback)
                        retryScheduled = false
                        retryAttempt = 0
                    }

                    Player.STATE_ENDED -> {
                        scheduleRetry()
                    }
                }
            }
        }

    init {
        player.addListener(recoveryListener)
    }

    /** Starts a fresh live connection when stopped; returns whether a reconnect was initiated. */
    fun start(onBeforeReconnect: () -> Unit = {}): Boolean {
        if (released.get() || streaming) return false
        streaming = true
        createMediaSession()
        val reconnect =
            player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED ||
                player.playerError != null
        return if (reconnect) {
            onBeforeReconnect()
            // Reset while acquisition is still inactive so Mahiron does not briefly open a stale
            // connection immediately before the fresh live connection.
            bmlMessageSource?.reset()
            bmlMessageSource?.start()
            reconnectInternal(resetSource = false)
            true
        } else {
            bmlMessageSource?.start()
            player.play()
            false
        }
    }

    /** Stops network loading and playback while retaining the engine for a later live reconnect. */
    fun stopStreaming() {
        if (!streaming || released.get()) return
        streaming = false
        retryHandler.removeCallbacks(retryPlayback)
        retryScheduled = false
        bmlMessageSource?.stop()
        player.stop()
        deinterlaceMetadata?.clear()
        releaseMediaSession()
    }

    /** Uses the same reset path for user reloads and automatic/lifecycle recovery. */
    fun reconnect(onBeforeReconnect: () -> Unit = {}): Boolean {
        if (!streaming || released.get()) return false
        retryHandler.removeCallbacks(retryPlayback)
        retryScheduled = false
        retryAttempt = 0
        onBeforeReconnect()
        reconnectInternal()
        return true
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        streaming = false
        retryHandler.removeCallbacks(retryPlayback)
        retryScheduled = false
        releaseMediaSession()
        player.removeListener(recoveryListener)
        player.release()
        bmlMessageSource?.release()
        deinterlaceMetadata?.clear()
        audioComponentState.release()
    }

    fun updateAudioComponents(components: List<BroadcastAudioComponent>): Boolean =
        !released.get() && audioComponentState.updateComponents(components)

    fun addAudioStateListener(listener: () -> Unit) = audioComponentState.addListener(listener)

    fun removeAudioStateListener(listener: () -> Unit) = audioComponentState.removeListener(listener)

    fun isReleased(): Boolean = released.get()

    private fun reconnectInternal(resetSource: Boolean = true) {
        if (!streaming || released.get()) return
        tsStreamRelay.invalidate()
        if (resetSource) bmlMessageSource?.reset()
        bmlMessageSource?.start()
        deinterlaceMetadata?.clear()
        // A fresh source also creates a fresh extractor and discovers the current PMT tracks.
        player.setMediaItem(liveMediaItem(config.url, config.mediaTitle, config.mediaSubtitle, config.mediaArtworkData))
        player.prepare()
        player.playWhenReady = true
    }

    private fun onAudioPidsAdded(generation: Long) {
        retryHandler.post {
            if (!streaming || released.get() || tsStreamRelay.generation != generation) return@post
            Log.i("VisiomataPlayer", "AAC PID added to PMT; rebuilding the live media source")
            retryHandler.removeCallbacks(retryPlayback)
            retryScheduled = false
            val playWhenReady = player.playWhenReady
            reconnectInternal()
            player.playWhenReady = playWhenReady
        }
    }

    private fun scheduleRetry(error: PlaybackException? = null) {
        if (!streaming || released.get() || retryScheduled) return
        val delayMs = min(1_000L shl retryAttempt.coerceAtMost(4), 10_000L)
        retryAttempt++
        retryScheduled = true
        bmlMessageSource?.stop()
        Log.w(
            "VisiomataPlayer",
            "Playback interrupted; retrying in ${delayMs}ms (attempt $retryAttempt)",
            error,
        )
        retryHandler.postDelayed(retryPlayback, delayMs)
    }

    private fun createMediaSession() {
        if (mediaSession != null) return
        mediaSession =
            MediaSession
                .Builder(applicationContext, sessionPlayer)
                .setCallback(LiveBroadcastSessionCallback)
                .build()
    }

    private fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    private fun createPlayer(): ExoPlayer {
        val subtitleParserFactory = VisiomataSubtitleParserFactory(config.aribFontFiles)
        val httpDataSourceFactory = createHttpDataSourceFactory()
        val dataSourceFactory = RelayingDataSourceFactory(httpDataSourceFactory, tsStreamRelay)
        logConfiguration(config)
        val builder =
            ExoPlayer
                .Builder(applicationContext, createRenderersFactory())
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        dataSourceFactory,
                        createExtractorsFactory(subtitleParserFactory),
                        subtitleParserFactory,
                    ),
                )
        if (config.memoryPolicy.isLowRamDevice) {
            builder.setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(
                        config.memoryPolicy.minBufferMs,
                        config.memoryPolicy.maxBufferMs,
                        1_000,
                        2_000,
                    ).setTargetBufferBytes(config.memoryPolicy.targetBufferBytes)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
        }
        return builder.build().apply {
            addAnalyticsListener(
                object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        Log.w("VisiomataPlayer", "Dropped $droppedFrames video frames in ${elapsedMs}ms")
                    }
                },
            )
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            if (deinterlaceEffect != null) setVideoEffects(listOf(deinterlaceEffect))
            trackSelectionParameters =
                trackSelectionParameters
                    .buildUpon()
                    .setPreferredVideoMimeTypes(MimeTypes.VIDEO_MPEG2)
                    .setPreferredTextLanguage("jpn")
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
            setMediaItem(liveMediaItem(config.url, config.mediaTitle, config.mediaSubtitle, config.mediaArtworkData))
        }
    }

    private fun createHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory().apply {
            authorizationHeader(config)?.let { authorization ->
                setDefaultRequestProperties(mapOf("Authorization" to authorization))
            }
        }

    private fun createExtractorsFactory(subtitleParserFactory: VisiomataSubtitleParserFactory): ExtractorsFactory =
        ExtractorsFactory {
            val streamGeneration = audioComponentState.beginStream()
            arrayOf<Extractor>(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    0,
                    subtitleParserFactory,
                    TimestampAdjuster(0),
                    AribTsPayloadReaderFactory(
                        transcodeMpeg2Video = config.transcodeMpeg2Video,
                        deinterlaceMetadata = deinterlaceMetadata,
                        bmlDemuxer = bmlTsDemuxer,
                        audioComponentState = audioComponentState,
                        streamGeneration = streamGeneration,
                    ),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
                ),
            )
        }

    private fun createRenderersFactory(): DefaultRenderersFactory =
        DefaultRenderersFactory(applicationContext).apply {
            if (!config.transcodeMpeg2Video && config.forceHardwareMpeg2Decoder != null) {
                setMediaCodecSelector(
                    MediaCodecSelector { mimeType, secure, tunneling ->
                        val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                        if (mimeType == MimeTypes.VIDEO_MPEG2) {
                            decoderInfos.filter { codec ->
                                if (config.forceHardwareMpeg2Decoder) {
                                    codec.hardwareAccelerated
                                } else {
                                    codec.softwareOnly
                                }
                            }
                        } else {
                            decoderInfos
                        }
                    },
                )
            }
        }
}

private fun createBmlMessageSource(config: VisiomataPlaybackEngineConfig): BmlMessageSource? =
    when {
        !config.dataBroadcastingEnabled -> {
            null
        }

        config.mahironApiRoot != null && config.serviceId != null -> {
            MahironBmlMessageSource(
                apiRoot = config.mahironApiRoot,
                serviceId = config.serviceId,
                basicAuthUsername = config.basicAuthUsername,
                basicAuthPassword = config.basicAuthPassword,
                bearerToken = config.bearerToken,
                maxModuleBytes = config.memoryPolicy.bmlMaxModuleBytes,
            )
        }

        else -> {
            BmlTsDemuxer(
                BmlResourceLimits(
                    maxModuleBytes = config.memoryPolicy.bmlMaxModuleBytes,
                    maxCarouselBytes = config.memoryPolicy.bmlMaxCarouselBytes,
                    queueCapacity = config.memoryPolicy.bmlQueueCapacity,
                ),
            )
        }
    }

private fun authorizationHeader(config: VisiomataPlaybackEngineConfig): String? =
    when {
        config.bearerToken.isNotEmpty() -> {
            "Bearer ${config.bearerToken}"
        }

        config.basicAuthUsername.isNotEmpty() -> {
            val credentials =
                Base64.encodeToString(
                    "${config.basicAuthUsername}:${config.basicAuthPassword}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
            "Basic $credentials"
        }

        else -> {
            null
        }
    }

private fun logConfiguration(config: VisiomataPlaybackEngineConfig) {
    Log.i(
        "VisiomataPlayer",
        when {
            config.forceMpeg2Transcoding == true -> {
                "Video path: forced MPEG-2 to H.264 transcode"
            }

            config.forceMpeg2Transcoding == false && config.forceHardwareMpeg2Decoder == true -> {
                "Video path: forced hardware MPEG-2 decoder"
            }

            config.forceMpeg2Transcoding == false && config.forceHardwareMpeg2Decoder == false -> {
                "Video path: forced software MPEG-2 decoder"
            }

            config.forceMpeg2Transcoding == false -> {
                "Video path: forced direct MPEG-2 playback"
            }

            config.transcodeMpeg2Video -> {
                "Video path: MPEG-2 to H.264 fallback"
            }

            else -> {
                "Video path: hardware MPEG-2 decoder"
            }
        },
    )
    Log.i(
        "VisiomataPlayer",
        "Memory policy: lowRam=${config.memoryPolicy.isLowRamDevice}, " +
            "buffer=${config.memoryPolicy.minBufferMs}..${config.memoryPolicy.maxBufferMs}ms, " +
            "bmlCarousel=${config.memoryPolicy.bmlMaxCarouselBytes / (1024 * 1024)}MiB",
    )
}
