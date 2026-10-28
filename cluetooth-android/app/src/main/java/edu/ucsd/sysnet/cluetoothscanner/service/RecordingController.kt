package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionCoordinator
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecordingMode { IDLE, RECORDING, PAUSED }

/**
 * What the UI shows of a recording: [elapsedMs] counts only the time spent recording (not
 * paused), [rowCount] the observations written since the recording started.
 *
 * [finalizing] is true while a stopped recording is being sealed (the mode is already `IDLE`);
 * the foreground service and its wake lock stay until it ends. [scannerDeferred] is true while a
 * recording waits for the scanner to come back (Bluetooth off); nothing is being collected then.
 * [error] carries a problem the user should see, such as the foreground service not starting.
 */
data class RecordingState(
    val mode: RecordingMode = RecordingMode.IDLE,
    val elapsedMs: Long = 0,
    val rowCount: Long = 0,
    val finalizing: Boolean = false,
    val scannerDeferred: Boolean = false,
    val error: String? = null,
)

/** The foreground service and its wake lock are needed from the start until finalization ends. */
internal val RecordingState.serviceNeeded: Boolean
    get() = mode != RecordingMode.IDLE || finalizing

/** Hold the CPU only while rows are (or can be) collected, and while the payload is sealed. */
internal val RecordingState.wakeLockNeeded: Boolean
    get() = (mode == RecordingMode.RECORDING && !scannerDeferred) || finalizing

/** Keeps the process in the foreground while a recording is active. */
internal interface RecordingHost {
    /** Recording started (or resumed from idle): become a foreground service. */
    fun onActive()

    /** Recording ended: release the foreground service and the wake lock. */
    fun onIdle()
}

/**
 * The process-wide recording API: [state] plus [start], [pause], [resume] and [stop]. A
 * recording is a scan session: pause stops writing rows but keeps the session open, resume
 * continues it, and stop finishes the session (sealing the open payload and scheduling its
 * upload). While a recording is active (recording or paused) the [RecordingHost] keeps the
 * process alive as a foreground service, so scanning and collection continue with the screen off.
 *
 * Recording is separate from live scanning (see [ScanSupervisor] and docs/recording-model.md):
 * the core session opens when a recording starts and closes when it stops; rows are written
 * only in between. The UI observes [state] and calls these four functions.
 */
class RecordingController internal constructor(
    internal val coordinator: ScanTransitionCoordinator,
    private val observationCount: StateFlow<Long>,
    private val host: RecordingHost,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val tickMs: Long = 1_000,
) {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** The user's intent. A recording never starts by itself: live scanning is separate. */
    @Volatile
    var wantsRecording: Boolean = false
        internal set

    private var activeSinceMs: Long? = null
    private var accumulatedMs = 0L
    private var baselineRows = 0L
    private var lastTransition = ScanTransitionState.STOPPED
    private var error: String? = null

    /** The last recording's total, kept after it ends until the next one starts. */
    private var finalRows = 0L

    init {
        scope.launch {
            coordinator.state.collect { transition -> onTransition(transition) }
        }
        scope.launch {
            while (true) {
                delay(tickMs)
                publish()
            }
        }
    }

    fun start() {
        wantsRecording = true
        scope.launch { coordinator.start() }
    }

    fun pause() {
        scope.launch { coordinator.pause() }
    }

    fun resume() {
        wantsRecording = true
        scope.launch { if (coordinator.state.value == ScanTransitionState.PAUSED) coordinator.start() }
    }

    fun stop() {
        wantsRecording = false
        scope.launch { coordinator.stop() }
    }

    /** The foreground service could not start or stopped abnormally; shown through [state]. */
    fun reportServiceFailure(message: String) {
        error = message
        publish()
    }

    private fun isActive(transition: ScanTransitionState) = when (transition) {
        ScanTransitionState.STARTING,
        ScanTransitionState.SCANNING,
        ScanTransitionState.DEFERRED,
        ScanTransitionState.PAUSED,
        ScanTransitionState.FINALIZING,
        -> true
        ScanTransitionState.STOPPED,
        ScanTransitionState.FINALIZATION_FAILED,
        -> false
    }

    private fun onTransition(transition: ScanTransitionState) {
        val mode = modeOf(transition)
        val previousMode = modeOf(lastTransition)
        val now = elapsedRealtimeMs()
        val wasActive = isActive(lastTransition)
        val nowActive = isActive(transition)
        when {
            !wasActive && nowActive -> {
                accumulatedMs = 0
                baselineRows = observationCount.value
                activeSinceMs = if (mode == RecordingMode.RECORDING) now else null
                error = null
                lastTransition = transition
                // Publish first: the service reads the state as soon as it starts.
                publish()
                host.onActive()
                return
            }
            wasActive && (transition == ScanTransitionState.FINALIZING || !nowActive) -> {
                // Freeze the clock and the row count when the recording ends, not when the seal
                // completes.
                if (previousMode != RecordingMode.IDLE || activeSinceMs != null) {
                    activeSinceMs?.let { accumulatedMs += now - it }
                    activeSinceMs = null
                    finalRows = (observationCount.value - baselineRows).coerceAtLeast(0)
                }
                lastTransition = transition
                publish()
                // The service and the wake lock go only when finalization is over.
                if (!nowActive) host.onIdle()
                return
            }
            previousMode == RecordingMode.RECORDING && mode == RecordingMode.PAUSED -> {
                activeSinceMs?.let { accumulatedMs += now - it }
                activeSinceMs = null
            }
            previousMode == RecordingMode.PAUSED && mode == RecordingMode.RECORDING -> activeSinceMs = now
        }
        lastTransition = transition
        publish()
    }

    private fun publish() {
        val now = elapsedRealtimeMs()
        val mode = modeOf(lastTransition)
        val elapsed = accumulatedMs + (activeSinceMs?.let { now - it } ?: 0L)
        _state.value = RecordingState(
            mode = mode,
            elapsedMs = elapsed,
            rowCount = if (mode == RecordingMode.IDLE) finalRows else (observationCount.value - baselineRows).coerceAtLeast(0),
            finalizing = lastTransition == ScanTransitionState.FINALIZING,
            scannerDeferred = lastTransition == ScanTransitionState.DEFERRED,
            error = error,
        )
    }

    private fun modeOf(transition: ScanTransitionState) = when (transition) {
        ScanTransitionState.STARTING,
        ScanTransitionState.SCANNING,
        ScanTransitionState.DEFERRED,
        -> RecordingMode.RECORDING
        ScanTransitionState.PAUSED -> RecordingMode.PAUSED
        ScanTransitionState.STOPPED,
        ScanTransitionState.FINALIZING,
        ScanTransitionState.FINALIZATION_FAILED,
        -> RecordingMode.IDLE
    }
}

/** Text for the foreground notification; pure so it can be tested. */
internal object RecordingNotificationText {
    fun title(state: RecordingState) = when {
        state.finalizing -> "Saving recording"
        state.mode == RecordingMode.RECORDING && state.scannerDeferred -> "Recording waiting for Bluetooth"
        state.mode == RecordingMode.RECORDING -> "Recording"
        state.mode == RecordingMode.PAUSED -> "Recording paused"
        else -> "Recording stopped"
    }

    fun text(state: RecordingState): String {
        val seconds = state.elapsedMs / 1_000
        val clock = if (seconds >= 3_600) {
            "%d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
        return "$clock · ${"%,d".format(state.rowCount)} rows"
    }
}
