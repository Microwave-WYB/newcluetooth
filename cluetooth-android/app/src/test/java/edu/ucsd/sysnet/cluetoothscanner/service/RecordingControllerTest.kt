package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionCoordinator
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    private class Harness(scope: TestScope, startOutcome: ScannerStartOutcome = ScannerStartOutcome.STARTED) {
        val events = mutableListOf<String>()
        val rows = MutableStateFlow(100L) // rows of earlier sessions are not this recording's
        var sessionNumber = 0
        var stateSeenByHost: RecordingMode? = null
        lateinit var controller: RecordingController

        val host = object : RecordingHost {
            override fun onActive() {
                events += "host-active"
                stateSeenByHost = controller.state.value.mode
            }

            override fun onIdle() {
                events += "host-idle"
            }
        }
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessionNumber++; events += "session-start-$sessionNumber"; true },
            startScanner = { events += "scanner-start"; startOutcome },
            stopScannerAndFence = { events += "scanner-stop" },
            finishSession = { events += "session-finish"; true },
        )

        init {
            controller = RecordingController(
                coordinator = coordinator,
                observationCount = rows,
                host = host,
                scope = scope.backgroundScope,
                elapsedRealtimeMs = { scope.testScheduler.currentTime },
            )
        }
    }

    @Test
    fun `start begins a session and a scan and turns the foreground service on`() = runTest {
        val h = Harness(this)
        assertEquals(RecordingState(), h.controller.state.value)

        h.controller.start()
        runCurrent()

        assertEquals(RecordingMode.RECORDING, h.controller.state.value.mode)
        assertEquals(listOf("session-start-1", "scanner-start", "host-active"), h.events)
        // The service reads the state when it starts, so it must already say RECORDING.
        assertEquals(RecordingMode.RECORDING, h.stateSeenByHost)
    }

    @Test
    fun `rows and elapsed time count from the start of this recording`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        h.rows.value = 130
        advanceTimeBy(5_000)
        runCurrent()

        val state = h.controller.state.value
        assertEquals(30L, state.rowCount)
        assertTrue("elapsed ${state.elapsedMs}", state.elapsedMs in 4_000..5_000)
    }

    @Test
    fun `pause keeps the session, stops the scanner and freezes the clock`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        advanceTimeBy(10_000)
        h.rows.value = 150
        h.controller.pause()
        runCurrent()

        assertEquals(RecordingMode.PAUSED, h.controller.state.value.mode)
        assertEquals(listOf("session-start-1", "scanner-start", "host-active", "scanner-stop"), h.events)
        assertFalse("session must stay open", h.events.contains("session-finish"))
        assertFalse("service must stay up while paused", h.events.contains("host-idle"))
        val frozen = h.controller.state.value.elapsedMs
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(frozen, h.controller.state.value.elapsedMs)
        assertEquals(50L, h.controller.state.value.rowCount)
    }

    @Test
    fun `resume continues the same session and the same counters`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        advanceTimeBy(10_000)
        h.controller.pause()
        runCurrent()
        advanceTimeBy(60_000)
        h.controller.resume()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(RecordingMode.RECORDING, h.controller.state.value.mode)
        // One session; the scanner started twice; the service was told once.
        assertEquals(1, h.events.count { it.startsWith("session-start") })
        assertEquals(2, h.events.count { it == "scanner-start" })
        assertEquals(1, h.events.count { it == "host-active" })
        // 10 s before the pause plus about 5 s after, not the 60 s paused.
        assertTrue("elapsed ${h.controller.state.value.elapsedMs}", h.controller.state.value.elapsedMs in 14_000..16_000)
    }

    @Test
    fun `stop from recording finishes the session and releases the service`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        h.rows.value = 112
        h.controller.stop()
        runCurrent()

        assertEquals(RecordingMode.IDLE, h.controller.state.value.mode)
        assertEquals(listOf("session-start-1", "scanner-start", "host-active", "scanner-stop", "session-finish", "host-idle"), h.events)
        assertEquals(12L, h.controller.state.value.rowCount) // the last recording's total stays visible
        assertFalse(h.controller.wantsRecording)
    }

    @Test
    fun `stop from paused finishes the session too`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        h.controller.pause()
        runCurrent()
        h.controller.stop()
        runCurrent()

        assertEquals(RecordingMode.IDLE, h.controller.state.value.mode)
        assertEquals(1, h.events.count { it == "session-finish" })
        assertEquals(1, h.events.count { it == "host-idle" })
        assertEquals("host-idle", h.events.last())
    }

    @Test
    fun `a new recording after stop is a fresh session with zeroed counters`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        advanceTimeBy(5_000)
        h.rows.value = 140
        h.controller.stop()
        runCurrent()
        h.controller.start()
        runCurrent()

        assertEquals(2, h.events.count { it.startsWith("session-start") })
        assertEquals(RecordingMode.RECORDING, h.controller.state.value.mode)
        assertEquals(0L, h.controller.state.value.rowCount)
        assertTrue(h.controller.state.value.elapsedMs < 1_000)
        assertTrue(h.controller.wantsRecording)
    }

    @Test
    fun `pause when nothing is recording does nothing and resume only acts on a paused recording`() = runTest {
        val h = Harness(this)
        h.controller.pause()
        h.controller.resume()
        runCurrent()
        // resume() states the intent to record but, with nothing paused, starts nothing.
        assertEquals(emptyList<String>(), h.events)
        assertEquals(RecordingMode.IDLE, h.controller.state.value.mode)
    }

    @Test
    fun `a deferred scan (bluetooth off) still counts as recording and can be paused`() = runTest {
        val h = Harness(this, ScannerStartOutcome.DEFERRED_INTENT_RETAINED)
        h.controller.start()
        runCurrent()
        assertEquals(ScanTransitionState.DEFERRED, h.coordinator.state.value)
        assertEquals(RecordingMode.RECORDING, h.controller.state.value.mode)

        h.controller.pause()
        runCurrent()
        assertEquals(RecordingMode.PAUSED, h.controller.state.value.mode)
    }

    @Test
    fun `an Activity coming back never resumes a paused recording`() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        h.controller.pause()
        runCurrent()

        edu.ucsd.sysnet.cluetoothscanner.ui.reconcileScanReadiness(
            edu.ucsd.sysnet.cluetoothscanner.ui.ScanReadiness(true, true, true),
            h.controller.wantsRecording,
            h.coordinator,
        )
        assertEquals(ScanTransitionState.PAUSED, h.coordinator.state.value)
        assertEquals(1, h.events.count { it == "scanner-start" })
    }

    @Test
    fun `notification text shows mode, clock and row count`() {
        assertEquals("Recording", RecordingNotificationText.title(RecordingState(RecordingMode.RECORDING)))
        assertEquals("Recording paused", RecordingNotificationText.title(RecordingState(RecordingMode.PAUSED)))
        assertEquals("0:05 · 0 rows", RecordingNotificationText.text(RecordingState(RecordingMode.RECORDING, 5_000, 0)))
        assertEquals("12:34 · 1,234 rows", RecordingNotificationText.text(RecordingState(RecordingMode.RECORDING, 754_000, 1_234)))
        assertEquals("1:02:03 · 20 rows", RecordingNotificationText.text(RecordingState(RecordingMode.PAUSED, 3_723_000, 20)))
    }
}
