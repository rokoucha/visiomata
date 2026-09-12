@file:Suppress("ktlint:standard:no-wildcard-imports")

package net.rokoucha.visiomata.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.rokoucha.visiomata.settings.data.AuthenticationType
import net.rokoucha.visiomata.settings.data.AvcDecoderMode
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import net.rokoucha.visiomata.settings.data.Mpeg2PlaybackMode
import net.rokoucha.visiomata.settings.data.requiresDeviceMpeg2Decoder

private enum class TvSettingsCategory { Mirakurun, VideoPlayer, DataBroadcasting, VersionInfo, Licenses }

private enum class TvSettingsPane { Categories, Detail }

private fun Modifier.returnFocusTo(requester: FocusRequester): Modifier = focusProperties { left = requester }

@Composable
fun TvSettingsScreen(
    settings: MirakurunSettings,
    onSettingsChange: (MirakurunSettings) -> Unit,
    onConnectionSettingsChange: (MirakurunSettings) -> Unit = onSettingsChange,
    connectionState: MirakurunConnectionUiState = MirakurunConnectionUiState(),
    isRefreshingGuide: Boolean = false,
    onCheckConnection: (MirakurunSettings) -> Unit = {},
    onRefreshGuide: (MirakurunSettings) -> Unit = {},
    libraries: List<LibraryLicenseUiModel> = emptyList(),
    versionInfo: AppVersionInfo = AppVersionInfo("Visiomata", "1.0", 1),
    onSendFeedback: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var category by rememberSaveable { mutableStateOf(TvSettingsCategory.Mirakurun) }
    var activePane by remember { mutableStateOf(TvSettingsPane.Categories) }
    var selectedLibrary by remember { mutableStateOf<LibraryLicenseUiModel?>(null) }
    var lastFocusedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailFocusRequest by remember { mutableIntStateOf(0) }
    var isDetailFocusPending by remember { mutableStateOf(false) }
    val licenseListState = rememberLazyListState()
    val mirakurunCategoryFocusRequester = remember { FocusRequester() }
    val videoCategoryFocusRequester = remember { FocusRequester() }
    val dataCategoryFocusRequester = remember { FocusRequester() }
    val licensesCategoryFocusRequester = remember { FocusRequester() }
    val versionInfoCategoryFocusRequester = remember { FocusRequester() }
    val detailFocusRequester = remember { FocusRequester() }

    fun categoryFocusRequester() =
        when (category) {
            TvSettingsCategory.Mirakurun -> mirakurunCategoryFocusRequester
            TvSettingsCategory.VideoPlayer -> videoCategoryFocusRequester
            TvSettingsCategory.DataBroadcasting -> dataCategoryFocusRequester
            TvSettingsCategory.VersionInfo -> versionInfoCategoryFocusRequester
            TvSettingsCategory.Licenses -> licensesCategoryFocusRequester
        }

    fun showCategory(next: TvSettingsCategory) {
        category = next
        selectedLibrary = null
    }

    fun onCategoryFocused(next: TvSettingsCategory) {
        if (isDetailFocusPending) return
        activePane = TvSettingsPane.Categories
        showCategory(next)
    }

    fun enterDetail() {
        activePane = TvSettingsPane.Detail
        isDetailFocusPending = true
        detailFocusRequest++
    }
    LaunchedEffect(detailFocusRequest) {
        val request = detailFocusRequest
        if (request > 0) {
            if (category == TvSettingsCategory.Licenses) {
                val targetIndex =
                    lastFocusedLibraryId
                        ?.let { id -> libraries.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 }
                        ?: 0
                licenseListState.scrollToItem(targetIndex)
            }
            withFrameNanos { }
            if (isDetailFocusPending && request == detailFocusRequest) {
                detailFocusRequester.requestFocus()
                isDetailFocusPending = false
            }
        }
    }
    BackHandler {
        if (activePane == TvSettingsPane.Detail || isDetailFocusPending) {
            isDetailFocusPending = false
            activePane = TvSettingsPane.Categories
            categoryFocusRequester().requestFocus()
        } else {
            onBack()
        }
    }
    Row(modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 48.dp)) {
        Column(
            Modifier
                .width(340.dp)
                .fillMaxHeight(),
        ) {
            Text("設定", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryLabel("接続")
                TvCategoryItem(
                    "Mirakurun",
                    when (connectionState.status) {
                        MirakurunConnectionStatus.Connected -> connectionState.serverInfo ?: "接続済み"
                        MirakurunConnectionStatus.Checking -> "接続確認中…"
                        MirakurunConnectionStatus.Error -> "接続エラー"
                        MirakurunConnectionStatus.NotConfigured -> settings.url.ifBlank { "未設定" }
                    },
                    category == TvSettingsCategory.Mirakurun,
                    modifier = Modifier.focusRequester(mirakurunCategoryFocusRequester),
                    onFocused = { onCategoryFocused(TvSettingsCategory.Mirakurun) },
                ) {
                    showCategory(TvSettingsCategory.Mirakurun)
                    enterDetail()
                }
                CategoryLabel("視聴")
                TvCategoryItem(
                    "動画プレイヤー",
                    "${settings.mpeg2PlaybackMode.tvSummary()}・" +
                        if (settings.deinterlaceEnabled) "デインターレース有効" else "デインターレース無効",
                    category == TvSettingsCategory.VideoPlayer,
                    modifier = Modifier.focusRequester(videoCategoryFocusRequester),
                    onFocused = { onCategoryFocused(TvSettingsCategory.VideoPlayer) },
                ) {
                    showCategory(TvSettingsCategory.VideoPlayer)
                    enterDetail()
                }
                TvCategoryItem(
                    "データ放送",
                    if (settings.dataBroadcastingEnabled) "有効" else "無効",
                    category == TvSettingsCategory.DataBroadcasting,
                    modifier = Modifier.focusRequester(dataCategoryFocusRequester),
                    onFocused = { onCategoryFocused(TvSettingsCategory.DataBroadcasting) },
                ) {
                    showCategory(TvSettingsCategory.DataBroadcasting)
                    enterDetail()
                }
                CategoryLabel("アプリについて")
                TvCategoryItem(
                    "バージョン情報",
                    versionInfo.versionName,
                    category == TvSettingsCategory.VersionInfo,
                    modifier = Modifier.focusRequester(versionInfoCategoryFocusRequester),
                    onFocused = { onCategoryFocused(TvSettingsCategory.VersionInfo) },
                ) {
                    showCategory(TvSettingsCategory.VersionInfo)
                    if (onSendFeedback != null) enterDetail()
                }
                TvCategoryItem(
                    "オープンソースライセンス",
                    "${libraries.size}件のライブラリ",
                    category == TvSettingsCategory.Licenses,
                    modifier = Modifier.focusRequester(licensesCategoryFocusRequester),
                    onFocused = { onCategoryFocused(TvSettingsCategory.Licenses) },
                ) {
                    showCategory(TvSettingsCategory.Licenses)
                    enterDetail()
                }
            }
        }
        VerticalDivider(Modifier.padding(horizontal = 40.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged {
                    if (it.hasFocus) activePane = TvSettingsPane.Detail
                }.focusGroup(),
        ) {
            Text(
                if (category ==
                    TvSettingsCategory.Licenses
                ) {
                    selectedLibrary?.name ?: category.title
                } else {
                    category.title
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (category == TvSettingsCategory.Licenses) {
                val library = selectedLibrary
                if (library == null) {
                    TvLicensesSettings(
                        libraries = libraries,
                        listState = licenseListState,
                        restoreFocusToLibraryId = lastFocusedLibraryId,
                        firstFocusRequester = detailFocusRequester,
                        categoryFocusRequester = licensesCategoryFocusRequester,
                        onLibraryClick = {
                            lastFocusedLibraryId = it.id
                            selectedLibrary = it
                            enterDetail()
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    TvLicenseDetail(
                        library = library,
                        onBack = {
                            selectedLibrary = null
                            enterDetail()
                        },
                        firstFocusRequester = detailFocusRequester,
                        categoryFocusRequester = licensesCategoryFocusRequester,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp),
                ) {
                    when (category) {
                        TvSettingsCategory.Mirakurun -> {
                            TvMirakurunSettings(
                                settings,
                                onConnectionSettingsChange,
                                connectionState,
                                isRefreshingGuide,
                                onCheckConnection,
                                onRefreshGuide,
                                detailFocusRequester,
                                mirakurunCategoryFocusRequester,
                            )
                        }

                        TvSettingsCategory.VideoPlayer -> {
                            TvVideoPlayerSettings(
                                settings,
                                onSettingsChange,
                                detailFocusRequester,
                                videoCategoryFocusRequester,
                            )
                        }

                        TvSettingsCategory.DataBroadcasting -> {
                            TvDataBroadcastingSettings(
                                settings,
                                onSettingsChange,
                                detailFocusRequester,
                                dataCategoryFocusRequester,
                            )
                        }

                        TvSettingsCategory.VersionInfo -> {
                            TvVersionInfo(
                                versionInfo = versionInfo,
                                onSendFeedback = onSendFeedback,
                                firstFocusRequester = detailFocusRequester,
                                categoryFocusRequester = versionInfoCategoryFocusRequester,
                            )
                        }

                        TvSettingsCategory.Licenses -> {
                            Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvVersionInfo(
    versionInfo: AppVersionInfo,
    onSendFeedback: (() -> Unit)?,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Image(
            painter = painterResource(R.drawable.visiomata_logo),
            contentDescription = null,
            modifier = Modifier.size(128.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(versionInfo.appName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("バージョン ${versionInfo.versionName}", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "ビルド ${versionInfo.versionCode}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onSendFeedback != null) {
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = onSendFeedback,
                modifier =
                    Modifier
                        .focusRequester(firstFocusRequester)
                        .returnFocusTo(categoryFocusRequester),
                colors = tvClickableSurfaceColors(),
            ) {
                Text(
                    "フィードバックを送信",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private val TvSettingsCategory.title: String
    get() =
        when (this) {
            TvSettingsCategory.Mirakurun -> "Mirakurun"
            TvSettingsCategory.VideoPlayer -> "動画プレイヤー"
            TvSettingsCategory.DataBroadcasting -> "データ放送"
            TvSettingsCategory.VersionInfo -> "バージョン情報"
            TvSettingsCategory.Licenses -> "オープンソースライセンス"
        }

@Composable
private fun TvLicensesSettings(
    libraries: List<LibraryLicenseUiModel>,
    listState: LazyListState,
    restoreFocusToLibraryId: String?,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
    onLibraryClick: (LibraryLicenseUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(vertical = 20.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(libraries, key = { it.id }) { library ->
            Surface(
                onClick = { onLibraryClick(library) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .returnFocusTo(categoryFocusRequester)
                        .then(
                            if (
                                library.id == restoreFocusToLibraryId ||
                                (restoreFocusToLibraryId == null && library == libraries.firstOrNull())
                            ) {
                                Modifier.focusRequester(firstFocusRequester)
                            } else {
                                Modifier
                            },
                        ),
                colors = tvClickableSurfaceColors(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text(library.name, style = MaterialTheme.typography.titleMedium)
                    SupportingText(
                        listOfNotNull(
                            library.version,
                            library.licenses.joinToString { it.name }.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvLicenseDetail(
    library: LibraryLicenseUiModel,
    onBack: () -> Unit,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val pages =
        remember(library) {
            library.licenses
                .flatMap { license ->
                    val content = license.content ?: "ライセンス本文は提供されていません。"
                    content.chunkForTv().map { page -> license to page }
                }.ifEmpty {
                    listOf(LicenseUiModel("ライセンス情報なし", null, null) to "ライセンス情報は提供されていません。")
                }
        }
    var pageIndex by rememberSaveable(library.id) { mutableIntStateOf(0) }
    Column(modifier.padding(vertical = 20.dp).widthIn(max = 600.dp)) {
        Surface(
            onClick = onBack,
            colors = tvClickableSurfaceColors(),
            modifier =
                Modifier
                    .focusRequester(firstFocusRequester)
                    .returnFocusTo(categoryFocusRequester),
        ) {
            Text("ライブラリ一覧に戻る", modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        library.version?.let {
            Spacer(Modifier.height(24.dp))
            SupportingText("バージョン $it")
        }
        library.website?.let {
            Spacer(Modifier.height(8.dp))
            SupportingText(it)
        }
        val (license, page) = pages[pageIndex]
        Spacer(Modifier.height(24.dp))
        Text(license.name, style = MaterialTheme.typography.titleMedium)
        license.url?.let {
            Spacer(Modifier.height(6.dp))
            SupportingText(it)
        }
        Spacer(Modifier.height(14.dp))
        Text(page, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                enabled = pageIndex > 0,
                onClick = { pageIndex-- },
                colors = tvClickableSurfaceColors(),
                modifier = Modifier.returnFocusTo(categoryFocusRequester),
            ) {
                Text("前へ", modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
            }
            SupportingText("${pageIndex + 1} / ${pages.size}")
            Surface(
                enabled = pageIndex < pages.lastIndex,
                onClick = { pageIndex++ },
                colors = tvClickableSurfaceColors(),
                modifier = Modifier.returnFocusTo(categoryFocusRequester),
            ) {
                Text("次へ", modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
            }
        }
    }
}

private fun String.chunkForTv(maxChars: Int = 700): List<String> {
    if (length <= maxChars) return listOf(this)
    val pages = mutableListOf<String>()
    var start = 0
    while (start < length) {
        val candidateEnd = (start + maxChars).coerceAtMost(length)
        val end =
            if (candidateEnd == length) {
                length
            } else {
                lastIndexOfAny(charArrayOf('\n', ' '), candidateEnd).takeIf { it > start } ?: candidateEnd
            }
        pages += substring(start, end).trim()
        start = end
        while (start < length && this[start].isWhitespace()) start++
    }
    return pages
}

@Composable
private fun TvVideoPlayerSettings(
    settings: MirakurunSettings,
    update: (MirakurunSettings) -> Unit,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
) {
    CategoryLabel("MPEG-2映像の再生方法")
    Spacer(Modifier.height(12.dp))
    val mpeg2DecoderPresent = rememberMpeg2DecoderPresent()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Mpeg2PlaybackMode.entries.forEachIndexed { index, mode ->
            // The direct-playback modes show audio only without a device decoder.
            val selectable = !mode.requiresDeviceMpeg2Decoder || mpeg2DecoderPresent != false
            Surface(
                selected = settings.mpeg2PlaybackMode == mode,
                onClick = { update(settings.copy(mpeg2PlaybackMode = mode)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .returnFocusTo(categoryFocusRequester)
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier),
                enabled = selectable,
                colors = tvSelectableSurfaceColors(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text(mode.tvTitle(), style = MaterialTheme.typography.titleMedium)
                    SupportingText(
                        if (selectable) {
                            mode.tvDescription()
                        } else {
                            "この端末にはMPEG-2デコーダーが無いため選択できません"
                        },
                    )
                }
            }
        }
    }
    if (mpeg2DecoderPresent == false && settings.mpeg2PlaybackMode.requiresDeviceMpeg2Decoder) {
        SupportingText("MPEG-2デコーダーの無い端末では映像が表示されず音声のみになります。「自動」に変更してください。")
    }
    Spacer(Modifier.height(28.dp))
    CategoryLabel("H.264デコーダー")
    Spacer(Modifier.height(12.dp))
    val avcSoftwareDecoderPresent = rememberAvcSoftwareDecoderPresent()
    val avcDecoderSelectable =
        settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.Auto ||
            settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.ForceTranscode
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AvcDecoderMode.entries.forEach { mode ->
            // Forcing software decoding shows audio only without a software decoder.
            val selectable =
                avcDecoderSelectable &&
                    (mode != AvcDecoderMode.ForceSoftwareDecoder || avcSoftwareDecoderPresent != false)
            Surface(
                selected = settings.avcDecoderMode == mode,
                onClick = { update(settings.copy(avcDecoderMode = mode)) },
                modifier = Modifier.fillMaxWidth().returnFocusTo(categoryFocusRequester),
                enabled = selectable,
                colors = tvSelectableSurfaceColors(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text(mode.tvTitle(), style = MaterialTheme.typography.titleMedium)
                    SupportingText(
                        when {
                            !avcDecoderSelectable -> "H.264へ変換するときだけ使用します"
                            !selectable -> "この端末にはソフトウェアH.264デコーダーが無いため選択できません"
                            else -> mode.tvDescription()
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    CategoryLabel("デインターレース")
    Spacer(Modifier.height(12.dp))
    val canTranscode =
        settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.Auto ||
            settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.ForceTranscode
    Surface(
        selected = settings.deinterlaceEnabled,
        enabled = canTranscode,
        onClick = { update(settings.copy(deinterlaceEnabled = !settings.deinterlaceEnabled)) },
        modifier = Modifier.fillMaxWidth().returnFocusTo(categoryFocusRequester),
        colors = tvSelectableSurfaceColors(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                if (settings.deinterlaceEnabled) "有効" else "無効",
                style = MaterialTheme.typography.titleMedium,
            )
            SupportingText("mpeg2toh264でH.264へ変換するときだけ、単一レートBobを適用します")
        }
    }
    AutoSaveMessage()
}

private fun AvcDecoderMode.tvTitle(): String =
    when (this) {
        AvcDecoderMode.Auto -> "自動"
        AvcDecoderMode.ForceHardwareDecoder -> "ハードウェアデコード"
        AvcDecoderMode.ForceSoftwareDecoder -> "ソフトウェアデコード"
    }

private fun AvcDecoderMode.tvDescription(): String =
    when (this) {
        AvcDecoderMode.Auto -> "端末が選んだH.264デコーダーを使用します"
        AvcDecoderMode.ForceHardwareDecoder -> "ハードウェアH.264デコーダーを優先します"
        AvcDecoderMode.ForceSoftwareDecoder -> "ソフトウェアH.264デコーダーを優先します。CPU負荷が高まります"
    }

private fun Mpeg2PlaybackMode.tvTitle(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "自動"
        Mpeg2PlaybackMode.ForceTranscode -> "H.264へ変換"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "MPEG-2をソフトウェアデコード"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "MPEG-2をハードウェアデコード"
    }

private fun Mpeg2PlaybackMode.tvSummary(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "自動"
        Mpeg2PlaybackMode.ForceTranscode -> "H.264へ変換"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "ソフトウェアデコード"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "ハードウェアデコード"
    }

private fun Mpeg2PlaybackMode.tvDescription(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "ハードウェアMPEG-2デコーダーがあれば直接再生し、それ以外ではH.264へ変換します"
        Mpeg2PlaybackMode.ForceTranscode -> "端末の対応状況にかかわらずmpeg2toh264を使用します"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "mpeg2toh264を使用せず、ソフトウェアデコーダーだけを使用します"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "mpeg2toh264を使用せず、ハードウェアデコーダーだけを使用します"
    }

@Composable
private fun CategoryLabel(text: String) =
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )

@Composable
private fun TvCategoryItem(
    title: String,
    summary: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }.onFocusChanged {
                    if (it.isFocused) onFocused()
                },
        colors = tvSelectableSurfaceColors(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SupportingText(summary, maxLines = 1)
        }
    }
}

@Composable
private fun TvMirakurunSettings(
    settings: MirakurunSettings,
    update: (MirakurunSettings) -> Unit,
    connectionState: MirakurunConnectionUiState,
    isRefreshingGuide: Boolean,
    onCheckConnection: (MirakurunSettings) -> Unit,
    onRefreshGuide: (MirakurunSettings) -> Unit,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
) {
    var draft by remember { mutableStateOf(MirakurunConnectionDraft(settings)) }
    var savedDraft by remember { mutableStateOf(draft) }
    var urlWasFocused by remember { mutableStateOf(false) }
    var usernameWasFocused by remember { mutableStateOf(false) }
    var passwordWasFocused by remember { mutableStateOf(false) }
    var bearerTokenWasFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val currentSettings = { draft.applyTo(settings) }
    val save = {
        if (draft != savedDraft) {
            savedDraft = draft
            update(currentSettings())
        }
    }
    val done =
        KeyboardActions(onDone = {
            save()
            focusManager.clearFocus()
        })
    val isUrlInvalid = draft.url.isNotBlank() && !draft.url.isHttpUrl()
    CategoryLabel("接続先")
    Spacer(Modifier.height(12.dp))
    MaterialTextFieldTheme {
        OutlinedTextField(
            draft.url,
            { draft = draft.copy(url = it) },
            label = { androidx.compose.material3.Text("URL") },
            placeholder = { androidx.compose.material3.Text("http://192.168.1.10:40772") },
            supportingText = {
                androidx.compose.material3.Text(
                    if (isUrlInvalid) "http:// または https:// で始まるURLを入力してください" else "決定ボタンでソフトウェアキーボードを開きます",
                )
            },
            isError = isUrlInvalid,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = done,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(firstFocusRequester)
                    .onFocusChanged {
                        if (urlWasFocused && !it.isFocused) save()
                        urlWasFocused = it.isFocused
                    }.returnFocusTo(categoryFocusRequester),
        )
    }
    Spacer(Modifier.height(32.dp))
    CategoryLabel("認証")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AuthenticationType.entries.forEach { type ->
            Surface(
                selected = draft.authenticationType == type,
                onClick = {
                    draft = draft.copy(authenticationType = type)
                    save()
                },
                modifier = Modifier.weight(1f).returnFocusTo(categoryFocusRequester),
                colors = tvSelectableSurfaceColors(),
            ) {
                Text(
                    when (type) {
                        AuthenticationType.None -> "なし"
                        AuthenticationType.Basic -> "Basic認証"
                        AuthenticationType.Bearer -> "Bearerトークン"
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
        }
    }
    MaterialTextFieldTheme {
        when (draft.authenticationType) {
            AuthenticationType.None -> {}

            AuthenticationType.Basic -> {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    draft.username,
                    { draft = draft.copy(username = it) },
                    label = { androidx.compose.material3.Text("ユーザー名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = done,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (usernameWasFocused && !it.isFocused) save()
                                usernameWasFocused = it.isFocused
                            }.returnFocusTo(categoryFocusRequester),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    draft.password,
                    { draft = draft.copy(password = it) },
                    label = { androidx.compose.material3.Text("パスワード") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = done,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (passwordWasFocused && !it.isFocused) save()
                                passwordWasFocused = it.isFocused
                            }.returnFocusTo(categoryFocusRequester),
                )
            }

            AuthenticationType.Bearer -> {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    draft.bearerToken,
                    { draft = draft.copy(bearerToken = it) },
                    label = { androidx.compose.material3.Text("Bearerトークン") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = done,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (bearerTokenWasFocused && !it.isFocused) save()
                                bearerTokenWasFocused = it.isFocused
                            }.returnFocusTo(categoryFocusRequester),
                )
            }
        }
    }
    Spacer(Modifier.height(32.dp))
    CategoryLabel("接続状態")
    Spacer(Modifier.height(12.dp))
    Text(
        connectionState.message,
        color =
            if (connectionState.status == MirakurunConnectionStatus.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            enabled =
                draft.url.isNotBlank() &&
                    !isUrlInvalid &&
                    connectionState.status != MirakurunConnectionStatus.Checking,
            onClick = {
                val value = currentSettings()
                save()
                onCheckConnection(value)
            },
            modifier = Modifier.weight(1f).returnFocusTo(categoryFocusRequester),
            colors = tvClickableSurfaceColors(),
        ) {
            Text(
                if (connectionState.status == MirakurunConnectionStatus.Checking) "確認中…" else "接続を確認",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
        Surface(
            enabled = draft.url.isNotBlank() && !isUrlInvalid && !isRefreshingGuide,
            onClick = {
                val value = currentSettings()
                save()
                onRefreshGuide(value)
            },
            modifier = Modifier.weight(1f).returnFocusTo(categoryFocusRequester),
            colors = tvClickableSurfaceColors(),
        ) {
            Text(
                if (isRefreshingGuide) "再取得中…" else "番組表を再取得",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
    AutoSaveMessage()
}

@Composable
private fun TvDataBroadcastingSettings(
    settings: MirakurunSettings,
    update: (MirakurunSettings) -> Unit,
    firstFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
) {
    Surface(
        selected = settings.dataBroadcastingEnabled,
        onClick = { update(settings.copy(dataBroadcastingEnabled = !settings.dataBroadcastingEnabled)) },
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(firstFocusRequester)
                .returnFocusTo(categoryFocusRequester),
        colors = tvSelectableSurfaceColors(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("データ放送を表示", style = MaterialTheme.typography.titleMedium)
                SupportingText("番組に含まれるデータ放送を受信して表示します")
            }
            MaterialTextFieldTheme {
                androidx.compose.material3.Switch(settings.dataBroadcastingEnabled, null)
            }
        }
    }
    Spacer(Modifier.height(32.dp))
    CategoryLabel("取得方法")
    Spacer(Modifier.height(12.dp))
    Surface(
        selected = settings.useMahironDataBroadcastApi,
        enabled = settings.dataBroadcastingEnabled,
        onClick = {
            update(settings.copy(useMahironDataBroadcastApi = !settings.useMahironDataBroadcastApi))
        },
        modifier = Modifier.fillMaxWidth().returnFocusTo(categoryFocusRequester),
        colors = tvSelectableSurfaceColors(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mahiron APIを使用", style = MaterialTheme.typography.titleMedium)
                SupportingText("OFFにするとデバッグ用に受信TSを端末内でdemuxします")
            }
            MaterialTextFieldTheme {
                androidx.compose.material3.Switch(
                    settings.useMahironDataBroadcastApi,
                    null,
                    enabled = settings.dataBroadcastingEnabled,
                )
            }
        }
    }
    Spacer(Modifier.height(32.dp))
    CategoryLabel("通信機能")
    Spacer(Modifier.height(12.dp))
    Surface(
        selected = settings.dataBroadcastingInternetEnabled,
        enabled = settings.dataBroadcastingEnabled,
        onClick = {
            update(
                settings.copy(
                    dataBroadcastingInternetEnabled = !settings.dataBroadcastingInternetEnabled,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().returnFocusTo(categoryFocusRequester),
        colors = tvSelectableSurfaceColors(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("インターネット接続", style = MaterialTheme.typography.titleMedium)
                SupportingText("放送局の通信コンテンツへの接続を許可します（証明書検証なし）")
            }
            MaterialTextFieldTheme {
                androidx.compose.material3.Switch(
                    settings.dataBroadcastingInternetEnabled,
                    null,
                    enabled = settings.dataBroadcastingEnabled,
                )
            }
        }
    }
    Spacer(Modifier.height(32.dp))
    CategoryLabel("地域設定")
    Spacer(Modifier.height(12.dp))
    MaterialTextFieldTheme {
        OutlinedTextField(
            settings.postalCode,
            { update(settings.copy(postalCode = it.filter(Char::isDigit).take(7))) },
            label = { androidx.compose.material3.Text("郵便番号") },
            placeholder = { androidx.compose.material3.Text("1234567") },
            supportingText = { androidx.compose.material3.Text("お住まいの地域に応じた情報を表示するために使用します") },
            isError = settings.postalCode.isNotEmpty() && settings.postalCode.length != 7,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().returnFocusTo(categoryFocusRequester),
        )
    }
    AutoSaveMessage()
}

@Composable
private fun MaterialTextFieldTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(content = content)
}

@Composable
private fun tvSelectableSurfaceColors() =
    SelectableSurfaceDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        pressedContainerColor = MaterialTheme.colorScheme.primary,
        pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        focusedSelectedContainerColor = MaterialTheme.colorScheme.primary,
        focusedSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
        pressedSelectedContainerColor = MaterialTheme.colorScheme.primary,
        pressedSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
    )

@Composable
private fun tvClickableSurfaceColors() =
    ClickableSurfaceDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        pressedContainerColor = MaterialTheme.colorScheme.primary,
        pressedContentColor = MaterialTheme.colorScheme.onPrimary,
    )

@Composable
private fun SupportingText(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        color = LocalContentColor.current.copy(alpha = 0.76f),
        maxLines = maxLines,
    )
}

@Composable
private fun AutoSaveMessage() {
    Spacer(Modifier.height(24.dp))
    Text(
        "変更内容は自動的に保存されます。戻るボタンでホームへ戻れます。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Preview(name = "TV settings", device = Devices.TV_1080p, showBackground = true)
@Composable
private fun TvSettingsPreview() =
    MaterialTheme {
        TvSettingsScreen(MirakurunSettings(authenticationType = AuthenticationType.Basic), {})
    }
