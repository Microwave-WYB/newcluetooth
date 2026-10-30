package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** R-4a's data on the screen: the snapshot, then the session's newer deltas applied; an Err is passed on once. */
class DiscoveryRatesTest {
    private val bin = DiscoveryBins.BIN_MS
    private val running = listOf(Run(0, null))

    /** A scripted core: [snapshot] answers the query, after [onQuery] ran (e.g. a delta sent while it reads). */
    private class Script(val snapshot: CoreResult<DiscoveryRate>, val onQuery: suspend (MutableSharedFlow<DiscoveryRateDelta>) -> Unit = {}) : DiscoveryRates {
        override val deltas = MutableSharedFlow<DiscoveryRateDelta>(extraBufferCapacity = 16)
        var queried = 0
        override suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate> {
            queried++
            onQuery(deltas)
            return snapshot
        }
    }

    @Test fun aDeltaReplacesItsBinAndTheRuns() {
        val rate = DiscoveryRate(4, bin, listOf(Bin(0, 2), Bin(bin, 1)), running)
        val paused = listOf(Run(0, 2 * bin))
        val next = rate.apply(DiscoveryRateDelta("s", 5, listOf(Bin(bin, 3), Bin(2 * bin, 1)), paused))
        assertEquals(DiscoveryRate(5, bin, listOf(Bin(0, 2), Bin(bin, 3), Bin(2 * bin, 1)), paused), next)
        assertEquals("an old delta is dropped", rate, rate.apply(DiscoveryRateDelta("s", 4, listOf(Bin(0, 9)), paused)))
        assertEquals(DiscoveryBins(bin, next.bins, paused), next.toBins())
    }

    /** §2.6: subscribed before the query, so a delta sent while it reads is kept, and applied only if newer. */
    @Test fun subscribesFirstAndAppliesTheSessionsNewerDeltas() = runTest {
        val snapshot = DiscoveryRate(10, bin, listOf(Bin(0, 2)), running)
        val core = Script(CoreResult.Ok(snapshot)) { d ->
            d.emit(DiscoveryRateDelta("s", 9, listOf(Bin(0, 1)), running)) // older than the snapshot
            d.emit(DiscoveryRateDelta("s", 11, listOf(Bin(bin, 4)), running))
        }
        val got = async(start = CoroutineStart.UNDISPATCHED) { core.feed("s").take(3).toList() }
        testScheduler.runCurrent() // subscribed, queried, the snapshot sent
        core.deltas.emit(DiscoveryRateDelta("other", 12, listOf(Bin(0, 50)), running)) // another session's
        core.deltas.emit(DiscoveryRateDelta("s", 12, listOf(Bin(bin, 5)), running))
        assertEquals(
            listOf(
                CoreResult.Ok(DiscoveryBins(bin, listOf(Bin(0, 2)), running)),
                CoreResult.Ok(DiscoveryBins(bin, listOf(Bin(0, 2), Bin(bin, 4)), running)),
                CoreResult.Ok(DiscoveryBins(bin, listOf(Bin(0, 2), Bin(bin, 5)), running)),
            ),
            got.await(),
        )
        assertEquals(1, core.queried)
    }

    /** An Err is emitted once and ends the feed; the screen's notice reads it again. */
    @Test fun anErrEndsTheFeed() = runTest {
        val err = CoreResult.Err(CoreException.Db("locked"))
        assertEquals(listOf(err), Script(err).feed("s").toList())
    }

    /** The stand-in until lane A's call lands: an empty rate, which draws an empty chart. */
    @Test fun theStandInIsEmpty() = runTest {
        assertEquals(listOf(CoreResult.Ok(DiscoveryBins.Empty)), NoDiscoveryRates.feed("s").take(1).toList())
    }
}
