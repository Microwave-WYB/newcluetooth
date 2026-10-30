package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingNotificationPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotificationPolicyTest {
    private val recording = RecorderState.Idle.copy(
        phase = Phase.Recording, sessionId = "s1", activeBeforeMs = 60_000, runningSinceMs = 1_000_000, deviceCount = 26,
    )

    @Test
    fun `section 4_4 text reads elapsed time and device count`() {
        assertEquals("Recording", RecordingNotificationPolicy.title(recording))
        assertEquals("12:34 · 26 devices", RecordingNotificationPolicy.text(recording, nowMs = 1_000_000 + 694_000))
        assertEquals("1:00:00 · 1 device", RecordingNotificationPolicy.text(recording.copy(activeBeforeMs = 3_600_000, runningSinceMs = null, deviceCount = 1), 0))
        assertEquals("0:00 · 12,345 devices", RecordingNotificationPolicy.text(recording.copy(activeBeforeMs = 0, runningSinceMs = null, deviceCount = 12_345), 0))
    }

    @Test
    fun `paused time does not tick`() {
        val paused = recording.copy(phase = Phase.Paused, runningSinceMs = null)
        assertEquals("Recording paused", RecordingNotificationPolicy.title(paused))
        assertEquals("1:00 · 26 devices", RecordingNotificationPolicy.text(paused, nowMs = 99_999_999))
    }

    @Test
    fun `titles for starting, stopping and Bluetooth off`() {
        assertEquals("Starting recording", RecordingNotificationPolicy.title(recording.copy(phase = Phase.Starting)))
        assertEquals("Saving recording", RecordingNotificationPolicy.title(recording.copy(phase = Phase.Stopping)))
        assertEquals("Recording waiting for Bluetooth", RecordingNotificationPolicy.title(recording.copy(scannerDeferred = true)))
    }

    @Test
    fun `actions are Pause or Resume plus Stop while live`() {
        assertEquals(Action.Pause, RecordingNotificationPolicy.pauseAction(recording))
        assertEquals(Action.Resume, RecordingNotificationPolicy.pauseAction(recording.copy(phase = Phase.Paused)))
        assertNull(RecordingNotificationPolicy.pauseAction(recording.copy(phase = Phase.Stopping)))
        assertTrue(RecordingNotificationPolicy.stopAction(recording.copy(phase = Phase.Paused)))
        assertFalse(RecordingNotificationPolicy.stopAction(recording.copy(phase = Phase.Starting)))
    }

    @Test
    fun `the service lives until idle and pause keeps it`() {
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Starting)))
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Paused)))
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Stopping)))
        assertFalse(RecordingNotificationPolicy.serviceNeeded(RecorderState.Idle))
    }

    @Test
    fun `the wake lock is held only while collecting or sealing`() {
        assertTrue(RecordingNotificationPolicy.wakeLockNeeded(recording))
        assertTrue(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Stopping)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Paused)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(scannerDeferred = true)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Starting)))
    }

    @Test
    fun `updates are throttled to 5 s except on a phase or Bluetooth change`() {
        val throttle = RecordingNotificationPolicy.Throttle()
        assertTrue(throttle.shouldUpdate(recording, 0))
        assertFalse(throttle.shouldUpdate(recording.copy(deviceCount = 30), 4_999))
        assertTrue(throttle.shouldUpdate(recording.copy(deviceCount = 30), 5_000))
        assertTrue(throttle.shouldUpdate(recording.copy(phase = Phase.Paused), 5_001))
        assertTrue(throttle.shouldUpdate(recording.copy(phase = Phase.Paused, scannerDeferred = true), 5_002))
    }

    @Test
    fun `R-10 storage full shows in the notification, with Resume and Stop`() {
        val paused = recording.copy(phase = Phase.Paused, runningSinceMs = null, pause = RecorderState.PauseReason.User)
        val full = paused.copy(pause = RecorderState.PauseReason.StorageFull(150_000_000))
        assertEquals("Recording paused: storage is full", RecordingNotificationPolicy.title(full))
        assertEquals("Free up space to continue · 150 MB free", RecordingNotificationPolicy.text(full, 0))
        assertEquals(Action.Resume, RecordingNotificationPolicy.pauseAction(full))
        assertTrue(RecordingNotificationPolicy.stopAction(full))
        assertTrue("the service stays", RecordingNotificationPolicy.serviceNeeded(full))
        assertFalse("nothing is collected", RecordingNotificationPolicy.wakeLockNeeded(full))
        val throttle = RecordingNotificationPolicy.Throttle()
        assertTrue(throttle.shouldUpdate(paused, 0))
        assertTrue("storage full shows at once", throttle.shouldUpdate(full, 1))
    }
}
