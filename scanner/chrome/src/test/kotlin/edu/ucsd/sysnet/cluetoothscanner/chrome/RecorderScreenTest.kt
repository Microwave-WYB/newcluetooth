package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.chrome.gallery.DiscoverySamples
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecorderScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun ui(form: RecorderUi.Form, paused: Boolean = false, discovery: DiscoveryBins = DiscoverySamples.rising) =
        RecorderUi(form, paused, DiscoverySamples.ELAPSED_MS, discovery)

    private fun shots(tablet: Boolean, size: String, dark: Boolean) {
        run {
            var state by mutableStateOf(ui(RecorderUi.Form.Fab))
            val log = mutableListOf<String>()
            val actions = RecorderActions(
                onStart = { log += "start"; state = ui(RecorderUi.Form.Capsule) },
                onExpand = { log += "expand"; state = state.copy(form = RecorderUi.Form.Panel) },
                onCollapse = { log += "collapse"; state = state.copy(form = RecorderUi.Form.Capsule) },
                onPause = { log += "pause"; state = state.copy(paused = true) },
                onResume = { log += "resume"; state = state.copy(paused = false) },
                onStop = { log += "stop"; state = state.copy(form = RecorderUi.Form.Fab) },
            )
            compose.setContent {
                CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                    Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                        RecorderControl(state, actions, tablet, bottomCover = if (tablet) Tokens0 else Peek)
                    }
                }
            }
            val scheme = if (dark) "dark" else "light"
            fun shot(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/recorder/$size-$scheme-$name.png")
            shot("fab")
            compose.onNodeWithContentDescription("Record").performClick(); compose.waitForIdle(); shot("capsule")
            compose.onNodeWithContentDescription("Pause").performClick(); compose.waitForIdle(); shot("capsule-paused")
            compose.onNodeWithTag("recorderOpen").performClick(); compose.waitForIdle(); shot("panel")
            compose.onNodeWithTag("recorderCatch").performClick(); compose.waitForIdle()
            compose.onNodeWithContentDescription("Resume").performClick()
            compose.onNodeWithContentDescription("Stop").performClick(); compose.waitForIdle()
            assertEquals(listOf("start", "pause", "expand", "collapse", "resume", "stop"), log)
        }
    }

    /**
     * R-4: the catch is behind the panel (`.recorder-catch` in the prototype): a tap on the panel itself, its chart
     * or its empty space, keeps it open, and its buttons still work; only a tap outside collapses it.
     */
    private fun insideTapsKeepThePanel(tablet: Boolean) {
        val log = mutableListOf<String>()
        val actions = RecorderActions({ log += "start" }, { log += "expand" }, { log += "collapse" }, { log += "pause" },
            { log += "resume" }, { log += "stop" })
        compose.setContent {
            CluetoothTheme(reducedMotion = true) {
                Box(Modifier.fillMaxSize()) { RecorderControl(ui(RecorderUi.Form.Panel), actions, tablet, bottomCover = if (tablet) Tokens0 else Peek) }
            }
        }
        compose.onNodeWithTag("discoveryChart").performClick()
        // the panel's empty start of the row, left of the centered dot and time
        compose.onNodeWithTag("recorder").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.05f, height / 2f)) }
        compose.runOnIdle { assertEquals(emptyList<String>(), log) }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithTag("recorderCatch").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height - 10f)) }
        compose.runOnIdle { assertEquals(listOf("pause", "collapse"), log) }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneInsideTapsKeepThePanel() = insideTapsKeepThePanel(false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletInsideTapsKeepThePanel() = insideTapsKeepThePanel(true)

    /** R-4a: the panel's chart for each case: just started (empty), walking (rising), standing still, after a pause,
     *  and a rate that drops at its end (under the label). */
    private fun chartShots(tablet: Boolean, size: String, dark: Boolean) {
        val cases = listOf(
            Triple("empty", DiscoverySamples.empty, DiscoverySamples.EMPTY_ELAPSED_MS),
            Triple("rising", DiscoverySamples.rising, DiscoverySamples.ELAPSED_MS),
            Triple("stationary", DiscoverySamples.stationary, DiscoverySamples.ELAPSED_MS),
            Triple("pause-gap", DiscoverySamples.pauseGap, DiscoverySamples.ELAPSED_MS),
            Triple("drop", DiscoverySamples.dropAtEnd, DiscoverySamples.DROP_ELAPSED_MS),
        )
        var case by mutableStateOf(cases[0])
        val none = RecorderActions({}, {}, {}, {}, {}, {})
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                    RecorderControl(RecorderUi(RecorderUi.Form.Panel, false, case.third, case.second), none, tablet, bottomCover = if (tablet) Tokens0 else Peek)
                }
            }
        }
        val labels = mutableListOf<String?>()
        for (c in cases) {
            case = c
            compose.waitForIdle()
            labels += compose.onNodeWithTag("discoveryChart").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single()
            compose.onRoot().captureRoboImage("src/test/screenshots/recorder/$size-${if (dark) "dark" else "light"}-chart-${c.first}.png")
        }
        // the one label is the current value; none before the first bin completes
        assertEquals(listOf(null, "29 new/min", "0 new/min", "15 new/min", "0 new/min"), labels)
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneChartLight() = chartShots(false, "412x915", false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneChartDark() = chartShots(false, "412x915", true)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletChartLight() = chartShots(true, "1024x768", false)

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneLight() = shots(false, "412x915", false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneDark() = shots(false, "412x915", true)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletLight() = shots(true, "1024x768", false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletDark() = shots(true, "1024x768", true)

    private companion object {
        val Peek = androidx.compose.ui.unit.Dp(78f)
        val Tokens0 = androidx.compose.ui.unit.Dp(0f)
    }
}
