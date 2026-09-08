package net.rokoucha.visiomata

import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.mikepenz.aboutlibraries.Libs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import net.rokoucha.visiomata.data.ProgramGuideUseCases
import net.rokoucha.visiomata.guide.GuideTimeline
import net.rokoucha.visiomata.guide.ProgramGuideScreen
import net.rokoucha.visiomata.home.HandheldHomeScreen
import net.rokoucha.visiomata.home.TvHomeScreen
import net.rokoucha.visiomata.mirakurun.MirakurunConnectionUseCase
import net.rokoucha.visiomata.mirakurun.PlaybackSessionUseCase
import net.rokoucha.visiomata.mirakurun.ServerKind
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.ProgramGuideAvailability
import net.rokoucha.visiomata.model.ServiceGroup
import net.rokoucha.visiomata.model.displayOrder
import net.rokoucha.visiomata.playback.PlayerAudioComponent
import net.rokoucha.visiomata.playback.PlayerProgramDetail
import net.rokoucha.visiomata.playback.PlayerProgramInfo
import net.rokoucha.visiomata.playback.PlayerScreen
import net.rokoucha.visiomata.playback.PlayerUpcomingProgram
import net.rokoucha.visiomata.settings.AppVersionInfo
import net.rokoucha.visiomata.settings.DataBroadcastingSettingsScreen
import net.rokoucha.visiomata.settings.LibraryLicenseUiModel
import net.rokoucha.visiomata.settings.LicenseUiModel
import net.rokoucha.visiomata.settings.LicensesScreen
import net.rokoucha.visiomata.settings.MirakurunConnectionStatus
import net.rokoucha.visiomata.settings.MirakurunConnectionUiState
import net.rokoucha.visiomata.settings.MirakurunSettingsScreen
import net.rokoucha.visiomata.settings.SettingsListScreen
import net.rokoucha.visiomata.settings.TvSettingsScreen
import net.rokoucha.visiomata.settings.VersionInfoScreen
import net.rokoucha.visiomata.settings.VideoPlayerSettingsScreen
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import net.rokoucha.visiomata.settings.data.MirakurunSettingsUseCases
import net.rokoucha.visiomata.theme.VisiomataTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Ready(
        val choices: List<ServiceGroup>,
        val channelTypes: List<ChannelType>,
    ) : HomeUiState

    data class Error(
        val message: String,
    ) : HomeUiState
}

private const val APP_LOG_TAG = "Visiomata"

@Serializable data object HomeRoute : NavKey

@Serializable data object GuideRoute : NavKey

@Serializable data object PlayerRoute : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data object MirakurunSettingsRoute : NavKey

@Serializable data object DataBroadcastingSettingsRoute : NavKey

@Serializable data object VideoPlayerSettingsRoute : NavKey

@Serializable data object LicensesRoute : NavKey

@Serializable data object VersionInfoRoute : NavKey

private val navSavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(HomeRoute.serializer())
                    subclass(GuideRoute.serializer())
                    subclass(PlayerRoute.serializer())
                    subclass(SettingsRoute.serializer())
                    subclass(MirakurunSettingsRoute.serializer())
                    subclass(DataBroadcastingSettingsRoute.serializer())
                    subclass(VideoPlayerSettingsRoute.serializer())
                    subclass(LicensesRoute.serializer())
                    subclass(VersionInfoRoute.serializer())
                }
            }
    }

internal fun <T> MutableList<T>.popLastIfNotRoot(): Boolean {
    if (size <= 1) return false
    removeAt(lastIndex)
    return true
}

internal fun <T> MutableList<T>.popIfCurrent(route: T): Boolean {
    if (lastOrNull() != route) return false
    return popLastIfNotRoot()
}

