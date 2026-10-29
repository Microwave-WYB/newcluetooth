package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget as CoreGattTarget

/**
 * §4.3, SPEC D-13: one manual discovery per collection, over the v3 engine and its durable queue.
 *
 * - Preconditions: no `BLUETOOTH_CONNECT` or Bluetooth off ([available] false) and no `core.gattTarget(key)` each
 *   give a failed Connecting step ([GattRunner.GATT_UNAVAILABLE], [GattRunner.GATT_NO_TARGET]); nothing connects.
 * - Progress views follow the engine stages ([GattStepTracker]); the final view is what `core.ingestGatt` returned.
 *   If the core could not store it, the result stays in the `gatt-pending` queue (retried) and [resultView] is shown.
 * - One discovery at a time: a new collection cancels the running one and starts after it wound down. Cancelling
 *   the collector cancels the discovery (D-13.8), which is still saved as `cancelled` and never shown.
 * - It works with or without a recording; the target's session id comes from the core.
 */
class PlatformGattRunner(
    private val core: Deferred<CoreIngest>,
    private val io: CoroutineDispatcher,
    private val manager: (GattStore) -> GattDiscoveryManager,
    /** `BLUETOOTH_CONNECT` granted and Bluetooth on. */
    private val available: () -> Boolean,
    /** The link to [CoreGattTarget.addr]; null when Bluetooth cannot reach it. */
    private val links: (CoreGattTarget) -> GattLinkFactory?,
    private val location: () -> GattLocation? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : GattRunner {
    /** Views the core returned, by discovery id; cleared when a run ends (one run at a time). */
    private val stored = ConcurrentHashMap<String, GattView>()

    private val discoveries: GattDiscoveryManager = manager(object : GattStore {
        override suspend fun newDiscoveryId() = withContext(io) { core.await().newGattDiscoveryId() }
        override suspend fun save(json: String) {
            val view = withContext(io) { core.await().ingestGatt(json) }
            stored[view.discoveryId] = view
        }
    })
    private val oneAtATime = Mutex()
    private var current: Job? = null

    /** App start: store what process death or a failed save left in the queue (§4.3). */
    suspend fun recover() = discoveries.recoverStale()

    override fun discover(key: DeviceKey): Flow<GattView> = channelFlow {
        // One discovery at a time: a new collection cancels the previous run's own job, never its collector.
        val run = launch(start = CoroutineStart.LAZY) { oneAtATime.withLock { run(key) { send(it) } } }
        synchronized(this@PlatformGattRunner) {
            current?.cancel()
            current = run
        }
        run.start()
        run.join()
    }

    private suspend fun run(key: DeviceKey, emit: suspend (GattView) -> Unit) {
        val startedMs = nowMs()
        fun fail(failure: GattFailure) = failedBeforeConnect("", key, startedMs, failure)
        if (!available()) return emit(fail(GattFailure.Other(GattRunner.GATT_UNAVAILABLE)))
        val target = withContext(io) { core.await().gattTarget(key) }
            ?: return emit(fail(GattFailure.Other(GattRunner.GATT_NO_TARGET)))
        val link = links(target) ?: return emit(fail(GattFailure.Other(GattRunner.GATT_UNAVAILABLE)))
        discoveries.dismissResult()
        val job = discoveries.launch(GattStartRequest(target.toEngineTarget(), target.sessionId, location(), link))
            ?: return emit(fail(GattFailure.ConnectionFailed))
        val tracker = GattStepTracker()
        var finished = false
        try {
            coroutineScope {
                val progress = launch {
                    discoveries.state.collect { state ->
                        if (state is GattUiState.Running) {
                            emit(progressView(state.request.id, key, startedMs, tracker.steps(state.progress)))
                        }
                    }
                }
                job.join()
                progress.cancel()
            }
            finished = true
            when (val end = discoveries.state.value) {
                is GattUiState.Finished -> emit(stored[end.result.request.id] ?: resultView(end.result, key))
                // No discovery identity: nothing connected.
                else -> emit(fail(GattFailure.ConnectionFailed))
            }
        } finally {
            if (!finished) {
                // D-13.8: the collector went away; the engine stops and the result is stored as cancelled.
                withContext(NonCancellable) {
                    discoveries.cancel()
                    job.join()
                }
            }
            stored.clear()
        }
    }

    private fun CoreGattTarget.toEngineTarget() = GattTarget(
        addr = addr,
        addrType = addrType,
        name = name,
        // The contract needs a non-empty advertisement; an empty one is no trigger.
        triggerAdv = triggerRaw.takeIf { it.isNotEmpty() }?.let {
            GattTriggerAdv(it, triggerRssi, triggerScannedAtMs, triggerIsLegacy, triggerPrimaryPhy, triggerSecondaryPhy, triggerAdvSid)
        },
    )
}
