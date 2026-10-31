package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-4 (`.phone.rec-top-open .menu-btn, .search-box { pointer-events: none }`): while the phone recorder panel is open,
 * ☰ and search take no taps, so the panel's Stop and Pause over them get theirs; closed, they work again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class TopChromeTapsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRowUnderTheOpenPanelPassesTapsThrough() {
        var below = 0
        var menu = 0
        var search = 0
        var panelOpen by mutableStateOf(true)
        val actions = TopChromeActions({ menu++ }, { search++ }, {}, {}, {}, {}, {}, {}, {})
        compose.setContent {
            CluetoothTheme(reducedMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    // what lies under the top chrome: the panel's buttons in the app
                    Box(Modifier.fillMaxSize().testTag("below").clickable { below++ })
                    PhoneTopChrome(TopChromeState(hasSession = true, recording = true, recorderPanelOpen = panelOpen), actions, emptyList(), false)
                }
            }
        }
        val dp = compose.density.density
        val row = (Tokens.edge + Tokens.rb / 2).value * dp
        val left = Offset(row, row)
        val right = Offset(412.dp.value * dp - row, row)
        compose.onNodeWithTag("below").performTouchInput { click(left) }
        compose.onNodeWithTag("below").performTouchInput { click(right) }
        compose.runOnIdle { assertEquals(Triple(2, 0, 0), Triple(below, menu, search)) }
        panelOpen = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sessions and settings").performClick()
        compose.onNodeWithContentDescription("Search devices").performClick()
        compose.runOnIdle { assertEquals(Triple(2, 1, 1), Triple(below, menu, search)) }
    }
}