@Composable
fun VisiomataApp(
    settingsUseCases: MirakurunSettingsUseCases,
    guideUseCases: ProgramGuideUseCases,
    connectionUseCase: MirakurunConnectionUseCase,
    playbackSessionUseCase: PlaybackSessionUseCase,
    modifier: Modifier = Modifier,
    isTv: Boolean = LocalConfiguration.current.isTelevision,
) {
    if (isTv) {
        TvVisiomataApp(
            settingsUseCases,
            guideUseCases,
            connectionUseCase,
            playbackSessionUseCase,
            modifier,
        )
    } else {
        HandheldVisiomataApp(
            settingsUseCases,
            guideUseCases,
            connectionUseCase,
            playbackSessionUseCase,
            modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun HandheldVisiomataApp(
    settingsUseCases: MirakurunSettingsUseCases,
    guideUseCases: ProgramGuideUseCases,
    connectionUseCase: MirakurunConnectionUseCase,
    playbackSessionUseCase: PlaybackSessionUseCase,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(navSavedStateConfiguration, HomeRoute)
    val showSettingsDetail: (NavKey) -> Unit = { route ->
        while (
            backStack.lastOrNull() == MirakurunSettingsRoute ||
            backStack.lastOrNull() == DataBroadcastingSettingsRoute ||
            backStack.lastOrNull() == VideoPlayerSettingsRoute ||
            backStack.lastOrNull() == LicensesRoute ||
            backStack.lastOrNull() == VersionInfoRoute
        ) {
            backStack.removeLastOrNull()
        }
        backStack.add(route)
    }
    val context = LocalContext.current
    val settings by settingsUseCases.settings.collectAsState()
    val libraries = rememberLibraryLicenses()
    val versionInfo = rememberAppVersionInfo()
    val maintenance =
        remember(guideUseCases, connectionUseCase) {
            MirakurunMaintenanceState(guideUseCases, connectionUseCase)
        }
    val maintenanceScope = rememberCoroutineScope()
    MirakurunMaintenanceEffect(settings, maintenance)
    GuideEventSync(settings, guideUseCases, maintenance.eventReconnectGeneration)
    var selectedServiceId by rememberSaveable { mutableLongStateOf(TEST_SERVICE_ID) }
    val currentRoute = backStack.lastOrNull()
    val selectedDestination =
        when (currentRoute) {
            SettingsRoute, MirakurunSettingsRoute, DataBroadcastingSettingsRoute,
            VideoPlayerSettingsRoute, LicensesRoute, VersionInfoRoute,
            -> SettingsRoute

            GuideRoute -> GuideRoute

            else -> HomeRoute
        }
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val paneDirective =
        remember(windowAdaptiveInfo) {
            calculatePaneScaffoldDirective(windowAdaptiveInfo)
                .copy(horizontalPartitionSpacerSize = 0.dp)
        }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = paneDirective)
    val showsTwoPanes = paneDirective.maxHorizontalPartitions > 1
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()

    LaunchedEffect(currentRoute) {
        val shouldHideNavigation =
            currentRoute == PlayerRoute ||
                (currentRoute == MirakurunSettingsRoute && !showsTwoPanes) ||
                (currentRoute == DataBroadcastingSettingsRoute && !showsTwoPanes) ||
                (currentRoute == VideoPlayerSettingsRoute && !showsTwoPanes) ||
                (currentRoute == LicensesRoute && !showsTwoPanes) ||
                (currentRoute == VersionInfoRoute && !showsTwoPanes)
        if (shouldHideNavigation) navigationSuiteState.hide() else navigationSuiteState.show()
    }

    VisiomataTheme {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                item(
                    selected = selectedDestination == HomeRoute,
                    onClick = {
                        backStack.clear()
                        backStack.add(HomeRoute)
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("ホーム") },
                )
                item(
                    selected = selectedDestination == GuideRoute,
                    onClick = {
                        backStack.clear()
                        backStack.add(GuideRoute)
                    },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("番組表") },
                )
                item(
                    selected = selectedDestination == SettingsRoute,
                    onClick = {
                        backStack.clear()
                        backStack.add(SettingsRoute)
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("設定") },
                )
            },
            state = navigationSuiteState,
            modifier = modifier,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.popLastIfNotRoot() },
                sceneStrategies = listOf(listDetailStrategy),
                entryProvider =
                    entryProvider {
                        entry<HomeRoute> {
                            ConnectedHandheldHome(
                                settings = settings,
                                guideUseCases = guideUseCases,
                                onPlay = { serviceId ->
                                    selectedServiceId = serviceId
                                    backStack.add(PlayerRoute)
                                },
                                onConfigure = { backStack.add(SettingsRoute) },
                            )
                        }
                        entry<PlayerRoute> {
                            ConfiguredPlayerScreen(
                                settings = settings,
                                serviceId = selectedServiceId,
                                guideUseCases = guideUseCases,
                                playbackSessionUseCase = playbackSessionUseCase,
                                onBack = { backStack.popIfCurrent(PlayerRoute) },
                            )
                        }
                        entry<GuideRoute> {
                            ConnectedProgramGuide(
                                settings = settings,
                                guideUseCases = guideUseCases,
                                isTv = false,
                                onPlay = { serviceId ->
                                    selectedServiceId = serviceId
                                    backStack.add(PlayerRoute)
                                },
                                onConfigure = { backStack.add(SettingsRoute) },
                            )
                        }
                        entry<SettingsRoute>(
                            metadata =
                                ListDetailSceneStrategy.listPane(
                                    detailPlaceholder = { SettingsDetailPlaceholder() },
                                ),
                        ) {
                            LaunchedEffect(showsTwoPanes) {
                                if (showsTwoPanes && backStack.lastOrNull() == SettingsRoute) {
                                    showSettingsDetail(MirakurunSettingsRoute)
                                }
                            }
                            SettingsListScreen(
                                settings = settings,
                                connectionState = maintenance.connectionState,
                                onMirakurunClick = {
                                    if (backStack.lastOrNull() != MirakurunSettingsRoute) {
                                        showSettingsDetail(MirakurunSettingsRoute)
                                    }
                                },
                                onDataBroadcastingClick = {
                                    if (backStack.lastOrNull() != DataBroadcastingSettingsRoute) {
                                        showSettingsDetail(DataBroadcastingSettingsRoute)
                                    }
                                },
                                onVideoPlayerClick = {
                                    if (backStack.lastOrNull() != VideoPlayerSettingsRoute) {
                                        showSettingsDetail(VideoPlayerSettingsRoute)
                                    }
                                },
                                onLicensesClick = {
                                    if (backStack.lastOrNull() != LicensesRoute) {
                                        showSettingsDetail(LicensesRoute)
                                    }
                                },
                                onVersionInfoClick = {
                                    if (backStack.lastOrNull() != VersionInfoRoute) {
                                        showSettingsDetail(VersionInfoRoute)
                                    }
                                },
                            )
                        }
                        entry<VersionInfoRoute>(
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            VersionInfoScreen(
                                versionInfo = versionInfo,
                                onSendFeedback = appDistributionFeedbackAction(),
                                onBack =
                                    if (showsTwoPanes) {
                                        null
                                    } else {
                                        { backStack.popIfCurrent(VersionInfoRoute) }
                                    },
                            )
                        }
                        entry<LicensesRoute>(
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            LicensesScreen(
                                libraries = libraries,
                                onBack =
                                    if (showsTwoPanes) {
                                        null
                                    } else {
                                        { backStack.popIfCurrent(LicensesRoute) }
                                    },
                            )
                        }
                        entry<VideoPlayerSettingsRoute>(
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            VideoPlayerSettingsScreen(
                                settings = settings,
                                onSettingsChange = settingsUseCases::update,
                                onBack =
                                    if (showsTwoPanes) {
                                        null
                                    } else {
                                        { backStack.popIfCurrent(VideoPlayerSettingsRoute) }
                                    },
                            )
                        }
                        entry<DataBroadcastingSettingsRoute>(
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            DataBroadcastingSettingsScreen(
                                settings = settings,
                                onSettingsChange = settingsUseCases::update,
                                onBack =
                                    if (showsTwoPanes) {
                                        null
                                    } else {
                                        { backStack.popIfCurrent(DataBroadcastingSettingsRoute) }
                                    },
                            )
                        }
                        entry<MirakurunSettingsRoute>(
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            MirakurunSettingsScreen(
                                settings = settings,
                                onSettingsChange = settingsUseCases::update,
                                connectionState = maintenance.connectionState,
                                isRefreshingGuide = maintenance.isRefreshingGuide,
                                onCheckConnection = {
                                    maintenanceScope.launch { maintenance.checkConnection(settings) }
                                },
                                onRefreshGuide = {
                                    maintenanceScope.launch {
                                        val message = maintenance.refreshGuide(settings) ?: return@launch
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onBack =
                                    if (showsTwoPanes) {
                                        null
                                    } else {
                                        { backStack.popIfCurrent(MirakurunSettingsRoute) }
                                    },
                            )
                        }
                    },
            )
        }
    }
}

