@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.playback.R
import net.rokoucha.visiomata.playback.bml.BmlWebView
import net.rokoucha.visiomata.playback.bml.createBmlWebView
import net.rokoucha.visiomata.playback.mpeg2toh264.Mpeg2DecoderCapabilities
import java.io.File
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

private data class PlaybackSetup(
    val fontFiles: List<String>,
    val transcodeMpeg2Video: Boolean,
)

internal data class VideoRectPx(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * BMLが指示する映像面の配置を、現在の表示モードに合わせて有効化する。
 *
 * PiPウィンドウは通常画面より大幅に小さいため、フルスクリーン用の絶対座標をそのまま適用すると
 * 映像が窓の外に押し出されてしまう。PiPではBMLの矩形指示を無視して映像を全画面表示する。
 */
internal fun effectiveVideoRect(
    bmlInvisible: Boolean,
    videoRect: VideoRectPx?,
    isInPictureInPictureMode: Boolean,
): VideoRectPx? =
    if (
        bmlInvisible ||
        isInPictureInPictureMode ||
        videoRect == null ||
        videoRect.width <= 0 ||
        videoRect.height <= 0
    ) {
        null
    } else {
        videoRect
    }

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
    reloadRequest: Int = 0,
    selectedAudioTrackId: String? = null,
    isInPictureInPictureMode: Boolean = false,
    onBmlInputStateChanged: (
        available: Boolean,
        contentVisible: Boolean,
        usedKeyGroups: Set<String>,
    ) -> Unit = { _, _, _ -> },
    onPlaybackErrorChanged: (String?) -> Unit = {},
    onAudioTracksChanged: (List<AudioTrackOption>) -> Unit = {},
    onIsPlayingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnAudioTracksChanged by rememberUpdatedState(onAudioTracksChanged)
    val currentOnBmlInputStateChanged by rememberUpdatedState(onBmlInputStateChanged)
    val currentOnPlaybackErrorChanged by rememberUpdatedState(onPlaybackErrorChanged)
    val currentOnIsPlayingChanged by rememberUpdatedState(onIsPlayingChanged)
    val memoryPolicy = remember(context) { PlaybackMemoryPolicy.from(context) }
    // Font extraction and codec enumeration stall the main thread for hundreds of
    // milliseconds on low-end storage, so resolve them off-thread. The player below stays
    // black until ready, matching the previous behavior of blocking first composition.
    var playbackSetup by remember(forceMpeg2Transcoding) { mutableStateOf<PlaybackSetup?>(null) }
    LaunchedEffect(forceMpeg2Transcoding) {
        playbackSetup =
            withContext(Dispatchers.IO) {
                val fontFiles =
                    aribFontAssetPaths.map { prepareAribCaptionFont(context, it).absolutePath } +
                        systemSymbolFontPaths()
                val transcode = forceMpeg2Transcoding ?: !Mpeg2DecoderCapabilities.hasHardwareDecoder
                PlaybackSetup(fontFiles, transcode)
            }
    }
    val setup = playbackSetup
    if (setup == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    val aribFontFiles = setup.fontFiles
    val transcodeMpeg2Video = setup.transcodeMpeg2Video
    val engine =
        remember(
            url,
            basicAuthUsername,
            basicAuthPassword,
            bearerToken,
            forceMpeg2Transcoding,
            forceHardwareMpeg2Decoder,
            deinterlaceEnabled,
            dataBroadcastingEnabled,
            mahironApiRoot,
            serviceId,
            memoryPolicy,
            transcodeMpeg2Video,
        ) {
            VisiomataPlaybackEngine(
                context.applicationContext,
                VisiomataPlaybackEngineConfig(
                    url = url,
                    basicAuthUsername = basicAuthUsername,
                    basicAuthPassword = basicAuthPassword,
                    bearerToken = bearerToken,
                    forceMpeg2Transcoding = forceMpeg2Transcoding,
                    forceHardwareMpeg2Decoder = forceHardwareMpeg2Decoder,
                    transcodeMpeg2Video = transcodeMpeg2Video,
                    deinterlaceEnabled = deinterlaceEnabled,
                    dataBroadcastingEnabled = dataBroadcastingEnabled,
                    mahironApiRoot = mahironApiRoot,
                    serviceId = serviceId,
                    mediaTitle = mediaTitle,
                    mediaSubtitle = mediaSubtitle,
                    mediaArtworkData = mediaArtworkData,
                    aribFontFiles = aribFontFiles,
                    memoryPolicy = memoryPolicy,
                ),
                initialAudioComponents = audioComponents,
            )
        }
    val bmlMessageSource = engine.bmlMessageSource
    val player = engine.player
    var audioStateRevision by remember(engine) { mutableIntStateOf(0) }
    LaunchedEffect(engine, audioComponents) {
        engine.updateAudioComponents(audioComponents)
    }
    var bmlInvisible by remember(bmlMessageSource) { mutableStateOf(true) }
    var bmlDocumentLoaded by remember(bmlMessageSource) { mutableStateOf(false) }
    var bmlUsedKeyGroups by remember(bmlMessageSource) { mutableStateOf(emptySet<String>()) }
    var bmlVideoRect by remember(bmlMessageSource) { mutableStateOf<VideoRectPx?>(null) }
    var bmlWebView by remember(bmlMessageSource) { mutableStateOf<BmlWebView?>(null) }
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
    LaunchedEffect(player, selectedAudioTrackId, audioStateRevision) {
        val trackId = selectedAudioTrackId ?: return@LaunchedEffect
        var fallback: Pair<Tracks.Group, Int>? = null
        var fallbackPriority = -1
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val priority = engine.audioComponentState.selectionPriority(format.id)
                if (group.isTrackSupported(trackIndex) && priority > fallbackPriority) {
                    fallback = group to trackIndex
                    fallbackPriority = priority
                }
                if (
                    audioTrackId(group, trackIndex) == trackId &&
                    group.isTrackSupported(trackIndex) &&
                    engine.audioComponentState.isAudioTrackAvailable(format.id)
                ) {
                    if (!group.isTrackSelected(trackIndex)) {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                                ).build()
                    }
                    return@LaunchedEffect
                }
            }
        }
        val fallbackTrack = fallback
        player.trackSelectionParameters =
            if (fallbackTrack != null) {
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(fallbackTrack.first.mediaTrackGroup, fallbackTrack.second),
                    ).build()
            } else {
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
            }
    }
    LaunchedEffect(player, url, mediaTitle, mediaSubtitle, mediaArtworkData) {
        val mediaItem = liveMediaItem(url, mediaTitle, mediaSubtitle, mediaArtworkData)
        if (player.mediaItemCount == 0) {
            player.setMediaItem(mediaItem)
        } else {
            player.replaceMediaItem(player.currentMediaItemIndex, mediaItem)
        }
    }

    fun resetBmlViewForNewStream() {
        bmlInvisible = true
        bmlDocumentLoaded = false
        bmlUsedKeyGroups = emptySet()
        bmlVideoRect = null
        bmlWebView?.reloadForNewStream()
    }
    LaunchedEffect(engine, reloadRequest) {
        if (reloadRequest > 0) engine.reconnect(::resetBmlViewForNewStream)
    }

    DisposableEffect(engine, lifecycleOwner) {
        var readyMemoryRecorded = false

        fun recordReadyMemory() {
            if (readyMemoryRecorded) return
            readyMemoryRecorded = true
            PlaybackMemoryMonitor.record(context, "ready", videoPath, bmlMessageSource != null)
        }

        val listener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    // A recreated media source has new track groups; reapply the selected audio ID.
                    audioStateRevision++
                    currentOnAudioTracksChanged(tracks.audioTrackOptions(engine.audioComponentState))
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    currentOnIsPlayingChanged(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                    resetBmlViewForNewStream()
                    val detail = error.localizedMessage ?: "再生に失敗しました"
                    // The engine listener runs first, so a give-up is already recorded here.
                    currentOnPlaybackErrorChanged(
                        if (engine.isDecoderRetryGaveUp()) {
                            "$detail\n自動再接続を中止しました"
                        } else {
                            "$detail\n再接続します…"
                        },
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            currentOnPlaybackErrorChanged(null)
                            recordReadyMemory()
                        }

                        Player.STATE_ENDED -> {
                            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                            resetBmlViewForNewStream()
                            currentOnPlaybackErrorChanged("ストリームが終了しました\n再接続します…")
                        }
                    }
                }
            }

        fun invalidateAudioTracks() {
            Handler(Looper.getMainLooper()).post {
                if (engine.isReleased() || !engine.player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) return@post
                audioStateRevision++
                currentOnAudioTracksChanged(
                    engine.player.currentTracks.audioTrackOptions(engine.audioComponentState),
                )
            }
        }
        val audioStateListener = ::invalidateAudioTracks
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        bmlWebView?.setActive(true)
                        engine.start(::resetBmlViewForNewStream)
                    }

                    Lifecycle.Event.ON_STOP -> {
                        bmlWebView?.setActive(false)
                        engine.stopStreaming()
                        currentOnPlaybackErrorChanged(null)
                    }

                    else -> {
                        return@LifecycleEventObserver
                    }
                }
            }
        player.addListener(listener)
        engine.addAudioStateListener(audioStateListener)
        currentOnAudioTracksChanged(player.currentTracks.audioTrackOptions(engine.audioComponentState))
        currentOnIsPlayingChanged(player.isPlaying)
        if (player.playbackState == Player.STATE_READY) recordReadyMemory()
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            bmlWebView?.setActive(true)
            engine.start(::resetBmlViewForNewStream)
        }
        onDispose {
            PlaybackMemoryMonitor.record(context, "release-before", videoPath, bmlMessageSource != null)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            engine.removeAudioStateListener(audioStateListener)
            bmlWebView?.setActive(false)
            engine.release()
            currentOnBmlInputStateChanged(false, false, emptySet())
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
                                webView.setActive(
                                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                                )
                            } finally {
                                Trace.endSection()
                            }
                        }
                    }
                },
                update = { host ->
                    val videoHost = host.getChildAt(0) as FrameLayout
                    val playerView = videoHost.getChildAt(videoHost.childCount - 1) as PlayerView
                    // PiPではBML描画プレーンを隠し、映像面の矩形指示も無視して全画面表示する。
                    // BML非表示時も合成対象から外し、全画面透過レイヤの毎フレーム合成を省く。
                    (host.getChildAt(1) as? View)?.visibility =
                        if (isInPictureInPictureMode || bmlInvisible) View.GONE else View.VISIBLE
                    val videoRect = effectiveVideoRect(bmlInvisible, bmlVideoRect, isInPictureInPictureMode)
                    playerView.player = player
                    val desiredLayoutParams =
                        if (videoRect != null) {
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
                    // AndroidView exclusively owns and releases both view instances. The engine's
                    // independent release path remains safe whichever disposal callback runs first.
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
                    (host.getChildAt(1) as? BmlWebView)?.let { webView ->
                        webView.release()
                        if (bmlWebView === webView) bmlWebView = null
                    }
                    host.removeAllViews()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun audioTrackId(
    group: Tracks.Group,
    trackIndex: Int,
): String =
    group.getTrackFormat(trackIndex).id?.takeIf(String::isNotBlank)
        ?: "${group.mediaTrackGroup.id}:$trackIndex"

internal fun Tracks.audioTrackOptions(audioComponentState: AudioComponentState): List<AudioTrackOption> {
    val audioGroups = groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
    val trackCount = audioGroups.sumOf { it.value.length }
    val options =
        buildList {
            audioGroups.forEach { (_, group) ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    if (!audioComponentState.isAudioTrackAvailable(format.id)) continue
                    val fallbackLabel = if (trackCount > 1) "音声${size + 1}" else "音声"
                    val presentation =
                        audioComponentState.presentation(format.id, format.language, format.label)
                    val details =
                        buildList {
                            presentation.language?.takeUnless { it == "und" }?.let(::add)
                            format.channelCount.takeIf { it > 0 }?.let { add("${it}ch") }
                        }
                    val name = presentation.label?.takeIf(String::isNotBlank) ?: fallbackLabel
                    add(
                        AudioTrackOption(
                            id = audioTrackId(group, trackIndex),
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

internal fun liveMediaItem(
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

internal object LiveBroadcastSessionCallback : MediaSession.Callback {
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
internal class LiveBroadcastPlayer(
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
