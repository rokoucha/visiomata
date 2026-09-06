@file:Suppress("ktlint:standard:no-wildcard-imports")

package net.rokoucha.visiomata.guide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.Program
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.ProgramGuideAvailability
import net.rokoucha.visiomata.model.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramGuideScreen(
    guide: ProgramGuide,
    channelTypes: List<ChannelType>,
    selectedType: ChannelType,
    broadcastDate: LocalDate,
    isTv: Boolean,
    isLoading: Boolean,
    availability: ProgramGuideAvailability?,
    onChannelTypeSelected: (ChannelType) -> Unit,
    onBroadcastDateChanged: (LocalDate) -> Unit,
    onPlay: (Long) -> Unit,
    loadExtended: suspend (Long) -> Map<String, String>,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val windowStart =
        remember(broadcastDate, zone) {
            GuideTimeline.windowStart(broadcastDate, zone)
        }
    val initialFocusInstant =
        remember(broadcastDate, zone) {
            if (broadcastDate == currentBroadcastDate()) {
                Instant.now()
            } else {
                broadcastDate.atStartOfDay(zone).toInstant()
            }
        }
    val earliestDate =
        remember(availability, zone) {
            availability?.let { GuideTimeline.displayDateAt(it.startAt, zone) }
        }
    val latestDate =
        remember(availability, zone) {
            availability?.let { GuideTimeline.displayDateAt(it.endAt.minusMillis(1), zone) }
        }
    val requestDate: (LocalDate) -> Unit = { requested ->
        onBroadcastDateChanged(GuideTimeline.clampDate(requested, earliestDate, latestDate))
    }
    var displayedDate by remember(broadcastDate) { mutableStateOf(broadcastDate) }
    var selected by remember { mutableStateOf<SelectedProgram?>(null) }
    var jumpToNowRequest by remember { mutableIntStateOf(0) }
    var isNowVisible by remember { mutableStateOf(true) }
    var extended by remember(selected?.program?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
        )
    val guideFocusRequester = remember { FocusRequester() }
    val toolbarFocusRequester = remember { FocusRequester() }
    var guideHasFocus by remember { mutableStateOf(false) }
    val currentLoadExtended by rememberUpdatedState(loadExtended)

    val jumpToNow: () -> Unit = {
        requestDate(currentBroadcastDate())
        jumpToNowRequest++
    }

    LaunchedEffect(selected?.program?.id) {
        val program = selected?.program ?: return@LaunchedEffect
        extended =
            try {
                currentLoadExtended(program.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                program.extended
            }
    }

    BackHandler(enabled = isTv && guideHasFocus) {
        toolbarFocusRequester.requestFocus()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (isTv) Modifier else Modifier.statusBarsPadding())) {
            GuideToolbar(
                date = displayedDate,
                types = channelTypes,
                selectedType = selectedType,
                isTv = isTv,
                onTypeSelected = onChannelTypeSelected,
                earliestDate = earliestDate,
                latestDate = latestDate,
                onDateChanged = requestDate,
                onJumpToNow = jumpToNow,
                toolbarFocusRequester = toolbarFocusRequester,
                guideFocusRequester = guideFocusRequester,
            )
            HorizontalDivider()
            key(selectedType) {
                VirtualizedGuide(
                    guide = guide,
                    rangeAnchorDate = broadcastDate,
                    windowStart = windowStart,
                    initialFocusInstant = initialFocusInstant,
                    availability = availability,
                    isTv = isTv,
                    loadLogo = loadLogo,
                    jumpToNowRequest = jumpToNowRequest,
                    onNowVisibilityChanged = { isNowVisible = it },
                    onVisibleDateChanged = { displayedDate = it },
                    onRangeAnchorChanged = requestDate,
                    onProgramSelected = { service, program -> selected = SelectedProgram(service, program) },
                    focusRequester = guideFocusRequester,
                    onFocusChanged = { guideHasFocus = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (isLoading && guide.programs.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        AnimatedVisibility(
            visible = !isTv && !isNowVisible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            ExtendedFloatingActionButton(
                onClick = jumpToNow,
                icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                text = { Text("現在へ", fontWeight = FontWeight.Bold) },
            )
        }
    }

    selected?.let { item ->
        if (isTv) {
            TvProgramDialog(item, extended, { selected = null }, onPlay)
        } else {
            ModalBottomSheet(
                onDismissRequest = { selected = null },
                sheetState = sheetState,
            ) {
                ProgramDetails(
                    item,
                    extended,
                    onPlay,
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.86f)
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideToolbar(
    date: LocalDate,
    types: List<ChannelType>,
    selectedType: ChannelType,
    isTv: Boolean,
    earliestDate: LocalDate?,
    latestDate: LocalDate?,
    onTypeSelected: (ChannelType) -> Unit,
    onDateChanged: (LocalDate) -> Unit,
    onJumpToNow: () -> Unit,
    toolbarFocusRequester: FocusRequester,
    guideFocusRequester: FocusRequester,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var datePickerExpanded by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("M/d (E)") }
    if (isTv) {
        TvGuideToolbar(
            date = date,
            formatter = formatter,
            types = types,
            selectedType = selectedType,
            earliestDate = earliestDate,
            latestDate = latestDate,
            onTypeSelected = onTypeSelected,
            onDateChanged = onDateChanged,
            onJumpToNow = onJumpToNow,
            toolbarFocusRequester = toolbarFocusRequester,
            guideFocusRequester = guideFocusRequester,
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = if (isTv) 40.dp else 12.dp, vertical = if (isTv) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 6.dp),
    ) {
        TextButton(
            onClick = { onDateChanged(date.minusDays(1)) },
            enabled = earliestDate == null || date > earliestDate,
        ) {
            Text("前日")
        }
        FilledTonalButton(
            onClick = { datePickerExpanded = true },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (date == LocalDate.now()) "今日 ${formatter.format(date)}" else formatter.format(date),
                maxLines = 1,
                fontWeight = FontWeight.Bold,
            )
        }
        TextButton(
            onClick = { onDateChanged(date.plusDays(1)) },
            enabled = latestDate == null || date < latestDate,
        ) {
            Text("翌日")
        }
        Box {
            Button(
                onClick = { menuExpanded = true },
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(selectedType.value, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                types.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.value) },
                        onClick = {
                            menuExpanded = false
                            onTypeSelected(type)
                        },
                    )
                }
            }
        }
    }
    if (datePickerExpanded) {
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates =
                    remember(earliestDate, latestDate) {
                        object : SelectableDates {
                            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                                val candidate =
                                    Instant
                                        .ofEpochMilli(utcTimeMillis)
                                        .atZone(ZoneOffset.UTC)
                                        .toLocalDate()
                                return (earliestDate == null || candidate >= earliestDate) &&
                                    (latestDate == null || candidate <= latestDate)
                            }

                            override fun isSelectableYear(year: Int): Boolean =
                                (earliestDate == null || year >= earliestDate.year) &&
                                    (latestDate == null || year <= latestDate.year)
                        }
                    },
            )
        DatePickerDialog(
            onDismissRequest = { datePickerExpanded = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateChanged(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        datePickerExpanded = false
                    },
                ) { Text("移動") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerExpanded = false }) { Text("キャンセル") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun TvGuideToolbar(
    date: LocalDate,
    formatter: DateTimeFormatter,
    types: List<ChannelType>,
    selectedType: ChannelType,
    earliestDate: LocalDate?,
    latestDate: LocalDate?,
    onTypeSelected: (ChannelType) -> Unit,
    onDateChanged: (LocalDate) -> Unit,
    onJumpToNow: () -> Unit,
    toolbarFocusRequester: FocusRequester,
    guideFocusRequester: FocusRequester,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val actionColors =
        androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color(0xFF263246),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF10131A),
            pressedContainerColor = Color(0xFFDDE7FF),
            pressedContentColor = Color(0xFF10131A),
            disabledContainerColor = Color(0xFF252932),
            disabledContentColor = Color(0xFFB8BEC9),
        )
    val selectedTypeColors =
        androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color(0xFFD6E2FF),
            contentColor = Color(0xFF101B30),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF10131A),
            pressedContainerColor = Color(0xFFB9CDFF),
            pressedContentColor = Color(0xFF10131A),
        )
    val typeColors =
        androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color(0xFF202A3A),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF10131A),
            pressedContainerColor = Color(0xFFDDE7FF),
            pressedContentColor = Color(0xFF10131A),
        )
    Row(
        Modifier
            .fillMaxWidth()
            .focusRestorer()
            .focusRequester(toolbarFocusRequester)
            .focusGroup()
            .onFocusChanged { hasFocus = it.hasFocus }
            .background(
                if (hasFocus) Color(0xFF303A50) else Color(0xFF151A23),
            ).padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.background(Color(0xFF0D121A), RoundedCornerShape(28.dp)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.tv.material3.Button(
                onClick = { onDateChanged(date.minusDays(1)) },
                enabled = earliestDate == null || date > earliestDate,
                colors = actionColors,
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    Modifier.height(36.dp).padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.tv.material3.Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    androidx.tv.material3.Text("前日")
                }
            }
            androidx.tv.material3.Text(
                text = if (date == LocalDate.now()) "今日 ${formatter.format(date)}" else formatter.format(date),
                modifier =
                    Modifier
                        .height(36.dp)
                        .padding(horizontal = 9.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                color = Color.White,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            )
            androidx.tv.material3.Button(
                onClick = { onDateChanged(date.plusDays(1)) },
                enabled = latestDate == null || date < latestDate,
                colors = actionColors,
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    Modifier.height(36.dp).padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.tv.material3.Text("翌日")
                    Spacer(Modifier.width(5.dp))
                    androidx.tv.material3.Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        androidx.tv.material3.Button(
            onClick = onJumpToNow,
            colors = actionColors,
            contentPadding = PaddingValues(0.dp),
        ) {
            Row(
                Modifier.height(36.dp).padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.tv.material3.Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                androidx.tv.material3.Text("現在へ")
            }
        }
        Spacer(Modifier.weight(1f))
        LazyRow(
            modifier = Modifier.widthIn(max = 560.dp).focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            items(types, key = { it.value }) { type ->
                val selected = type == selectedType
                androidx.tv.material3.Button(
                    onClick = { onTypeSelected(type) },
                    modifier = Modifier.focusProperties { down = guideFocusRequester },
                    colors = if (selected) selectedTypeColors else typeColors,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(
                        Modifier.height(36.dp).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { androidx.tv.material3.Text(type.value) }
                }
            }
        }
    }
}

@Composable
private fun VirtualizedGuide(
    guide: ProgramGuide,
    rangeAnchorDate: LocalDate,
    windowStart: Instant,
    initialFocusInstant: Instant,
    availability: ProgramGuideAvailability?,
    isTv: Boolean,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    jumpToNowRequest: Int,
    onNowVisibilityChanged: (Boolean) -> Unit,
    onVisibleDateChanged: (LocalDate) -> Unit,
    onRangeAnchorChanged: (LocalDate) -> Unit,
    onProgramSelected: (Service, Program) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep subchannels hidden during simulcast and expose them when the loaded guide
    // contains distinct programming (multi-channel operation).
    val services = guide.servicesWithDistinctProgramming
    var stationLogos by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    val logoKeys = remember(services) { services.map { it.id to it.logoId } }
    val currentLoadLogo by rememberUpdatedState(loadLogo)
    val currentOnNowVisibilityChanged by rememberUpdatedState(onNowVisibilityChanged)
    val currentOnVisibleDateChanged by rememberUpdatedState(onVisibleDateChanged)
    val currentOnRangeAnchorChanged by rememberUpdatedState(onRangeAnchorChanged)
    LaunchedEffect(logoKeys) {
        // Publish each result as it arrives. Waiting for the whole type makes every
        // station wait for one slow logo endpoint, which is especially noticeable for BS.
        supervisorScope {
            logoKeys.forEach { (serviceId, logoId) ->
                launch {
                    val bytes =
                        try {
                            currentLoadLogo(serviceId, logoId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                    val bitmap =
                        bytes?.let {
                            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    if (bitmap != null) stationLogos = stationLogos + (serviceId to bitmap)
                }
            }
        }
    }
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val timeRailPx = with(density) { (if (isTv) 56.dp else 52.dp).toPx() }
    val stationWidthPx = with(density) { (if (isTv) 216.dp else 216.dp).toPx() }
    val headerHeightPx = with(density) { (if (isTv) 52.dp else 54.dp).toPx() }
    val minuteHeightPx = with(density) { (if (isTv) 2.25.dp else 2.5.dp).toPx() }
    val stationGutterPx = with(density) { (if (isTv) 6.dp else 4.dp).toPx() }
    val totalHeightPx = GuideTimeline.minutesPerDay * GuideTimeline.windowDays * minuteHeightPx
    val windowEnd = remember(rangeAnchorDate, zone) { GuideTimeline.windowEnd(rangeAnchorDate, zone) }
    var horizontalOffset by remember(services) { mutableFloatStateOf(0f) }
    var verticalOffset by remember {
        mutableFloatStateOf(GuideTimeline.minutesPerDay * GuideTimeline.leadingDays * minuteHeightPx)
    }
    var previousWindowStart by remember { mutableStateOf(windowStart) }
    var pendingRebase by remember { mutableStateOf(false) }
    var requestedAnchorDate by remember { mutableStateOf<LocalDate?>(null) }
    var hasInitialPosition by remember { mutableStateOf(false) }
    var handledJumpRequest by remember { mutableIntStateOf(jumpToNowRequest) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var selectedStation by remember(services) { mutableIntStateOf(0) }
    var selectedProgram by remember(services) { mutableIntStateOf(0) }
    var hasInitialSelection by remember(services) { mutableStateOf(false) }
    // A window change is observed by composition one frame before LaunchedEffect can
    // commit the rebased state. Render with the rebased coordinate immediately so the
    // absolute time at the top of the viewport never jumps for that transitional frame.
    val renderVerticalOffset =
        if (windowStart != previousWindowStart && pendingRebase) {
            GuideTimeline.rebaseOffset(
                previousWindowStart,
                windowStart,
                verticalOffset,
                minuteHeightPx,
            )
        } else {
            verticalOffset
        }
    val now = Instant.now()
    val isNowInViewport =
        if (viewportHeight <= 0f) {
            true
        } else if (now < windowStart || now >= windowEnd) {
            false
        } else {
            val nowY = Duration.between(windowStart, now).toMinutes() * minuteHeightPx
            nowY >= renderVerticalOffset &&
                nowY <= renderVerticalOffset + viewportHeight - headerHeightPx
        }
    val visibleInstant =
        remember(windowStart, renderVerticalOffset, minuteHeightPx) {
            GuideTimeline.instantAtOffset(windowStart, renderVerticalOffset, minuteHeightPx)
        }
    val visibleDisplayDate =
        remember(visibleInstant, zone) {
            GuideTimeline.displayDateAt(visibleInstant, zone)
        }
    val visibleBroadcastDate =
        remember(visibleInstant, zone) {
            GuideTimeline.broadcastDateAt(visibleInstant, zone)
        }
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val colorScheme = MaterialTheme.colorScheme
    val hourDividerColor = colorScheme.outlineVariant.copy(alpha = 0.72f)
    val hourDividerHeight = with(density) { 1.dp.toPx() }
    val programOutlineWidth = with(density) { (if (isTv) 1.dp else 0.75.dp).toPx() }
    val stationDividerColor = colorScheme.outlineVariant
    val stationDividerWidth = with(density) { 1.dp.toPx() }
    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariantArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val genreContainers =
        remember(surfaceContainer) {
            // ARIB content genre (level 1). Stronger semantic hues are blended into the
            // active Material surface so they stay expressive in both light and dark themes.
            listOf(
                lerp(surfaceContainer, Color(0xFF3F67D7), 0.34f),
                lerp(surfaceContainer, Color(0xFF00A878), 0.36f),
                lerp(surfaceContainer, Color(0xFFF59E0B), 0.34f),
                lerp(surfaceContainer, Color(0xFFE64A6D), 0.36f),
                lerp(surfaceContainer, Color(0xFF8B5CF6), 0.34f),
                lerp(surfaceContainer, Color(0xFFF97316), 0.34f),
                lerp(surfaceContainer, Color(0xFF6366F1), 0.34f),
                lerp(surfaceContainer, Color(0xFF06A6C7), 0.34f),
                lerp(surfaceContainer, Color(0xFF168C72), 0.32f),
                lerp(surfaceContainer, Color(0xFFC241A7), 0.32f),
                lerp(surfaceContainer, Color(0xFF65A30D), 0.32f),
                lerp(surfaceContainer, Color(0xFF53789E), 0.30f),
            )
        }
    val currentHorizontalOffset by rememberUpdatedState(horizontalOffset)
    val currentVerticalOffset by rememberUpdatedState(renderVerticalOffset)
    val flingDecay = rememberSplineBasedDecay<Float>()
    val titlePaint =
        remember {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface =
                    android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        }.also { it.color = onSurfaceArgb }
    val secondaryPaint =
        remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
            .also { it.color = onSurfaceVariantArgb }
    val chromePaint =
        remember {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface =
                    android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        }.also { it.color = onSurfaceArgb }
    val chromeSecondaryPaint =
        remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
            .also { it.color = onSurfaceVariantArgb }

    fun maxHorizontal() = max(0f, services.size * stationWidthPx - (viewportWidth - timeRailPx))

    fun defaultMaxVertical() = max(0f, totalHeightPx - (viewportHeight - headerHeightPx))

    fun minVertical(): Float {
        val first = availability?.startAt ?: return 0f
        if (first <= windowStart) return 0f
        return GuideTimeline
            .offsetForInstant(windowStart, minOf(first, windowEnd), minuteHeightPx)
            .coerceIn(0f, defaultMaxVertical())
    }

    fun maxVertical(): Float {
        val last = availability?.endAt ?: return defaultMaxVertical()
        if (last >= windowEnd) return defaultMaxVertical()
        val contentHeight = (viewportHeight - headerHeightPx).coerceAtLeast(0f)
        val lastOffset =
            GuideTimeline.offsetForInstant(
                windowStart,
                maxOf(last, windowStart),
                minuteHeightPx,
            )
        return (lastOffset - contentHeight)
            .coerceIn(0f, defaultMaxVertical())
            .coerceAtLeast(minVertical())
    }

    fun keepSelectionVisible() {
        val stationLeft = selectedStation * stationWidthPx
        val stationRight = stationLeft + stationWidthPx
        val contentWidth = viewportWidth - timeRailPx
        if (stationLeft < horizontalOffset) horizontalOffset = stationLeft
        if (stationRight > horizontalOffset + contentWidth) horizontalOffset = stationRight - contentWidth
        val service = services.getOrNull(selectedStation) ?: return
        val program = guide.displaySchedule(service).getOrNull(selectedProgram) ?: return
        val top = Duration.between(windowStart, program.startAt).toMinutes() * minuteHeightPx
        val bottom = Duration.between(windowStart, program.endAt).toMinutes() * minuteHeightPx
        val contentHeight = viewportHeight - headerHeightPx
        if (top < verticalOffset) verticalOffset = top
        if (bottom > verticalOffset + contentHeight) verticalOffset = bottom - contentHeight
        horizontalOffset = horizontalOffset.coerceIn(0f, maxHorizontal())
        verticalOffset = verticalOffset.coerceIn(minVertical(), maxVertical())
    }

    fun moveVertical(delta: Int) {
        val schedule = services.getOrNull(selectedStation)?.let(guide::displaySchedule).orEmpty()
        selectedProgram = (selectedProgram + delta).coerceIn(0, (schedule.size - 1).coerceAtLeast(0))
        keepSelectionVisible()
    }

    fun moveHorizontal(delta: Int) {
        val oldProgram = services.getOrNull(selectedStation)?.let(guide::displaySchedule)?.getOrNull(selectedProgram)
        selectedStation = (selectedStation + delta).coerceIn(0, (services.size - 1).coerceAtLeast(0))
        val schedule = services.getOrNull(selectedStation)?.let(guide::displaySchedule).orEmpty()
        val midpoint = oldProgram?.let { it.startAt.plusMillis(Duration.between(it.startAt, it.endAt).toMillis() / 2) }
        selectedProgram =
            if (midpoint == null) {
                0
            } else {
                schedule
                    .indexOfFirst {
                        midpoint >= it.startAt && midpoint < it.endAt
                    }.takeIf { it >= 0 } ?: schedule.indices.minByOrNull {
                    abs(Duration.between(midpoint, schedule[it].startAt).toMinutes())
                } ?: 0
            }
        keepSelectionVisible()
    }

    LaunchedEffect(isNowInViewport) {
        currentOnNowVisibilityChanged(isNowInViewport)
    }
    LaunchedEffect(
        visibleDisplayDate,
        visibleBroadcastDate,
        rangeAnchorDate,
        availability,
        renderVerticalOffset,
        viewportHeight,
    ) {
        currentOnVisibleDateChanged(visibleDisplayDate)
        val preloadThreshold = GuideTimeline.minutesPerDay * minuteHeightPx * 0.35f
        val nearWindowEdge =
            viewportHeight > 0f && (
                renderVerticalOffset < preloadThreshold ||
                    renderVerticalOffset > maxVertical() - preloadThreshold
            )
        val earliestAnchor = availability?.let { GuideTimeline.broadcastDateAt(it.startAt, zone) }
        val latestAnchor =
            availability?.let {
                GuideTimeline.broadcastDateAt(it.endAt.minusMillis(1), zone)
            }
        val nextAnchor =
            GuideTimeline.clampDate(
                visibleBroadcastDate,
                earliestAnchor,
                latestAnchor,
            )
        if (
            nearWindowEdge &&
            nextAnchor != rangeAnchorDate &&
            requestedAnchorDate != nextAnchor &&
            !pendingRebase
        ) {
            pendingRebase = true
            requestedAnchorDate = nextAnchor
            currentOnRangeAnchorChanged(nextAnchor)
        } else if (nextAnchor == rangeAnchorDate) {
            requestedAnchorDate = null
        }
    }

    LaunchedEffect(windowStart, viewportHeight, jumpToNowRequest) {
        if (viewportHeight <= 0f) return@LaunchedEffect
        val windowChanged = windowStart != previousWindowStart
        val jumpChanged = jumpToNowRequest != handledJumpRequest
        if (windowChanged && pendingRebase) {
            verticalOffset =
                GuideTimeline
                    .rebaseOffset(
                        previousWindowStart,
                        windowStart,
                        verticalOffset,
                        minuteHeightPx,
                    ).coerceIn(minVertical(), maxVertical())
            pendingRebase = false
        } else if (windowChanged || !hasInitialPosition || jumpChanged) {
            val focusInstant = if (jumpChanged) Instant.now() else initialFocusInstant
            if (focusInstant >= windowStart && focusInstant < windowEnd) {
                val focusY = GuideTimeline.offsetForInstant(windowStart, focusInstant, minuteHeightPx)
                val isCurrentFocus = abs(Duration.between(focusInstant, Instant.now()).seconds) < 60
                val viewportBias = if (isCurrentFocus) 0.32f else 0.08f
                verticalOffset =
                    (focusY - viewportHeight * viewportBias)
                        .coerceIn(minVertical(), maxVertical())
            }
        }
        previousWindowStart = windowStart
        handledJumpRequest = jumpToNowRequest
        hasInitialPosition = true
    }
    LaunchedEffect(availability, viewportHeight, windowStart) {
        if (viewportHeight > 0f) {
            verticalOffset = verticalOffset.coerceIn(minVertical(), maxVertical())
        }
    }
    LaunchedEffect(isTv, services) {
        if (isTv && services.isNotEmpty()) focusRequester.requestFocus()
    }
    LaunchedEffect(isTv, services, guide.programs) {
        if (isTv && !hasInitialSelection && services.isNotEmpty()) {
            val schedule = guide.displaySchedule(services[selectedStation])
            if (schedule.isNotEmpty()) {
                selectedProgram = GuideTimeline.programIndexAt(schedule, initialFocusInstant)
                hasInitialSelection = true
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(background)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (!isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        moveVertical(-1)
                        true
                    }

                    Key.DirectionDown -> {
                        moveVertical(1)
                        true
                    }

                    Key.DirectionLeft -> {
                        moveHorizontal(-1)
                        true
                    }

                    Key.DirectionRight -> {
                        moveHorizontal(1)
                        true
                    }

                    Key.Enter, Key.DirectionCenter -> {
                        val service = services.getOrNull(selectedStation)
                        val program = service?.let(guide::displaySchedule)?.getOrNull(selectedProgram)
                        if (service != null && program != null) onProgramSelected(service, program)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }.focusable(isTv)
            .pointerInput(guide, services) {
                detectTapGestures { tap ->
                    if (tap.x < timeRailPx || tap.y < headerHeightPx) return@detectTapGestures
                    val stationIndex = floor((tap.x - timeRailPx + currentHorizontalOffset) / stationWidthPx).toInt()
                    val minute = (tap.y - headerHeightPx + currentVerticalOffset) / minuteHeightPx
                    val instant = windowStart.plusSeconds((minute * 60).toLong())
                    val service = services.getOrNull(stationIndex) ?: return@detectTapGestures
                    val program =
                        guide
                            .displaySchedule(service)
                            .firstOrNull { instant >= it.startAt && instant < it.endAt }
                            ?: return@detectTapGestures
                    onProgramSelected(service, program)
                }
            }.pointerInput(windowStart, guide, services, availability, viewportWidth, viewportHeight) {
                coroutineScope {
                    val velocityTracker = VelocityTracker()
                    var horizontalFling: Job? = null
                    var verticalFling: Job? = null
                    detectDragGestures(
                        onDragStart = {
                            horizontalFling?.cancel()
                            verticalFling?.cancel()
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity()
                            horizontalFling =
                                launch {
                                    AnimationState(
                                        initialValue = currentHorizontalOffset,
                                        initialVelocity = -velocity.x,
                                    ).animateDecay(flingDecay) {
                                        val bounded = value.coerceIn(0f, maxHorizontal())
                                        horizontalOffset = bounded
                                        if (bounded != value) cancelAnimation()
                                    }
                                }
                            verticalFling =
                                launch {
                                    AnimationState(
                                        initialValue = currentVerticalOffset,
                                        initialVelocity = -velocity.y,
                                    ).animateDecay(flingDecay) {
                                        val bounded = value.coerceIn(minVertical(), maxVertical())
                                        verticalOffset = bounded
                                        if (bounded != value) cancelAnimation()
                                    }
                                }
                        },
                        onDragCancel = {
                            velocityTracker.resetTracking()
                        },
                    ) { change, drag ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        horizontalOffset = (horizontalOffset - drag.x).coerceIn(0f, maxHorizontal())
                        verticalOffset =
                            (verticalOffset - drag.y)
                                .coerceIn(minVertical(), maxVertical())
                    }
                }
            },
    ) {
        viewportWidth = size.width
        viewportHeight = size.height
        drawRect(surface)
        val firstStation = floor(horizontalOffset / stationWidthPx).toInt().coerceAtLeast(0)
        val lastStation =
            ceil((horizontalOffset + size.width - timeRailPx) / stationWidthPx)
                .toInt()
                .coerceAtMost(services.size)
        val visibleStartMinutes = floor(renderVerticalOffset / minuteHeightPx).toLong().coerceAtLeast(0)
        val visibleEndMinutes =
            ceil((renderVerticalOffset + size.height - headerHeightPx) / minuteHeightPx)
                .toLong()
                .coerceAtMost((GuideTimeline.minutesPerDay * GuideTimeline.windowDays).toLong())
        val visibleStart = windowStart.plusSeconds(visibleStartMinutes * 60)
        val visibleEnd = windowStart.plusSeconds(visibleEndMinutes * 60)
        clipRect(left = timeRailPx, top = headerHeightPx) {
            for (stationIndex in firstStation until lastStation) {
                val service = services[stationIndex]
                val left = timeRailPx + stationIndex * stationWidthPx - horizontalOffset
                drawRect(Color.White.copy(alpha = 0.08f), Offset(left, headerHeightPx), Size(1f, size.height))
                guide.displaySchedule(service).forEachIndexed { programIndex, program ->
                    if (program.endAt <= visibleStart || program.startAt >= visibleEnd) return@forEachIndexed
                    val clippedStart = maxOf(program.startAt, windowStart)
                    val top =
                        headerHeightPx + Duration.between(windowStart, clippedStart).toMinutes() * minuteHeightPx -
                            renderVerticalOffset
                    val durationMinutes =
                        Duration
                            .between(
                                clippedStart,
                                minOf(program.endAt, windowEnd),
                            ).toMinutes()
                            .coerceAtLeast(1)
                    val cardHeight = durationMinutes * minuteHeightPx
                    val cardLeft = left + stationGutterPx / 2f
                    val cardWidth = stationWidthPx - stationGutterPx
                    val selectedCard = isTv && stationIndex == selectedStation && programIndex == selectedProgram
                    val cardColor = genreContainers.getOrElse(program.primaryGenre?.level1 ?: -1) { surfaceContainer }
                    val cardOutlineColor = lerp(cardColor, colorScheme.onSurface, 0.22f)
                    drawRoundRect(
                        cardColor,
                        Offset(cardLeft, top),
                        Size(cardWidth, cardHeight.coerceAtLeast(1f)),
                        CornerRadius.Zero,
                    )
                    drawRect(
                        cardOutlineColor,
                        Offset(cardLeft + programOutlineWidth / 2f, top + programOutlineWidth / 2f),
                        Size(
                            (cardWidth - programOutlineWidth).coerceAtLeast(1f),
                            (cardHeight - programOutlineWidth).coerceAtLeast(1f),
                        ),
                        style = Stroke(programOutlineWidth),
                    )
                    if (selectedCard) {
                        drawRoundRect(
                            colorScheme.onSurface,
                            Offset(cardLeft, top),
                            Size(cardWidth, cardHeight.coerceAtLeast(1f)),
                            CornerRadius.Zero,
                            style = Stroke(with(density) { 3.dp.toPx() }),
                        )
                    }
                    if (cardHeight >= with(density) { 24.dp.toPx() }) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(cardLeft, top, cardLeft + cardWidth, top + cardHeight)
                            titlePaint.textSize = with(density) { 14.sp.toPx() }
                            val inset = with(density) { 8.dp.toPx() }
                            val cardBottom = top + cardHeight
                            val titleLineHeight = with(density) { 18.dp.toPx() }
                            val titleLines = if (cardHeight >= with(density) { 58.dp.toPx() }) 2 else 1
                            var nextBaseline =
                                drawWrappedText(
                                    native,
                                    program.title,
                                    cardLeft + inset,
                                    top + with(density) { (if (isTv) 18.dp else 17.dp).toPx() },
                                    cardWidth - inset * 2,
                                    titlePaint,
                                    titleLines,
                                    titleLineHeight,
                                )
                            if (nextBaseline + with(density) { 12.dp.toPx() } < cardBottom) {
                                secondaryPaint.textSize = with(density) { 11.sp.toPx() }
                                native.drawText(
                                    "${timeFormatter.format(program.startAt)}–${timeFormatter.format(program.endAt)}",
                                    cardLeft + inset,
                                    nextBaseline,
                                    secondaryPaint,
                                )
                                nextBaseline += with(density) { 15.dp.toPx() }
                            }
                            if (program.description.isNotBlank() &&
                                nextBaseline + with(density) { 12.dp.toPx() } < cardBottom
                            ) {
                                secondaryPaint.textSize = with(density) { 10.sp.toPx() }
                                val descriptionLineHeight = with(density) { 14.dp.toPx() }
                                val lines =
                                    floor(
                                        (
                                            cardBottom - nextBaseline -
                                                with(
                                                    density,
                                                ) { 4.dp.toPx() }
                                        ) / descriptionLineHeight,
                                    ).toInt()
                                        .coerceIn(0, 4)
                                if (lines > 0) {
                                    drawWrappedText(
                                        native,
                                        program.description,
                                        cardLeft + inset,
                                        nextBaseline,
                                        cardWidth - inset * 2,
                                        secondaryPaint,
                                        lines,
                                        descriptionLineHeight,
                                    )
                                }
                            }
                            native.restore()
                        }
                    }
                }
            }
            if (now >= windowStart && now < windowEnd) {
                val y =
                    headerHeightPx + Duration.between(windowStart, now).toMinutes() * minuteHeightPx -
                        renderVerticalOffset
                drawRect(
                    Color(0xFFFF7696),
                    Offset(timeRailPx, y),
                    Size(size.width - timeRailPx, with(density) { 2.dp.toPx() }),
                )
            }
        }

        drawRect(surfaceContainer, Offset(timeRailPx, 0f), Size(size.width - timeRailPx, headerHeightPx))
        clipRect(left = timeRailPx, top = 0f, bottom = headerHeightPx) {
            for (stationIndex in firstStation until lastStation) {
                val service = services[stationIndex]
                val left = timeRailPx + stationIndex * stationWidthPx - horizontalOffset
                val logo = stationLogos[service.id]
                drawIntoCanvas { canvas ->
                    chromePaint.textSize = with(density) { (if (isTv) 14.sp else 13.sp).toPx() }
                    val inset = with(density) { 12.dp.toPx() }
                    val logoBoxWidth = with(density) { 44.dp.toPx() }
                    val logoBoxHeight = with(density) { 30.dp.toPx() }
                    val logoGap = with(density) { 8.dp.toPx() }
                    val textX =
                        if (logo != null) {
                            val scale = minOf(logoBoxWidth / logo.width, logoBoxHeight / logo.height)
                            val width = logo.width * scale
                            val height = logo.height * scale
                            val logoTop = (headerHeightPx - height) / 2f
                            canvas.nativeCanvas.drawBitmap(
                                logo,
                                null,
                                RectF(left + inset, logoTop, left + inset + width, logoTop + height),
                                null,
                            )
                            left + inset + logoBoxWidth + logoGap
                        } else {
                            left + inset
                        }
                    val channelGap = with(density) { 8.dp.toPx() }
                    drawEllipsized(
                        canvas.nativeCanvas,
                        service.name,
                        textX,
                        headerHeightPx * 0.48f,
                        left + stationWidthPx - inset - textX,
                        chromePaint,
                    )
                    chromeSecondaryPaint.textSize = with(density) { (if (isTv) 11.sp else 10.sp).toPx() }
                    val channelBaseline = headerHeightPx * 0.78f
                    var channelX = textX
                    canvas.nativeCanvas.drawText(
                        service.channelType.value,
                        channelX,
                        channelBaseline,
                        chromeSecondaryPaint,
                    )
                    channelX += chromeSecondaryPaint.measureText(service.channelType.value) + channelGap
                    chromePaint.textSize = with(density) { (if (isTv) 13.sp else 12.sp).toPx() }
                    canvas.nativeCanvas.drawText(service.logicalChannelNumber, channelX, channelBaseline, chromePaint)
                }
                drawRect(
                    stationDividerColor,
                    Offset(left, 0f),
                    Size(stationDividerWidth, headerHeightPx),
                )
            }
        }

        drawRect(surface, Offset.Zero, Size(timeRailPx, size.height))
        drawRect(surfaceContainer, Offset.Zero, Size(timeRailPx, headerHeightPx))
        drawIntoCanvas { canvas ->
            chromeSecondaryPaint.textSize = with(density) { (if (isTv) 12.sp else 11.sp).toPx() }
            canvas.nativeCanvas.drawText(
                "時刻",
                with(density) { 10.dp.toPx() },
                headerHeightPx * 0.62f,
                chromeSecondaryPaint,
            )
        }
        val firstHour = floor(visibleStartMinutes / 60f).toInt()
        val lastHour = ceil(visibleEndMinutes / 60f).toInt().coerceAtMost(24 * GuideTimeline.windowDays)
        clipRect(top = headerHeightPx) {
            for (hour in firstHour..lastHour) {
                val y = headerHeightPx + hour * 60 * minuteHeightPx - renderVerticalOffset
                drawRect(
                    hourDividerColor,
                    Offset(0f, y),
                    Size(timeRailPx, hourDividerHeight),
                )
                drawIntoCanvas { canvas ->
                    chromePaint.textSize = with(density) { 16.sp.toPx() }
                    canvas.nativeCanvas.drawText(
                        hourFormatter.format(windowStart.plus(Duration.ofHours(hour.toLong()))),
                        with(density) { 10.dp.toPx() },
                        y + with(density) { 22.dp.toPx() },
                        chromePaint,
                    )
                }
            }
        }
    }
}

private fun drawEllipsized(
    canvas: android.graphics.Canvas,
    text: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    paint: android.graphics.Paint,
) {
    val rendered =
        android.text.TextUtils
            .ellipsize(
                text,
                android.text.TextPaint(paint),
                maxWidth,
                android.text.TextUtils.TruncateAt.END,
            ).toString()
    canvas.drawText(rendered, x, y, paint)
}

private fun drawWrappedText(
    canvas: android.graphics.Canvas,
    text: String,
    x: Float,
    firstBaseline: Float,
    maxWidth: Float,
    paint: android.graphics.Paint,
    maxLines: Int,
    lineHeight: Float,
): Float {
    var remaining = text.trim()
    var baseline = firstBaseline
    repeat(maxLines) { line ->
        if (remaining.isEmpty()) return baseline
        val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
        val hasMore = count < remaining.length
        val source = if (line == maxLines - 1 && hasMore) remaining else remaining.take(count)
        val rendered =
            if (line == maxLines - 1 && hasMore) {
                android.text.TextUtils
                    .ellipsize(
                        source,
                        android.text.TextPaint(paint),
                        maxWidth,
                        android.text.TextUtils.TruncateAt.END,
                    ).toString()
            } else {
                source
            }
        canvas.drawText(rendered, x, baseline, paint)
        baseline += lineHeight
        remaining = remaining.drop(count).trimStart()
    }
    return baseline
}

@Composable
private fun TvProgramDialog(
    selected: SelectedProgram,
    extended: Map<String, String>,
    onDismiss: () -> Unit,
    onPlay: (Long) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.72f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
        ) {
            ProgramDetails(selected, extended, onPlay, Modifier.padding(36.dp))
        }
    }
}

@Composable
private fun ProgramDetails(
    selected: SelectedProgram,
    extended: Map<String, String>,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val program = selected.program
    val isOnAir = !Instant.now().isBefore(program.startAt) && Instant.now().isBefore(program.endAt)
    Column(modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(selected.service.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                "${selected.service.channelType.value}  ${selected.service.logicalChannelNumber}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(program.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${dateFormatter.format(
                    program.startAt,
                )}  ${timeFormatter.format(program.startAt)}–${timeFormatter.format(program.endAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isOnAir) {
                Button(
                    onClick = { onPlay(selected.service.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("視聴する") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (program.description.isNotBlank()) Text(program.description, style = MaterialTheme.typography.bodyLarge)
            extended.forEach { (heading, body) ->
                if (body.isNotBlank()) {
                    Text(heading, fontWeight = FontWeight.Bold)
                    Text(body)
                }
            }
        }
    }
}

private data class SelectedProgram(
    val service: Service,
    val program: Program,
)

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val hourFormatter = DateTimeFormatter.ofPattern("HH").withZone(ZoneId.systemDefault())
private val dateFormatter = DateTimeFormatter.ofPattern("M/d (E)").withZone(ZoneId.systemDefault())

private fun currentBroadcastDate(): LocalDate =
    LocalDate.now().let { date ->
        if (LocalTime.now().hour < GuideTimeline.broadcastDayStartHour) date.minusDays(1) else date
    }
