package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.PauseReason
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import java.util.Locale

/** What `RecordingService` does for a [RecorderState]; pure so it can be tested on the JVM. */
internal object RecordingNotificationPolicy {
    /** §4.4: the notification changes at most this often, except on a phase or Bluetooth change. */
    const val UPDATE_INTERVAL_MS = 5_000L

    /** §1.2.3: the wake lock is bounded; it is re-acquired on every resume. */
    const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1_000L

    /** The service is needed from Starting until the seal after Stop is done. */
    fun serviceNeeded(state: RecorderState) = state.phase != Phase.Idle

    /** Hold the CPU only while observations are collected, and while the stop seals the payload. */
    fun wakeLockNeeded(state: RecorderState) =
        (state.phase == Phase.Recording && !state.scannerDeferred) || state.phase == Phase.Stopping

    fun elapsedMs(state: RecorderState, nowMs: Long) =
        state.activeBeforeMs + (state.runningSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

    fun title(state: RecorderState) = when {
        state.pause is PauseReason.StorageFull -> "Recording paused: storage is full"
        state.phase == Phase.Starting -> "Starting recording"
        state.phase == Phase.Stopping -> "Saving recording"
        state.phase == Phase.Paused -> "Recording paused"
        state.scannerDeferred -> "Recording waiting for Bluetooth"
        else -> "Recording"
    }

    /** The line under [title]: "12:34 · 26 devices", so the notification reads "Recording · 12:34 · 26 devices" (§4.4). */
    fun text(state: RecorderState, nowMs: Long): String {
        // R-10: what to do, and the free space the core reported.
        (state.pause as? PauseReason.StorageFull)?.let { return "Free up space to continue · ${megabytes(it.freeBytes)} free" }
        val seconds = elapsedMs(state, nowMs) / 1_000
        val clock = if (seconds >= 3_600) {
            "%d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
        val devices = if (state.deviceCount == 1) "1 device" else "%,d devices".format(Locale.US, state.deviceCount)
        return "$clock · $devices"
    }

    private fun megabytes(bytes: Long) = "%,d MB".format(Locale.US, bytes.coerceAtLeast(0) / 1_000_000)

    /** Pause while recording, Resume while paused, nothing while starting or stopping. Stop is always offered when live. */
    fun pauseAction(state: RecorderState): Action? = when (state.phase) {
        Phase.Recording -> Action.Pause
        Phase.Paused -> Action.Resume
        else -> null
    }

    fun stopAction(state: RecorderState) = state.phase == Phase.Recording || state.phase == Phase.Paused

    enum class Action { Pause, Resume }

    /** Rate limits notification updates: always on a visible change of kind, otherwise every [UPDATE_INTERVAL_MS]. */
    class Throttle {
        private var lastAtMs = Long.MIN_VALUE
        private var lastKey: Any? = null

        fun shouldUpdate(state: RecorderState, elapsedRealtimeMs: Long): Boolean {
            val key = Triple(state.phase, state.scannerDeferred, state.pause is PauseReason.StorageFull)
            if (key == lastKey && elapsedRealtimeMs - lastAtMs < UPDATE_INTERVAL_MS) return false
            lastKey = key
            lastAtMs = elapsedRealtimeMs
            return true
        }
    }
}
