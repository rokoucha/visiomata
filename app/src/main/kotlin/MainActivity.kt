package net.rokoucha.visiomata

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import net.rokoucha.visiomata.data.ProgramGuideUseCases
import net.rokoucha.visiomata.mirakurun.MirakurunConnectionUseCase
import net.rokoucha.visiomata.mirakurun.PlaybackSessionUseCase
import net.rokoucha.visiomata.settings.data.MirakurunSettingsUseCases
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @Inject lateinit var settingsUseCases: MirakurunSettingsUseCases

    @Inject lateinit var guideUseCases: ProgramGuideUseCases

    @Inject lateinit var connectionUseCase: MirakurunConnectionUseCase

    @Inject lateinit var playbackSessionUseCase: PlaybackSessionUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            VisiomataApp(
                settingsUseCases = settingsUseCases,
                guideUseCases = guideUseCases,
                connectionUseCase = connectionUseCase,
                playbackSessionUseCase = playbackSessionUseCase,
            )
        }

        if (
            Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
