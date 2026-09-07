package net.rokoucha.visiomata

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import net.rokoucha.visiomata.data.ProgramGuideUseCases
import net.rokoucha.visiomata.mirakurun.MirakurunConnectionUseCase
import net.rokoucha.visiomata.mirakurun.PlaybackSessionUseCase
import net.rokoucha.visiomata.settings.data.MirakurunSettingsUseCases
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var localNetworkGranted by mutableStateOf(false)

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkGranted = granted
        }

    @Inject lateinit var settingsUseCases: MirakurunSettingsUseCases

    @Inject lateinit var guideUseCases: ProgramGuideUseCases

    @Inject lateinit var connectionUseCase: MirakurunConnectionUseCase

    @Inject lateinit var playbackSessionUseCase: PlaybackSessionUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        localNetworkGranted = hasLocalNetworkPermission()
        setContent {
            // Do not start guide synchronization or playback until LAN access is available.
            if (localNetworkGranted) {
                VisiomataApp(
                    settingsUseCases = settingsUseCases,
                    guideUseCases = guideUseCases,
                    connectionUseCase = connectionUseCase,
                    playbackSessionUseCase = playbackSessionUseCase,
                )
            } else {
                LocalNetworkPermissionScreen(
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= 37) {
                            requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }
                    },
                    onOpenSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recheck after returning from system settings, including permission revocation.
        localNetworkGranted = hasLocalNetworkPermission()
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}
