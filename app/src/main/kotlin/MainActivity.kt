package net.rokoucha.visiomata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import net.rokoucha.visiomata.data.ProgramGuideUseCases
import net.rokoucha.visiomata.mirakurun.MirakurunConnectionUseCase
import net.rokoucha.visiomata.mirakurun.PlaybackSessionUseCase
import net.rokoucha.visiomata.settings.data.MirakurunSettingsUseCases
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    }
}
