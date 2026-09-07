@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.Flow
import net.rokoucha.visiomata.playback.R
import net.rokoucha.visiomata.playback.bml.BmlResourceLimits
import net.rokoucha.visiomata.playback.bml.BmlTsDemuxer
import net.rokoucha.visiomata.playback.bml.BmlWebView
import net.rokoucha.visiomata.playback.bml.MahironBmlMessageSource
import net.rokoucha.visiomata.playback.bml.createBmlWebView
import net.rokoucha.visiomata.playback.media3.AribTsPayloadReaderFactory
import net.rokoucha.visiomata.playback.media3.RelayingDataSourceFactory
import net.rokoucha.visiomata.playback.media3.TsStreamRelay
import net.rokoucha.visiomata.playback.media3.VisiomataSubtitleParserFactory
import net.rokoucha.visiomata.playback.mpeg2toh264.DeinterlaceMetadataQueue
import net.rokoucha.visiomata.playback.mpeg2toh264.LinearDeinterlaceEffect
import net.rokoucha.visiomata.playback.mpeg2toh264.Mpeg2DecoderCapabilities
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

data class BmlRemoteKeyEvent(
    val key: String,
    val isDown: Boolean,
)

data class AudioTrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val supported: Boolean,
)

data class BroadcastAudioComponent(
    val componentTag: Int,
    val isMain: Boolean,
    val isDualMono: Boolean,
    val languages: List<String> = emptyList(),
)

private val aribFontAssetPaths =
    listOf(
        "fonts/KosugiMaru-Regular.ttf",
        "fonts/rounded-mplus-1m-wadalab-comp-arib.ttf",
    )

