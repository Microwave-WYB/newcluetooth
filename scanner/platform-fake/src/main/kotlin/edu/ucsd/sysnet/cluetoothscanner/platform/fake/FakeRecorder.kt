package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [Recorder] without Android. The callbacks drive whatever stands in for the core (`:core-fake`'s live simulator in
 * the gallery): [onStart] returns the new session id, [onStop] the finished one. Elapsed time follows the core's
 * rule: [RecorderState.activeBeforeMs] + (now − [RecorderState.runningSinceMs]), with paused time excluded.
 */
class FakeRecorder(
    private val onStart: () -> String,
    private val onStop: () -> String,
    private val onPause: () -> Unit = {},
    private val onResume: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** False plays a missing grant (§1.2.3): no session, an error, still idle. */
    private val grantsOk: () -> Boolean = { true },
) : Recorder {
    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    override suspend fun start(): PlatformResult<String> {
        if (_state.value.phase != Phase.Idle) return PlatformResult.Err(PlatformError.Busy)
        if (!grantsOk()) {
            val error = PlatformError.MissingGrants(listOf(MISSING_PERMISSION))
            _state.update { it.copy(error = error.message) }
            return PlatformResult.Err(error)
        }
        val id = onStart()
        _state.value = RecorderState.Idle.copy(phase = Phase.Recording, sessionId = id, runningSinceMs = nowMs())
        return PlatformResult.Ok(id)
    }

    override suspend fun pause(): PlatformResult<Unit> {
        val current = _state.value
        if (current.phase != Phase.Recording) return PlatformResult.Ok(Unit)
        onPause()
        val now = nowMs()
        _state.value = current.copy(
            phase = Phase.Paused,
            activeBeforeMs = current.activeBeforeMs + (now - (current.runningSinceMs ?: now)),
            runningSinceMs = null,
        )
        return PlatformResult.Ok(Unit)
    }

    override suspend fun resume(): PlatformResult<Unit> {
        val current = _state.value
        if (current.phase != Phase.Paused) return PlatformResult.Ok(Unit)
        onResume()
        _state.value = current.copy(phase = Phase.Recording, runningSinceMs = nowMs())
        return PlatformResult.Ok(Unit)
    }

    override suspend fun stop(): PlatformResult<String> {
        val current = _state.value
        if (current.phase != Phase.Recording && current.phase != Phase.Paused) return PlatformResult.Err(PlatformError.NotRecording)
        val id = onStop()
        // V-3: the UI stays on the session that just ended; the state keeps its id.
        _state.value = RecorderState.Idle.copy(sessionId = id)
        return PlatformResult.Ok(id)
    }

    /** The live simulator's shown device count. */
    fun setDeviceCount(count: Int) = _state.update { it.copy(deviceCount = count) }

    /** Bluetooth off while recording (§4.1). */
    fun setScannerDeferred(deferred: Boolean) = _state.update { it.copy(scannerDeferred = deferred) }

    private companion object {
        const val MISSING_PERMISSION = "android.permission.BLUETOOTH_SCAN"
    }
}
