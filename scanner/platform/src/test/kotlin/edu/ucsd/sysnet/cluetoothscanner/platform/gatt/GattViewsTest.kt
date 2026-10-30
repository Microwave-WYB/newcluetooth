package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Connecting
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Disconnected
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.DiscoveringServices
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.ReadingCharacteristics
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-13 rows and the §2.7 failure table, driven through the ported v3 engine and its fake links. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattViewsTest {
    private val request = GattRequest(
        id = "0195ca99-5000-7131-b131-313131313131",
        sessionId = null,
        target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null),
        location = null,
        appVersion = "0.1.0-test",
        deviceModel = "Pixel 8",
        androidVersion = "15 (API 35)",
    )

    private suspend fun TestScope.run(behavior: FakeLink.Behavior, progress: MutableList<GattProgress> = mutableListOf()) =
        GattEngine(FakeFactory(attempts = arrayOf(behavior)), GattTimeouts(), nowMs = { testScheduler.currentTime }, onProgress = { progress += it })
            .run(request)

    private fun kinds(view: edu.ucsd.sysnet.cluetoothscanner.api.core.GattView) = view.steps.map { it.kind }

    @Test
    fun `D-13 success shows four done steps and the services`() = runTest {
        val view = resultView(run(Cooperative), key = 7)
        assertTrue(view.ok)
        assertEquals(GattStepKind.entries, kinds(view))
        assertTrue(view.steps.all { it.state == GattStepState.Done })
        assertEquals(2, view.steps[1].services)
        assertEquals(view.steps[2].readable, view.steps[2].read)
        assertEquals(listOf("180A", "180F"), view.services.map { it.uuid })
        assertEquals(7L, view.key)
        assertEquals(request.id, view.discoveryId)
    }

    @Test
    fun `D-13 progress rows run one by one with 0_8 s, N services and m of n`() = runTest {
        val progress = mutableListOf<GattProgress>()
        run(Cooperative, progress)
        val tracker = GattStepTracker()
        val views = progress.map(tracker::steps)
        assertEquals(listOf(Connecting), views.first().map { it.kind })
        val reading = views.first { it.last().kind == ReadingCharacteristics }
        assertEquals(GattStepState.Done, reading[0].state)
        assertEquals(2, reading[1].services)
        assertEquals(GattStepState.Running, reading[2].state)
        assertEquals(0, reading[2].read)
        val disconnecting = views.last()
        assertEquals(Disconnected, disconnecting.last().kind)
        assertEquals(GattStepState.Done, disconnecting[2].state)
        assertEquals(disconnecting[2].readable, disconnecting[2].read)
        assertTrue("MTU is folded into Connecting", disconnecting[0].durationMs != null)
    }

    @Test
    fun `section 2_7 an unanswered connect times out at Connecting`() = runTest {
        val view = resultView(run(object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit
        }), key = 1)
        assertFalse(view.ok)
        assertEquals(listOf(Connecting), kinds(view))
        assertEquals(GattFailure.TimedOut, view.steps.single().failure)
        assertTrue(view.services.isEmpty())
    }

    @Test
    fun `section 2_7 status 133 before connect fails Connecting with its code`() = runTest {
        val view = resultView(run(object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = link.emit(GattEvent.Disconnected(GattStatus.ERROR))
        }), key = 1)
        assertEquals(listOf(Connecting), kinds(view))
        assertEquals("#92: the Android status reaches the UI", GattFailure.Other(GattStatus.ERROR), view.steps.single().failure)
    }

    @Test
    fun `section 2_7 discovery that never answers times out at Discovering`() = runTest {
        val view = resultView(run(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        }), key = 1)
        assertEquals(listOf(Connecting, DiscoveringServices), kinds(view))
        assertEquals(GattStepState.Done, view.steps[0].state)
        assertEquals(GattFailure.TimedOut, view.steps[1].failure)
    }

    @Test
    fun `section 2_7 a remote disconnect while reading is unexpected`() = runTest {
        val view = resultView(run(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER))
                return true
            }
        }), key = 1)
        assertFalse(view.ok)
        assertEquals(listOf(Connecting, DiscoveringServices, ReadingCharacteristics), kinds(view))
        assertEquals(GattFailure.Other(GattStatus.CONN_TERMINATE_PEER_USER), view.steps.last().failure)
        assertTrue("D-13.6: no services after a failure", view.services.isEmpty())
    }

    private fun result(error: GattError?, disconnect: GattDisconnect?, outcome: GattOutcome = GattOutcome.FAILED) = GattDiscoveryResult(
        request = GattRequest("0195ca99-5000-7131-b131-313131313131", null, GattTarget("AA:BB:CC:DD:EE:FF", "random", null, null), null, "0.1.0", "Pixel 8", "15 (API 35)"),
        startedAtMs = 0, endedAtMs = 1, outcome = outcome, stageReached = error?.stage ?: GattStage.READ,
        error = error, disconnect = disconnect, mtu = null, phy = null, timings = GattTimings(null, null, null, null), services = emptyList(),
    )

    @Test
    fun `an engine crash keeps its own code, never Bluetooth unavailable (#92 review)`() {
        val crashed = result(GattError(GattStage.READ, GattStatus.INTERNAL_ERROR, "INTERNAL_ERROR"), null)
        assertEquals(GattFailure.Other(GattRunner.GATT_INTERNAL_ERROR), failure(crashed))
        val codes = listOf(GattRunner.GATT_UNAVAILABLE, GattRunner.GATT_NO_TARGET, GattRunner.GATT_INTERNAL_ERROR)
        assertEquals("the runner codes are distinct", codes.size, codes.toSet().size)
        assertTrue(
            "and none is an engine status",
            codes.none { it in listOf(GattStatus.TIMED_OUT, GattStatus.PROCESS_DEATH, GattStatus.READ_QUEUE_STALLED) },
        )
    }

    @Test
    fun `a known status is carried, the named failures stay for when none is known (#92 review)`() {
        assertEquals(GattFailure.Other(62), failure(result(GattError(GattStage.CONNECT, 62, "x"), null)))
        assertEquals(GattFailure.Other(19), failure(result(null, GattDisconnect(DisconnectInitiator.REMOTE, 19))))
        assertEquals(GattFailure.DisconnectedUnexpectedly, failure(result(null, GattDisconnect(DisconnectInitiator.REMOTE, null))))
        assertEquals(GattFailure.DisconnectedUnexpectedly, failure(result(null, GattDisconnect(DisconnectInitiator.REMOTE, GattStatus.SUCCESS))))
        assertEquals(GattFailure.ConnectionFailed, failure(result(GattError(GattStage.CONNECT, GattStatus.SUCCESS, "x"), null)))
        assertEquals(GattFailure.TimedOut, failure(result(GattError(GattStage.CONNECT, GattStatus.TIMED_OUT, "x"), null)))
        assertEquals(GattFailure.Cancelled, failure(result(null, null, GattOutcome.CANCELLED)))
    }
}