@Composable
private fun SettingsDetailPlaceholder() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text = "設定する項目を選択してください",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvVisiomataApp(
    settingsUseCases: MirakurunSettingsUseCases,
    guideUseCases: ProgramGuideUseCases,
    connectionUseCase: MirakurunConnectionUseCase,
    playbackSessionUseCase: PlaybackSessionUseCase,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(navSavedStateConfiguration, HomeRoute)
    val context = LocalContext.current
    val settings by settingsUseCases.settings.collectAsState()
    val libraries = rememberLibraryLicenses()
    val versionInfo = rememberAppVersionInfo()
    val maintenance =
        remember(guideUseCases, connectionUseCase) {
            MirakurunMaintenanceState(guideUseCases, connectionUseCase)
        }
    val maintenanceScope = rememberCoroutineScope()
    MirakurunMaintenanceEffect(settings, maintenance)
    GuideEventSync(settings, guideUseCases, maintenance.eventReconnectGeneration)
    var selectedServiceId by rememberSaveable { mutableLongStateOf(TEST_SERVICE_ID) }
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.popLastIfNotRoot() },
        modifier = modifier,
        entryProvider =
            entryProvider {
                entry<HomeRoute> {
                    AppTheme(isTv = true) {
                        ConnectedTvHome(
                            settings = settings,
                            guideUseCases = guideUseCases,
                            onPlay = { serviceId ->
                                selectedServiceId = serviceId
                                backStack.add(PlayerRoute)
                            },
                            onGuide = { backStack.add(GuideRoute) },
                            onSettings = { backStack.add(SettingsRoute) },
                        )
                    }
                }
                entry<SettingsRoute> {
                    AppTheme(isTv = true) {
                        TvSettingsScreen(
                            settings = settings,
                            onSettingsChange = settingsUseCases::update,
                            connectionState = maintenance.connectionState,
                            isRefreshingGuide = maintenance.isRefreshingGuide,
                            onCheckConnection = {
                                maintenanceScope.launch { maintenance.checkConnection(settings) }
                            },
                            onRefreshGuide = {
                                maintenanceScope.launch {
                                    val message = maintenance.refreshGuide(settings) ?: return@launch
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            libraries = libraries,
                            versionInfo = versionInfo,
                            onSendFeedback = appDistributionFeedbackAction(),
                            onBack = { backStack.popLastIfNotRoot() },
                        )
                    }
                }
                entry<GuideRoute> {
                    VisiomataTheme {
                        ConnectedProgramGuide(
                            settings = settings,
                            guideUseCases = guideUseCases,
                            isTv = true,
                            onPlay = { serviceId ->
                                selectedServiceId = serviceId
                                backStack.add(PlayerRoute)
                            },
                            onConfigure = { backStack.add(SettingsRoute) },
                        )
                    }
                }
                entry<PlayerRoute> {
                    VisiomataTheme {
                        ConfiguredPlayerScreen(
                            settings = settings,
                            serviceId = selectedServiceId,
                            guideUseCases = guideUseCases,
                            playbackSessionUseCase = playbackSessionUseCase,
                            onBack = { backStack.popIfCurrent(PlayerRoute) },
                        )
                    }
                }
            },
    )
}

