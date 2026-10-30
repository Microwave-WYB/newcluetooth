package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun step(kind: GattStepKind, state: GattStepState, failure: GattFailure? = null) = GattStep(kind, state, 800, null, null, null, failure)

private fun ok(id: String, service: String) = GattView(
    id, 7, 0, ok = true,
    steps = GattStepKind.entries.map { step(it, GattStepState.Done) },
    services = listOf(GattServiceView(service, null, emptyList())),
)

private val failed = GattView("f", 7, 0, ok = false, steps = listOf(step(GattStepKind.Connecting, GattStepState.Failed, GattFailure.TimedOut)), services = emptyList())
private val running = GattView("r", 7, 0, ok = false, steps = listOf(step(GattStepKind.Connecting, GattStepState.Done), step(GattStepKind.DiscoveringServices, GattStepState.Running)), services = emptyList())

/** D-13.7 "Discover again" in the GATT reducer: every transition with a last result. */
class GattDiscoverAgainTest {
    private val old = ok("old", "180f")
    private val new = ok("new", "180a")

    private fun after(s: GattState, vararg events: GattEvent) = events.fold(s) { st, e -> reduceGatt(st, e).state }

    private val read = after(GattState(), GattEvent.LastLoaded(old))

    @Test
    fun `a device already read opens on its last result, and Discover again leads to the same prompt every time`() {
        assertEquals(GattState(GattUi.Done(old), 0, old), read)
        val prompt = after(read, GattEvent.Discover)
        assertEquals(GattUi.Confirm, prompt.ui)
        assertEquals(old, prompt.last)
        // Cancel returns to the last result; asking again prompts again (never remembered)
        assertEquals(read, after(prompt, GattEvent.CancelPrompt))
        assertEquals(GattUi.Confirm, after(prompt, GattEvent.CancelPrompt, GattEvent.Discover).ui)
    }

    @Test
    fun `while a new run runs its steps replace the old ones and the last result stays, until it succeeds`() {
        val started = reduceGatt(after(read, GattEvent.Discover), GattEvent.Authorized(7, 0))
        assertEquals(listOf(GattEffect.StartRun(1)), started.effects)
        assertTrue(started.state.ui is GattUi.Running)
        assertEquals(old, started.state.last)
        val progressing = after(started.state, GattEvent.Progress(1, running))
        assertEquals(GattUi.Running(running), progressing.ui)
        assertEquals(old, progressing.last)
        // success replaces the last result
        val done = after(progressing, GattEvent.Finished(1, new))
        assertEquals(GattState(GattUi.Done(new), 1, new), done)
    }

    @Test
    fun `a failure shows its steps and Try again, with the last result kept below`() {
        val run = after(read, GattEvent.Discover, GattEvent.Authorized(7, 0))
        val fail = after(run, GattEvent.Progress(1, failed), GattEvent.Finished(1, failed))
        assertEquals(GattUi.Failed(failed), fail.ui)
        assertEquals(old, fail.last)
        // the runner throwing fails the running step, the last result kept too
        assertEquals(old, after(run, GattEvent.RunFailed(1)).last)
        // Try again prompts; cancelling it goes back to the last result
        val again = after(fail, GattEvent.TryAgain)
        assertEquals(GattUi.Confirm, again.ui)
        assertEquals(GattUi.Done(old), after(again, GattEvent.CancelPrompt).ui)
    }

    @Test
    fun `a cancelled or empty run goes back to the last result, never to a blank tab`() {
        val run = after(read, GattEvent.Discover, GattEvent.Authorized(7, 0))
        val cancelled = reduceGatt(run, GattEvent.Cancel)
        assertEquals(GattUi.Done(old), cancelled.state.ui)
        assertEquals(listOf(GattEffect.CancelRun), cancelled.effects)
        assertEquals(GattUi.Done(old), after(run, GattEvent.Finished(1, null)).ui)
        // never read: the same paths go back to Idle
        val fresh = after(GattState(), GattEvent.Discover, GattEvent.Authorized(7, 0), GattEvent.Cancel)
        assertEquals(GattState(GattUi.Idle, 1, null), fresh)
    }

    @Test
    fun `the last result changes only on a success`() {
        val events = listOf(
            GattEvent.Discover, GattEvent.Authorized(7, 0), GattEvent.Progress(1, running), GattEvent.Progress(1, failed),
            GattEvent.TryAgain, GattEvent.Discover, GattEvent.Authorized(7, 0), GattEvent.Cancel, GattEvent.Discover,
            GattEvent.CancelPrompt, GattEvent.LastLoaded(new),
        )
        var s = read
        for (e in events) {
            val next = reduceGatt(s, e).state
            if (next.last != s.last) assertTrue("$e changed the last result", next.ui is GattUi.Done)
            s = next
        }
        assertEquals(old, s.last) // a stored result loads only on a fresh tab
    }
}

/** D-13.7 in the tab: "Discover again" (tonal) above the last result; it stays below a run and a failure. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class GattDiscoverAgainUiTest {
    @get:Rule val compose = createComposeRule()

    private fun controller(vararg views: GattView) = GattController(7, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
        override fun discover(key: DeviceKey): Flow<GattView> = flowOf(*views)
    })

    @Test
    fun d13_7_aDeviceReadBeforeShowsDiscoverAgainAboveItsLastResult() {
        val gatt = controller().apply { showLast(ok("old", "180f")) }
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.NOTE).assertExists()
        compose.onNodeWithTag(DeviceTags.DISCOVER).assertTextEquals(Strings.DiscoverAgain)
        compose.onNodeWithTag(DeviceTags.LAST).assertExists()
        assertTrue(compose.onAllNodesWithText("180f", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun d13_7_aFailedNewRunShowsTryAgainWithTheLastResultBelow() {
        val gatt = controller(running, failed).apply { showLast(ok("old", "180f")); onDiscover(); onAuthorized() }
        assertTrue(gatt.state is GattUi.Failed)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.TRY_AGAIN).assertExists()
        compose.onNodeWithTag(DeviceTags.LAST).assertExists()
        assertTrue(compose.onAllNodesWithText("180f", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun d13_7_aSuccessfulNewRunReplacesTheLastResult() {
        val gatt = controller(running, ok("new", "180a")).apply { showLast(ok("old", "180f")); onDiscover(); onAuthorized() }
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        assertTrue(compose.onAllNodesWithText("180a", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodesWithText("180f", substring = true).assertCountEquals(0)
        compose.onNodeWithTag(DeviceTags.DISCOVER).assertTextEquals(Strings.DiscoverAgain)
    }
}