private data class VideoRectPx(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

private fun updateSubtitleViewport(
    playerView: PlayerView,
    videoSize: VideoSize,
) {
    val subtitleView = playerView.subtitleView ?: return
    val viewWidth = playerView.width
    val viewHeight = playerView.height
    if (viewWidth <= 0 || viewHeight <= 0 || videoSize.width <= 0 || videoSize.height <= 0) {
        subtitleView.setPadding(0, 0, 0, 0)
        return
    }

    val videoAspectRatio =
        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
    val viewAspectRatio = viewWidth.toFloat() / viewHeight
    val horizontalInset: Int
    val verticalInset: Int
    if (viewAspectRatio > videoAspectRatio) {
        horizontalInset = ((viewWidth - viewHeight * videoAspectRatio) / 2f).roundToInt()
        verticalInset = 0
    } else {
        horizontalInset = 0
        verticalInset = ((viewHeight - viewWidth / videoAspectRatio) / 2f).roundToInt()
    }
    subtitleView.setPadding(horizontalInset, verticalInset, horizontalInset, verticalInset)
}

private fun prepareAribCaptionFont(
    context: Context,
    assetPath: String,
): File {
    val destination = File(context.filesDir, assetPath)
    if (destination.isFile && destination.length() > 0) return destination

    destination.parentFile?.mkdirs()
    val temporary = File(destination.parentFile, "${destination.name}.part")
    context.assets.open(assetPath).use { input ->
        temporary.outputStream().use(input::copyTo)
    }
    check(temporary.renameTo(destination)) { "ARIB字幕フォントを展開できませんでした" }
    return destination
}

private fun systemSymbolFontPaths(): List<String> =
    runCatching {
        SystemFonts
            .getAvailableFonts()
            .asSequence()
            .mapNotNull { it.file }
            .filter { it.name.contains("symbols", ignoreCase = true) }
            .map(File::getAbsolutePath)
            .distinct()
            .sorted()
            .toList()
    }.getOrElse { error ->
        Log.w("VisiomataPlayer", "端末の記号フォントを列挙できませんでした", error)
        emptyList()
    }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VisiomataPlayer(
    url: String,
    modifier: Modifier = Modifier,
    basicAuthUsername: String = "",
    basicAuthPassword: String = "",
    bearerToken: String = "",
    forceMpeg2Transcoding: Boolean? = null,
    forceHardwareMpeg2Decoder: Boolean? = null,
    deinterlaceEnabled: Boolean = true,
    dataBroadcastingEnabled: Boolean = true,
    dataBroadcastingInternetEnabled: Boolean = false,
    mahironApiRoot: String? = null,
    serviceId: Long? = null,
    postalCode: String = "",
    mediaTitle: String? = null,
    mediaSubtitle: String? = null,
    mediaArtworkData: ByteArray? = null,
    audioComponents: List<BroadcastAudioComponent> = emptyList(),
    preferComposeKeyInput: Boolean = false,
    remoteKeyEvents: Flow<BmlRemoteKeyEvent>? = null,
    selectedAudioTrackId: String? = null,
    onBmlInputStateChanged: (
        available: Boolean,
        contentVisible: Boolean,
        usedKeyGroups: Set<String>,
    ) -> Unit = { _, _, _ -> },
    onPlaybackErrorChanged: (String?) -> Unit = {},
    onAudioTracksChanged: (List<AudioTrackOption>) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnAudioTracksChanged by rememberUpdatedState(onAudioTracksChanged)
    val currentOnBmlInputStateChanged by rememberUpdatedState(onBmlInputStateChanged)
    val currentOnPlaybackErrorChanged by rememberUpdatedState(onPlaybackErrorChanged)
    val memoryPolicy = remember(context) { PlaybackMemoryPolicy.from(context) }
    val aribFontFiles =
        remember {
            aribFontAssetPaths.map { prepareAribCaptionFont(context, it).absolutePath } +
                systemSymbolFontPaths()
        }
    val bmlMessageSource =
        remember(
            url,
            dataBroadcastingEnabled,
            mahironApiRoot,
            serviceId,
            basicAuthUsername,
            basicAuthPassword,
            bearerToken,
            memoryPolicy,
        ) {
            when {
                !dataBroadcastingEnabled -> {
                    null
                }

                mahironApiRoot != null && serviceId != null -> {
                    MahironBmlMessageSource(
                        apiRoot = mahironApiRoot,
                        serviceId = serviceId,
                        basicAuthUsername = basicAuthUsername,
                        basicAuthPassword = basicAuthPassword,
                        bearerToken = bearerToken,
                        maxModuleBytes = memoryPolicy.bmlMaxModuleBytes,
                    )
                }

                else -> {
                    BmlTsDemuxer(
                        BmlResourceLimits(
                            maxModuleBytes = memoryPolicy.bmlMaxModuleBytes,
                            maxCarouselBytes = memoryPolicy.bmlMaxCarouselBytes,
                            queueCapacity = memoryPolicy.bmlQueueCapacity,
                        ),
                    )
                }
            }
        }
    val bmlTsDemuxer = bmlMessageSource as? BmlTsDemuxer
    val tsStreamRelay = remember(bmlTsDemuxer) { bmlTsDemuxer?.let(::TsStreamRelay) }
    var bmlInvisible by remember(bmlMessageSource) { mutableStateOf(true) }
    var bmlDocumentLoaded by remember(bmlMessageSource) { mutableStateOf(false) }
    var bmlUsedKeyGroups by remember(bmlMessageSource) { mutableStateOf(emptySet<String>()) }
    var bmlVideoRect by remember(bmlMessageSource) { mutableStateOf<VideoRectPx?>(null) }
    var bmlWebView by remember(bmlMessageSource) { mutableStateOf<BmlWebView?>(null) }
    val transcodeMpeg2Video = forceMpeg2Transcoding ?: !Mpeg2DecoderCapabilities.hasHardwareDecoder
    val videoPath =
        when {
            transcodeMpeg2Video -> "mpeg2-to-h264"
            forceHardwareMpeg2Decoder == false -> "mpeg2-software"
            else -> "mpeg2-hardware"
        }
    LaunchedEffect(bmlDocumentLoaded, bmlInvisible, bmlUsedKeyGroups) {
        currentOnBmlInputStateChanged(
            bmlDocumentLoaded,
            bmlDocumentLoaded && !bmlInvisible,
            bmlUsedKeyGroups,
        )
        if (bmlDocumentLoaded) {
            PlaybackMemoryMonitor.record(
                context,
                "bml-${if (bmlInvisible) "hidden" else "visible"}",
                videoPath,
                bmlEnabled = true,
            )
        }
    }
    LaunchedEffect(remoteKeyEvents, bmlWebView) {
        val webView = bmlWebView ?: return@LaunchedEffect
        remoteKeyEvents?.collect { event ->
            webView.dispatchRemoteKey(event.key, event.isDown)
        }
    }
    val deinterlaceMetadata =
        remember(transcodeMpeg2Video) {
            if (transcodeMpeg2Video) DeinterlaceMetadataQueue() else null
        }
    val deinterlaceEffect =
        remember(deinterlaceMetadata, deinterlaceEnabled) {
            deinterlaceMetadata
                ?.takeIf { deinterlaceEnabled }
                ?.let(::LinearDeinterlaceEffect)
        }

    val player =
        remember(
            url,
            basicAuthUsername,
            basicAuthPassword,
            bearerToken,
            transcodeMpeg2Video,
            forceHardwareMpeg2Decoder,
            deinterlaceEnabled,
            memoryPolicy,
            audioComponents,
            tsStreamRelay,
        ) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            val authorizationHeader =
                if (bearerToken.isNotEmpty()) {
                    "Bearer $bearerToken"
                } else if (basicAuthUsername.isNotEmpty()) {
                    val credentials =
                        Base64.encodeToString(
                            "$basicAuthUsername:$basicAuthPassword".toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP,
                        )
                    "Basic $credentials"
                } else {
                    null
                }
            if (authorizationHeader != null) {
                httpDataSourceFactory.setDefaultRequestProperties(
                    mapOf("Authorization" to authorizationHeader),
                )
            }

            val subtitleParserFactory =
                VisiomataSubtitleParserFactory(aribFontFiles)
            Log.i(
                "VisiomataPlayer",
                when {
                    forceMpeg2Transcoding == true -> {
                        "Video path: forced MPEG-2 to H.264 transcode"
                    }

                    forceMpeg2Transcoding == false && forceHardwareMpeg2Decoder == true -> {
                        "Video path: forced hardware MPEG-2 decoder"
                    }

                    forceMpeg2Transcoding == false && forceHardwareMpeg2Decoder == false -> {
                        "Video path: forced software MPEG-2 decoder"
                    }

                    forceMpeg2Transcoding == false -> {
                        "Video path: forced direct MPEG-2 playback"
                    }

                    transcodeMpeg2Video -> {
                        "Video path: MPEG-2 to H.264 fallback"
                    }

                    else -> {
                        "Video path: hardware MPEG-2 decoder"
                    }
                },
            )
            Log.i(
                "VisiomataPlayer",
                "Memory policy: lowRam=${memoryPolicy.isLowRamDevice}, " +
                    "buffer=${memoryPolicy.minBufferMs}..${memoryPolicy.maxBufferMs}ms, " +
                    "bmlCarousel=${memoryPolicy.bmlMaxCarouselBytes / (1024 * 1024)}MiB",
            )
            val extractorsFactory =
                ExtractorsFactory {
                    arrayOf<Extractor>(
                        TsExtractor(
                            TsExtractor.MODE_SINGLE_PMT,
                            0,
                            subtitleParserFactory,
                            TimestampAdjuster(0),
                            AribTsPayloadReaderFactory(
                                transcodeMpeg2Video = transcodeMpeg2Video,
                                deinterlaceMetadata = deinterlaceMetadata,
                                bmlDemuxer = bmlTsDemuxer,
                                audioComponents = audioComponents,
                            ),
                            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
                        ),
                    )
                }

            val dataSourceFactory: DataSource.Factory =
                tsStreamRelay?.let {
                    RelayingDataSourceFactory(httpDataSourceFactory, it)
                } ?: httpDataSourceFactory
            val renderersFactory =
                DefaultRenderersFactory(context).apply {
                    if (!transcodeMpeg2Video && forceHardwareMpeg2Decoder != null) {
                        setMediaCodecSelector(
                            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                                val decoderInfos =
                                    MediaCodecSelector.DEFAULT.getDecoderInfos(
                                        mimeType,
                                        requiresSecureDecoder,
                                        requiresTunnelingDecoder,
                                    )
                                if (mimeType == MimeTypes.VIDEO_MPEG2) {
                                    decoderInfos.filter { codec ->
                                        if (forceHardwareMpeg2Decoder) codec.hardwareAccelerated else codec.softwareOnly
                                    }
                                } else {
                                    decoderInfos
                                }
                            },
                        )
                    }
                }

            val playerBuilder =
                ExoPlayer
                    .Builder(context, renderersFactory)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            dataSourceFactory,
                            extractorsFactory,
                            subtitleParserFactory,
                        ),
                    )
            if (memoryPolicy.isLowRamDevice) {
                playerBuilder.setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            memoryPolicy.minBufferMs,
                            memoryPolicy.maxBufferMs,
                            1_000,
                            2_000,
                        ).setTargetBufferBytes(memoryPolicy.targetBufferBytes)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                )
            }
            playerBuilder
                .build()
                .apply {
                    addAnalyticsListener(
                        object : AnalyticsListener {
                            override fun onDroppedVideoFrames(
                                eventTime: AnalyticsListener.EventTime,
                                droppedFrames: Int,
                                elapsedMs: Long,
                            ) {
                                Log.w(
                                    "VisiomataPlayer",
                                    "Dropped $droppedFrames video frames in ${elapsedMs}ms",
                                )
                            }
                        },
                    )
                    setAudioAttributes(AudioAttributes.DEFAULT, true)
                    setHandleAudioBecomingNoisy(true)
                    if (deinterlaceEffect != null) setVideoEffects(listOf(deinterlaceEffect))
                    // A Mirakurun channel stream can contain both the full-seg MPEG-2 video and
                    // the one-seg H.264 video. Prefer full-seg on TVs with an MPEG-2 decoder,
                    // while still allowing ExoPlayer to fall back on devices without one.
                    trackSelectionParameters =
                        trackSelectionParameters
                            .buildUpon()
                            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_MPEG2)
                            .setPreferredTextLanguage("jpn")
                            .setSelectUndeterminedTextLanguage(true)
                            .build()
                    setMediaItem(liveMediaItem(url, mediaTitle, mediaSubtitle, mediaArtworkData))
                    prepare()
                    playWhenReady = true
                }
        }

    val sessionPlayer = remember(player) { LiveBroadcastPlayer(player) }
    DisposableEffect(bmlMessageSource) {
        onDispose {
            bmlMessageSource?.setConsumer(null)
            bmlMessageSource?.release()
            bmlWebView?.release()
            bmlWebView = null
            currentOnBmlInputStateChanged(false, false, emptySet())
        }
    }
    LaunchedEffect(player, selectedAudioTrackId) {
        val trackId = selectedAudioTrackId ?: return@LaunchedEffect
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (audioTrackId(groupIndex, trackIndex) == trackId && group.isTrackSupported(trackIndex)) {
                    player.trackSelectionParameters =
                        player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                            ).build()
                    return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(player, url, mediaTitle, mediaSubtitle, mediaArtworkData) {
        if (player.mediaItemCount == 0) return@LaunchedEffect
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            liveMediaItem(url, mediaTitle, mediaSubtitle, mediaArtworkData),
        )
    }

    DisposableEffect(player, lifecycleOwner) {
        val retryHandler = Handler(Looper.getMainLooper())
        var mediaSession: MediaSession? = null
        var retryAttempt = 0
        var retryScheduled = false
        var readyMemoryRecorded = false
        lateinit var retryPlayback: Runnable

        fun recordReadyMemory() {
            if (readyMemoryRecorded) return
            readyMemoryRecorded = true
            PlaybackMemoryMonitor.record(context, "ready", videoPath, bmlMessageSource != null)
        }

        fun createMediaSession() {
            if (mediaSession != null) return
            mediaSession =
                MediaSession
                    .Builder(context, sessionPlayer)
                    .setCallback(LiveBroadcastSessionCallback)
                    .build()
        }

        fun releaseMediaSession() {
            mediaSession?.release()
            mediaSession = null
        }

        fun scheduleRetry(error: PlaybackException? = null) {
            if (retryScheduled) return

            val delayMs = min(1_000L shl retryAttempt.coerceAtMost(4), 10_000L)
            retryAttempt++
            retryScheduled = true
            currentOnPlaybackErrorChanged(
                error?.localizedMessage?.let { "$it\n再接続します…" }
                    ?: "ストリームが終了しました\n再接続します…",
            )
            Log.w(
                "VisiomataPlayer",
                "Playback interrupted; retrying in ${delayMs}ms (attempt $retryAttempt)",
                error,
            )
            retryHandler.postDelayed(retryPlayback, delayMs)
        }

        retryPlayback =
            Runnable {
                retryScheduled = false
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@Runnable
                }

                // Re-prepare the live media source from its default position. This also tears down
                // failed renderers/codecs, which is necessary when a decoder dies during a format or
                // resolution change.
                // The BML demuxer is reset when the new DataSource opens, but its JavaScript runtime
                // also owns PCR and carousel state. Reload it so the new stream cannot be interpreted
                // against timestamps or modules retained from the interrupted connection.
                bmlMessageSource?.setConsumer(null)
                bmlMessageSource?.reset()
                bmlInvisible = true
                bmlVideoRect = null
                bmlWebView?.reload()
                deinterlaceMetadata?.clear()
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }

        val listener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    currentOnAudioTracksChanged(tracks.audioTrackOptions())
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleRetry(error)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            retryHandler.removeCallbacks(retryPlayback)
                            retryScheduled = false
                            retryAttempt = 0
                            currentOnPlaybackErrorChanged(null)
                            recordReadyMemory()
                        }

                        Player.STATE_ENDED -> {
                            scheduleRetry()
                        }
                    }
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        createMediaSession()
                        if (player.playerError != null || player.playbackState == Player.STATE_ENDED) {
                            retryHandler.removeCallbacks(retryPlayback)
                            retryScheduled = false
                            retryHandler.post(retryPlayback)
                        } else {
                            player.play()
                        }
                    }

                    Lifecycle.Event.ON_STOP -> {
                        retryHandler.removeCallbacks(retryPlayback)
                        retryScheduled = false
                        player.pause()
                        releaseMediaSession()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        player.addListener(listener)
        currentOnAudioTracksChanged(player.currentTracks.audioTrackOptions())
        if (player.playbackState == Player.STATE_READY) recordReadyMemory()
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            createMediaSession()
        }
        onDispose {
            PlaybackMemoryMonitor.record(context, "release-before", videoPath, bmlMessageSource != null)
            retryHandler.removeCallbacks(retryPlayback)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            releaseMediaSession()
            player.release()
            deinterlaceMetadata?.clear()
            currentOnPlaybackErrorChanged(null)
            currentOnAudioTracksChanged(emptyList())
            Handler(Looper.getMainLooper()).postDelayed(
                { PlaybackMemoryMonitor.record(context, "release-after", videoPath, false) },
                1_000L,
            )
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        key(bmlMessageSource) {
            AndroidView(
                factory = { viewContext ->
                    val playerView =
                        (
                            LayoutInflater.from(viewContext).inflate(
                                R.layout.player_view,
                                null,
                            ) as PlayerView
                        ).apply {
                            this.player = player
                            useController = false
                            keepScreenOn = player.isPlaying
                            val subtitleViewportListener =
                                object : Player.Listener {
                                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                                        keepScreenOn = isPlaying
                                    }

                                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                                        updateSubtitleViewport(this@apply, videoSize)
                                    }
                                }
                            tag = subtitleViewportListener
                            player.addListener(subtitleViewportListener)
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                updateSubtitleViewport(this, player.videoSize)
                            }
                        }
                    val videoHost =
                        FrameLayout(viewContext).apply {
                            addView(
                                playerView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    FrameLayout(viewContext).apply {
                        addView(
                            videoHost,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        // Creating Chromium synchronously here makes it part of Compose's first
                        // applyChanges pass. Give the video host one frame first, then attach the BML plane.
                        // The demuxer keeps completed messages queued until setConsumer() is called.
                        postOnAnimation {
                            if (!isAttachedToWindow || bmlWebView != null || bmlMessageSource == null) {
                                return@postOnAnimation
                            }
                            Trace.beginSection("BML deferred WebView init")
                            try {
                                val webView =
                                    createBmlWebView(
                                        context = viewContext,
                                        messageSource = bmlMessageSource,
                                        postalCode = postalCode,
                                        internetAccessEnabled = dataBroadcastingInternetEnabled,
                                        acceptsKeyFocus = !preferComposeKeyInput,
                                        lowMemoryMode = memoryPolicy.isLowRamDevice,
                                        onBmlInvisibleChanged = { bmlInvisible = it },
                                        onBmlUsedKeyGroupsChanged = {
                                            bmlDocumentLoaded = true
                                            bmlUsedKeyGroups = it
                                        },
                                        onBmlVideoRectChanged = { left, top, width, height ->
                                            val next =
                                                with(density) {
                                                    VideoRectPx(
                                                        left = left.dp.roundToPx(),
                                                        top = top.dp.roundToPx(),
                                                        width = width.dp.roundToPx(),
                                                        height = height.dp.roundToPx(),
                                                    )
                                                }
                                            if (bmlVideoRect != next) bmlVideoRect = next
                                        },
                                    )
                                addView(
                                    webView,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                bmlWebView = webView
                            } finally {
                                Trace.endSection()
                            }
                        }
                    }
                },
                update = { host ->
                    val videoHost = host.getChildAt(0) as FrameLayout
                    val playerView = videoHost.getChildAt(videoHost.childCount - 1) as PlayerView
                    val videoRect = bmlVideoRect
                    playerView.player = player
                    val desiredLayoutParams =
                        if (!bmlInvisible && videoRect != null && videoRect.width > 0f && videoRect.height > 0f) {
                            FrameLayout
                                .LayoutParams(
                                    videoRect.width,
                                    videoRect.height,
                                ).apply {
                                    leftMargin = videoRect.left
                                    topMargin = videoRect.top
                                }
                        } else {
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    val currentLayoutParams = videoHost.layoutParams as FrameLayout.LayoutParams
                    if (
                        currentLayoutParams.width != desiredLayoutParams.width ||
                        currentLayoutParams.height != desiredLayoutParams.height ||
                        currentLayoutParams.leftMargin != desiredLayoutParams.leftMargin ||
                        currentLayoutParams.topMargin != desiredLayoutParams.topMargin
                    ) {
                        videoHost.layoutParams = desiredLayoutParams
                    }
                },
                onRelease = { host ->
                    // Break View -> player/surface references before Compose releases the host. WebView
                    // destruction is idempotent because DisposableEffect and AndroidView can be disposed in
                    // either order.
                    val videoHost = host.getChildAt(0) as? FrameLayout
                    if (videoHost != null) {
                        for (index in 0 until videoHost.childCount) {
                            when (val child = videoHost.getChildAt(index)) {
                                is PlayerView -> {
                                    (child.tag as? Player.Listener)?.let(player::removeListener)
                                    child.tag = null
                                    child.player = null
                                }
                            }
                        }
                    }
                    (host.getChildAt(1) as? BmlWebView)?.release()
                    host.removeAllViews()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun audioTrackId(
    groupIndex: Int,
    trackIndex: Int,
): String = "$groupIndex:$trackIndex"

private fun Tracks.audioTrackOptions(): List<AudioTrackOption> {
    val audioGroups = groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
    val trackCount = audioGroups.sumOf { it.value.length }
    val options =
        buildList {
            audioGroups.forEach { (groupIndex, group) ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val fallbackLabel = if (trackCount > 1) "音声${size + 1}" else "音声"
                    val details =
                        buildList {
                            format.language?.takeUnless { it == "und" }?.let(::add)
                            format.channelCount.takeIf { it > 0 }?.let { add("${it}ch") }
                        }
                    val name = format.label?.takeIf(String::isNotBlank) ?: fallbackLabel
                    add(
                        AudioTrackOption(
                            id = audioTrackId(groupIndex, trackIndex),
                            label = if (details.isEmpty()) name else "$name (${details.joinToString(" / ")})",
                            selected = group.isTrackSelected(trackIndex),
                            supported = group.isTrackSupported(trackIndex),
                        ),
                    )
                }
            }
        }
    val labelCounts = options.groupingBy(AudioTrackOption::label).eachCount()
    val labelIndices = mutableMapOf<String, Int>()
    return options.map { option ->
        if (labelCounts[option.label] == 1) return@map option
        val index = labelIndices.getOrDefault(option.label, 0) + 1
        labelIndices[option.label] = index
        option.copy(label = "${option.label} $index")
    }
}

private fun liveMediaItem(
    url: String,
    title: String?,
    subtitle: String?,
    artworkData: ByteArray?,
): MediaItem =
    MediaItem
        .Builder()
        .setUri(url)
        .setMimeType(MimeTypes.VIDEO_MP2T)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title ?: "ライブ放送")
                .setArtist(subtitle)
                .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
                .build(),
        ).build()

private object LiveBroadcastSessionCallback : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult
            .AcceptedResultBuilder(session, controller)
            .setAvailablePlayerCommands(liveBroadcastCommands(session.player.availableCommands))
            .build()
}

/**
 * Player view exposed to MediaSession for a non-timeshifted live broadcast.
 *
 * Filtering only the commands granted in [MediaSession.Callback.onConnect] is insufficient for
 * legacy controllers: MediaSession also derives PlaybackStateCompat actions from the commands
 * advertised by its Player. Keep the underlying ExoPlayer unrestricted for the app's lifecycle
 * and recovery code, while making unsupported pause and seek operations absent at the Player
 * boundary consumed by MediaSession.
 */
private class LiveBroadcastPlayer(
    player: Player,
) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        return state
            .buildUpon()
            .setAvailableCommands(liveBroadcastCommands(state.availableCommands))
            .build()
    }
}

internal fun liveBroadcastCommands(commands: Player.Commands): Player.Commands =
    commands
        .buildUpon()
        .remove(Player.COMMAND_PLAY_PAUSE)
        .remove(Player.COMMAND_SEEK_BACK)
        .remove(Player.COMMAND_SEEK_FORWARD)
        .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_NEXT)
        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()
