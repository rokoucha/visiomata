package net.rokoucha.visiomata.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rokoucha.visiomata.settings.data.AuthenticationType
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import net.rokoucha.visiomata.settings.data.Mpeg2PlaybackMode
import net.rokoucha.visiomata.settings.data.requiresDeviceMpeg2Decoder

enum class MirakurunConnectionStatus { NotConfigured, Checking, Connected, Error }

data class MirakurunConnectionUiState(
    val status: MirakurunConnectionStatus = MirakurunConnectionStatus.NotConfigured,
    val message: String = "接続先が設定されていません",
    val serverInfo: String? = null,
)

internal data class MirakurunConnectionDraft(
    val url: String,
    val authenticationType: AuthenticationType,
    val username: String,
    val password: String,
    val bearerToken: String,
) {
    constructor(settings: MirakurunSettings) : this(
        settings.url,
        settings.authenticationType,
        settings.username,
        settings.password,
        settings.bearerToken,
    )

    fun applyTo(settings: MirakurunSettings): MirakurunSettings =
        settings.copy(
            url = url,
            authenticationType = authenticationType,
            username = username,
            password = password,
            bearerToken = bearerToken,
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsListScreen(
    settings: MirakurunSettings,
    onMirakurunClick: () -> Unit,
    onVideoPlayerClick: () -> Unit,
    onDataBroadcastingClick: () -> Unit,
    onVersionInfoClick: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: MirakurunConnectionUiState = MirakurunConnectionUiState(),
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("設定") }) },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "接続",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
            )
            SegmentedListItem(
                onClick = onMirakurunClick,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                content = { Text("Mirakurun") },
                supportingContent = {
                    Text(
                        connectionState.settingsSummary(settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "視聴",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
            )
            SegmentedListItem(
                onClick = onVideoPlayerClick,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                content = { Text("動画プレイヤー") },
                supportingContent = {
                    Text(
                        "${settings.mpeg2PlaybackMode.summary()}・" +
                            if (settings.deinterlaceEnabled) "デインターレース有効" else "デインターレース無効",
                    )
                },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedListItem(
                onClick = onDataBroadcastingClick,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                content = { Text("データ放送") },
                supportingContent = {
                    Text(if (settings.dataBroadcastingEnabled) "有効" else "無効")
                },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "アプリについて",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
            )
            SegmentedListItem(
                onClick = onVersionInfoClick,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                content = { Text("バージョン情報") },
                supportingContent = { Text("アプリのバージョンを表示") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedListItem(
                onClick = onLicensesClick,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                content = { Text("オープンソースライセンス") },
                supportingContent = { Text("使用しているライブラリとライセンスを表示") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun MirakurunConnectionUiState.settingsSummary(settings: MirakurunSettings): String =
    when (status) {
        MirakurunConnectionStatus.Connected -> serverInfo ?: "接続済み"
        MirakurunConnectionStatus.Checking -> message
        MirakurunConnectionStatus.Error -> "接続エラー"
        MirakurunConnectionStatus.NotConfigured -> settings.url.ifBlank { message }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerSettingsScreen(
    settings: MirakurunSettings,
    onSettingsChange: (MirakurunSettings) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("動画プレイヤー") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "MPEG-2映像の再生方法",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            val mpeg2DecoderPresent = rememberMpeg2DecoderPresent()
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                Mpeg2PlaybackMode.entries.forEachIndexed { index, mode ->
                    // The direct-playback modes show audio only without a device decoder.
                    val selectable = !mode.requiresDeviceMpeg2Decoder || mpeg2DecoderPresent != false
                    SegmentedListItem(
                        checked = settings.mpeg2PlaybackMode == mode,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(mpeg2PlaybackMode = mode))
                        },
                        enabled = selectable,
                        shapes =
                            ListItemDefaults.segmentedShapes(
                                index = index,
                                count = Mpeg2PlaybackMode.entries.size,
                            ),
                        content = { Text(mode.title()) },
                        supportingContent = {
                            Text(
                                if (selectable) {
                                    mode.description()
                                } else {
                                    "この端末にはMPEG-2デコーダーが無いため選択できません"
                                },
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = settings.mpeg2PlaybackMode == mode,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (mpeg2DecoderPresent == false && settings.mpeg2PlaybackMode.requiresDeviceMpeg2Decoder) {
                Text(
                    "MPEG-2デコーダーの無い端末では映像が表示されず音声のみになります。「自動」に変更してください。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "デインターレース",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
            )
            val canTranscode =
                settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.Auto ||
                    settings.mpeg2PlaybackMode == Mpeg2PlaybackMode.ForceTranscode
            SegmentedListItem(
                checked = settings.deinterlaceEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(deinterlaceEnabled = it))
                },
                enabled = canTranscode,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                content = { Text("有効") },
                supportingContent = {
                    Text("mpeg2toh264でH.264へ変換するときだけ、単一レートBobを適用します")
                },
                trailingContent = {
                    Switch(
                        checked = settings.deinterlaceEnabled,
                        onCheckedChange = null,
                        enabled = canTranscode,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "変更内容は次にプレイヤーを開いたときから反映されます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
            )
        }
    }
}

private fun Mpeg2PlaybackMode.title(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "自動"
        Mpeg2PlaybackMode.ForceTranscode -> "H.264へ変換"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "MPEG-2をソフトウェアデコード"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "MPEG-2をハードウェアデコード"
    }

private fun Mpeg2PlaybackMode.summary(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "自動"
        Mpeg2PlaybackMode.ForceTranscode -> "H.264へ変換"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "ソフトウェアデコード"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "ハードウェアデコード"
    }

private fun Mpeg2PlaybackMode.description(): String =
    when (this) {
        Mpeg2PlaybackMode.Auto -> "ハードウェアMPEG-2デコーダーがあれば直接再生し、それ以外ではH.264へ変換します"
        Mpeg2PlaybackMode.ForceTranscode -> "端末の対応状況にかかわらずmpeg2toh264を使用します"
        Mpeg2PlaybackMode.ForceSoftwareDecoder -> "mpeg2toh264を使用せず、ソフトウェアデコーダーだけを使用します"
        Mpeg2PlaybackMode.ForceHardwareDecoder -> "mpeg2toh264を使用せず、ハードウェアデコーダーだけを使用します"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataBroadcastingSettingsScreen(
    settings: MirakurunSettings,
    onSettingsChange: (MirakurunSettings) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("データ放送") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            SegmentedListItem(
                checked = settings.dataBroadcastingEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(dataBroadcastingEnabled = it))
                },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                content = { Text("データ放送を表示") },
                supportingContent = { Text("番組に含まれるデータ放送を受信して表示します") },
                trailingContent = {
                    Switch(
                        checked = settings.dataBroadcastingEnabled,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "取得方法",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
            )
            SegmentedListItem(
                checked = settings.useMahironDataBroadcastApi,
                onCheckedChange = {
                    onSettingsChange(settings.copy(useMahironDataBroadcastApi = it))
                },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                enabled = settings.dataBroadcastingEnabled,
                content = { Text("Mahiron APIを使用") },
                supportingContent = {
                    Text("OFFにするとデバッグ用に受信TSを端末内でdemuxします")
                },
                trailingContent = {
                    Switch(
                        checked = settings.useMahironDataBroadcastApi,
                        onCheckedChange = null,
                        enabled = settings.dataBroadcastingEnabled,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "通信機能",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
            )
            SegmentedListItem(
                checked = settings.dataBroadcastingInternetEnabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(dataBroadcastingInternetEnabled = it))
                },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                enabled = settings.dataBroadcastingEnabled,
                content = { Text("インターネット接続") },
                supportingContent = {
                    Text("放送局の通信コンテンツへの接続を許可します（証明書検証なし）")
                },
                trailingContent = {
                    Switch(
                        checked = settings.dataBroadcastingInternetEnabled,
                        onCheckedChange = null,
                        enabled = settings.dataBroadcastingEnabled,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "地域設定",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = settings.postalCode,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(postalCode = value.filter(Char::isDigit).take(7)))
                },
                label = { Text("郵便番号") },
                placeholder = { Text("1234567") },
                supportingText = { Text("お住まいの地域に応じた情報を表示するために使用します") },
                isError = settings.postalCode.isNotEmpty() && settings.postalCode.length != 7,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "変更内容は自動的に保存されます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirakurunSettingsScreen(
    settings: MirakurunSettings,
    onSettingsChange: (MirakurunSettings) -> Unit,
    onBack: (() -> Unit)?,
    connectionState: MirakurunConnectionUiState = MirakurunConnectionUiState(),
    isRefreshingGuide: Boolean = false,
    onCheckConnection: (MirakurunSettings) -> Unit = {},
    onRefreshGuide: (MirakurunSettings) -> Unit = {},
    modifier: Modifier = Modifier,
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
            onSettingsChange(currentSettings())
        }
    }
    val done =
        KeyboardActions(onDone = {
            save()
            focusManager.clearFocus()
        })
    val isUrlInvalid = draft.url.isNotBlank() && !draft.url.isHttpUrl()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Mirakurun") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "接続先",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.url,
                onValueChange = { draft = draft.copy(url = it) },
                label = { Text("URL") },
                placeholder = { Text("http://192.168.1.10:40772") },
                supportingText = {
                    Text(if (isUrlInvalid) "http:// または https:// で始まるURLを入力してください" else "MirakurunサーバーのベースURL")
                },
                isError = isUrlInvalid,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = done,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            if (urlWasFocused && !it.isFocused) save()
                            urlWasFocused = it.isFocused
                        },
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = "認証",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                AuthenticationType.entries.forEachIndexed { index, type ->
                    AuthenticationRow(
                        type = type,
                        selected = draft.authenticationType == type,
                        index = index,
                        count = AuthenticationType.entries.size,
                        onClick = {
                            draft = draft.copy(authenticationType = type)
                            save()
                        },
                    )
                }
            }

            when (draft.authenticationType) {
                AuthenticationType.None -> {}

                AuthenticationType.Basic -> {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = draft.username,
                        onValueChange = { draft = draft.copy(username = it) },
                        label = { Text("ユーザー名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = done,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged {
                                if (usernameWasFocused && !it.isFocused) save()
                                usernameWasFocused = it.isFocused
                            },
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft.password,
                        onValueChange = { draft = draft.copy(password = it) },
                        label = { Text("パスワード") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = done,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged {
                                if (passwordWasFocused && !it.isFocused) save()
                                passwordWasFocused = it.isFocused
                            },
                    )
                }

                AuthenticationType.Bearer -> {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = draft.bearerToken,
                        onValueChange = { draft = draft.copy(bearerToken = it) },
                        label = { Text("Bearerトークン") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = done,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged {
                                if (bearerTokenWasFocused && !it.isFocused) save()
                                bearerTokenWasFocused = it.isFocused
                            },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text = "接続状態",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = connectionState.message,
                color =
                    if (connectionState.status == MirakurunConnectionStatus.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val value = currentSettings()
                        save()
                        onCheckConnection(value)
                    },
                    enabled =
                        draft.url.isNotBlank() &&
                            !isUrlInvalid &&
                            connectionState.status != MirakurunConnectionStatus.Checking,
                ) {
                    if (connectionState.status == MirakurunConnectionStatus.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("接続を確認")
                }
                Button(
                    onClick = {
                        val value = currentSettings()
                        save()
                        onRefreshGuide(value)
                    },
                    enabled = draft.url.isNotBlank() && !isUrlInvalid && !isRefreshingGuide,
                ) {
                    if (isRefreshingGuide) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("番組表を再取得")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "変更内容は自動的に保存されます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AuthenticationRow(
    type: AuthenticationType,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        content = {
            Text(
                text =
                    when (type) {
                        AuthenticationType.None -> "なし"
                        AuthenticationType.Basic -> "Basic認証"
                        AuthenticationType.Bearer -> "Bearerトークン"
                    },
            )
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun String.isHttpUrl(): Boolean {
    val value = trim()
    return value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
}

@Preview(name = "Settings phone", device = Devices.PHONE, showBackground = true)
@Composable
private fun SettingsListPreview() {
    MaterialTheme {
        SettingsListScreen(
            MirakurunSettings(),
            onMirakurunClick = {},
            onVideoPlayerClick = {},
            onDataBroadcastingClick = {},
            onVersionInfoClick = {},
            onLicensesClick = {},
        )
    }
}

@Preview(name = "Mirakurun detail", device = Devices.TABLET, showBackground = true)
@Composable
private fun MirakurunSettingsPreview() {
    MaterialTheme {
        MirakurunSettingsScreen(
            settings = MirakurunSettings(authenticationType = AuthenticationType.Basic),
            onSettingsChange = {},
            onBack = null,
        )
    }
}
