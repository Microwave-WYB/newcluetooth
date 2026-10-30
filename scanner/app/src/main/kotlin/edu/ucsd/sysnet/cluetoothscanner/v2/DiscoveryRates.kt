package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryBin
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryRun
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * R-4a, the core's discovery rate as the screen uses it: `CoreQueries.discoveryRate(sessionId)` and the live delta's
 * `discovery` field (#112). [CoreDiscoveryRates] is the core's; tests pass their own.
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

/**
 * R-4a on the core (#112): `CoreQueries.discoveryRate` and each live delta's `discovery` (null when neither its bins
 * nor its runs changed), the core's bins and runs mapped 1:1. [core] suspends until the core is open (§1.2.2).
 */
internal class CoreDiscoveryRates(private val core: suspend () -> CoreHandle) : DiscoveryRates {
    override val deltas: Flow<DiscoveryRateDelta> = flow {
        emitAll(core().queries.live.mapNotNull { d -> d.discovery?.let { DiscoveryRateDelta(d.sessionId, d.seq, it.bins.map(::bin), it.runs.map(::run)) } })
    }

    override suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate> =
        when (val r = core().queries.discoveryRate(sessionId)) {
            is CoreResult.Ok -> CoreResult.Ok(DiscoveryRate(r.value.seq, r.value.binMs, r.value.bins.map(::bin), r.value.runs.map(::run)))
            is CoreResult.Err -> r
        }

    private fun bin(b: DiscoveryBin) = DiscoveryBins.Bin(b.startMs, b.newDevices)
    private fun run(r: DiscoveryRun) = DiscoveryBins.Run(r.startMs, r.endMs)
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

/**
 * R-4a: the recording's discovery rate for the screen. It reads the rate (and follows its deltas, [feed]) for the
 * recording's [session], and reads it again on each [reads] bump (the notice's Retry) and each [invalidated] (the
 * core's `DataInvalidated`: e.g. the display filter changed which devices count). A re-read keeps the shown rate
 * until the new one arrives; a new session (or none) shows empty first. Each Err, and anything a read throws, goes
 * to [failed] as [MainTask.DiscoveryRate]; the next trigger reads again.
 */
internal suspend fun DiscoveryRates.follow(
    session: Flow<String?>,
    reads: Flow<Int>,
    invalidated: Flow<Unit>,
    show: (DiscoveryBins) -> Unit,
    failed: (MainTask, Throwable) -> Unit,
) = coroutineScope {
    val invalidations = MutableStateFlow(0)
    launch(start = CoroutineStart.UNDISPATCHED) { invalidated.collect { invalidations.value += 1 } }
    var shown: String? = null
    combine(session.distinctUntilChanged(), reads, invalidations) { id, _, _ -> id }.collectLatest { id ->
        if (id != shown || id == null) show(DiscoveryBins.Empty)
        shown = id
        if (id == null) return@collectLatest
        guarded(MainTask.DiscoveryRate, failed) {
            feed(id).collect { r ->
                when (r) {
                    is CoreResult.Ok -> show(r.value)
                    is CoreResult.Err -> failed(MainTask.DiscoveryRate, r.error)
                }
            }
        }
    }
}
