package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class SessionsListStateTest {
    /** L-2: grouped by day with the day heads; sessions without data never appear. */
    @Test fun groupsByDay() {
        val s = SessionsListState.build(Samples.current, Samples.all, Samples.finished, Scope.session("now"), Samples.now, Samples.utc)
        assertEquals(listOf("Today · Oct 28", "Yesterday · Oct 27", "Oct 25"), s.days.map { it.label })
        assertEquals(listOf("r1", "r2"), s.days[0].sessions.map { it.id })
        assertEquals(listOf("r3", "r5"), s.days[1].sessions.map { it.id }) // r4 has no devices
        assertFalse(s.days.flatMap { it.sessions }.any { it.id == "now" })
        assertEquals("now", s.current?.id)
        val idle = SessionsListState.build(null, Samples.all, Samples.finished, Scope.All, Samples.now, Samples.utc)
        assertNull(idle.current)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidebarScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun state(recording: Boolean, selected: Scope) =
        SessionsListState.build(if (recording) Samples.current else null, Samples.all, Samples.finished, selected, Samples.now, Samples.utc)

    private fun shot(name: String, dark: Boolean, tablet: Boolean, recording: Boolean) {
        val picked = mutableListOf<Scope>()
        var settings = 0
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Box(Modifier.width(if (tablet) 400.dp else 338.dp)) {
                    SessionsList(state(recording, if (recording) Scope.session("now") else Scope.session("r1")), { picked += it }, { settings++ }, tablet = tablet, zone = Samples.utc)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/sidebar/$name-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Yesterday · Oct 27").assertIsDisplayed()
        compose.onNodeWithText("23 min · 1.8 km · 214 devices").assertIsDisplayed()
        compose.onNodeWithText("8 min · 41 devices").assertIsDisplayed()
        compose.onNodeWithText("All sessions").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle { assertEquals(listOf(Scope.All), picked); assertEquals(1, settings) }
        if (recording) compose.onNodeWithText("Started 7:12 PM · 26 devices").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun recordingLight() = shot("phone-recording", false, false, true)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun recordingDark() = shot("phone-recording", true, false, true)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun historyLight() = shot("phone-history", false, false, false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletLight() = shot("tablet-history", false, true, false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletDark() = shot("tablet-history", true, true, false)

    /** L-1: the main screen slides by 82% and the dimmed strip closes the sidebar. */
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun phoneLayout() {
        var open by mutableStateOf(true)
        compose.setContent {
            CluetoothTheme(reducedMotion = true) {
                PhoneSidebarLayout(open, { open = false }, { m -> SessionsList(state(true, Scope.session("now")), {}, {}, m, zone = Samples.utc) }) {
                    Box(Modifier.fillMaxSize()) { Text("main") }
                }
                PhoneTopChrome(TopChromeState(hasSession = true, sidebarOpen = open), noActions, emptyList(), false)
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/sidebar/phone-open-light.png")
        compose.onNodeWithTag("sidebarDim").performClick()
        compose.runOnIdle { assertFalse(open) }
    }
}
