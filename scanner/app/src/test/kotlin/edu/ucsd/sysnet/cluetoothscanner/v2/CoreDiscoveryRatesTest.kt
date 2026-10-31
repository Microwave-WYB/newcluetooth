package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryBin
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryRun
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryUpdate
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryRate as CoreRate

/** R-4a's adapter on the core (#112): the call and the live delta's `discovery`, mapped 1:1, Err kept typed. */
class CoreDiscoveryRatesTest {
    private val state = CoreRecordingState(RecordingPhase.Recording, "s", 0, 0, 0, 3, 0.0)

    private class Spy(fake: FakeCore, val answer: CoreResult<CoreRate>) : CoreQueries by fake, CoreStorage by fake, CoreIngest by fake {
        override val live = MutableSharedFlow<LiveDelta>(extraBufferCapacity = 8)
        val asked = mutableListOf<String>()
        override suspend fun discoveryRate(sessionId: String): CoreResult<CoreRate> = answer.also { asked += sessionId }
    }

    private fun delta(seq: Long, discovery: DiscoveryUpdate?) =
        LiveDelta("s", seq, 0, state, emptyList(), emptyList(), emptyList(), emptyList(), null, null, discovery)

    @Test fun theCallIsMappedOneToOne() = runTest {
        val core = CoreRate(7, 30_000, listOf(DiscoveryBin(0, 2), DiscoveryBin(60_000, 1)), listOf(DiscoveryRun(0, 90_000), DiscoveryRun(120_000, null)))
        val spy = Spy(FakeCore(backgroundScope, simulate = false), CoreResult.Ok(core))
        val port = CoreDiscoveryRates { CoreHandle.of(spy) }
        assertEquals(
            CoreResult.Ok(DiscoveryRate(7, 30_000, listOf(Bin(0, 2), Bin(60_000, 1)), listOf(Run(0, 90_000), Run(120_000, null)))),
            port.discoveryRate("s"),
        )
        assertEquals(listOf("s"), spy.asked)
    }

    @Test fun anErrKeepsTheCoresError() = runTest {
        val err = CoreResult.Err(CoreException.Db("locked"))
        assertEquals(err, CoreDiscoveryRates { CoreHandle.of(Spy(FakeCore(backgroundScope, simulate = false), err)) }.discoveryRate("s"))
    }

    /** Each live delta with a `discovery` is one delta, with the live delta's session and seq; the others are none. */
    @Test fun theLiveDeltasDiscoveryIsTheDelta() = runTest {
        val spy = Spy(FakeCore(backgroundScope, simulate = false), CoreResult.Ok(CoreRate.Empty))
        val port = CoreDiscoveryRates { CoreHandle.of(spy) }
        val got = mutableListOf<DiscoveryRateDelta>()
        backgroundScope.launch { port.deltas.take(1).toList(got) }
        testScheduler.runCurrent()
        spy.live.emit(delta(3, null))
        spy.live.emit(delta(4, DiscoveryUpdate(listOf(DiscoveryBin(30_000, 5)), listOf(DiscoveryRun(0, null)))))
        testScheduler.runCurrent()
        assertEquals(listOf(DiscoveryRateDelta("s", 4, listOf(Bin(30_000, 5)), listOf(Run(0, null)))), got)
    }
}
