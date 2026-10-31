package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Connecting
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Disconnected
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.DiscoveringServices
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.ReadingCharacteristics
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState.Done
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState.Failed
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun s(kind: GattStepKind, state: GattStepState, failure: GattFailure? = null) = GattStep(kind, state, null, null, null, null, failure)
private fun view(vararg steps: GattStep, ok: Boolean = false) = GattView("d", 7, 0, ok, steps.toList(), emptyList())

/** D-13.5: a failed discovery skips the remaining steps at the failed row and still ends with Disconnected. */
class GattDisconnectedTest {
    private fun kinds(ui: GattUi) = (ui as GattUi.Failed).view.steps.map { it.kind to it.state }

    private fun run(vararg events: GattEvent): GattUi =
        events.fold(reduceGatt(reduceGatt(GattState(), GattEvent.Discover).state, GattEvent.Authorized(7, 0)).state) { st, e -> reduceGatt(st, e).state }.ui

    private val timedOut = view(s(Connecting, Failed, GattFailure.TimedOut))
    private val dropped = view(s(Connecting, Done), s(DiscoveringServices, Done), s(ReadingCharacteristics, Failed, GattFailure.DisconnectedUnexpectedly))

    @Test
    fun `a timeout - the failed row, then Disconnected once the run ended`() {
        // shown as soon as it fails; the link isn't closed yet
        assertEquals(listOf(Connecting to Failed), kinds(run(GattEvent.Progress(1, timedOut))))
        // the run ended (the link is closed): the list ends with Disconnected
        assertEquals(listOf(Connecting to Failed, Disconnected to Done), kinds(run(GattEvent.Progress(1, timedOut), GattEvent.Finished(1, timedOut))))
        assertEquals(listOf(Connecting to Failed, Disconnected to Done), kinds(run(GattEvent.Finished(1, timedOut))))
    }

    @Test
    fun `disconnected unexpectedly - the remaining steps are skipped, the list ends with Disconnected`() {
        assertEquals(
            listOf(Connecting to Done, DiscoveringServices to Done, ReadingCharacteristics to Failed, Disconnected to Done),
            kinds(run(GattEvent.Progress(1, dropped), GattEvent.Finished(1, dropped))),
        )
    }

    @Test
    fun `the runner throwing, a stored failure - both end with Disconnected`() {
        val running = view(s(Connecting, Done), s(DiscoveringServices, GattStepState.Running))
        assertEquals(
            listOf(Connecting to Done, DiscoveringServices to Failed, Disconnected to Done),
            kinds(run(GattEvent.Progress(1, running), GattEvent.RunFailed(1), GattEvent.Finished(1, running))),
        )
        assertEquals(listOf(Connecting to Failed, Disconnected to Done), kinds(reduceGatt(GattState(), GattEvent.LastLoaded(timedOut)).state.ui))
    }

    @Test
    fun `no link, no Disconnected - preconditions, a success and a failure at Disconnected stay as they are`() {
        for (code in listOf(GattRunner.GATT_UNAVAILABLE, GattRunner.GATT_NO_TARGET)) {
            val pre = view(s(Connecting, Failed, GattFailure.Other(code)))
            assertEquals(listOf(Connecting to Failed), kinds(run(GattEvent.Finished(1, pre))))
        }
        val atDisconnect = view(s(Connecting, Done), s(DiscoveringServices, Done), s(ReadingCharacteristics, Done), s(Disconnected, Failed, GattFailure.TimedOut))
        assertEquals(atDisconnect.steps.size, (run(GattEvent.Finished(1, atDisconnect)) as GattUi.Failed).view.steps.size)
        val ok = view(s(Connecting, Done), s(DiscoveringServices, Done), s(ReadingCharacteristics, Done), s(Disconnected, Done), ok = true)
        assertEquals(GattUi.Done(ok), run(GattEvent.Finished(1, ok)))
        // a stale run's end changes nothing
        assertEquals(listOf(Connecting to Failed), kinds(run(GattEvent.Progress(1, timedOut), GattEvent.Finished(2, timedOut))))
    }
}

/** D-13.5 in the tab: the failed row, Disconnected, then Try again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class GattDisconnectedUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun d13_5_aFailedRunEndsWithDisconnected() {
        val dropped = view(s(Connecting, Done), s(DiscoveringServices, Failed, GattFailure.DisconnectedUnexpectedly))
        val gatt = GattController(7, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
            override fun discover(key: DeviceKey): Flow<GattView> = flowOf(dropped)
        }).apply { onDiscover(); onAuthorized() }
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.step(DiscoveringServices)).assertExists()
        compose.onNodeWithTag(DeviceTags.step(ReadingCharacteristics)).assertDoesNotExist() // skipped
        compose.onNodeWithTag(DeviceTags.step(Disconnected)).assertExists()
        compose.onNodeWithTag(DeviceTags.TRY_AGAIN).assertExists()
    }
}
