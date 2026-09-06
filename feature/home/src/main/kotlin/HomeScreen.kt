@file:Suppress("ktlint:standard:no-wildcard-imports")

package net.rokoucha.visiomata.home

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.Program
import net.rokoucha.visiomata.model.ServiceGroup
import net.rokoucha.visiomata.model.ServiceVariant
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun HandheldHomeScreen(
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    choices: List<ServiceGroup> = demoServiceChoices,
    channelTypes: List<ChannelType> = choices.map { it.channelType }.distinct(),
    loadLogo: suspend (Long, Int?) -> ByteArray? = { _, _ -> null },
    isLoadingChannel: Boolean = false,
    onSelectedType: (ChannelType) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    var selectedType by remember { mutableStateOf(channelTypes.firstOrNull()) }
    val currentOnSelectedType by rememberUpdatedState(onSelectedType)
    LaunchedEffect(channelTypes) {
        if (selectedType !in channelTypes) selectedType = channelTypes.firstOrNull()
    }
    LaunchedEffect(selectedType) {
        selectedType?.let(currentOnSelectedType)
    }
    val visibleChoices =
        remember(choices, selectedType) {
            choices.filter { it.channelType == selectedType }
        }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (headerHeightPx == 0 || available.y == 0f) return Offset.Zero
                    headerOffsetPx = (headerOffsetPx + available.y).coerceIn(-headerHeightPx.toFloat(), 0f)
                    return Offset.Zero
                }
            }
        }
    val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp
            val useSingleColumn = maxWidth < 600.dp
            Box(
                Modifier
                    .statusBarsPadding()
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
            ) {
                if (useSingleColumn) {
                    LazyColumn(
                        contentPadding =
                            PaddingValues(
                                start = horizontalPadding,
                                top = headerHeight,
                                end = horizontalPadding,
                                bottom = 32.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visibleChoices, key = { it.id }) { choice ->
                            HandheldServiceCard(choice, onPlay, loadLogo, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(300.dp),
                        contentPadding =
                            PaddingValues(
                                start = horizontalPadding,
                                top = headerHeight,
                                end = horizontalPadding,
                                bottom = 32.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(visibleChoices, key = { it.id }) { choice ->
                            HandheldServiceCard(choice, onPlay, loadLogo, Modifier.fillMaxWidth())
                        }
                    }
                }
                HomeHeader(
                    selectedType = selectedType,
                    types = channelTypes,
                    onSelected = { selectedType = it },
                    modifier =
                        Modifier
                            .zIndex(1f)
                            .offset { IntOffset(x = 0, y = headerOffsetPx.roundToInt()) }
                            .onSizeChanged { headerHeightPx = it.height },
                    horizontalPadding = horizontalPadding,
                )
                if (isLoadingChannel && !isRefreshing && visibleChoices.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/**
 * Loads only the logos needed by the selected channel type. Keeping this work above the lazy
 * lists avoids starting one untracked request from every card after the loading indicator ends.
 */
@Composable
private fun HomeHeader(
    selectedType: ChannelType?,
    types: List<ChannelType>,
    onSelected: (ChannelType) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = horizontalPadding, top = 24.dp, end = horizontalPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("放送を見る", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        ChannelTypeSelector(selectedType, types, onSelected)
    }
}

@Composable
private fun ChannelTypeSelector(
    selected: ChannelType?,
    types: List<ChannelType>,
    onSelected: (ChannelType) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { type ->
            FilterChip(selected = type == selected, onClick = { onSelected(type) }, label = { Text(type.value) })
        }
    }
}

@Composable
private fun HandheldServiceCard(
    choice: ServiceGroup,
    onPlay: (Long) -> Unit,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    var subServicesExpanded by remember(choice.id) { mutableStateOf(false) }
    Card(
        onClick = { onPlay(choice.primaryServiceId) },
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ServiceIdentity(choice, loadLogo)
            CurrentProgram(choice.current)
            choice.next?.let { next ->
                NextProgram(next) {
                    if (choice.variants.isNotEmpty()) {
                        SubServiceToggle(
                            count = choice.variants.size,
                            expanded = subServicesExpanded,
                            onClick = { subServicesExpanded = !subServicesExpanded },
                        )
                    }
                }
            }
            if (choice.next == null && choice.variants.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SubServiceToggle(
                        count = choice.variants.size,
                        expanded = subServicesExpanded,
                        onClick = { subServicesExpanded = !subServicesExpanded },
                    )
                }
            }
            AnimatedVisibility(visible = subServicesExpanded) {
                SubServiceList(choice.variants, choice.channelType, onPlay, loadLogo)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServiceIdentity(
    choice: ServiceGroup,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    focused: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StationLogo(
            choice.logoLabel,
            choice.channelType.fallbackColor,
            serviceId = choice.primaryServiceId,
            logoId = choice.logoId,
            loadLogo = loadLogo,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = choice.serviceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier,
            )
            ChannelIdentity(choice.channelType.value, choice.logicalChannelNumber)
        }
    }
}

@Composable
private fun ChannelIdentity(
    type: String,
    number: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StationLogo(
    label: String,
    color: Color,
    serviceId: Long,
    logoId: Int?,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    modifier: Modifier = Modifier.size(width = 58.dp, height = 38.dp),
) {
    val bitmap by produceState<ImageBitmap?>(null, serviceId, logoId, loadLogo) {
        value =
            withContext(Dispatchers.Default) {
                val bytes =
                    try {
                        loadLogo(serviceId, logoId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                bytes?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                }
            }
    }
    if (bitmap != null) {
        Image(bitmap!!, contentDescription = "$label ロゴ", modifier = modifier)
    } else {
        Box(
            modifier.clip(RoundedCornerShape(8.dp)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CurrentProgram(program: Program) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "放送中",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                program.timeRange(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            program.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (program.description.isNotBlank()) {
            Text(
                program.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { program.progress() },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun NextProgram(
    program: Program,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                "次",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                program.timeRange(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                program.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent()
    }
}

@Composable
private fun SubServiceToggle(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            if (expanded) "サブ ${count}件 ▲" else "サブ ${count}件 ▼",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SubServiceList(
    services: List<ServiceVariant>,
    channelType: ChannelType,
    onPlay: (Long) -> Unit,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        services.forEach { service ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPlay(service.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StationLogo(
                    label = service.logoLabel,
                    color = channelType.fallbackColor,
                    serviceId = service.id,
                    logoId = service.logoId,
                    loadLogo = loadLogo,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    service.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                ChannelIdentity(channelType.value, service.logicalChannelNumber)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    onPlay: (Long) -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    choices: List<ServiceGroup> = demoServiceChoices,
    channelTypes: List<ChannelType> = choices.map { it.channelType }.distinct(),
    loadLogo: suspend (Long, Int?) -> ByteArray? = { _, _ -> null },
    isLoadingChannel: Boolean = false,
) {
    val initialCardFocusRequester = remember { FocusRequester() }
    // Nested row prefetch can synchronously compose multiple rich TV cards while handling a
    // D-pad event. On lower-powered TV hardware that stalls focus dispatch for hundreds of ms;
    // compose the row only when it reaches the viewport instead.
    val columnState =
        rememberLazyListState(
            prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 0) },
        )
    var initialCardFocusRequested by remember { mutableStateOf(false) }
    val rows =
        remember(choices, channelTypes) {
            val choicesByType = choices.groupBy { it.channelType }
            channelTypes.map { type -> type to choicesByType[type].orEmpty() }
        }
    val firstChoiceId = rows.firstNotNullOfOrNull { it.second.firstOrNull()?.id }
    LaunchedEffect(firstChoiceId) {
        if (!initialCardFocusRequested && firstChoiceId != null) {
            withFrameNanos { }
            if (initialCardFocusRequester.requestFocus()) {
                initialCardFocusRequested = true
            }
        }
    }
    Column(
        modifier.fillMaxSize().padding(top = 52.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 64.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.tv.material3.Text(
                text = "放送を見る",
                modifier = Modifier.weight(1f),
                color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                style = androidx.tv.material3.MaterialTheme.typography.displaySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.tv.material3.Button(
                    onClick = onGuide,
                ) {
                    androidx.tv.material3.Text("番組表")
                }
                androidx.tv.material3.Button(
                    onClick = onSettings,
                ) {
                    androidx.tv.material3.Text("設定")
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TvPivotScroll {
                LazyColumn(
                    state = columnState,
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(
                        items = rows,
                        key = { (type, _) -> type.value },
                        contentType = { "channel-type-row" },
                    ) { (type, typeChoices) ->
                        TvChannelTypeRow(
                            type = type,
                            choices = typeChoices,
                            onPlay = onPlay,
                            loadLogo = loadLogo,
                            initialCardFocusRequester = initialCardFocusRequester,
                            isInitialRow = typeChoices.firstOrNull()?.id == firstChoiceId,
                        )
                    }
                }
            }
            if (isLoadingChannel && choices.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvPivotScroll(content: @Composable () -> Unit) {
    val bringIntoViewSpec =
        remember {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float {
                    // Bias the active row slightly below center so the previous row's card edge and
                    // the next row's heading can both remain visible on a 1080p TV.
                    val target = containerSize * 0.58f - size * 0.5f
                    return offset - target
                }
            }
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvChannelTypeRow(
    type: ChannelType,
    choices: List<ServiceGroup>,
    onPlay: (Long) -> Unit,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    initialCardFocusRequester: FocusRequester,
    isInitialRow: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.tv.material3.Text(
            text = type.value,
            modifier = Modifier.padding(horizontal = 64.dp),
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(
                items = choices,
                key = { _, choice -> choice.id },
                contentType = { _, _ -> "service-card" },
            ) { index, choice ->
                TvServiceCard(
                    choice = choice,
                    onPlay = onPlay,
                    loadLogo = loadLogo,
                    modifier =
                        if (isInitialRow && index == 0) {
                            Modifier.focusRequester(initialCardFocusRequester)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@Composable
private fun TvServiceCard(
    choice: ServiceGroup,
    onPlay: (Long) -> Unit,
    loadLogo: suspend (Long, Int?) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val cardShape = MaterialTheme.shapes.large
    val containerColor =
        if (focused) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    val contentColor =
        if (focused) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Column(
        modifier
            .width(256.dp)
            .height(168.dp)
            .clip(cardShape)
            .background(containerColor)
            .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, cardShape)
            .clickable(interactionSource = interaction, indication = null) { onPlay(choice.primaryServiceId) }
            .focusable(interactionSource = interaction)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            ServiceIdentity(choice, loadLogo, focused = focused)
            TvCurrentProgram(choice.current, focused)
            choice.next?.let {
                NextProgram(it)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCurrentProgram(
    program: Program,
    focused: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "放送中",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                program.timeRange(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            program.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee() else Modifier,
        )
        TvProgramProgress(program)
    }
}

@Composable
private fun TvProgramProgress(program: Program) {
    LinearProgressIndicator(
        progress = { program.progress() },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        drawStopIndicator = {},
    )
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun Program.timeRange() = "${timeFormatter.format(startAt)}–${timeFormatter.format(endAt)}"

private fun Program.progress(now: Instant = Instant.now()): Float {
    val total = Duration.between(startAt, endAt).toMillis().coerceAtLeast(1)
    return (Duration.between(startAt, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

private fun program(
    title: String,
    startOffsetMinutes: Long,
    durationMinutes: Long,
    description: String = "",
) = Program(
    id = 0,
    eventId = 0,
    networkId = 0,
    transportStreamId = null,
    serviceId = 0,
    title = title,
    description = description,
    startAt = Instant.now().plusSeconds(startOffsetMinutes * 60),
    endAt = Instant.now().plusSeconds((startOffsetMinutes + durationMinutes) * 60),
)

private fun choice(
    id: String,
    channelType: ChannelType,
    number: String,
    name: String,
    label: String,
    current: Program,
    next: Program?,
    variants: List<ServiceVariant> = emptyList(),
) = ServiceGroup(
    id,
    number.toLongOrNull() ?: 0,
    channelType,
    if (channelType.value == "GR") "${number}1".padStart(3, '0') else number.padStart(3, '0'),
    name,
    label,
    null,
    current,
    next,
    variants,
)

val demoServiceChoices =
    listOf(
        choice(
            "gr-nhk",
            ChannelType("GR"),
            "1",
            "NHK総合・東京",
            "NHK G",
            program("【土曜ドラマ】リラの花咲くけものみち2（2）", -19, 45, "家畜保健衛生所の講義で口てい疫について学ぶ聡里たち。"),
            program("秀サルでもわかる 豊臣兄弟！", 26, 5),
        ),
        choice(
            "gr-etv",
            ChannelType("GR"),
            "2",
            "NHK Eテレ東京",
            "NHK E",
            program("居場所を探す君へ LEX LANA 10代と紡ぐ歌", -19, 52, "孤独や生きづらさを抱える10代の声に向き合う。"),
            program("ふすま絵 奇跡の再会", 33, 3),
        ),
        choice(
            "gr-ntv",
            ChannelType("GR"),
            "4",
            "日テレ1",
            "日テレ",
            program("24時間テレビ49『わたしの家族の話』", -229, 454, "すべての子どもたちが夢を叶えられる社会に。"),
            program("24時間テレビ49", 225, 176),
        ),
        choice(
            "bs-nhk",
            ChannelType("BS"),
            "101",
            "NHK BS",
            "NHK BS",
            program("プレミアムシネマ", -39, 120),
            program("国際報道2026", 81, 50),
            listOf(ServiceVariant(101, "101", "BS"), ServiceVariant(102, "102", "サブ")),
        ),
        choice(
            "bs11",
            ChannelType("BS"),
            "211",
            "BS11イレブン",
            "BS11",
            program("報道ライブ インサイドOUT", -19, 54),
            program("Anison Days", 35, 30),
        ),
        choice(
            "cs-news",
            ChannelType("CS"),
            "349",
            "日テレNEWS24",
            "NEWS",
            program("Daily Planet", -19, 60),
            program("深層NEWS", 41, 60),
        ),
        choice(
            "sky-movie",
            ChannelType("SKY"),
            "601",
            "映画・チャンネルNECO",
            "NECO",
            program("邦画セレクション", -49, 120),
            program("名作ドラマ", 71, 60),
        ),
    )

private val ChannelType.fallbackColor: Color
    get() = Color.hsv((value.hashCode().toUInt().toLong() % 360).toFloat(), 0.55f, 0.65f)

@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Composable
private fun PhoneHomePreview() {
    MaterialTheme { HandheldHomeScreen(onPlay = {}) }
}

@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
@Composable
private fun TabletHomePreview() {
    MaterialTheme { HandheldHomeScreen(onPlay = {}) }
}

@Preview(name = "TV", device = Devices.TV_1080p, showBackground = true)
@Composable
private fun TvHomePreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        androidx.tv.material3.MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                TvHomeScreen(onPlay = {}, onGuide = {}, onSettings = {})
            }
        }
    }
}
