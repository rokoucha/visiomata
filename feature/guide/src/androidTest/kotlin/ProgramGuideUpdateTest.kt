package net.rokoucha.visiomata.guide

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.Program
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.Service
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgramGuideUpdateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val date = LocalDate.of(2026, 9, 20)
    private val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
    private val type = ChannelType("GR")
    private val services =
        (1..4).map {
            Service(it.toLong(), it, it, it, "Station $it", type, "$it", it, null)
        }
    private val programs =
        services.map {
            Program(
                it.id,
                1,
                it.networkId,
                it.transportStreamId,
                it.serviceId,
                "Original program",
                "Unchanged description",
                start,
                start.plusSeconds(14400),
            )
        }

    @Test
    fun repeatedProgramUpdatesChangeVisiblePixels() {
        val state = mutableStateOf(ProgramGuide(services, programs))
        compose.setContent {
            MaterialTheme {
                ProgramGuideScreen(
                    guide = state.value,
                    channelTypes = listOf(type),
                    selectedType = type,
                    broadcastDate = date,
                    isTv = true,
                    isLoading = false,
                    availability = null,
                    onChannelTypeSelected = {},
                    onBroadcastDateChanged = {},
                    onPlay = {},
                    loadExtended = { emptyMap() },
                    loadLogo = { _, _ -> null },
                    modifier = Modifier.testTag("guide"),
                )
            }
        }
        compose.waitForIdle()
        Thread.sleep(2000)
        var previous = compose.onNodeWithTag("guide").captureToImage()
        repeat(12) { update ->
            compose.runOnIdle {
                state.value =
                    ProgramGuide(
                        services,
                        programs.mapIndexed { index, program ->
                            if (index == 0) program.copy(description = "Updated description $update") else program
                        },
                    )
            }
            compose.waitForIdle()
            val current = compose.onNodeWithTag("guide").captureToImage()
            val before = previous.toPixelMap()
            val after = current.toPixelMap()
            var changed = 0
            for (y in after.height / 4 until after.height step 2) {
                for (x in after.width / 16 until after.width / 4 step 2) {
                    if (before[x, y] != after[x, y]) changed++
                }
            }
            assertTrue("Update $update must change rendered program pixels", changed > 0)
            previous = current
            Thread.sleep(750)
        }
    }
}
