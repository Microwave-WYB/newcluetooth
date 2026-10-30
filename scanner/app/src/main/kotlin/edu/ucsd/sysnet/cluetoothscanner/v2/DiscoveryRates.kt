package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * R-4a, the core's discovery rate as the screen uses it: lane A's coming `CoreQueries.discoveryRate(sessionId):
 * CoreResult<DiscoveryRate>` and the live delta's matching field. [NoDiscoveryRates] stands in until the core has
 * the call; tests pass their own.
 */
interface DiscoveryRates {
    /** The live deltas' discovery-rate field (§2.6: subscribe first, then query; a delta with `seq` ≤ the snapshot's is old). */
    val deltas: Flow<DiscoveryRateDelta>
    suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate>
}

/**
 * A session's discovery rate at [seq]: the devices first scanned in each [binMs] bin (30 000) and the recording's
 * runs, so the chart can leave the pauses out (wall-clock ms).
 */
data class DiscoveryRate(val seq: Long, val binMs: Long, val bins: List<DiscoveryBins.Bin>, val runs: List<DiscoveryBins.Run>) {
    /** The snapshot with [d] applied: each of its bins replaces the bin with the same start; its runs are the runs now. */
    fun apply(d: DiscoveryRateDelta): DiscoveryRate =
        if (d.seq <= seq) this
        else copy(seq = d.seq, bins = (bins.associateBy { it.startMs } + d.bins.associateBy { it.startMs }).values.toList(), runs = d.runs)

    fun toBins(): DiscoveryBins = DiscoveryBins(binMs, bins, runs)
}

/** One live delta's discovery-rate field: the bins that changed or are new (their totals) and the session's runs. */
data class DiscoveryRateDelta(val sessionId: String, val seq: Long, val bins: List<DiscoveryBins.Bin>, val runs: List<DiscoveryBins.Run>)

/** Until lane A's call lands: an empty rate and no deltas, so the panel's chart stays empty. */
object NoDiscoveryRates : DiscoveryRates {
    override val deltas: Flow<DiscoveryRateDelta> = emptyFlow()
    override suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate> =
        CoreResult.Ok(DiscoveryRate(0, DiscoveryBins.BIN_MS, emptyList(), emptyList()))
}

/**
 * §2.6 for [sessionId]: subscribes to the deltas, then reads the snapshot; emits the snapshot, then it again with
 * each newer delta of the session applied. An Err is emitted once and ends the flow (the screen's notice retries).
 */
internal fun DiscoveryRates.feed(sessionId: String): Flow<CoreResult<DiscoveryBins>> = channelFlow {
    val pending = Channel<DiscoveryRateDelta>(Channel.UNLIMITED)
    val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
        deltas.collect { if (it.sessionId == sessionId) pending.send(it) }
    }
    when (val r = discoveryRate(sessionId)) {
        is CoreResult.Err -> {
            subscription.cancel()
            send(r)
        }
        is CoreResult.Ok -> {
            var rate = r.value
            send(CoreResult.Ok(rate.toBins()))
            for (d in pending) {
                val next = rate.apply(d)
                if (next != rate) {
                    rate = next
                    send(CoreResult.Ok(rate.toBins()))
                }
            }
        }
    }
}
