@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:no-wildcard-imports")

package net.rokoucha.visiomata.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.rokoucha.visiomata.playback.AudioTrackOption
import net.rokoucha.visiomata.playback.BmlRemoteKeyEvent
import net.rokoucha.visiomata.playback.BroadcastAudioComponent
import net.rokoucha.visiomata.playback.ui.R
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import android.view.KeyEvent as AndroidKeyEvent

data class PlayerProgramInfo(
    val serviceName: String,
    val channelLabel: String,
    val channelType: String = "",
    val logicalChannelNumber: String = "",
    val serviceLogo: ByteArray? = null,
    val title: String,
    val timeRange: String,
    val description: String = "",
    val progress: Float? = null,
    val details: List<PlayerProgramDetail> = emptyList(),
    val nextProgram: PlayerUpcomingProgram? = null,
    val audioComponents: List<PlayerAudioComponent> = emptyList(),
)

data class PlayerAudioComponent(
    val componentTag: Int,
    val isMain: Boolean,
    val isDualMono: Boolean,
    val languages: List<String> = emptyList(),
)

data class PlayerProgramDetail(
    val label: String,
    val content: String,
)

data class PlayerUpcomingProgram(
    val title: String,
    val timeRange: String,
    val description: String = "",
)

@Composable
fun PlayerScreen(
    url: String,
    modifier: Modifier = Modifier,
    basicAuthUsername: String = "",
    basicAuthPassword: String = "",
    bearerToken: String = "",
    forceMpeg2Transcoding: Boolean? = null,
    forceHardwareMpeg2Decoder: Boolean? = null,
    forceHardwareAvcDecoder: Boolean? = null,
    deinterlaceEnabled: Boolean = true,
    dataBroadcastingEnabled: Boolean = true,
    dataBroadcastingInternetEnabled: Boolean = false,
    mahironApiRoot: String? = null,
    serviceId: Long? = null,
    postalCode: String = "",
    programInfo: PlayerProgramInfo? = null,
    isTv: Boolean = LocalConfiguration.current.isTelevision,
    overlayTimeout: Duration = 5.seconds,
    onBack: () -> Unit = {},
    controls: @Composable () -> Unit = {},
) {
    var errorMessage by remember(url) { mutableStateOf<String?>(null) }
    var reloadGeneration by remember(url) { mutableIntStateOf(0) }
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT && !isTv
    LandscapeSystemBarsEffect(enabled = !portrait)
    var bmlActive by remember(url) { mutableStateOf(false) }
    var bmlContentVisible by remember(url) { mutableStateOf(false) }
    var bmlUsedKeyGroups by remember(url) { mutableStateOf(emptySet<String>()) }
    var audioTracks by remember(url) { mutableStateOf(emptyList<AudioTrackOption>()) }
    var selectedAudioTrackId by remember(url) { mutableStateOf<String?>(null) }
    var tvInputMode by remember(url) { mutableStateOf(TvInputMode.Player) }
    val remoteKeyEvents = remember(url) { MutableSharedFlow<BmlRemoteKeyEvent>(extraBufferCapacity = 16) }
    val sendRemoteKey: (String) -> Unit = { key ->
        remoteKeyEvents.tryEmit(BmlRemoteKeyEvent(key, true))
        remoteKeyEvents.tryEmit(BmlRemoteKeyEvent(key, false))
    }
    val reloadPlayer = {
        errorMessage = null
        reloadGeneration += 1
    }
    val pipView = LocalView.current
    val pipActivity = remember(pipView) { pipView.context.findActivity() }
    val pipSupported = remember(pipActivity, isTv) { !isTv && supportsPictureInPicture(pipActivity) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    val isInPictureInPictureMode = rememberIsInPictureInPictureMode(pipActivity)
    PictureInPictureAutoEnterEffect(
        activity = pipActivity,
        enabled = pipSupported && isPlaying,
        hintView = pipView,
    )
    Box(modifier) {
        PlayerLayout(
            portrait = portrait,
            isInPictureInPictureMode = isInPictureInPictureMode,
            onEnterPictureInPicture =
                if (pipSupported) {
                    { pipActivity?.let { enterPictureInPicture(it, pipView) } }
                } else {
                    null
                },
            programInfo = programInfo,
            overlayTimeout = overlayTimeout,
            controls = controls,
            isTv = isTv,
            dataBroadcastingEnabled = dataBroadcastingEnabled,
            bmlActive = bmlActive,
            bmlContentVisible = bmlContentVisible,
            bmlUsedKeyGroups = bmlUsedKeyGroups,
            audioTracks = audioTracks,
            selectedAudioTrackId = selectedAudioTrackId,
            tvInputMode = tvInputMode,
            onTvInputModeChanged = { tvInputMode = it },
            onAudioTrackSelected = { selectedAudioTrackId = it },
            onRemoteKey = sendRemoteKey,
            onBack = onBack,
            onReload = reloadPlayer,
            errorMessage = errorMessage,
            player = {
                VisiomataPlayer(
                    url = url,
                    basicAuthUsername = basicAuthUsername,
                    basicAuthPassword = basicAuthPassword,
                    bearerToken = bearerToken,
                    forceMpeg2Transcoding = forceMpeg2Transcoding,
                    forceHardwareMpeg2Decoder = forceHardwareMpeg2Decoder,
                    forceHardwareAvcDecoder = forceHardwareAvcDecoder,
                    deinterlaceEnabled = deinterlaceEnabled,
                    dataBroadcastingEnabled = dataBroadcastingEnabled,
                    dataBroadcastingInternetEnabled = dataBroadcastingInternetEnabled,
                    mahironApiRoot = mahironApiRoot,
                    serviceId = serviceId,
                    postalCode = postalCode,
                    mediaTitle = programInfo?.title,
                    mediaSubtitle = programInfo?.serviceName,
                    mediaArtworkData = programInfo?.serviceLogo,
                    audioComponents =
                        programInfo?.audioComponents.orEmpty().map { audio ->
                            BroadcastAudioComponent(
                                componentTag = audio.componentTag,
                                isMain = audio.isMain,
                                isDualMono = audio.isDualMono,
                                languages = audio.languages,
                            )
                        },
                    preferComposeKeyInput = isTv,
                    remoteKeyEvents = remoteKeyEvents,
                    reloadRequest = reloadGeneration,
                    selectedAudioTrackId = selectedAudioTrackId,
                    isInPictureInPictureMode = isInPictureInPictureMode,
                    onBmlInputStateChanged = { available, contentVisible, groups ->
                        bmlActive = available
                        bmlContentVisible = contentVisible
                        bmlUsedKeyGroups = groups
                    },
                    onPlaybackErrorChanged = { errorMessage = it },
                    onIsPlayingChanged = { isPlaying = it },
                    onAudioTracksChanged = { tracks ->
                        audioTracks = tracks
                        tracks.firstOrNull(AudioTrackOption::selected)?.let {
                            selectedAudioTrackId = it.id
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
}

@Composable
private fun LandscapeSystemBarsEffect(enabled: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    DisposableEffect(view, activity, enabled) {
        if (!enabled || activity == null) return@DisposableEffect onDispose {}

        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun PlayerErrorOverlay(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.82f))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("再生エラー", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(message, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun PlayerLayout(
    portrait: Boolean,
    isTv: Boolean,
    dataBroadcastingEnabled: Boolean,
    bmlActive: Boolean,
    bmlContentVisible: Boolean,
    bmlUsedKeyGroups: Set<String>,
    audioTracks: List<AudioTrackOption>,
    selectedAudioTrackId: String?,
    onAudioTrackSelected: (String) -> Unit,
    programInfo: PlayerProgramInfo?,
    overlayTimeout: Duration,
    player: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    onRemoteKey: (String) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    tvInputMode: TvInputMode = TvInputMode.Player,
    onTvInputModeChanged: (TvInputMode) -> Unit = {},
    errorMessage: String? = null,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: (() -> Unit)? = null,
) {
    val currentOnTvInputModeChanged by rememberUpdatedState(onTvInputModeChanged)
    val currentPlayer by rememberUpdatedState(player)
    val movablePlayer = remember { movableContentOf { currentPlayer() } }
    val pipSwipeModifier = rememberPictureInPictureSwipeModifier(onEnterPictureInPicture)
    if (isInPictureInPictureMode) {
        // PiPウィンドウには映像のみを表示し、操作UIや番組情報は隠す。
        Box(modifier.fillMaxSize().background(Color.Black)) {
            movablePlayer()
        }
        return
    }
    if (portrait) {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var overlayVisible by remember { mutableStateOf(true) }
        var interactionGeneration by remember { mutableIntStateOf(0) }
        val toggleOverlay = {
            if (overlayVisible) overlayVisible = false else interactionGeneration++
        }
        LaunchedEffect(interactionGeneration, overlayTimeout) {
            overlayVisible = true
            delay(overlayTimeout)
            overlayVisible = false
        }
        Column(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                movablePlayer()
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { toggleOverlay() }
                        .then(pipSwipeModifier),
                )
                errorMessage?.let { message ->
                    PlayerErrorOverlay(message, Modifier.matchParentSize().zIndex(1f))
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.72f),
                                    0.24f to Color.Transparent,
                                    0.45f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.78f),
                                ),
                            ).padding(16.dp),
                    ) {
                        PlayerBackButton(onBack, Modifier.align(Alignment.TopStart))
                        Column(Modifier.align(Alignment.BottomStart)) {
                            controls()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerReloadButton {
                                    interactionGeneration++
                                    onReload()
                                }
                                onEnterPictureInPicture?.let { enterPip ->
                                    PlayerPipButton(onClick = enterPip)
                                }
                                Spacer(Modifier.weight(1f))
                                AudioTrackMenu(
                                    tracks = audioTracks,
                                    selectedTrackId = selectedAudioTrackId,
                                    onTrackSelected = onAudioTrackSelected,
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
                if (dataBroadcastingEnabled) {
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("番組情報") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("リモコン") })
                    }
                }
                if (selectedTab == 0 || !dataBroadcastingEnabled) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        ProgramDetails(programInfo)
                    }
                } else {
                    BmlRemoteControl(
                        onRemoteKey = onRemoteKey,
                        bmlActive = bmlActive && bmlContentVisible,
                        usedKeyGroups = bmlUsedKeyGroups,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    } else {
        var remoteVisible by rememberSaveable { mutableStateOf(false) }
        var remoteOffset by remember { mutableStateOf(Offset.Zero) }
        val playerFocusRequester = remember { FocusRequester() }
        val remoteButtonFocusRequester = remember { FocusRequester() }
        val remoteWindowFocusRequester = remember { FocusRequester() }
        val overlayBackFocusRequester = remember { FocusRequester() }
        val reloadButtonFocusRequester = remember { FocusRequester() }
        val dataBroadcastButtonFocusRequester = remember { FocusRequester() }
        var overlayVisible by remember { mutableStateOf(true) }
        var interactionGeneration by remember { mutableIntStateOf(0) }
        var longPressHandledKeyCode by remember { mutableStateOf<Int?>(null) }
        var overlayWakeKeyCode by remember { mutableStateOf<Int?>(null) }
        var audioMenuVisible by remember { mutableStateOf(false) }
        var dataBroadcastHintGeneration by remember { mutableIntStateOf(0) }
        var backPressedAtMillis by remember { mutableLongStateOf(0L) }
        var backLongPressJob by remember { mutableStateOf<Job?>(null) }
        var bmlShortcutLongPressJob by remember { mutableStateOf<Job?>(null) }
        var suppressTvBackUntilMillis by remember { mutableLongStateOf(0L) }
        val inputScope = rememberCoroutineScope()
        val bmlInputActive = bmlActive && bmlContentVisible
        val toggleOverlay = {
            if (overlayVisible) {
                overlayVisible = false
            } else {
                overlayVisible = true
                interactionGeneration++
            }
        }
        BackHandler(enabled = isTv) {
            if (SystemClock.uptimeMillis() < suppressTvBackUntilMillis) {
                suppressTvBackUntilMillis = 0L
            } else if (tvInputMode == TvInputMode.DataBroadcast) {
                onRemoteKey("Backspace")
            } else if (!overlayVisible) {
                overlayVisible = true
                interactionGeneration++
            } else {
                onBack()
            }
        }
        LaunchedEffect(overlayVisible, interactionGeneration, overlayTimeout, remoteVisible, audioMenuVisible, isTv) {
            if (!overlayVisible || remoteVisible || audioMenuVisible) return@LaunchedEffect
            delay(overlayTimeout)
            overlayVisible = false
        }
        LaunchedEffect(isTv) {
            if (isTv) {
                remoteVisible = false
            }
        }
        LaunchedEffect(isTv, dataBroadcastingEnabled) {
            if (!isTv || !dataBroadcastingEnabled) {
                currentOnTvInputModeChanged(TvInputMode.Player)
                backLongPressJob?.cancel()
                backLongPressJob = null
                bmlShortcutLongPressJob?.cancel()
                bmlShortcutLongPressJob = null
            }
        }
        LaunchedEffect(isTv, overlayVisible, remoteVisible, dataBroadcastingEnabled) {
            if (!isTv) return@LaunchedEffect
            if (remoteVisible) {
                remoteWindowFocusRequester.requestFocus()
            } else if (overlayVisible) {
                overlayBackFocusRequester.requestFocus()
            } else {
                playerFocusRequester.requestFocus()
            }
        }
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    val keyCode = event.nativeKeyEvent.keyCode
                    val isConfirmKey =
                        keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isTv && event.type == KeyEventType.KeyUp && longPressHandledKeyCode == keyCode) {
                        longPressHandledKeyCode = null
                        overlayWakeKeyCode = null
                        return@onPreviewKeyEvent true
                    }
                    if (isTv && tvInputMode == TvInputMode.DataBroadcast) {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            // Android TV may route the previous key-up through OnBackPressedDispatcher
                            // instead of this focus tree. A fresh down always starts a new gesture, so
                            // discard any handled marker left behind by that missing key-up.
                            longPressHandledKeyCode = null
                            dataBroadcastHintGeneration++
                        }
                        if (keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                            when (event.type) {
                                KeyEventType.KeyDown -> {
                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                        backPressedAtMillis = event.nativeKeyEvent.eventTime
                                        backLongPressJob?.cancel()
                                        backLongPressJob =
                                            inputScope.launch {
                                                delay(DATA_BROADCAST_EXIT_HOLD_MILLIS)
                                                suppressTvBackUntilMillis =
                                                    SystemClock.uptimeMillis() + TV_BACK_SUPPRESSION_MILLIS
                                                longPressHandledKeyCode = AndroidKeyEvent.KEYCODE_BACK
                                                onTvInputModeChanged(TvInputMode.Player)
                                                overlayVisible = true
                                                interactionGeneration++
                                                backLongPressJob = null
                                            }
                                    }
                                    return@onPreviewKeyEvent true
                                }

                                KeyEventType.KeyUp -> {
                                    val wasLongPress =
                                        tvInputMode != TvInputMode.DataBroadcast ||
                                            event.nativeKeyEvent.eventTime - backPressedAtMillis >=
                                            DATA_BROADCAST_EXIT_HOLD_MILLIS
                                    backLongPressJob?.cancel()
                                    backLongPressJob = null
                                    if (wasLongPress) {
                                        suppressTvBackUntilMillis =
                                            SystemClock.uptimeMillis() + TV_BACK_SUPPRESSION_MILLIS
                                        longPressHandledKeyCode = AndroidKeyEvent.KEYCODE_BACK
                                        onTvInputModeChanged(TvInputMode.Player)
                                        overlayVisible = true
                                        interactionGeneration++
                                    }
                                    return@onPreviewKeyEvent wasLongPress
                                }

                                else -> {
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        val shortcutLongPressKey = keyCode.toLongPressBmlKey()
                        if (shortcutLongPressKey != null) {
                            when (event.type) {
                                KeyEventType.KeyDown -> {
                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                        bmlShortcutLongPressJob?.cancel()
                                        bmlShortcutLongPressJob =
                                            inputScope.launch {
                                                delay(DATA_BROADCAST_SHORTCUT_HOLD_MILLIS)
                                                if (longPressHandledKeyCode == null) {
                                                    longPressHandledKeyCode = keyCode
                                                    dataBroadcastHintGeneration++
                                                    onRemoteKey(shortcutLongPressKey)
                                                }
                                                bmlShortcutLongPressJob = null
                                            }
                                    } else if (longPressHandledKeyCode == null) {
                                        bmlShortcutLongPressJob?.cancel()
                                        bmlShortcutLongPressJob = null
                                        longPressHandledKeyCode = keyCode
                                        dataBroadcastHintGeneration++
                                        onRemoteKey(shortcutLongPressKey)
                                    }
                                    return@onPreviewKeyEvent true
                                }

                                KeyEventType.KeyUp -> {
                                    bmlShortcutLongPressJob?.cancel()
                                    bmlShortcutLongPressJob = null
                                    keyCode.toBmlKey()?.let(onRemoteKey)
                                    return@onPreviewKeyEvent true
                                }

                                else -> {
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        val dataBroadcastKey = keyCode.toBmlKey()
                        if (dataBroadcastKey != null) {
                            if (event.type == KeyEventType.KeyUp) onRemoteKey(dataBroadcastKey)
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (isTv && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        // BML presentation changes can move focus before key-up arrives.
                        // Treat every fresh down as a new gesture so a missed key-up
                        // cannot permanently block the next synthetic remote key.
                        longPressHandledKeyCode = null
                        overlayWakeKeyCode = null
                    }
                    val bmlKey = keyCode.toBmlKey()
                    val isOverlayNavigationKey =
                        isConfirmKey ||
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                    val longPressBmlKey = keyCode.toLongPressBmlKey()
                    if (
                        isTv && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0 &&
                        longPressHandledKeyCode == null && longPressBmlKey != null && longPressBmlKey != "d" &&
                        bmlHandlesKeyGroup(bmlInputActive, bmlUsedKeyGroups, "data-button")
                    ) {
                        overlayWakeKeyCode = null
                        longPressHandledKeyCode = keyCode
                        overlayVisible = false
                        onRemoteKey(longPressBmlKey)
                        return@onPreviewKeyEvent true
                    }
                    if (isTv && event.type == KeyEventType.KeyDown && overlayWakeKeyCode == keyCode) {
                        if (
                            isConfirmKey && dataBroadcastingEnabled && event.nativeKeyEvent.repeatCount > 0 &&
                            longPressHandledKeyCode == null
                        ) {
                            overlayWakeKeyCode = null
                            longPressHandledKeyCode = keyCode
                            overlayVisible = false
                            onTvInputModeChanged(TvInputMode.DataBroadcast)
                            dataBroadcastHintGeneration++
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (isTv && event.type == KeyEventType.KeyUp && overlayWakeKeyCode == keyCode) {
                        overlayWakeKeyCode = null
                        if (isConfirmKey) {
                            overlayVisible = true
                            interactionGeneration++
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (
                        isTv && !overlayVisible && isOverlayNavigationKey &&
                        event.type == KeyEventType.KeyDown
                    ) {
                        overlayWakeKeyCode = keyCode
                        // Delay opening for the confirm key until key-up so a hold can be
                        // distinguished from the synthetic d-button gesture.
                        if (!isConfirmKey) {
                            overlayVisible = true
                            interactionGeneration++
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (
                        isTv && overlayVisible && isOverlayNavigationKey &&
                        event.type == KeyEventType.KeyDown
                    ) {
                        interactionGeneration++
                    }
                    if (isTv && dataBroadcastingEnabled && isConfirmKey) {
                        if (
                            event.type == KeyEventType.KeyDown &&
                            event.nativeKeyEvent.repeatCount > 0 &&
                            longPressHandledKeyCode == null
                        ) {
                            longPressHandledKeyCode = keyCode
                            overlayWakeKeyCode = null
                            overlayVisible = false
                            onTvInputModeChanged(TvInputMode.DataBroadcast)
                            dataBroadcastHintGeneration++
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (isTv && bmlKey != null && (
                            bmlKey == "d" || (
                                bmlKey.toBmlKeyGroup() == "data-button" && bmlActive
                            ) || (
                                bmlKey.toBmlKeyGroup() != "basic" &&
                                    bmlHandlesKeyGroup(bmlInputActive, bmlUsedKeyGroups, bmlKey.toBmlKeyGroup())
                            )
                        )
                    ) {
                        if (event.type == KeyEventType.KeyUp) onRemoteKey(bmlKey)
                        return@onPreviewKeyEvent true
                    }
                    val togglesOverlay =
                        keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_SPACE
                    if (!isTv && event.type == KeyEventType.KeyUp && togglesOverlay) {
                        toggleOverlay()
                        true
                    } else {
                        false
                    }
                },
        ) {
            movablePlayer()
            if (isTv && tvInputMode == TvInputMode.DataBroadcast) {
                DataBroadcastInputHint(
                    interactionGeneration = dataBroadcastHintGeneration,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(start = 32.dp, bottom = 24.dp),
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { toggleOverlay() }
                    .then(pipSwipeModifier)
                    .focusRequester(playerFocusRequester)
                    .focusable(),
            )
            errorMessage?.let { message ->
                PlayerErrorOverlay(message, Modifier.matchParentSize().zIndex(1f))
            }
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.06f))
                        .background(
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0f to Color.Black.copy(alpha = 0.78f),
                                        0.18f to Color.Transparent,
                                        0.20f to Color.Transparent,
                                        0.52f to Color.Black.copy(alpha = 0.56f),
                                        1f to Color.Black.copy(alpha = 0.92f),
                                    ),
                            ),
                        ).statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(32.dp),
                ) {
                    PlayerBackButton(
                        onBack,
                        Modifier
                            .align(Alignment.TopStart)
                            .focusRequester(overlayBackFocusRequester)
                            .focusProperties { down = reloadButtonFocusRequester },
                    )
                    Column(Modifier.align(Alignment.BottomStart)) {
                        ProgramDetails(programInfo, onDarkBackground = true)
                        Spacer(Modifier.height(8.dp))
                        controls()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerReloadButton(
                                onClick = {
                                    interactionGeneration++
                                    onReload()
                                },
                                modifier = Modifier.focusRequester(reloadButtonFocusRequester),
                            )
                            onEnterPictureInPicture?.let { enterPip ->
                                PlayerPipButton(onClick = enterPip)
                            }
                            if (dataBroadcastingEnabled && !remoteVisible && !isTv) {
                                var remoteButtonFocused by remember { mutableStateOf(false) }
                                FilledTonalIconButton(
                                    onClick = { remoteVisible = true },
                                    modifier =
                                        Modifier
                                            .focusRequester(remoteButtonFocusRequester)
                                            .onFocusChanged { remoteButtonFocused = it.isFocused }
                                            .then(
                                                if (remoteButtonFocused) {
                                                    Modifier.border(
                                                        3.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.shapes.extraLarge,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    colors =
                                        IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.primaryContainer.copy(
                                                    alpha = 0.94f,
                                                ),
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.settings_remote_24),
                                        contentDescription = "リモコンを開く",
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            if (isTv && dataBroadcastingEnabled) {
                                var dataBroadcastButtonFocused by remember { mutableStateOf(false) }
                                FilledTonalIconButton(
                                    onClick = {
                                        onTvInputModeChanged(TvInputMode.DataBroadcast)
                                        dataBroadcastHintGeneration++
                                        overlayVisible = false
                                    },
                                    modifier =
                                        Modifier
                                            .focusRequester(dataBroadcastButtonFocusRequester)
                                            .onFocusChanged { dataBroadcastButtonFocused = it.isFocused }
                                            .then(
                                                if (dataBroadcastButtonFocused) {
                                                    Modifier.border(
                                                        3.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.shapes.extraLarge,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    colors =
                                        IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.primaryContainer.copy(
                                                    alpha = 0.94f,
                                                ),
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.settings_remote_24),
                                        contentDescription = "データ放送を操作",
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            AudioTrackMenu(
                                tracks = audioTracks,
                                selectedTrackId = selectedAudioTrackId,
                                onTrackSelected = onAudioTrackSelected,
                                onExpandedChanged = { audioMenuVisible = it },
                            )
                        }
                    }
                }
            }
            if (dataBroadcastingEnabled && remoteVisible && !isTv) {
                Surface(
                    tonalElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset {
                                androidx.compose.ui.unit
                                    .IntOffset(remoteOffset.x.toInt(), remoteOffset.y.toInt())
                            }.width(280.dp)
                            .padding(16.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                remoteOffset += dragAmount
                                            }
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 48.dp, height = 5.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            MaterialTheme.shapes.extraLarge,
                                        ),
                                )
                            }
                            IconButton(
                                onClick = { remoteVisible = false },
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "リモコンを閉じる")
                            }
                        }
                        BmlRemoteControl(
                            onRemoteKey = onRemoteKey,
                            bmlActive = bmlInputActive,
                            usedKeyGroups = bmlUsedKeyGroups,
                            compact = true,
                            initialFocusRequester = remoteWindowFocusRequester,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTrackMenu(
    tracks: List<AudioTrackOption>,
    selectedTrackId: String?,
    onTrackSelected: (String) -> Unit,
    onExpandedChanged: (Boolean) -> Unit = {},
) {
    if (tracks.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val setExpanded: (Boolean) -> Unit = {
        expanded = it
        onExpandedChanged(it)
    }
    Box {
        var focused by remember { mutableStateOf(false) }
        FilledTonalIconButton(
            onClick = { setExpanded(true) },
            modifier =
                Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .then(
                        if (focused) {
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.extraLarge,
                            )
                        } else {
                            Modifier
                        },
                    ),
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.volume_up_24),
                contentDescription = "音声トラックを選択",
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.label) },
                    leadingIcon = {
                        RadioButton(selected = track.id == selectedTrackId, onClick = null)
                    },
                    enabled = track.supported,
                    onClick = {
                        onTrackSelected(track.id)
                        setExpanded(false)
                    },
                )
            }
        }
    }
}

private enum class TvInputMode { Player, DataBroadcast }

private const val DATA_BROADCAST_EXIT_HOLD_MILLIS = 800L
private const val DATA_BROADCAST_SHORTCUT_HOLD_MILLIS = 800L
private const val TV_BACK_SUPPRESSION_MILLIS = 1_000L

@Composable
private fun DataBroadcastInputHint(
    interactionGeneration: Int,
    modifier: Modifier = Modifier,
) {
    var emphasized by remember { mutableStateOf(true) }
    LaunchedEffect(interactionGeneration) {
        emphasized = true
        delay(1_500)
        emphasized = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (emphasized) 0.92f else 0.28f,
        animationSpec = tween(durationMillis = 300),
        label = "data broadcast input hint alpha",
    )
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = 0.78f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.settings_remote_24),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text("データ放送操作中", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.remote_dpad_color_hold_96),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(64.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("方向キー長押し", style = MaterialTheme.typography.labelMedium)
                        Text("上: 青　右: 赤", style = MaterialTheme.typography.labelSmall)
                        Text("下: 緑　左: 黄", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.remote_select_hold_40),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text("決定長押し: dボタン", style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.remote_back_hold_32),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text("戻る長押し: 操作を終了", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun Int.toBmlKey(): String? =
    when (this) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
            "ArrowUp"
        }

        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
            "ArrowDown"
        }

        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
            "ArrowLeft"
        }

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
            "ArrowRight"
        }

        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> {
            "Enter"
        }

        AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_DEL -> {
            "Backspace"
        }

        AndroidKeyEvent.KEYCODE_PROG_RED, AndroidKeyEvent.KEYCODE_R -> {
            "R"
        }

        AndroidKeyEvent.KEYCODE_PROG_GREEN, AndroidKeyEvent.KEYCODE_G -> {
            "G"
        }

        AndroidKeyEvent.KEYCODE_PROG_YELLOW, AndroidKeyEvent.KEYCODE_Y -> {
            "Y"
        }

        AndroidKeyEvent.KEYCODE_PROG_BLUE, AndroidKeyEvent.KEYCODE_B -> {
            "B"
        }

        AndroidKeyEvent.KEYCODE_TV_DATA_SERVICE, AndroidKeyEvent.KEYCODE_D -> {
            "d"
        }

        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
            (this - AndroidKeyEvent.KEYCODE_0).toString()
        }

        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
            (this - AndroidKeyEvent.KEYCODE_NUMPAD_0).toString()
        }

        else -> {
            null
        }
    }

private fun Int.toLongPressBmlKey(): String? =
    when (this) {
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> "d"

        AndroidKeyEvent.KEYCODE_DPAD_UP -> "B"

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "R"

        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "G"

        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "Y"

        else -> null
    }

private fun String.toBmlKeyGroup(): String =
    when (this) {
        "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter", "Backspace" -> "basic"
        "B", "R", "G", "Y" -> "data-button"
        in "0".."9" -> "numeric-tuning"
        else -> "none"
    }

private fun bmlHandlesKeyGroup(
    active: Boolean,
    usedKeyGroups: Set<String>,
    group: String,
): Boolean {
    if (!active || "none" in usedKeyGroups) return false
    val effectiveGroups = usedKeyGroups.ifEmpty { setOf("basic") }
    return group in effectiveGroups
}

@Composable
private fun BmlRemoteControl(
    onRemoteKey: (String) -> Unit,
    bmlActive: Boolean,
    usedKeyGroups: Set<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    initialFocusRequester: FocusRequester? = null,
) {
    BoxWithConstraints(
        modifier =
            modifier.then(
                if (compact) Modifier.size(width = 248.dp, height = 300.dp) else Modifier.fillMaxSize(),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val basicEnabled = bmlHandlesKeyGroup(bmlActive, usedKeyGroups, "basic")
        val colorEnabled = bmlHandlesKeyGroup(bmlActive, usedKeyGroups, "data-button")
        val buttonSize = if (compact) 48.dp else minOf(72.dp, maxWidth / 4.6f, maxHeight / 6f)
        val colorButtonHeight = if (compact) 42.dp else minOf(64.dp, maxHeight / 7f)
        val groupSpacing = if (compact) 10.dp else minOf(24.dp, maxHeight / 18f)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var dataButtonFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = { onRemoteKey("d") },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(buttonSize)
                            .then(
                                if (initialFocusRequester !=
                                    null
                                ) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            ).onFocusChanged { dataButtonFocused = it.isFocused }
                            .then(
                                if (dataButtonFocused) {
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onPrimary,
                                        MaterialTheme.shapes.medium,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "d",
                        style = MaterialTheme.typography.titleLarge,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                var backButtonFocused by remember { mutableStateOf(false) }
                FilledTonalButton(
                    onClick = { onRemoteKey("Backspace") },
                    enabled = basicEnabled,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(buttonSize)
                            .onFocusChanged { backButtonFocused = it.isFocused }
                            .then(
                                if (backButtonFocused) {
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.shapes.extraLarge,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Text("戻る", style = MaterialTheme.typography.labelLarge) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    "B" to Color(0xFF2556D8),
                    "R" to Color(0xFFD32F2F),
                    "G" to Color(0xFF2E7D32),
                    "Y" to Color(0xFFF9A825),
                ).forEach { (key, color) ->
                    var focused by remember(key) { mutableStateOf(false) }
                    Button(
                        onClick = { onRemoteKey(key) },
                        enabled = colorEnabled,
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(colorButtonHeight)
                                .onFocusChanged { focused = it.isFocused }
                                .then(
                                    if (focused) {
                                        Modifier.border(
                                            3.dp,
                                            Color.White,
                                            MaterialTheme.shapes.medium,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color),
                    ) { }
                }
            }
            Spacer(Modifier.height(groupSpacing))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RemoteKeyButton("▲", "ArrowUp", onRemoteKey, buttonSize, enabled = basicEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteKeyButton("◀", "ArrowLeft", onRemoteKey, buttonSize, enabled = basicEnabled)
                    RemoteKeyButton("決定", "Enter", onRemoteKey, buttonSize, emphasized = true, enabled = basicEnabled)
                    RemoteKeyButton("▶", "ArrowRight", onRemoteKey, buttonSize, enabled = basicEnabled)
                }
                RemoteKeyButton("▼", "ArrowDown", onRemoteKey, buttonSize, enabled = basicEnabled)
            }
        }
    }
}

@Composable
private fun RemoteKeyButton(
    label: String,
    key: String,
    onRemoteKey: (String) -> Unit,
    size: androidx.compose.ui.unit.Dp,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = { onRemoteKey(key) },
        enabled = enabled,
        modifier =
            Modifier
                .size(size)
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) {
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            if (emphasized) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = if (emphasized) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(0.dp),
        colors =
            if (emphasized) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun PlayerBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    FilledTonalIconButton(
        onClick = onClick,
        modifier =
            modifier
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) {
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.extraLarge,
                        )
                    } else {
                        Modifier
                    },
                ),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "戻る",
        )
    }
}

@Composable
private fun PlayerReloadButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    FilledTonalIconButton(
        onClick = onClick,
        modifier =
            modifier
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) {
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.extraLarge,
                        )
                    } else {
                        Modifier
                    },
                ),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "再読み込み",
        )
    }
}

@Composable
private fun PlayerPipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    FilledTonalIconButton(
        onClick = onClick,
        modifier =
            modifier
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) {
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.extraLarge,
                        )
                    } else {
                        Modifier
                    },
                ),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(
            painter = painterResource(R.drawable.picture_in_picture_24),
            contentDescription = "ピクチャ イン ピクチャで表示",
        )
    }
}

