package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the recorder needs of the BLE scanner (§4.1). */
interface RecordingScanner {
    /** Bluetooth off while recording: the scan waits and restarts by itself on `STATE_ON`. */
    val deferred: StateFlow<Boolean>

    /** Observations the inbound queue dropped (§4.1). */
    val dropped: StateFlow<Long>

    /** Starts the scan (or defers it while Bluetooth is off). */
    fun start()

    /**
     * Stops the scan, waits until every queued scan callback has run (the main-looper fence), then flushes the
     * batcher into `ingestScans`, so no observation of the running session reaches the core after it pauses or stops.
     */
    suspend fun stopAndDrain()
}

/** The RECORDING location request (§4.2). */
interface RecordingLocation {
    fun startRecording()
    fun stopRecording()
}

/** The foreground service (§1.2.3). */
interface ForegroundHost {
    /** Starts `RecordingService` and waits for its `startForeground`; false when Android refused or it timed out. */
    suspend fun enterForeground(): Boolean

    fun leaveForeground()
}

/** The grants a `location|connectedDevice` foreground service needs on Android 14 (§1.2.3). */
fun interface RecordingGrants {
    /** Missing permission names; empty when recording may start. */
    fun missing(): List<String>
}

/**
 * The process-wide [Recorder] (§4.4, docs `scanner/platform/FOREGROUND_SERVICE.md` §2–§4).
 *
 * - Start: grants, then the foreground service and its confirmation, then `core.startRecording`, then location and
 *   the scanner. A missing grant or a refused service leaves no session.
 * - Pause and stop drain the scanner before the core call; pause keeps the foreground service.
 * - Elapsed time always comes from the core's state, so paused time is excluded in one place.
 *
 * Transitions are serialized by one mutex; the core calls ([W] in §2) run on [io].
 */
