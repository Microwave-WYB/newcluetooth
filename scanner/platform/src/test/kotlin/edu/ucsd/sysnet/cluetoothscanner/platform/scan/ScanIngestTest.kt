package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanIngestTest {
    private val batches = mutableListOf<List<Long>>()
    private var refuse: (Int) -> Exception? = { null }

    private val core = object : TestCoreIngest() {
        var calls = 0
        override fun ingestScans(batch: List<ScanObservationInput>): IngestAck {
            refuse(++calls)?.let { throw it }
            batches += batch.map { it.scannedAtMs }
            return IngestAck(batch.size, 0, 0)
        }
    }

    private fun observation(n: Long) = ScanObservationInput(
        "AA:BB:CC:00:11:22", -60, n, n, byteArrayOf(2, 1, 6), null, null, true,
        false, 1, 0, null, null, null, 0, 1,
    )

    private fun TestScope.ingest(capacity: Int = 1_024) = ScanIngest(
        core = CompletableDeferred<CoreIngest>(core),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        capacity = capacity,
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun `section 4_1 batches hold at most 64 observations`() = runTest {
        val ingest = ingest()
        for (n in 1L..150L) ingest.offer(observation(n))
        runCurrent()
        assertEquals("full batches go at once", listOf(64, 64), batches.map { it.size })
        advanceTimeBy(251)
        runCurrent()
        assertEquals("the rest after the window", listOf(64, 64, 22), batches.map { it.size })
        assertEquals((1L..150L).toList(), batches.flatten())
    }

    @Test
    fun `section 4_1 a partial batch waits 250 ms from its first observation`() = runTest {
        val ingest = ingest()
        ingest.offer(observation(1))
        runCurrent()
        advanceTimeBy(100)
        ingest.offer(observation(2))
        advanceTimeBy(149)
        runCurrent()
        assertTrue("still inside the window", batches.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(listOf(1L, 2L)), batches)
    }

    @Test
    fun `section 4_1 backpressure keeps the batch and retries after 100 ms`() = runTest {
        refuse = { call -> if (call <= 2) CoreException.Backpressure() else null }
        val ingest = ingest()
        for (n in 1L..64L) ingest.offer(observation(n))
        runCurrent()
        assertTrue(batches.isEmpty())
        advanceTimeBy(100)
        runCurrent()
        assertTrue(batches.isEmpty())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf((1L..64L).toList()), batches)
        assertEquals(0L, ingest.dropped.value)
    }

    @Test
    fun `section 4_1 a full queue drops the oldest and counts them`() = runTest {
        val ingest = ingest(capacity = 4)
        for (n in 1L..6L) ingest.offer(observation(n))
        assertEquals(2L, ingest.dropped.value)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(listOf(3L, 4L, 5L, 6L)), batches)
    }

    @Test
    fun `flush delivers everything offered before it without waiting for the window`() = runTest {
        val ingest = ingest()
        ingest.offer(observation(1))
        runCurrent()
        ingest.offer(observation(2))
        ingest.offer(observation(3))
        val flushed = async { ingest.flush() }
        runCurrent()
        assertTrue(flushed.isCompleted)
        assertEquals(listOf(1L, 2L, 3L), batches.flatten())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `flush with nothing queued returns at once`() = runTest {
        val ingest = ingest()
        val flushed = async { ingest.flush() }
        runCurrent()
        assertTrue(flushed.isCompleted)
    }

    @Test
    fun `a flush the core never takes ends false after its bound, never hangs (#70 review)`() = runTest {
        refuse = { CoreException.Backpressure() }
        val ingest = ingest()
        ingest.offer(observation(1))
        val flushed = async { ingest.flush() }
        advanceTimeBy(ScanIngest.FLUSH_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(flushed.isCompleted)
        assertFalse(flushed.await())
    }

    @Test
    fun `a flush after the batcher stopped is released at once with false (#70 review)`() = runTest {
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val ingest = ScanIngest(CompletableDeferred<CoreIngest>(core), owner, StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime })
        refuse = { CoreException.Backpressure() }
        ingest.offer(observation(1))
        val waiting = async { ingest.flush() }
        runCurrent()
        owner.cancel()
        runCurrent()
        assertTrue("a waiting flush is released", waiting.isCompleted)
        assertFalse(waiting.await())
        assertFalse(ingest.flush())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a cancelled caller of flush is cancelled, not answered false (#70 re-review)`() = runTest {
        refuse = { CoreException.Backpressure() }
        val ingest = ingest()
        ingest.offer(observation(1))
        var after = false
        val caller = launch {
            ingest.flush()
            after = true
        }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(caller.isCancelled)
        assertFalse("nothing after flush runs in a cancelled caller", after)
    }

    @Test
    fun `a refused batch is counted and the next one still goes in`() = runTest {
        refuse = { call -> if (call == 1) CoreException.InvalidPayload("bad raw") else null }
        val ingest = ingest()
        for (n in 1L..64L) ingest.offer(observation(n))
        runCurrent()
        assertEquals(64L, ingest.rejected.value)
        ingest.offer(observation(65))
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf(listOf(65L)), batches)
    }
}