@Composable
private fun ProgramDetails(
    info: PlayerProgramInfo?,
    onDarkBackground: Boolean = false,
) {
    val primary = if (onDarkBackground) Color.White else MaterialTheme.colorScheme.onBackground
    val secondary =
        if (onDarkBackground) {
            Color.White.copy(
                alpha = 0.78f,
            )
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    if (info == null) {
        Text("番組情報を取得しています", color = secondary, style = MaterialTheme.typography.bodyLarge)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (onDarkBackground) 6.dp else 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (onDarkBackground) 10.dp else 12.dp),
        ) {
            if (onDarkBackground) PlayerStationLogo(info.serviceLogo, info.serviceName)
            if (info.logicalChannelNumber.isNotBlank()) {
                Text(info.serviceName, color = secondary, style = MaterialTheme.typography.labelLarge)
                Text(
                    "${info.channelType}  ${info.logicalChannelNumber}",
                    color = secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Text(
                    listOf(info.channelLabel, info.serviceName)
                        .filter(String::isNotBlank)
                        .joinToString("  •  "),
                    color = secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(
            info.title,
            color = primary,
            style = if (onDarkBackground) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            info.timeRange,
            color = secondary,
            style = if (onDarkBackground) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        info.progress?.let {
            LinearProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
        }
        if (!onDarkBackground) {
            val informationFields =
                buildList {
                    if (info.description.isNotBlank()) {
                        add(PlayerProgramDetail("番組概要", info.description))
                    }
                    addAll(info.details)
                }
            if (informationFields.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
                informationFields.forEachIndexed { index, detail ->
                    Column(
                        modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            detail.label,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(detail.content, color = primary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            info.nextProgram?.let { next ->
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
                Text(
                    "次の番組",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(next.timeRange, color = secondary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    next.title,
                    color = primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (next.description.isNotBlank()) {
                    Text(
                        next.description,
                        color = secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerStationLogo(
    logoBytes: ByteArray?,
    serviceName: String,
) {
    if (logoBytes == null) return
    val bitmap =
        remember(logoBytes) {
            BitmapFactory.decodeByteArray(logoBytes, 0, logoBytes.size)
        } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "$serviceName ロゴ",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(width = 64.dp, height = 38.dp),
    )
}

private val Configuration.isTelevision: Boolean
    get() = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

private val previewInfo =
    PlayerProgramInfo(
        serviceName = "東京テレビ",
        channelLabel = "7  •  GR",
        title = "今日のニュースと天気",
        timeRange = "19:00–20:00",
        description = "国内外のニュースと、明日の詳しい天気をお伝えします。",
        progress = 0.62f,
        details =
            listOf(
                PlayerProgramDetail("出演者", "山田太郎、佐藤花子"),
                PlayerProgramDetail("番組内容", "季節のニュースを詳しく解説します。"),
            ),
        nextProgram =
            PlayerUpcomingProgram(
                "街歩き紀行",
                "20:00–20:54",
                "港町の歴史と食文化を訪ねます。",
            ),
    )

@Preview(name = "Portrait player", device = Devices.PHONE, showBackground = true)
@Composable
private fun PortraitPlayerPreview() {
    MaterialTheme {
        PlayerLayout(
            portrait = true,
            isTv = false,
            dataBroadcastingEnabled = true,
            bmlActive = false,
            bmlContentVisible = false,
            bmlUsedKeyGroups = emptySet(),
            audioTracks = emptyList(),
            selectedAudioTrackId = null,
            onAudioTrackSelected = {},
            programInfo = previewInfo,
            overlayTimeout = 5.seconds,
            player = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
            controls = { Row { Button(onClick = {}) { Text("一時停止") } } },
            onRemoteKey = {},
            onBack = {},
            onReload = {},
        )
    }
}

@Preview(name = "Portrait playback error", device = Devices.PHONE, showBackground = true)
@Preview(name = "Landscape playback error", widthDp = 640, heightDp = 360, showBackground = true)
@Preview(name = "Large text playback error", device = Devices.PHONE, fontScale = 2f, showBackground = true)
@Composable
private fun PlayerErrorPreview() {
    MaterialTheme {
        PlayerLayout(
            portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT,
            isTv = false,
            dataBroadcastingEnabled = true,
            bmlActive = false,
            bmlContentVisible = false,
            bmlUsedKeyGroups = emptySet(),
            audioTracks = emptyList(),
            selectedAudioTrackId = null,
            onAudioTrackSelected = {},
            programInfo = previewInfo,
            overlayTimeout = 5.seconds,
            player = { Box(Modifier.fillMaxSize().background(Color.Black)) },
            controls = {},
            onRemoteKey = {},
            onBack = {},
            onReload = {},
            errorMessage =
                "MediaCodecVideoRenderer error, index=0, format=Format(1024/256, null, " +
                    "video/mp2t, video/avc, null, [1440, 1080]), format_supported=YES\n再接続します…",
        )
    }
}

@Preview(name = "TV player", device = Devices.TV_1080p, showBackground = true)
@Composable
private fun TvPlayerPreview() {
    MaterialTheme {
        PlayerLayout(
            portrait = false,
            isTv = true,
            dataBroadcastingEnabled = true,
            bmlActive = false,
            bmlContentVisible = false,
            bmlUsedKeyGroups = emptySet(),
            audioTracks =
                listOf(
                    AudioTrackOption("0:0", "主音声 (jpn / 2ch)", true, true),
                    AudioTrackOption("0:1", "副音声 (jpn / 2ch)", false, true),
                ),
            selectedAudioTrackId = "0:0",
            onAudioTrackSelected = {},
            programInfo = previewInfo,
            overlayTimeout = 5.seconds,
            player = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
            controls = { Row { Button(onClick = {}) { Text("番組表") } } },
            onRemoteKey = {},
            onBack = {},
            onReload = {},
        )
    }
}