class PlatformRecorder(
    private val core: Deferred<CoreIngest>,
    private val scanner: RecordingScanner,
    private val location: RecordingLocation,
    private val host: ForegroundHost,
    private val grants: RecordingGrants,
    private val environment: () -> ScanEnvironment,
    private val io: CoroutineDispatcher,
    /** The process scope: transitions run here, so a cancelled caller never leaves one half done. */
    private val scope: CoroutineScope,
    /** `CoreQueries.events` `RecordingChanged` states, for the device count between transitions. */
    coreStates: Flow<CoreRecordingState>? = null,
) : Recorder {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    init {
        scope.launch { scanner.deferred.collect { deferred -> _state.update { it.copy(scannerDeferred = deferred) } } }
        scope.launch { scanner.dropped.collect { dropped -> _state.update { it.copy(dropped = dropped) } } }
        if (coreStates != null) {
            scope.launch {
                coreStates.collect { core ->
                    _state.update { if (it.sessionId == core.sessionId && it.phase.live) it.copy(deviceCount = core.deviceCount) else it }
                }
            }
        }
        // RecordingService reads the process recorder from here (notification actions, foreground confirmation).
        RecordingServiceLink.recorder = this
    }

    /** After a process restart (§4.4): adopt the core's state. `Core::open` has already closed a left-open session. */
    suspend fun restore() = mutex.withLock {
        val current = withContext(io) { core.await().recordingState() }
        if (current.phase == RecordingPhase.Idle) return@withLock
        _state.value = merge(current, error = null)
    }

    override suspend fun start(): Result<String> = transition {
        val current = _state.value
        if (current.phase == Phase.Recording || current.phase == Phase.Paused) {
            return@transition Result.success(requireNotNull(current.sessionId))
        }
        val missing = grants.missing()
        if (missing.isNotEmpty()) {
            return@transition fail(SecurityException("$MISSING_GRANTS: ${missing.joinToString()}"), MISSING_GRANTS)
        }
        _state.value = RecorderState.Idle.copy(phase = Phase.Starting, scannerDeferred = current.scannerDeferred, dropped = current.dropped)
        if (!host.enterForeground()) {
            return@transition fail(IllegalStateException(SERVICE_REFUSED), SERVICE_REFUSED)
        }
        val started = try {
            withContext(io) {
                val ingest = core.await()
                try {
                    ingest.startRecording(environment())
                } catch (already: CoreException.AlreadyRecording) {
                    requireNotNull(ingest.recordingState().sessionId) { "the core is recording without a session" }
                }
                ingest.recordingState()
            }
        } catch (cancelled: CancellationException) {
            host.leaveForeground()
            _state.value = RecorderState.Idle
            throw cancelled
        } catch (error: Exception) {
            host.leaveForeground()
            return@transition fail(error, "The recording could not start (${error.message ?: error::class.java.simpleName})")
        }
        location.startRecording()
        scanner.start()
        _state.value = merge(started, error = null)
        Result.success(requireNotNull(started.sessionId))
    }

    override suspend fun pause() = transition {
        if (_state.value.phase != Phase.Recording) return@transition
        scanner.stopAndDrain()
        val paused = try {
            withContext(io) {
                val ingest = core.await()
                ingest.pauseRecording()
                ingest.recordingState()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The core still records: collect again.
            scanner.start()
            _state.update { it.copy(error = "The recording could not pause (${error.message})") }
            return@transition
        }
        location.stopRecording()
        // The service stays in the foreground (owner decision "Pause"); it only drops the wake lock.
        _state.value = merge(paused, error = null)
    }

    override suspend fun resume() = transition {
        if (_state.value.phase != Phase.Paused) return@transition
        val resumed = try {
            withContext(io) {
                val ingest = core.await()
                ingest.resumeRecording()
                ingest.recordingState()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(error = "The recording could not resume (${error.message})") }
            return@transition
        }
        location.startRecording()
        scanner.start()
        _state.value = merge(resumed, error = null)
    }

    override suspend fun stop(): Result<String> = transition {
        val before = _state.value
        if (before.phase != Phase.Recording && before.phase != Phase.Paused) {
            return@transition Result.failure(CoreException.NotRecording())
        }
        _state.value = before.copy(phase = Phase.Stopping, runningSinceMs = null)
        if (before.phase == Phase.Recording) scanner.stopAndDrain()
        val stopped = try {
            withContext(io) { core.await().stopRecording() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Reconcile with what the core says: still open means the user can try again.
            val after = runCatching { withContext(io) { core.await().recordingState() } }.getOrNull()
            if (after != null && after.phase != RecordingPhase.Idle) {
                if (after.phase == RecordingPhase.Recording) scanner.start()
                _state.value = merge(after, error = "The recording could not stop (${error.message})")
                return@transition Result.failure(error)
            }
            location.stopRecording()
            host.leaveForeground()
            _state.value = RecorderState.Idle.copy(sessionId = before.sessionId, error = "The recording could not stop (${error.message})")
            return@transition Result.failure(error)
        }
        location.stopRecording()
        // V-3: the UI stays on the session that just ended.
        _state.value = RecorderState.Idle.copy(sessionId = stopped, deviceCount = before.deviceCount, dropped = before.dropped)
        host.leaveForeground()
        Result.success(stopped)
    }

    /**
     * Runs one transition under the mutex in the recorder's [scope]. Cancelling the caller only stops its wait: the
     * transition always completes, so the core session and the foreground service never disagree.
     */
    private suspend fun <T> transition(block: suspend () -> T): T = scope.async { mutex.withLock { block() } }.await()

    private fun merge(core: CoreRecordingState, error: String?): RecorderState = _state.value.copy(
        phase = when (core.phase) {
            RecordingPhase.Idle -> Phase.Idle
            RecordingPhase.Recording -> Phase.Recording
            RecordingPhase.Paused -> Phase.Paused
        },
        sessionId = core.sessionId,
        activeBeforeMs = core.activeBeforeMs,
        runningSinceMs = core.runningSinceMs,
        deviceCount = core.deviceCount,
        error = error,
    )

    private fun fail(error: Throwable, message: String): Result<String> {
        _state.value = RecorderState.Idle.copy(error = message, scannerDeferred = _state.value.scannerDeferred, dropped = _state.value.dropped)
        return Result.failure(error)
    }

    private val Phase.live get() = this == Phase.Recording || this == Phase.Paused || this == Phase.Stopping

    companion object {
        const val MISSING_GRANTS = "Nearby devices and precise location are needed to record"
        const val SERVICE_REFUSED = "Android did not allow the recording service to start"
    }
}
