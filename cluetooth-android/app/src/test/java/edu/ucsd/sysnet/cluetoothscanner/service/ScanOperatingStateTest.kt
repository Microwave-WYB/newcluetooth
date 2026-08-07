package edu.ucsd.sysnet.cluetoothscanner.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanOperatingStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun callbackFenceAlwaysWaitsForPostedMainQueueDrain() = runTest {
        val posted = ArrayDeque<Runnable>()
        val fence = CallbackFence { runnable ->
            posted += runnable
            true
        }

        val wait = async { fence.await() }
        runCurrent()

        assertFalse(wait.isCompleted)
        assertEquals(1, posted.size)
        posted.removeFirst().run()
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun callbackFenceCanEnqueueNonBlockingTeardownAfterEarlierCallbacks() {
        val posted = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        posted += Runnable { events += "callback" }
        val fence = CallbackFence { runnable -> posted.add(runnable) }

        fence.enqueue { events += "finish" }
        assertTrue(events.isEmpty())
        while (posted.isNotEmpty()) posted.removeFirst().run()

        assertEquals(listOf("callback", "finish"), events)
    }

    @Test
    fun generationGateRejectsPlatformCallbackAfterFenceAndAcceptsOnlyRestartedGeneration() {
        val posted = ArrayDeque<Runnable>()
        val accepted = mutableListOf<String>()
        val requests = ActiveRequestState<String>()
        val first = requests.begin { "first-callback" }
        val fence = CallbackFence { runnable -> posted.add(runnable) }

        assertEquals("first-callback", requests.stop())
        fence.enqueue { accepted += "fence" }
        posted.removeFirst().run()
        posted += Runnable {
            if (requests.accepts(first.generation)) accepted += "stale-after-fence"
        }
        posted.removeFirst().run()

        val second = requests.begin { "second-callback" }
        if (requests.accepts(first.generation)) accepted += "stale-after-restart"
        if (requests.accepts(second.generation)) accepted += "current"

        assertEquals(listOf("fence", "current"), accepted)
    }

    @Test
    fun scanStatusRepresentsPausedFailedAndBluetoothOffStates() {
        val statuses = listOf(
            ScanStatus(ScanOperatingState.PAUSED_BACKPRESSURE),
            ScanStatus(ScanOperatingState.FAILED, "scan failed"),
            ScanStatus(ScanOperatingState.BLUETOOTH_OFF, "Bluetooth is not enabled"),
        )

        assertEquals(
            listOf(
                ScanOperatingState.PAUSED_BACKPRESSURE,
                ScanOperatingState.FAILED,
                ScanOperatingState.BLUETOOTH_OFF,
            ),
            statuses.map(ScanStatus::state),
        )
    }
}
