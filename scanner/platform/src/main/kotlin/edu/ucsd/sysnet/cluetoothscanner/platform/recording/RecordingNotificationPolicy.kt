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
        state.pause is PauseReason.StorageFailed -> "Recording paused: it could not be stored"
        state.phase == Phase.Starting -> "Starting recording"
        state.phase == Phase.Stopping -> "Saving recording"
        state.phase == Phase.Paused -> "Recording paused"
        state.scannerDeferred -> "Recording waiting for Bluetooth"
        else -> "Recording"
    }

    /**
     * §4.4, R-3: while the recording runs, Android ticks the elapsed time itself (a chronometer counting up from this
     * wall-clock instant: the run's start moved back by the time recorded before the last pause, so paused time is
     * excluded). Null while paused, starting or stopping; the text then shows the frozen elapsed time.
     */
    fun chronometerBaseMs(state: RecorderState): Long? =
        if (state.phase == Phase.Recording) state.runningSinceMs?.let { it - state.activeBeforeMs } else null

    /**
     * The line under [title]: "26 devices" next to the ticking chronometer while recording ("Recording · 12:34 · 26
     * devices", §4.4), else "12:34 · 26 devices" with the elapsed time frozen.
     */
    fun text(state: RecorderState, nowMs: Long): String {
        // R-10: what to do, and the free space the core reported.
        when (val pause = state.pause) {
            is PauseReason.StorageFull -> return "Free up space to continue · ${megabytes(pause.freeBytes)} free"
            is PauseReason.StorageFailed -> return "Resume to try again · ${pause.message}"
            PauseReason.User, null -> Unit
        }
        val seconds = elapsedMs(state, nowMs) / 1_000
        val clock = if (seconds >= 3_600) {
            "%d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
        val devices = if (state.deviceCount == 1) "1 device" else "%,d devices".format(Locale.US, state.deviceCount)
        return if (chronometerBaseMs(state) != null) devices else "$clock · $devices"
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

    /**
     * The coalescing rate limit (§4.4) as a value: a change of kind (phase, Bluetooth, a storage pause) shows at once;
     * otherwise at most one update per [UPDATE_INTERVAL_MS], and the latest state held back meanwhile is shown when
     * the window opens, never dropped. [offer] and [fired] are its pure transitions; the service runs the effects.
     */
    data class Throttle(
        val lastAtMs: Long? = null,
        val lastKind: Any? = null,
        /** The newest state not shown yet. */
        val pending: RecorderState? = null,
        /** A timer is armed to show [pending]. */
        val armed: Boolean = false,
    )

    sealed interface ThrottleEffect {
        data class Show(val state: RecorderState) : ThrottleEffect

        /** Call [fired] at [atMs] (the same monotonic clock as the offers). */
        data class Arm(val atMs: Long) : ThrottleEffect
    }

    data class ThrottleStep(val throttle: Throttle, val effects: List<ThrottleEffect>)

    fun offer(throttle: Throttle, state: RecorderState, nowMs: Long): ThrottleStep {
        val kind = kindOf(state)
        val last = throttle.lastAtMs
        if (last == null || kind != throttle.lastKind || nowMs - last >= UPDATE_INTERVAL_MS) {
            return ThrottleStep(throttle.copy(lastAtMs = nowMs, lastKind = kind, pending = null), listOf(ThrottleEffect.Show(state)))
        }
        val arm = if (throttle.armed) emptyList() else listOf(ThrottleEffect.Arm(last + UPDATE_INTERVAL_MS))
        return ThrottleStep(throttle.copy(pending = state, armed = true), arm)
    }

    fun fired(throttle: Throttle, nowMs: Long): ThrottleStep {
        val pending = throttle.pending ?: return ThrottleStep(throttle.copy(armed = false), emptyList())
        val opensAt = (throttle.lastAtMs ?: nowMs) + UPDATE_INTERVAL_MS
        // An update shown since the timer was armed moved the window: wait for it to open.
        if (nowMs < opensAt) return ThrottleStep(throttle, listOf(ThrottleEffect.Arm(opensAt)))
        return ThrottleStep(Throttle(lastAtMs = nowMs, lastKind = kindOf(pending)), listOf(ThrottleEffect.Show(pending)))
    }

    private fun kindOf(state: RecorderState): Any =
        Triple(state.phase, state.scannerDeferred, state.pause?.takeIf { it != PauseReason.User }?.let { it::class })
}
