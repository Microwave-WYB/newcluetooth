package edu.ucsd.sysnet.cluetoothscanner.gatt

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where finished discoveries go: the Rust core mints the ID and stores/uploads the JSON. */
interface GattStore {
    suspend fun newDiscoveryId(): String
    suspend fun save(json: String)
}

/**
 * A single slot holding the JSON that should reach the [GattStore] if the process dies
 * before it does. While a discovery runs it holds a `failed` object for the stage the
 * discovery is in; once it finishes it holds the real result until the store accepted it.
 */
interface PendingDiscoveryStore {
    fun put(json: String)
    fun peek(): String?
    fun clear()
}

data class GattDeviceInfo(val appVersion: String, val deviceModel: String, val androidVersion: String)

/** What the caller knows when the operator taps the trigger. */
data class GattStartRequest(
    val target: GattTarget,
    val sessionId: String?,
    val location: GattLocation?,
    val linkFactory: GattLinkFactory,
)

sealed interface GattUiState {
    data object Idle : GattUiState
    data class Running(val request: GattRequest, val progress: GattProgress) : GattUiState

    /** [saved] is false when the core refused or failed to store the object; see [saveError]. */
    data class Finished(
        val result: GattDiscoveryResult,
        val saved: Boolean,
        val saveError: String? = null,
    ) : GattUiState
}

/**
 * The simple API the UI uses for manual GATT discovery: [start], [cancel], the [state]
 * flow (progress while running, the result afterwards). One connection at a time; there
 * is no background or automatic discovery anywhere.
 */
class GattDiscoveryManager(
    private val scope: CoroutineScope,
    private val store: GattStore,
    private val pending: PendingDiscoveryStore,
    private val device: GattDeviceInfo,
    private val timeouts: GattTimeouts = GattTimeouts(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var engine: GattEngine? = null
    private val _state = MutableStateFlow<GattUiState>(GattUiState.Idle)
    val state: StateFlow<GattUiState> = _state.asStateFlow()

    /** False when a discovery is already running. */
    fun start(request: GattStartRequest): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            try {
                execute(request)
            } finally {
                engine = null
                running.set(false)
            }
        }
        return true
    }

    /** Stops the running discovery; whatever was collected is kept and stored as `cancelled`. */
    fun cancel() {
        engine?.cancel()
    }

    /** Back to idle after the UI showed a finished result. */
    fun dismissResult() {
        if (_state.value is GattUiState.Finished) _state.value = GattUiState.Idle
    }

    private suspend fun execute(start: GattStartRequest) {
        val startedAt = nowMs()
        val request = try {
            GattRequest(
                id = store.newDiscoveryId(),
                sessionId = start.sessionId,
                target = start.target,
                location = start.location,
                appVersion = device.appVersion,
                deviceModel = device.deviceModel,
                androidVersion = device.androidVersion,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // No identity, no discovery: nothing was connected.
            _state.value = GattUiState.Idle
            return
        }
        val running = GattEngine(
            linkFactory = start.linkFactory,
            timeouts = timeouts,
            nowMs = nowMs,
            onProgress = { progress ->
                _state.value = GattUiState.Running(request, progress)
                recordStage(request, startedAt, progress.stage)
            },
        )
        engine = running
        _state.value = GattUiState.Running(request, GattProgress(GattStage.CONNECT))
        recordStage(request, startedAt, GattStage.CONNECT)

        val result = running.run(request)
        val json = GattJson.encode(result)
        pending.put(json)
        _state.value = try {
            store.save(json)
            pending.clear()
            GattUiState.Finished(result, saved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The real result stays in the slot; the next start retries the store.
            GattUiState.Finished(result, saved = false, saveError = error.message ?: error::class.java.simpleName)
        }
    }

    private fun recordStage(request: GattRequest, startedAt: Long, stage: GattStage) {
        val interrupted = GattDiscoveryResult(
            request = request,
            startedAtMs = startedAt,
            endedAtMs = nowMs(),
            outcome = GattOutcome.FAILED,
            stageReached = stage,
            error = GattError(stage, GattStatus.PROCESS_DEATH, GattStatus.name(GattStatus.PROCESS_DEATH)),
            disconnect = null,
            mtu = null,
            phy = null,
            timings = GattTimings(null, null, null, null),
            services = emptyList(),
        )
        pending.put(GattJson.encode(interrupted))
    }

    /**
     * Cleans up after process death: an in-flight discovery counts as `failed`, and a
     * finished result the store never accepted is stored now. Call once at app start.
     */
    suspend fun recoverStale() {
        if (running.get()) return
        val json = pending.peek() ?: return
        try {
            store.save(json)
            pending.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep it; the next start tries again.
        }
    }
}
