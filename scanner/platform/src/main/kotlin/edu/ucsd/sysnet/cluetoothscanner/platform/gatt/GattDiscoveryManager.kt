package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where finished discoveries go: the Rust core mints the ID and stores/uploads the JSON. */
interface GattStore {
    suspend fun newDiscoveryId(): String
    suspend fun save(json: String)
}

/** One finished (or in-flight) discovery that has not reached the [GattStore] yet. */
data class PendingDiscovery(val id: String, val json: String)

/**
 * A durable queue with one entry per discovery, so a result the store has not accepted is
 * never overwritten by the next discovery. While a discovery runs its entry holds a `failed`
 * object for the stage it is in (what process death would leave behind); once it finishes
 * the entry holds the real result until the store accepted it.
 */
interface PendingDiscoveryStore {
    /** Durable when it returns; replaces an earlier entry with the same [id]. */
    fun put(id: String, json: String)

    /** Oldest first. */
    fun entries(): List<PendingDiscovery>
    fun remove(id: String)
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
 *
 * A finished result is queued durably before anything else happens and removed only once
 * the store accepted it; unsaved results are retried with a back-off, at every start and at
 * app start ([recoverStale]).
 */
class GattDiscoveryManager(
    private val scope: CoroutineScope,
    private val store: GattStore,
    private val pending: PendingDiscoveryStore,
    private val device: GattDeviceInfo,
    private val timeouts: GattTimeouts = GattTimeouts(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Monotonic clock for durations; production passes `SystemClock::elapsedRealtime`. */
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryFirstMs: Long = 30_000,
    private val retryMaxMs: Long = 10 * 60_000,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var engine: GattEngine? = null

    /** Guards the queue and [activeId] so a flush never saves the marker of a running discovery. */
    private val queueLock = Mutex()
    private var activeId: String? = null

    @Volatile
    private var retryJob: Job? = null
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
                // A cancelled run leaves its queue entry (a `failed` object) for recovery.
                if (_state.value is GattUiState.Running) _state.value = GattUiState.Idle
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
        // Earlier results the store never accepted get another try, without delaying this run.
        scope.launch { flushPending() }
        queueLock.withLock {
            activeId = request.id
            pending.put(request.id, interruptedJson(request, startedAt, GattStage.CONNECT))
        }

        // The queue entry follows the stage, not every read: one write per stage change.
        val stages = Channel<GattStage>(Channel.CONFLATED)
        val marker = scope.launch {
            for (stage in stages) {
                queueLock.withLock {
                    if (activeId == request.id) pending.put(request.id, interruptedJson(request, startedAt, stage))
                }
            }
        }
        var lastStage = GattStage.CONNECT
        val running = GattEngine(
            linkFactory = start.linkFactory,
            timeouts = timeouts,
            nowMs = nowMs,
            elapsedMs = elapsedMs,
            onProgress = { progress ->
                _state.value = GattUiState.Running(request, progress)
                if (progress.stage != lastStage) {
                    lastStage = progress.stage
                    stages.trySend(progress.stage)
                }
            },
        )
        engine = running
        _state.value = GattUiState.Running(request, GattProgress(GattStage.CONNECT))

        val result = try {
            try {
                running.run(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An unexpected throw must not lose the discovery or leave the UI running.
                failedResult(request, startedAt, lastStage, GattStatus.INTERNAL_ERROR)
            }
        } catch (cancelled: CancellationException) {
            // Teardown: the queue entry (a `failed` object) stays for recovery.
            withContext(NonCancellable) { queueLock.withLock { activeId = null } }
            throw cancelled
        } finally {
            stages.close()
            marker.join()
        }
        val json = GattJson.encode(result)
        queueLock.withLock {
            pending.put(request.id, json)
            activeId = null
        }
        _state.value = try {
            store.save(json)
            queueLock.withLock { pending.remove(request.id) }
            GattUiState.Finished(result, saved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            scheduleRetry()
            GattUiState.Finished(result, saved = false, saveError = error.message ?: error::class.java.simpleName)
        }
    }

    private fun failedResult(request: GattRequest, startedAt: Long, stage: GattStage, status: Int) =
        GattDiscoveryResult(
            request = request,
            startedAtMs = startedAt,
            // The wall clock may have stepped back; the contract needs ended >= started.
            endedAtMs = maxOf(nowMs(), startedAt),
            outcome = GattOutcome.FAILED,
            stageReached = stage,
            error = GattError(stage, status, GattStatus.name(status)),
            disconnect = null,
            mtu = null,
            phy = null,
            timings = GattTimings(null, null, null, null),
            services = emptyList(),
        )

    /** What process death would leave behind for a discovery that is in [stage]. */
    internal fun interruptedJson(request: GattRequest, startedAt: Long, stage: GattStage): String =
        GattJson.encode(failedResult(request, startedAt, stage, GattStatus.PROCESS_DEATH))

    /**
     * Stores every queued discovery except the one running now and returns whether something
     * is still queued afterwards (the store refused it).
     */
    private suspend fun saveQueued(): Boolean {
        val queued = queueLock.withLock { pending.entries().filter { it.id != activeId } }
        var refused = false
        for (entry in queued) {
            try {
                store.save(entry.json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                refused = true
                continue
            }
            queueLock.withLock { pending.remove(entry.id) }
        }
        return refused
    }

    private suspend fun flushPending() {
        if (saveQueued()) scheduleRetry()
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var wait = retryFirstMs
            while (true) {
                delay(wait)
                if (!saveQueued()) return@launch
                wait = minOf(wait * 2, retryMaxMs)
            }
        }
    }

    /**
     * Cleans up after process death and after earlier failed saves: an in-flight discovery
     * counts as `failed`, and a finished result the store never accepted is stored now. Call
     * once at app start; safe at any time, it never touches the discovery running now.
     */
    suspend fun recoverStale() {
        flushPending()
    }
}
