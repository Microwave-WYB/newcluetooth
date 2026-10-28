package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.recordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting a recording on Scan and then leaving for a page where the control turns into the
 * top-right pill (Nearby, History, a sub-page such as a category or a map) used to crash
 * ("Placement happened before lookahead.") in the record control's shared-bounds transition. This
 * drives the real shell through those changes, with and without the animation clock, switches pages
 * in the middle of a transition, and checks that the record button exists only on Scan.
 */
@RunWith(AndroidJUnit4::class)
class RecordControlTransitionTest {
    @get:Rule
    val rule = createComposeRule()

    /** A page: a tab, or a sub-page (no tab, like a category or a map) when [tab] is null. */
    private data class Page(val name: String, val tab: AppTab?)

    private val scan = Page("Scan", AppTab.Scan)
    private val pages = listOf(Page("Nearby", AppTab.Nearby), Page("History", AppTab.History), Page("Category", null), Page("Map", null))

    private var phase by mutableStateOf(RecorderPhase.Idle)
    private var page by mutableStateOf(Page("Scan", AppTab.Scan))
    private var controls by mutableStateOf(false)

    private fun setUp() {
        rule.setContent {
            V3Theme {
                val recorder = RecorderUiState(phase, elapsedSeconds = 5, sightings = 10)
                val onScan = page.tab == AppTab.Scan
                AppShell(
                    recorder = recorder,
                    callbacks = RecorderCallbacks(
                        onStart = { phase = RecorderPhase.Recording },
                        onPause = { phase = RecorderPhase.Paused },
                        onResume = { phase = RecorderPhase.Recording },
                        onStop = { phase = RecorderPhase.Idle; controls = false },
                        onOpenControls = { controls = true },
                    ),
                    placement = recordPlacement(phase, roomForExpanded = onScan && !controls),
                    tab = page.tab,
                    onTab = { t -> page = Page(t.name, t) },
                    controlBarOpen = controls,
                    onDismissControlBar = { controls = false },
                    startAllowed = onScan,
                ) { Box(Modifier.fillMaxSize()) { Text(page.name) } }
            }
        }
    }

    private fun goTo(p: Page) {
        rule.runOnIdle { page = p }
        rule.waitForIdle()
    }

    private fun startOnScan() {
        goTo(scan)
        rule.onNodeWithContentDescription("Record").performClickAndSettle()
        rule.onNodeWithContentDescription("Stop").assertIsDisplayed()
    }

    @Test
    fun theRecordButtonIsOnlyOnScan() {
        setUp()
        rule.onNodeWithContentDescription("Record").assertIsDisplayed()
        for (p in pages) {
            goTo(p)
            rule.onNodeWithContentDescription("Record").assertDoesNotExist()
            rule.onNodeWithContentDescription("Recorder controls").assertDoesNotExist()
        }
        goTo(scan)
        rule.onNodeWithContentDescription("Record").assertIsDisplayed()
    }

    @Test
    fun startOnScanThenPauseResumeAndStopFromThePillOnEveryOtherPage() {
        setUp()
        for (p in pages) {
            startOnScan()
            goTo(p)
            rule.onNodeWithContentDescription("Record").assertDoesNotExist()
            rule.onNodeWithContentDescription("Recorder controls").performClickAndSettle()
            rule.onNodeWithText("Pause").performClickAndSettle()
            rule.onNodeWithText("Resume").performClickAndSettle()
            rule.onNodeWithText("Pause").assertIsDisplayed()
            rule.onNodeWithText("Stop").performClickAndSettle()
            // Stopped off Scan: no pill, no sheet and no way to start until back on Scan.
            rule.onNodeWithContentDescription("Recorder controls").assertDoesNotExist()
            rule.onNodeWithText("Stop").assertDoesNotExist()
            rule.onNodeWithContentDescription("Record").assertDoesNotExist()
        }
        goTo(scan)
        rule.onNodeWithContentDescription("Record").assertIsDisplayed()
    }

    @Test
    fun walkingEveryPageWhileRecordingAndPausedFromTheExpandedControl() {
        setUp()
        startOnScan()
        rule.onNodeWithContentDescription("Pause").performClickAndSettle()
        for (p in pages + scan + pages.reversed()) {
            goTo(p)
            if (p.tab == AppTab.Scan) rule.onNodeWithContentDescription("Resume").assertIsDisplayed()
            else rule.onNodeWithContentDescription("Recorder controls").assertIsDisplayed()
        }
        goTo(scan)
        rule.onNodeWithContentDescription("Resume").performClickAndSettle()
        rule.onNodeWithContentDescription("Stop").performClickAndSettle()
        rule.onNodeWithContentDescription("Record").assertIsDisplayed()
    }

    @Test
    fun pageSwitchesAndPhaseChangesInTheMiddleOfTransitions() {
        setUp()
        rule.mainClock.autoAdvance = false
        repeat(3) {
            for (p in pages) {
                // Start on Scan, leave mid-transition, pause and stop while the pill fades in, then go back.
                rule.runOnIdle { page = scan; phase = RecorderPhase.Recording }
                rule.mainClock.advanceTimeByFrame()
                rule.runOnIdle { page = p }
                rule.mainClock.advanceTimeBy(48)
                rule.runOnIdle { phase = RecorderPhase.Paused; controls = true }
                rule.mainClock.advanceTimeByFrame()
                rule.runOnIdle { phase = RecorderPhase.Idle; controls = false }
                rule.mainClock.advanceTimeBy(32)
                rule.runOnIdle { page = scan }
                rule.mainClock.advanceTimeBy(80)
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Record").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndSettle() {
        performClick()
        rule.waitForIdle()
    }
}
