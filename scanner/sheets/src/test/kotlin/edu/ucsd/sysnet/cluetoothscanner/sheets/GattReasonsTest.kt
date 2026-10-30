package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D-13.5: every GATT failure reads as a plain-English reason, with its code kept as secondary text. */
class GattReasonsTest {
    private val now = 1_000_000L
    private val near = GattDeviceFacts(connectable = true, lastSeenMs = now - 2_000, nowMs = now)

    private fun reasonOf(status: Int, device: GattDeviceFacts = near) = gattProblem(GattFailure.Other(status), device)

    @Test
    fun `the runner's codes and Android's common statuses map to their own reasons`() {
        val expected = mapOf(
            GattRunner.GATT_UNAVAILABLE to "Bluetooth is off or permission is missing",
            GattRunner.GATT_NO_TARGET to "This device hasn't been seen with a connectable address yet. Stay near it and try again",
            GattRunner.GATT_INTERNAL_ERROR to "The Bluetooth stack failed during discovery",
            -2 to "The app was closed while the discovery ran",
            -3 to "The device stopped answering reads",
            133 to "The connection failed with a generic Bluetooth error. Try again",
            8 to "The device stopped responding",
            19 to "The device closed the connection",
            22 to "This phone closed the connection",
            62 to "Couldn't establish a connection. The device may be out of range",
            5 to "The device requires pairing",
            15 to "The device requires an encrypted (paired) connection",
        )
        for ((status, reason) in expected) {
            val problem = reasonOf(status)
            assertEquals("status $status", reason, problem.reason())
            assertEquals("status $status", "(code $status)", problem.codeText())
            assertTrue("status $status", problem !is GattProblem.Unknown)
        }
    }

    @Test
    fun `an engine crash is not Bluetooth being off - -6 and -4 read differently`() {
        assertEquals(GattProblem.EngineFailed, reasonOf(GattRunner.GATT_INTERNAL_ERROR))
        assertEquals("(code -6)", reasonOf(GattRunner.GATT_INTERNAL_ERROR).codeText())
        assertEquals(GattProblem.Unavailable, reasonOf(GattRunner.GATT_UNAVAILABLE))
        assertTrue(reasonOf(GattRunner.GATT_INTERNAL_ERROR).reason() != reasonOf(GattRunner.GATT_UNAVAILABLE).reason())
    }

    @Test
    fun `any other status is an unexpected Bluetooth error with its code`() {
        for (status in listOf(0, 1, 7, 128, 257, -1, -7, -99)) {
            val problem = reasonOf(status)
            assertEquals(GattProblem.Unknown(status), problem)
            assertEquals("Unexpected Bluetooth error", problem.reason())
            assertEquals("(code $status)", problem.codeText())
        }
    }

    @Test
    fun `no target says what the device sheet knows - not connectable, or not seen in the last 20 s`() {
        assertEquals(GattProblem.NotConnectable, reasonOf(GattRunner.GATT_NO_TARGET, near.copy(connectable = false)))
        assertEquals("This device doesn't accept connections", reasonOf(GattRunner.GATT_NO_TARGET, near.copy(connectable = false)).reason())
        val stale = near.copy(lastSeenMs = now - 20_001)
        assertEquals(GattProblem.NotSeenRecently, reasonOf(GattRunner.GATT_NO_TARGET, stale))
        assertEquals("(code -5)", reasonOf(GattRunner.GATT_NO_TARGET, stale).codeText())
        // exactly 20 s is still recent; unknown facts give the general reason; not connectable wins over stale
        assertEquals(GattProblem.NoTarget, reasonOf(GattRunner.GATT_NO_TARGET, near.copy(lastSeenMs = now - 20_000)))
        assertEquals(GattProblem.NoTarget, reasonOf(GattRunner.GATT_NO_TARGET, GattDeviceFacts.Unknown))
        assertEquals(GattProblem.NotConnectable, reasonOf(GattRunner.GATT_NO_TARGET, stale.copy(connectable = false)))
        // the facts only refine "no target"
        assertEquals(GattProblem.GattError, reasonOf(133, stale.copy(connectable = false)))
    }

    @Test
    fun `the runner's own outcomes read in words, without a code`() {
        val cases = mapOf(
            GattFailure.TimedOut to "The device didn't respond in time",
            GattFailure.DisconnectedUnexpectedly to "The device disconnected unexpectedly",
            GattFailure.ConnectionFailed to "Couldn't connect to the device",
            GattFailure.Cancelled to "Cancelled",
            null to "Failed",
        )
        for ((failure, reason) in cases) {
            val problem = gattProblem(failure, near)
            assertEquals("$failure", reason, problem.reason())
            assertNull("$failure", problem.codeText())
        }
    }

    @Test
    fun `no reason is a bare number`() {
        val all = listOf(-6, -5, -4, -3, -2, 0, 5, 8, 15, 19, 22, 62, 133, 999).map { reasonOf(it) } +
            listOf(GattProblem.NotConnectable, GattProblem.NotSeenRecently)
        // words, and the code only in the secondary text
        for (p in all) assertTrue(p.reason(), p.reason().any { it.isLetter() } && !p.reason().contains("${p.code}") && !p.reason().contains("status"))
    }
}

/** D-13.5 in the GATT tab: the reason under the failed step, the code as secondary text, never "Failed (status N)". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class GattReasonsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun failedWith(failure: GattFailure): GattController {
        val view = GattView(
            "d1", key = 7, startedMs = 0, ok = false,
            steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Failed, null, null, null, null, failure)),
            services = emptyList(),
        )
        return GattController(7, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
            override fun discover(key: DeviceKey): Flow<GattView> = flowOf(view)
        }).apply { onDiscover(); onAuthorized() }
    }

    @Test
    fun d13_5_noTargetShowsTheReasonAndTheCode() {
        val gatt = failedWith(GattFailure.Other(GattRunner.GATT_NO_TARGET))
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.REASON).assertTextEquals("This device hasn't been seen with a connectable address yet. Stay near it and try again")
        compose.onNodeWithTag(DeviceTags.CODE).assertTextEquals("(code -5)")
        compose.onNodeWithText("Failed (status -5)").assertDoesNotExist()
    }

    @Test
    fun d13_5_aNonConnectableDeviceSaysSo() {
        val gatt = failedWith(GattFailure.Other(GattRunner.GATT_NO_TARGET))
        val device = GattDeviceFacts(connectable = false, lastSeenMs = 0, nowMs = 0)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt, device) } }
        compose.onNodeWithTag(DeviceTags.REASON).assertTextEquals("This device doesn't accept connections")
    }

    @Test
    fun d13_5_aRunnerOutcomeHasNoCode() {
        val gatt = failedWith(GattFailure.TimedOut)
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { GattTab(gatt) } }
        compose.onNodeWithTag(DeviceTags.REASON).assertTextEquals("The device didn't respond in time")
        compose.onNodeWithTag(DeviceTags.CODE).assertDoesNotExist()
    }
}
