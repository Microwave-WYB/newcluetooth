package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D-13.1: the safety note shows above the button area whenever a button is present, Failed included. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class GattTabUiTest {
    @get:Rule val compose = createComposeRule()

    private val failed = GattView(
        "d1", key = 7, startedMs = 0, ok = false,
        steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Failed, null, null, null, null, GattFailure.TimedOut)),
        services = emptyList(),
    )

    private fun controller(view: GattView) = GattController(7, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
        override fun discover(key: DeviceKey): Flow<GattView> = flowOf(view)
    })

    @Test
    fun d13_1_theNoteShowsWithTryAgainWhenFailed() {
        val gatt = controller(failed).apply { onDiscover(); onAuthorized() }
        assertTrue(gatt.state is GattUi.Failed)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.TRY_AGAIN).assertExists()
        compose.onNodeWithTag(DeviceTags.NOTE).assertExists()
    }

    @Test
    fun d13_1_theNoteShowsWithDiscoverAndThePrompt() {
        val gatt = controller(failed)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.NOTE).assertExists() // Idle: Discover GATT
        gatt.onDiscover()
        compose.waitForIdle()
        compose.onNodeWithTag(DeviceTags.NOTE).assertExists() // Confirm: Cancel | I am authorized
    }

    @Test
    fun d13_1_noNoteWithoutButtons() {
        // a running discovery has no button, so no note (D-13.7: a read device shows "Discover again", with the note)
        val runningView = failed.copy(steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Running, null, null, null, null, null)))
        val running = GattController(7, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
            override fun discover(key: DeviceKey): Flow<GattView> = kotlinx.coroutines.flow.flow { emit(runningView); kotlinx.coroutines.awaitCancellation() }
        })
        running.onDiscover(); running.onAuthorized()
        assertTrue(running.state is GattUi.Running)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(running) } }
        compose.onNodeWithTag(DeviceTags.NOTE).assertDoesNotExist()
    }
}
