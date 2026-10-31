package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** §1.2.2: the core lifecycle state machine. */
class CoreTransitionsTest {
    private val core = CoreHandle.of(FakeCore(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), simulate = false))
    private val boom = IllegalStateException("boom")

    @Test fun transitions() {
        assertEquals(CoreState.Ready(core), reduce(CoreState.Opening, CoreTransition.Opened(core)))
        assertEquals(CoreState.Failed(boom), reduce(CoreState.Opening, CoreTransition.Error(boom)))
        assertEquals(CoreState.Failed(boom, core), reduce(CoreState.Ready(core), CoreTransition.Error(boom)))
        assertEquals(CoreState.Opening, reduce(CoreState.Failed(boom, core), CoreTransition.Retry))
        // no-ops: Retry outside Failed, Opened outside Opening
        assertEquals(CoreState.Ready(core), reduce(CoreState.Ready(core), CoreTransition.Retry))
        assertEquals(CoreState.Opening, reduce(CoreState.Opening, CoreTransition.Retry))
        assertEquals(CoreState.Failed(boom), reduce(CoreState.Failed(boom), CoreTransition.Opened(core)))
    }
}

/** A throwing CoreBinding: the process stays up, the state is Failed, Retry opens. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreOpenerTest {
    @Test fun aThrowingOpenFailsSoftlyAndRetries() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var attempts = 0
        val fake = FakeCore(backgroundScope, simulate = false)
        val logged = mutableListOf<Throwable>()
        val top = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io) // a top-level scope, as AppGraph's
        val opener = CoreOpener(top, io, log = { logged += it }) {
            attempts++
            if (attempts == 1) throw IOException("local.db: disk I/O error") else CoreHandle.of(fake)
        }
        assertEquals(CoreState.Opening, opener.state.value)
        opener.start()
        advanceUntilIdle()
        val failed = opener.state.value as CoreState.Failed
        assertTrue(failed.error is IOException)
        assertEquals("local.db: disk I/O error", failed.error.message)
        assertFalse(opener.ingest.isCompleted) // :platform waits instead of failing
        assertEquals(listOf(failed.error), logged)

        opener.retry()
        advanceUntilIdle()
        assertTrue(opener.state.value is CoreState.Ready)
        assertSame(fake, opener.ingest.await())
        assertSame(fake, opener.ready().queries)
        assertEquals(2, attempts)
    }

    /** A failure in any :app scope with the handler moves to Failed; Retry returns to the open core without reopening. */
    @Test fun aScopeFailureMovesToFailedAndRetryKeepsTheCore() = runTest {
        var attempts = 0
        val fake = FakeCore(backgroundScope, simulate = false)
        val io = StandardTestDispatcher(testScheduler)
        val top = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io)
        val opener = CoreOpener(top, io, log = {}) { attempts++; CoreHandle.of(fake) }
        opener.start(); opener.start()
        advanceUntilIdle()
        assertTrue(opener.state.value is CoreState.Ready)
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io + opener.handler)
        appScope.launch { error("a query blew up") }
        advanceUntilIdle()
        val failed = opener.state.value as CoreState.Failed
        assertEquals("a query blew up", failed.error.message)
        opener.retry()
        advanceUntilIdle()
        assertTrue(opener.state.value is CoreState.Ready)
        assertEquals(1, attempts)
    }
}
