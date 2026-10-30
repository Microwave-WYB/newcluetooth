package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** §4.4: the recorder's device count follows the live deltas (every 5 s while recording), not only transitions. */
class RecordingStatesTest {
    private fun state(devices: Int) = CoreRecordingState(RecordingPhase.Recording, "s1", 0, 0, 0, devices, 0.0)

    private fun delta(seq: Long, devices: Int) = LiveDelta("s1", seq, seq * 5_000, state(devices), emptyList(), emptyList(), emptyList(), emptyList(), null, null)

    @Test fun liveDeltasAndRecordingChangedBothReachTheRecorder() = runBlocking {
        val states = recordingStates(
            events = flowOf(CoreEvent.RecordingChanged(state(1)), CoreEvent.SessionsChanged),
            live = flowOf(delta(1, 5), delta(2, 9)),
        ).toList()
        assertEquals("every state, and only states", setOf(1, 5, 9), states.map { it.deviceCount }.toSet())
        assertEquals(3, states.size)
        assertEquals("the live deltas keep their order", listOf(5, 9), states.map { it.deviceCount }.filter { it != 1 })
    }
}
