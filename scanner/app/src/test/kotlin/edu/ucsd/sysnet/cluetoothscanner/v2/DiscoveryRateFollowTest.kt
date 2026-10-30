package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-4a, [follow]: the recording's rate is read per session, per Retry and again on each `DataInvalidated` (the
 * display filter changed which devices count); a re-read keeps the shown rate until the new one arrives.
 */
class DiscoveryRateFollowTest {
    private val bin = DiscoveryBins.BIN_MS
    private val running = listOf(Run(0, null))
    private fun rate(n: Int) = DiscoveryRate(1, bin, listOf(Bin(0, n)), running)
    private fun bins(n: Int) = DiscoveryBins(bin, listOf(Bin(0, n)), running)

    /** A core whose answer can change between reads; it records each read's session. */
    private class Core(var answer: (String) -> CoreResult<DiscoveryRate>) : DiscoveryRates {
        override val deltas = MutableSharedFlow<DiscoveryRateDelta>()
        val reads = mutableListOf<String>()
        override suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate> {
            reads += sessionId
            return answer(sessionId)
        }
    }

    private class Follow(val core: Core) {
        val session = MutableStateFlow<String?>("s")
        val retries = MutableStateFlow(0)
        val invalidated = MutableSharedFlow<Unit>()
        val shown = mutableListOf<DiscoveryBins>()
        val failed = mutableListOf<Pair<MainTask, Throwable>>()

        fun start(scope: TestScope) {
            scope.backgroundScope.launch { core.follow(session, retries, invalidated, { shown += it }) { t, e -> failed += t to e } }
            scope.testScheduler.runCurrent()
        }
    }

    private fun TestScope.settle() = testScheduler.runCurrent()

    /** The display filter changed what counts: the core says DataInvalidated, and the same session is read again. */
    @Test fun aDataInvalidatedReadsTheRateAgain() = runTest {
        val core = Core { CoreResult.Ok(rate(2)) }
        val r = Follow(core).apply { start(this@runTest) }
        assertEquals(listOf("s"), core.reads)
        core.answer = { CoreResult.Ok(rate(1)) } // an unnamed Apple device no longer counts
        r.invalidated.emit(Unit); settle()
        assertEquals(listOf("s", "s"), core.reads)
        // empty only for the new session, then each read's rate: no empty flash on the re-read
        assertEquals(listOf(DiscoveryBins.Empty, bins(2), bins(1)), r.shown)
        r.invalidated.emit(Unit); r.invalidated.emit(Unit); settle()
        assertEquals(4, core.reads.size)
        assertEquals(emptyList<Pair<MainTask, Throwable>>(), r.failed)
    }

    /** Not recording: nothing is read, not even on a DataInvalidated, and the chart is empty. */
    @Test fun notRecordingReadsNothing() = runTest {
        val core = Core { CoreResult.Ok(rate(2)) }
        val r = Follow(core)
        r.session.value = null
        r.start(this)
        r.invalidated.emit(Unit); settle()
        assertEquals(emptyList<String>(), core.reads)
        assertEquals(DiscoveryBins.Empty, r.shown.last())
    }

    /** A new session shows empty first and reads its own rate; Stop empties the chart. */
    @Test fun aNewSessionStartsEmpty() = runTest {
        val core = Core { id -> CoreResult.Ok(rate(if (id == "s") 2 else 7)) }
        val r = Follow(core).apply { start(this@runTest) }
        r.session.value = "t"; settle()
        assertEquals(listOf("s", "t"), core.reads)
        assertEquals(listOf(DiscoveryBins.Empty, bins(2), DiscoveryBins.Empty, bins(7)), r.shown)
        r.session.value = null; settle()
        assertEquals(DiscoveryBins.Empty, r.shown.last())
    }

    /** An Err is the notice's; its Retry reads again, and so does a later DataInvalidated. */
    @Test fun anErrIsReportedAndTheNextTriggerReadsAgain() = runTest {
        val err = CoreException.Db("locked")
        val core = Core { CoreResult.Err(err) }
        val r = Follow(core).apply { start(this@runTest) }
        assertEquals(listOf<Pair<MainTask, Throwable>>(MainTask.DiscoveryRate to err), r.failed)
        core.answer = { CoreResult.Ok(rate(3)) }
        r.retries.value = 1; settle()
        assertEquals(bins(3), r.shown.last())
        core.answer = { throw IllegalStateException("bridge") }
        r.invalidated.emit(Unit); settle()
        assertEquals(MainTask.DiscoveryRate, r.failed.last().first)
        assertEquals("bridge", r.failed.last().second.message)
        assertEquals("the shown rate stays after a failed re-read", bins(3), r.shown.last())
        core.answer = { CoreResult.Ok(rate(4)) }
        r.invalidated.emit(Unit); settle()
        assertEquals(bins(4), r.shown.last())
        assertEquals(4, core.reads.size)
    }

    /** A re-read follows the deltas again from its own snapshot. */
    @Test fun aReReadFollowsTheDeltasAgain() = runTest {
        val core = Core { CoreResult.Ok(rate(2)) }
        val r = Follow(core).apply { start(this@runTest) }
        core.answer = { CoreResult.Ok(DiscoveryRate(5, bin, listOf(Bin(0, 1)), running)) }
        r.invalidated.emit(Unit); settle()
        core.deltas.emit(DiscoveryRateDelta("s", 6, listOf(Bin(bin, 4)), running)); settle()
        assertEquals(DiscoveryBins(bin, listOf(Bin(0, 1), Bin(bin, 4)), running), r.shown.last())
    }
}