private fun appDistributionFeedbackAction(): (() -> Unit)? {
    if (!BuildConfig.APP_DISTRIBUTION_FEEDBACK_ENABLED) return null
    return {
        FirebaseAppDistribution.getInstance().startFeedback(R.string.app_distribution_feedback_notice)
    }
}

@Composable
private fun rememberAppVersionInfo(): AppVersionInfo {
    val context = LocalContext.current
    return remember(context) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        AppVersionInfo(
            appName = context.applicationInfo.loadLabel(context.packageManager).toString(),
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
        )
    }
}

@Composable
private fun rememberLibraryLicenses(): List<LibraryLicenseUiModel> {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val resourceId =
                context.resources.getIdentifier(
                    "aboutlibraries",
                    "raw",
                    context.packageName,
                )
            require(resourceId != 0) { "aboutlibraries resource was not generated" }
            val json =
                context.resources
                    .openRawResource(resourceId)
                    .bufferedReader()
                    .use { it.readText() }
            Libs.Builder().withJson(json).build().libraries.map { library ->
                LibraryLicenseUiModel(
                    id = library.artifactId,
                    name = library.name,
                    version = library.artifactVersion,
                    website = library.website,
                    licenses =
                        library.licenses.map { license ->
                            LicenseUiModel(
                                name = license.name,
                                url = license.url,
                                content = license.licenseContent,
                            )
                        },
                )
            }
        }.getOrElse { error ->
            Log.e(APP_LOG_TAG, "Failed to load open source licenses", error)
            emptyList()
        }
    }
}

@Composable
private fun MirakurunMaintenanceEffect(
    settings: MirakurunSettings,
    state: MirakurunMaintenanceState,
) {
    LaunchedEffect(
        state,
        settings.url,
        settings.authenticationType,
        settings.username,
        settings.password,
        settings.bearerToken,
    ) {
        if (settings.url.isBlank()) {
            state.markNotConfigured()
        } else {
            delay(750)
            state.checkConnection(settings)
        }
    }
}

