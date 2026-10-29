package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** §1.2.2: a core that fails to open leaves the process up, in a Failed state with a retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreOpenerTest {
    @Test fun aThrowingOpenFailsSoftlyAndRetries() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var attempts = 0
        val fake = FakeCore(backgroundScope, simulate = false)
        val opener = CoreOpener(this, io) {
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

        opener.retry()
        advanceUntilIdle()
        assertTrue(opener.state.value is CoreState.Ready)
        assertSame(fake, opener.ingest.await())
        assertSame(fake, opener.ready().queries)
        assertEquals(2, attempts)
    }

    @Test fun startIsIdempotentAndRetryNeedsAFailure() = runTest {
        var attempts = 0
        val fake = FakeCore(backgroundScope, simulate = false)
        val opener = CoreOpener(this, StandardTestDispatcher(testScheduler)) { attempts++; CoreHandle.of(fake) }
        opener.start(); opener.start()
        advanceUntilIdle()
        opener.retry()
        advanceUntilIdle()
        assertEquals(1, attempts)
    }
}
