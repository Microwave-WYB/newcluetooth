package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget as CoreGattTarget

/**
 * §4.3, SPEC D-13: one manual discovery per collection, over the v3 engine and its durable queue.
 *
 * - Preconditions: no `BLUETOOTH_CONNECT` or Bluetooth off ([available] false) and no `core.gattTarget(key)` each
 *   give a failed Connecting step ([GattRunner.GATT_UNAVAILABLE], [GattRunner.GATT_NO_TARGET]); nothing connects.
 * - Progress views follow the engine stages ([GattStepTracker]); the final view is what `core.ingestGatt` returned.
 *   If the core could not store it, the result stays in the `gatt-pending` queue (retried) and [resultView] is shown.
 * - One discovery at a time, ordered by the manager's admission ([GattAdmission]): a collection that passed the
 *   preconditions cancels the running discovery and starts after it ended; the earlier collector ends on a final
 *   view. Cancelling the collector cancels its discovery (D-13.8): a running one is still saved as `cancelled` and
 *   never shown, one that is still starting never connects, and one that waits never runs.
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
    /** Views the core returned, by discovery id; removed when the run ended. Copy-on-write, lock-free. */
    private val stored = AtomicReference<Map<String, GattView>>(emptyMap())

    private val discoveries: GattDiscoveryManager = manager(object : GattStore {
        override suspend fun newDiscoveryId() = withContext(io) { core.await().newGattDiscoveryId() }
        override suspend fun save(json: String) {
            val view = withContext(io) { core.await().ingestGatt(json) }
            stored.updateAndGet { it + (view.discoveryId to view) }
        }
    })

    /** App start, once the core is open: store what process death or a failed save left in the queue (§4.3). */
    suspend fun recover() = discoveries.recoverStale()

    override fun discover(key: DeviceKey): Flow<GattView> = channelFlow {
        val startedMs = nowMs()
        fun fail(failure: GattFailure) = failedBeforeConnect("", key, startedMs, failure)
        try {
            run(key, startedMs, ::fail) { send(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never expected (every platform call is a value): the collector still gets a final view.
            send(fail(GattFailure.ConnectionFailed))
        }
    }

    private suspend fun run(key: DeviceKey, startedMs: Long, fail: (GattFailure) -> GattView, send: suspend (GattView) -> Unit) {
        if (!(platform { available() } ?: false)) return send(fail(GattFailure.Other(GattRunner.GATT_UNAVAILABLE)))
        val target = platform { withContext(io) { core.await().gattTarget(key) } }
            ?: return send(fail(GattFailure.Other(GattRunner.GATT_NO_TARGET)))
        val link = platform { links(target) } ?: return send(fail(GattFailure.Other(GattRunner.GATT_UNAVAILABLE)))
        // One discovery at a time, ordered by the manager's admission: a new collection cancels the running
        // discovery and starts after it ended; the earlier collector ends on a final view, it is not cancelled.
        val ticket = discoveries.supersede(GattStartRequest(target.toEngineTarget(), target.sessionId, platform { location() }, link))
        var last: GattView? = null
        val emit: suspend (GattView) -> Unit = {
            last = it
            send(it)
        }
        try {
            if (!ticket.admitted.await()) {
                // Replaced by a newer discovery before it ran.
                superseded(last, key)?.let { emit(it) }
                return
            }
            val tracker = GattStepTracker()
            val end = coroutineScope {
                val progress = launch {
                    discoveries.state.collect { state ->
                        if (state is GattUiState.Running) {
                            emit(progressView(state.request.id, key, startedMs, tracker.steps(state.progress)))
                        }
                    }
                }
                ticket.ended.await().also { progress.cancel() }
            }
            val wasSuperseded = ticket.superseded.isCompleted
            when (end) {
                is TicketEnd.Finished -> {
                    val result = end.state.result
                    if (wasSuperseded && result.outcome == GattOutcome.CANCELLED) {
                        superseded(last, key)?.let { emit(it) }
                    } else {
                        emit(stored.get()[result.request.id] ?: resultView(result, key))
                    }
                }
                // Superseded before it connected, or no discovery identity: nothing connected.
                TicketEnd.NoResult ->
                    if (wasSuperseded) superseded(last, key)?.let { emit(it) } else emit(fail(GattFailure.ConnectionFailed))
            }
        } finally {
            withContext(NonCancellable) {
                // D-13.8: the collector went away; the engine stops and the result is stored as cancelled (a
                // discovery still starting never connects, a waiting one never runs).
                if (!ticket.ended.isCompleted) discoveries.cancel(ticket)
                val end = ticket.ended.await()
                if (end is TicketEnd.Finished) stored.updateAndGet { it - end.state.result.request.id }
            }
        }
    }

    /** The final view of a superseded run, so its collector never spins (§4.3); null when [last] already was. */
    private fun superseded(last: GattView?, key: DeviceKey) = supersededView(last, key, nowMs())

    /** A platform or core call whose failure is a value (null), never a throw out of the flow. */
    private suspend fun <T> platform(call: suspend () -> T): T? = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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