@Stable
private class MirakurunMaintenanceState(
    private val guideUseCases: ProgramGuideUseCases,
    private val connectionUseCase: MirakurunConnectionUseCase,
) {
    var connectionState by mutableStateOf(MirakurunConnectionUiState())
        private set
    var isRefreshingGuide by mutableStateOf(false)
        private set
    var eventReconnectGeneration by mutableLongStateOf(0L)
        private set

    fun markNotConfigured() {
        connectionState = MirakurunConnectionUiState()
    }

    suspend fun checkConnection(settings: MirakurunSettings) {
        if (settings.url.isBlank()) return markNotConfigured()
        connectionState =
            MirakurunConnectionUiState(
                MirakurunConnectionStatus.Checking,
                "接続を確認しています…",
            )
        connectionState =
            try {
                val connection = connectionUseCase(settings)
                MirakurunConnectionUiState(
                    MirakurunConnectionStatus.Connected,
                    "接続済み（${if (connection.kind == ServerKind.MAHIRON) "Mahiron" else "Mirakurun"} " +
                        "${connection.version.current}）",
                    "${if (connection.kind == ServerKind.MAHIRON) "Mahiron" else "Mirakurun"} " +
                        connection.version.current,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(APP_LOG_TAG, "Mirakurun connection check failed", error)
                MirakurunConnectionUiState(
                    MirakurunConnectionStatus.Error,
                    error.message ?: "接続できませんでした",
                )
            }
    }

    suspend fun refreshGuide(settings: MirakurunSettings): String? {
        if (isRefreshingGuide || settings.url.isBlank()) return null
        isRefreshingGuide = true
        return try {
            val result =
                try {
                    Result.success(guideUseCases.refreshAll(settings))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Result.failure(error)
                }
            result.fold(
                onSuccess = {
                    eventReconnectGeneration++
                    checkConnection(settings)
                    "番組表を再取得しました"
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    Log.e(APP_LOG_TAG, "Manual program guide refresh failed", it)
                    it.message ?: "番組表を再取得できませんでした"
                },
            )
        } finally {
            isRefreshingGuide = false
        }
    }
}

@Composable
private fun GuideEventSync(
    settings: MirakurunSettings,
    guideUseCases: ProgramGuideUseCases,
    reconnectGeneration: Long,
) {
    if (settings.url.isBlank()) return
    LaunchedEffect(
        guideUseCases,
        settings.url,
        settings.authenticationType,
        settings.username,
        settings.password,
        settings.bearerToken,
        reconnectGeneration,
    ) {
        try {
            guideUseCases.syncEvents(settings)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.e(APP_LOG_TAG, "Background guide sync failed", error)
        }
    }
}

@Composable
private fun ConnectedHandheldHome(
    settings: MirakurunSettings,
    guideUseCases: ProgramGuideUseCases,
    onPlay: (Long) -> Unit,
    onConfigure: () -> Unit,
) {
    if (settings.url.isBlank()) {
        HomeConnectionMessage(
            message = "Mirakurunを設定すると、受信できるサービスを表示します",
            actionLabel = "設定する",
            onAction = onConfigure,
        )
        return
    }
    val homeState = rememberHomeState(settings, guideUseCases)
    val scope = rememberCoroutineScope()
    when (val state = homeState.uiState) {
        HomeUiState.Loading -> {
            HomeLoadingIndicator()
        }

        is HomeUiState.Ready -> {
            HandheldHomeScreen(
                onPlay = onPlay,
                choices = state.choices,
                channelTypes = state.channelTypes,
                loadLogo = homeState.loadLogo,
                isLoadingChannel = homeState.isLoadingChannel,
                onSelectedType = { type -> scope.launch { homeState.selectType(type) } },
                isRefreshing = homeState.isRefreshing,
                onRefresh = { scope.launch { homeState.refresh(showIndicator = true) } },
            )
        }

        is HomeUiState.Error -> {
            HomeConnectionMessage(state.message, "設定を確認", onConfigure)
        }
    }
}

@Composable
private fun ConnectedTvHome(
    settings: MirakurunSettings,
    guideUseCases: ProgramGuideUseCases,
    onPlay: (Long) -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
) {
    if (settings.url.isBlank()) {
        HomeConnectionMessage(
            message = "Mirakurunを設定すると、受信できるサービスを表示します",
            actionLabel = "設定する",
            onAction = onSettings,
        )
        return
    }
    val homeState = rememberHomeState(settings, guideUseCases)
    when (val state = homeState.uiState) {
        HomeUiState.Loading -> {
            HomeLoadingIndicator()
        }

        is HomeUiState.Ready -> {
            LaunchedEffect(homeState, state.channelTypes) {
                homeState.loadTvTypes(state.channelTypes)
            }
            TvHomeScreen(
                onPlay = onPlay,
                onGuide = onGuide,
                onSettings = onSettings,
                choices = state.choices,
                channelTypes = state.channelTypes,
                loadLogo = homeState.loadLogo,
                isLoadingChannel = homeState.isLoadingChannel,
            )
        }

        is HomeUiState.Error -> {
            HomeConnectionMessage(state.message, "設定を確認", onSettings)
        }
    }
}

@Composable
private fun ConnectedProgramGuide(
    settings: MirakurunSettings,
    guideUseCases: ProgramGuideUseCases,
    isTv: Boolean,
    onPlay: (Long) -> Unit,
    onConfigure: () -> Unit,
) {
    if (settings.url.isBlank()) {
        HomeConnectionMessage(
            message = "Mirakurunを設定すると番組表を表示できます",
            actionLabel = "設定する",
            onAction = onConfigure,
        )
        return
    }
    var channelTypes by remember { mutableStateOf<List<ChannelType>>(emptyList()) }
    var isLoadingChannelTypes by remember { mutableStateOf(true) }
    var selectedTypeValue by rememberSaveable { mutableStateOf("GR") }
    var broadcastDateEpochDay by rememberSaveable {
        val now = LocalDate.now()
        mutableLongStateOf((if (LocalTime.now().hour < 4) now.minusDays(1) else now).toEpochDay())
    }
    val selectedType = ChannelType(selectedTypeValue)
    val broadcastDate = LocalDate.ofEpochDay(broadcastDateEpochDay)
    var guide by remember { mutableStateOf(ProgramGuide(emptyList(), emptyList())) }
    var renderedGuideType by remember { mutableStateOf<String?>(null) }
    var availabilityByType by remember {
        mutableStateOf<Map<String, ProgramGuideAvailability>>(emptyMap())
    }
    var isLoading by remember { mutableStateOf(true) }
    val zone = ZoneId.systemDefault()
    val startAt =
        remember(broadcastDate, zone) {
            GuideTimeline.windowStart(broadcastDate, zone)
        }
    val endAt = remember(broadcastDate, zone) { GuideTimeline.windowEnd(broadcastDate, zone) }

    LaunchedEffect(guideUseCases, settings) {
        guideUseCases.observeHome(settings).collect { state ->
            channelTypes =
                state.guide.services
                    .map { it.channelType }
                    .distinct()
                    .sortedWith(compareBy({ it.displayOrder }, { it.value }))
            if (ChannelType(selectedTypeValue) !in channelTypes && channelTypes.isNotEmpty()) {
                selectedTypeValue = channelTypes.first().value
            }
            isLoadingChannelTypes = state.isRefreshing && channelTypes.isEmpty()
            state.refreshError?.let {
                Log.e(APP_LOG_TAG, "Program guide service refresh failed", it)
            }
        }
    }
    LaunchedEffect(guideUseCases, settings, selectedType, startAt, endAt) {
        guideUseCases.observeGuide(settings, selectedType, startAt, endAt).collect { state ->
            val incoming = state.guide
            guide =
                if (renderedGuideType == selectedType.value) {
                    incoming.retainingWindow(guide, startAt, endAt)
                } else {
                    incoming
                }
            renderedGuideType = selectedType.value
            isLoading = state.isRefreshing
            state.availability?.let { availability ->
                availabilityByType = availabilityByType + (selectedType.value to availability)
            }
            state.refreshError?.let { Log.e(APP_LOG_TAG, "Program guide refresh failed", it) }
        }
    }

    if (isTv && isLoadingChannelTypes && channelTypes.isEmpty()) {
        HomeLoadingIndicator()
        return
    }

    ProgramGuideScreen(
        guide = guide,
        channelTypes = channelTypes,
        selectedType = selectedType,
        broadcastDate = broadcastDate,
        isTv = isTv,
        isLoading = isLoading,
        availability = availabilityByType[selectedType.value],
        onChannelTypeSelected = { selectedTypeValue = it.value },
        onBroadcastDateChanged = { broadcastDateEpochDay = it.toEpochDay() },
        onPlay = onPlay,
        loadExtended = { programId -> guideUseCases.programExtended(settings, programId) },
        loadLogo = { serviceId, logoId -> guideUseCases.logo(settings, serviceId, logoId) },
    )
}

@Composable
private fun rememberHomeState(
    settings: MirakurunSettings,
    guideUseCases: ProgramGuideUseCases,
): HomeState {
    val state = remember(guideUseCases, settings) { HomeState(guideUseCases, settings) }
    LaunchedEffect(state) {
        guideUseCases.observeHome(settings).collect { loadState ->
            state.updateGuide(loadState.guide)
            state.updateInitialLoad(loadState.isRefreshing, loadState.refreshError)
        }
    }
    return state
}

@Stable
private class HomeState(
    private val guideUseCases: ProgramGuideUseCases,
    private val settings: MirakurunSettings,
) {
    val loadLogo: suspend (Long, Int?) -> ByteArray? = { serviceId, logoId ->
        guideUseCases.logo(settings, serviceId, logoId)
    }
    var uiState by mutableStateOf<HomeUiState>(HomeUiState.Loading)
    var isRefreshing by mutableStateOf(false)
        private set
    var isLoadingChannel by mutableStateOf(false)
        private set
    private var refreshInProgress = false
    private var selectedType: ChannelType? = null
    private val requestedTvTypes = mutableSetOf<ChannelType>()

    fun updateGuide(guide: net.rokoucha.visiomata.model.ProgramGuide) {
        if (guide.services.isEmpty()) return
        val types =
            guide.services.map { it.channelType }.distinct().sortedWith(
                compareBy<ChannelType>({ it.displayOrder }, { it.value }),
            )
        val next = HomeUiState.Ready(guide.serviceGroups(), types)
        if (uiState != next) uiState = next
    }

    suspend fun loadTvTypes(types: List<ChannelType>) {
        val pending = types.filter(requestedTvTypes::add)
        if (pending.isEmpty()) return
        isLoadingChannel = true
        try {
            pending.forEach { type ->
                var loaded = false
                try {
                    guideUseCases.selectChannelType(settings, type)
                    loaded = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Leave this type retryable when loading failed.
                } finally {
                    if (!loaded) requestedTvTypes.remove(type)
                }
            }
        } finally {
            isLoadingChannel = false
        }
    }

    fun updateInitialLoad(
        isRefreshing: Boolean,
        error: Throwable?,
    ) {
        if (isRefreshing || uiState != HomeUiState.Loading) return
        uiState =
            if (error == null) {
                HomeUiState.Ready(emptyList(), emptyList())
            } else {
                Log.e(APP_LOG_TAG, "Home refresh failed", error)
                HomeUiState.Error(error.message ?: "番組情報を取得できませんでした")
            }
    }

    suspend fun selectType(type: ChannelType) {
        if (selectedType == type && isLoadingChannel) return
        selectedType = type
        val hasCachedContent =
            (uiState as? HomeUiState.Ready)
                ?.choices
                ?.any { it.channelType == type } == true
        isLoadingChannel = !hasCachedContent
        try {
            guideUseCases.selectChannelType(settings, type)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.e(APP_LOG_TAG, "Home program refresh failed", error)
        } finally {
            if (selectedType == type) isLoadingChannel = false
        }
    }

    suspend fun refresh(showIndicator: Boolean) {
        if (refreshInProgress) return
        refreshInProgress = true
        if (showIndicator) isRefreshing = true
        if (selectedType != null) isLoadingChannel = true
        try {
            try {
                guideUseCases.refreshHome(settings, selectedType, force = showIndicator)
                if (uiState == HomeUiState.Loading) {
                    uiState = HomeUiState.Ready(emptyList(), emptyList())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(APP_LOG_TAG, "Home refresh failed", error)
                if (uiState == HomeUiState.Loading) {
                    uiState = HomeUiState.Error(error.message ?: "番組情報を取得できませんでした")
                }
            }
        } finally {
            isLoadingChannel = false
            if (showIndicator) isRefreshing = false
            refreshInProgress = false
        }
    }
}

@Composable
private fun HomeLoadingIndicator() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
private fun HomeConnectionMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(message)
        androidx.compose.foundation.layout
            .Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ConfiguredPlayerScreen(
    settings: MirakurunSettings,
    serviceId: Long,
    guideUseCases: ProgramGuideUseCases,
    playbackSessionUseCase: PlaybackSessionUseCase,
    onBack: () -> Unit,
) {
    val programInfoState by produceState<LoadedPlayerProgramInfo?>(null, guideUseCases, settings, serviceId) {
        guideUseCases.observePlayback(settings, serviceId).collectLatest { data ->
            value =
                LoadedPlayerProgramInfo(
                    data?.let {
                        PlayerProgramInfo(
                            serviceName = it.service.name,
                            channelLabel = it.service.channelIdentityLabel,
                            channelType = it.service.channelType.value,
                            logicalChannelNumber = it.service.logicalChannelNumber,
                            title = it.program.title,
                            timeRange = it.program.playerTimeRange(),
                            description = it.program.description,
                            progress = it.program.progressAt(it.observedAt),
                            details = it.program.playerDetails(it.extended),
                            nextProgram =
                                it.nextProgram?.let { next ->
                                    PlayerUpcomingProgram(
                                        title = next.title,
                                        timeRange = next.playerTimeRange(),
                                        description = next.description,
                                    )
                                },
                            serviceLogo = it.serviceLogo,
                            audioComponents =
                                it.program.audios.map { audio ->
                                    PlayerAudioComponent(
                                        componentTag = audio.componentTag,
                                        isMain = audio.isMain,
                                        isDualMono = audio.isDualMono,
                                        languages = audio.languages,
                                    )
                                },
                        )
                    },
                )
        }
    }
    val playbackSession by produceState<net.rokoucha.visiomata.mirakurun.PlaybackSession?>(
        initialValue = null,
        playbackSessionUseCase,
        settings,
        serviceId,
    ) {
        value = playbackSessionUseCase(settings, serviceId)
    }
    val session = playbackSession
    val loadedProgramInfo = programInfoState
    if (session == null || loadedProgramInfo == null) {
        HomeLoadingIndicator()
        return
    }
    PlayerScreen(
        url = session.streamUrl,
        basicAuthUsername = session.basicAuthUsername,
        basicAuthPassword = session.basicAuthPassword,
        bearerToken = session.bearerToken,
        forceMpeg2Transcoding = session.forceMpeg2Transcoding,
        forceHardwareMpeg2Decoder = session.forceHardwareMpeg2Decoder,
        deinterlaceEnabled = session.deinterlaceEnabled,
        dataBroadcastingEnabled = session.dataBroadcastingEnabled,
        dataBroadcastingInternetEnabled = session.dataBroadcastingInternetEnabled,
        mahironApiRoot = session.mahironApiRoot,
        serviceId = session.serviceId,
        postalCode = session.postalCode,
        programInfo = loadedProgramInfo.value,
        onBack = onBack,
    )
}

private data class LoadedPlayerProgramInfo(
    val value: PlayerProgramInfo?,
)

private val playerTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun net.rokoucha.visiomata.model.Program.playerDetails(
    detailedExtended: Map<String, String>,
): List<PlayerProgramDetail> {
    val supplied =
        detailedExtended.mapNotNull { (label, content) ->
            val cleanLabel = label.trim().trimStart('◇').trim()
            val cleanContent = content.trim()
            if (cleanLabel.isBlank() || cleanContent.isBlank()) {
                null
            } else {
                PlayerProgramDetail(cleanLabel, cleanContent)
            }
        }
    if (supplied.isNotEmpty()) return supplied

    val sections = description.split(Regex("[▽\\r\\n]+")).map(String::trim).filter(String::isNotBlank)
    if (sections.isEmpty()) return emptyList()
    val labeled = mutableListOf<PlayerProgramDetail>()
    val knownLabels = listOf("出演者", "出演", "キャスト", "ゲスト", "監督", "脚本", "音楽")
    sections.forEach { section ->
        val matchedLabel =
            knownLabels.firstOrNull { label ->
                section.startsWith("$label：") || section.startsWith("$label:")
            }
        if (matchedLabel == null) {
            return@forEach
        } else {
            val content = section.substringAfter(':', section.substringAfter('：', "")).trim()
            if (content.isNotBlank()) {
                labeled +=
                    PlayerProgramDetail(
                        label = if (matchedLabel == "出演") "出演者" else matchedLabel,
                        content = content,
                    )
            }
        }
    }
    return labeled
}

private fun net.rokoucha.visiomata.model.Program.playerTimeRange() =
    "${playerTimeFormatter.format(startAt)}–${playerTimeFormatter.format(endAt)}"

private fun net.rokoucha.visiomata.model.Program.progressAt(now: Instant = Instant.now()): Float {
    val total = Duration.between(startAt, endAt).toMillis()
    if (total <= 0L) return 0f
    return (Duration.between(startAt, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

private const val TEST_SERVICE_ID = 3273601024L

private val Configuration.isTelevision: Boolean
    get() = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
