package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.service.ScannerStartOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTransitionCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidStopThenStartWaitsForFinishAndCreatesFreshSession() = runTest {
        val events = mutableListOf<String>()
        val finishAllowed = CompletableDeferred<Boolean>()
        var sessionNumber = 0
        val coordinator = ScanTransitionCoordinator(
            startSession = {
                sessionNumber++
                events += "start-session-$sessionNumber"
                true
            },
            startScanner = {
                events += "start-scanner-$sessionNumber"
                ScannerStartOutcome.STARTED
            },
            stopScannerAndFence = { events += "stop-fence" },
            finishSession = {
                events += "finish-begin"
                finishAllowed.await().also { events += "finish-ack" }
            },
        )
        assertTrue(coordinator.start())

        val stop = async { coordinator.stop() }
        runCurrent()
        val restart = async { coordinator.start() }
        runCurrent()

        assertEquals(ScanTransitionState.FINALIZING, coordinator.state.value)
        assertEquals(
            listOf("start-session-1", "start-scanner-1", "stop-fence", "finish-begin"),
            events,
        )
        finishAllowed.complete(true)
        assertTrue(stop.await())
        assertTrue(restart.await())
        assertEquals(
            listOf(
                "start-session-1",
                "start-scanner-1",
                "stop-fence",
                "finish-begin",
                "finish-ack",
                "start-session-2",
                "start-scanner-2",
            ),
            events,
        )
        assertEquals(ScanTransitionState.SCANNING, coordinator.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedFinishRejectsQueuedRestartAndNeverStartsSessionless() = runTest {
        val events = mutableListOf<String>()
        val finishAllowed = CompletableDeferred<Boolean>()
        val coordinator = ScanTransitionCoordinator(
            startSession = { events += "start-session"; true },
            startScanner = { events += "start-scanner"; ScannerStartOutcome.STARTED },
            stopScannerAndFence = { events += "stop-fence" },
            finishSession = {
                events += "finish-begin"
                finishAllowed.await()
            },
        )
        assertTrue(coordinator.start())

        val stop = async { coordinator.stop() }
        runCurrent()
        val restart = async { coordinator.start() }
        runCurrent()
        finishAllowed.complete(false)

        assertFalse(stop.await())
        assertFalse(restart.await())
        assertEquals(1, events.count { it == "start-session" })
        assertEquals(1, events.count { it == "start-scanner" })
        assertEquals(ScanTransitionState.FINALIZATION_FAILED, coordinator.state.value)
    }

    @Test
    fun bluetoothOffRequestRetainsOriginalSessionUntilOnResumeAndStop() = runTest {
        val events = mutableListOf<String>()
        var attempt = 0
        var activeSession: String? = null
        val coordinator = ScanTransitionCoordinator(
            startSession = {
                activeSession = "session-1"
                events += "start-session-1"
                true
            },
            startScanner = {
                attempt++
                events += "start-scanner-$attempt"
                if (attempt == 1) {
                    ScannerStartOutcome.DEFERRED_INTENT_RETAINED
                } else {
                    ScannerStartOutcome.STARTED
                }
            },
            stopScannerAndFence = { events += "stop-fence" },
            finishSession = {
                events += "finish-${activeSession}"
                activeSession = null
                true
            },
        )

        assertTrue(coordinator.start())
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
        assertTrue(coordinator.state.value.isRequested)
        assertTrue(coordinator.resumeDeferred())
        assertEquals(ScanTransitionState.SCANNING, coordinator.state.value)
        events += "observation-${activeSession}"
        assertTrue(coordinator.stop())

        assertEquals(
            listOf(
                "start-session-1",
                "start-scanner-1",
                "start-scanner-2",
                "observation-session-1",
                "stop-fence",
                "finish-session-1",
            ),
            events,
        )
        assertEquals(ScanTransitionState.STOPPED, coordinator.state.value)
    }

    @Test
    fun stopWhileDeferredClearsIntentAndFinishesRetainedSessionExactlyOnce() = runTest {
        var finishes = 0
        var fences = 0
        var scanIntent = false
        val coordinator = ScanTransitionCoordinator(
            startSession = { true },
            startScanner = {
                scanIntent = true
                ScannerStartOutcome.DEFERRED_INTENT_RETAINED
            },
            stopScannerAndFence = {
                scanIntent = false
                fences++
            },
            finishSession = { finishes++; true },
        )

        assertTrue(coordinator.start())
        assertTrue(scanIntent)
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
        assertTrue(coordinator.stop())
        assertFalse(scanIntent)
        assertTrue(coordinator.stop())
        assertEquals(1, fences)
        assertEquals(1, finishes)
        assertEquals(ScanTransitionState.STOPPED, coordinator.state.value)
    }

    @Test
    fun scannerUnavailableRecoveryResumesOnlyWhileCoordinatedRequestIsDeferred() = runTest {
        var sessions = 0
        var starts = 0
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessions++; true },
            startScanner = {
                starts++
                if (starts == 1) {
                    ScannerStartOutcome.DEFERRED_INTENT_RETAINED
                } else {
                    ScannerStartOutcome.STARTED
                }
            },
            stopScannerAndFence = {},
            finishSession = { true },
        )

        assertTrue(coordinator.start())
        assertTrue(coordinator.resumeDeferred())
        assertEquals(1, sessions)
        assertEquals(2, starts)
        assertTrue(coordinator.stop())
        assertFalse(coordinator.resumeDeferred())
        assertEquals(2, starts)
    }

    @Test
    fun explicitStartWhileDeferredRetriesScannerInSameSession() = runTest {
        var sessions = 0
        var scannerStarts = 0
        var scannerReady = false
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessions++; true },
            startScanner = {
                scannerStarts++
                if (scannerReady) {
                    ScannerStartOutcome.STARTED
                } else {
                    ScannerStartOutcome.DEFERRED_INTENT_RETAINED
                }
            },
            stopScannerAndFence = {},
            finishSession = { true },
        )

        assertTrue(coordinator.start())
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
        assertTrue(coordinator.start())
        assertEquals(1, sessions)
        assertEquals(2, scannerStarts)
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)

        scannerReady = true
        assertTrue(coordinator.start())
        assertEquals(1, sessions)
        assertEquals(3, scannerStarts)
        assertEquals(ScanTransitionState.SCANNING, coordinator.state.value)
    }

    @Test
    fun backpressurePauseAndResumeDoesNotCreateASecondSession() = runTest {
        var sessions = 0
        var scannerStarts = 0
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessions++; true },
            startScanner = { scannerStarts++; ScannerStartOutcome.STARTED },
            stopScannerAndFence = {},
            finishSession = { true },
        )

        assertTrue(coordinator.start())
        coordinator.scannerDeferred()
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
        assertTrue(coordinator.resumeDeferred())
        assertEquals(1, sessions)
        assertEquals(2, scannerStarts)
        assertEquals(ScanTransitionState.SCANNING, coordinator.state.value)
    }

    @Test
    fun permanentStartupRejectionClearsScannerIntentBeforeSingleSessionFinish() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ScanTransitionCoordinator(
            startSession = { events += "start-session"; true },
            startScanner = { events += "reject-cleared-intent"; ScannerStartOutcome.REJECTED },
            stopScannerAndFence = { events += "stop-fence" },
            finishSession = { events += "finish-session"; true },
            onRequestRejected = { events += "clear-user-intent" },
        )

        assertFalse(coordinator.start())
        assertEquals(
            listOf(
                "start-session",
                "reject-cleared-intent",
                "clear-user-intent",
                "stop-fence",
                "finish-session",
            ),
            events,
        )
        assertEquals(ScanTransitionState.STOPPED, coordinator.state.value)
        assertFalse(coordinator.state.value.isRequested)
    }
}
