package net.rokoucha.visiomata.home

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.ServiceGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TvHomeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showHome(
        cardCount: Int = 12,
        onPlay: (Long) -> Unit = {},
        restoration: StateRestorationTester? = null,
    ) {
        val choices =
            listOf("GR", "BS", "CS").flatMapIndexed { row, type ->
                List(cardCount) { index ->
                    ServiceGroup(
                        id = "$type-$index",
                        primaryServiceId = (row * 100 + index).toLong(),
                        channelType = ChannelType(type),
                        logicalChannelNumber = index.toString(),
                        serviceName = "$type-$index",
                        logoLabel = type,
                        logoId = null,
                        current = null,
                        next = null,
                        variants = emptyList(),
                    )
                }
            }
        val content: @Composable () -> Unit = {
            MaterialTheme {
                androidx.tv.material3.MaterialTheme {
                    TvHomeScreen(onPlay = onPlay, onGuide = {}, onSettings = {}, choices = choices)
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
        assertCardVisible("GR-0")
    }

    private fun move(
        key: Int,
        count: Int = 1,
    ) {
        repeat(count) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
            // Injected Android keys do not advance Compose's test animation clock.
            compose.mainClock.advanceTimeBy(80)
        }
        compose.waitForIdle()
    }

    private fun assertCardVisible(name: String) {
        val card = compose.onNodeWithText(name).assertIsFocused().assertIsDisplayed()
        val bounds = card.getBoundsInRoot()
        assertEquals(256f, (bounds.right - bounds.left).value, 0.5f)
        assertEquals(184f, (bounds.bottom - bounds.top).value, 0.5f)
        val viewport = compose.onRoot().getBoundsInRoot()
        assertTrue("Left screen margin", bounds.left.value >= viewport.left.value + 63.5f)
        assertTrue("Right screen margin", bounds.right.value <= viewport.right.value - 63.5f)
        assertTrue("Bottom screen margin", bounds.bottom.value <= viewport.bottom.value - 47.5f)
    }

    @Test
    fun screenRestorationRetainsHorizontalPositionAndSelection() {
        val restoration = StateRestorationTester(compose)
        showHome(restoration = restoration)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
        assertCardVisible("GR-8")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertCardVisible("GR-8")
    }

    @Test
    fun activationAfterRapidNavigationUsesTheRequestedCard() {
        var played: Long? = null
        showHome(cardCount = 24, onPlay = { played = it })
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 16)
        move(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(16L, played)
    }

    @Test
    fun rapidMixedDirectionsDoNotLeaveAStaleFocusRequest() {
        showHome(cardCount = 24)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 16)
        compose.waitForIdle()
        assertCardVisible("GR-16")
        move(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        move(KeyEvent.KEYCODE_DPAD_UP, 2)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 16)
        compose.waitForIdle()
        assertCardVisible("GR-0")
    }

    @Test
    fun horizontalEdgesAndReversalsKeepFocusVisible() {
        showHome()
        move(KeyEvent.KEYCODE_DPAD_LEFT)
        assertCardVisible("GR-0")
        repeat(2) {
            for (index in 1..11) {
                move(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertCardVisible("GR-$index")
            }
            move(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertCardVisible("GR-11")
            for (index in 10 downTo 0) {
                move(KeyEvent.KEYCODE_DPAD_LEFT)
                assertCardVisible("GR-$index")
            }
        }
    }

    @Test
    fun verticalNavigationRestoresEachRowsOwnCardAndHeaderReturn() {
        showHome()
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
        assertCardVisible("GR-8")
        move(KeyEvent.KEYCODE_DPAD_DOWN)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 12)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        assertCardVisible("BS-2")
        move(KeyEvent.KEYCODE_DPAD_DOWN)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 12)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 5)
        assertCardVisible("CS-5")
        repeat(4) {
            move(KeyEvent.KEYCODE_DPAD_UP)
            assertCardVisible("BS-2")
            move(KeyEvent.KEYCODE_DPAD_UP)
            assertCardVisible("GR-8")
            move(KeyEvent.KEYCODE_DPAD_DOWN)
            assertCardVisible("BS-2")
            move(KeyEvent.KEYCODE_DPAD_DOWN)
            assertCardVisible("CS-5")
        }
        move(KeyEvent.KEYCODE_DPAD_UP, 3)
        compose.onNode(isFocused()).assert(hasText("番組表") or hasText("設定"))
        move(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardVisible("GR-8")
    }
}
