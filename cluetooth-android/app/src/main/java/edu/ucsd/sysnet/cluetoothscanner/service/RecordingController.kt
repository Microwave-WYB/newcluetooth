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
 */
data class RecordingState(
    val mode: RecordingMode = RecordingMode.IDLE,
    val elapsedMs: Long = 0,
    val rowCount: Long = 0,
)

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
 * It owns nothing about the screen: the UI observes [state] and calls these four functions.
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

    /** The user's intent: a recording is wanted unless they stopped it (or it was rejected). */
    @Volatile
    var wantsRecording: Boolean = true
        internal set

    private var activeSinceMs: Long? = null
    private var accumulatedMs = 0L
    private var baselineRows = 0L
    private var lastMode = RecordingMode.IDLE

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

    private fun onTransition(transition: ScanTransitionState) {
        val mode = modeOf(transition)
        val now = elapsedRealtimeMs()
        when {
            lastMode == RecordingMode.IDLE && mode != RecordingMode.IDLE -> {
                accumulatedMs = 0
                baselineRows = observationCount.value
                activeSinceMs = if (mode == RecordingMode.RECORDING) now else null
                lastMode = mode
                // Publish first: the service reads the state as soon as it starts.
                publish()
                host.onActive()
                return
            }
            mode == RecordingMode.IDLE && lastMode != RecordingMode.IDLE -> {
                activeSinceMs?.let { accumulatedMs += now - it }
                activeSinceMs = null
                finalRows = (observationCount.value - baselineRows).coerceAtLeast(0)
                lastMode = mode
                publish()
                host.onIdle()
                return
            }
            lastMode == RecordingMode.RECORDING && mode == RecordingMode.PAUSED -> {
                activeSinceMs?.let { accumulatedMs += now - it }
                activeSinceMs = null
            }
            lastMode == RecordingMode.PAUSED && mode == RecordingMode.RECORDING -> activeSinceMs = now
        }
        lastMode = mode
        publish()
    }

    private fun publish() {
        val now = elapsedRealtimeMs()
        val elapsed = accumulatedMs + (activeSinceMs?.let { now - it } ?: 0L)
        _state.value = RecordingState(
            mode = lastMode,
            elapsedMs = elapsed,
            rowCount = if (lastMode == RecordingMode.IDLE) finalRows else (observationCount.value - baselineRows).coerceAtLeast(0),
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
    fun title(state: RecordingState) = when (state.mode) {
        RecordingMode.RECORDING -> "Recording"
        RecordingMode.PAUSED -> "Recording paused"
        RecordingMode.IDLE -> "Recording stopped"
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
