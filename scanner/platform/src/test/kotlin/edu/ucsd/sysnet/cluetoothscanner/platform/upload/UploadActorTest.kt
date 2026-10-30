package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #100 re-review: the per-kind actor - stopping, sharing, extra passes, failures - with real concurrency. */
class UploadActorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
    }

    /** A core whose listings can be held, made to throw, or made to seal a file right after they answered. */
    private inner class Core : UploadCore {
        val pending = ConcurrentLinkedQueue<PendingFile>()
        val succeeded = ConcurrentLinkedQueue<String>()
        val failed = ConcurrentLinkedQueue<String>()
        val listings = AtomicInteger()
        val holds = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var throwOnListing: Int? = null
        var afterListing: (Int) -> Unit = {}

        override suspend fun status() = PlatformResult.Ok(UploadStatus(pending.size, 0, failed.size, null))

        override suspend fun pending(): PlatformResult<List<PendingFile>> {
            val n = listings.incrementAndGet()
            holds[n]?.await()
            if (n == throwOnListing) throw IllegalStateException("the core went away")
            val answer = pending.toList()
            afterListing(n)
            return PlatformResult.Ok(answer)
        }

        override suspend fun prepare(id: String): PlatformResult<Prepared> {
            val file = pending.first { it.id == id }
            val bytes = File(folder.root, "c-$id").apply { writeText("cipher-$id") }
            return PlatformResult.Ok(Prepared(id, bytes.path, file.objectPath))
        }

        override suspend fun markSucceeded(id: String): PlatformResult<Unit> {
            succeeded += id
            pending.removeIf { it.id == id }
            return PlatformResult.Ok(Unit)
        }

        override suspend fun markFailed(id: String, message: String): PlatformResult<Unit> {
            failed += id
            return PlatformResult.Ok(Unit)
        }
    }

    /** A store whose transfer can be held until cancelled, like a Firebase task cancelled by its coroutine. */
    private class Store : ObjectStore {
        var hold = false
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult {
            if (hold) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            return StoreResult.Stored
        }

        override suspend fun exists(objectPath: String) = false
    }

    private val core = Core()
    private val store = Store()
    /** Touched by one pass at a time (the actor serializes them), so a plain map is enough. */
    private val sessions = mutableMapOf<String, UploadSession>()
    private val place by lazy {
        UploadPlace(
            object : UploadSessionStore {
                override fun get(id: String) = sessions[id]
                override fun put(id: String, session: UploadSession) {
                    sessions[id] = session
                }

                override fun remove(id: String) {
                    sessions.remove(id)
                }

                override fun ids() = sessions.keys.toSet()
            },
            folder.newFolder("cache"),
        )
    }

    private val uploads by lazy {
        PlatformUploads(
            core,
            UploadPassRunner(core, store, { place }, Dispatchers.Default),
            object : UploadScheduler {
                override fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime) = Unit
                override fun periodic(wifiOnly: Boolean, policy: Periodic) = Unit
            },
            object : UploadMarks {
                override fun v3WorkReplaced() = true
                override fun markV3WorkReplaced() = Unit
            },
            scope,
            MutableStateFlow(false),
        )
    }

    private val a = PendingFile("a", "scans/v3/a.parquet.encrypted")
    private val b = PendingFile("b", "scans/v3/b.parquet.encrypted")

    private suspend fun until(what: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }.also { assertTrue(what, condition()) }

    @Test
    fun `a worker stopped mid-transfer stops the transfer, and the file stays pending and unmarked`() = runBlocking {
        core.pending += a
        store.hold = true
        val worker = scope.async { uploads.pass(UploadKind.SCANS) }
        withTimeout(5_000) { store.started.await() }
        worker.cancel() // WorkManager stopped it: Wi-Fi lost, quota, or Upload now replacing the job
        withTimeout(5_000) { store.cancelled.await() }
        delay(100)
        assertTrue("no success mark", core.succeeded.isEmpty())
        assertTrue("no failure mark either: the file just waits", core.failed.isEmpty())
        assertEquals("still pending", listOf(a), core.pending.toList())
        // The actor is alive: the next worker uploads it.
        store.hold = false
        assertEquals(UploadTally(succeeded = 1), withTimeout(5_000) { uploads.pass(UploadKind.SCANS) })
    }

    @Test
    fun `a stopped worker does not stop a pass another worker still waits for`() = runBlocking {
        core.holds[1] = CompletableDeferred()
        core.holds[2] = CompletableDeferred()
        val first = scope.async { uploads.pass(UploadKind.SCANS) }
        until("the first pass lists") { core.listings.get() == 1 }
        val stopped = scope.async { uploads.pass(UploadKind.SCANS) }
        val waiting = scope.async { uploads.pass(UploadKind.SCANS) }
        delay(100)
        core.holds.getValue(1).complete(Unit)
        until("the shared pass lists") { core.listings.get() == 2 }
        stopped.cancel()
        delay(100)
        core.holds.getValue(2).complete(Unit)
        assertEquals("the other waiter gets the pass, not a stop", UploadTally(), withTimeout(5_000) { waiting.await() })
        assertEquals(UploadTally(), first.await())
        assertTrue(runCatching { stopped.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `requests that arrive during a pass share one pass after it`() = runBlocking {
        core.holds[1] = CompletableDeferred()
        val first = scope.async { uploads.pass(UploadKind.SCANS) }
        until("the first pass lists") { core.listings.get() == 1 }
        val second = scope.async { uploads.pass(UploadKind.SCANS) }
        val third = scope.async { uploads.pass(UploadKind.SCANS) }
        delay(100)
        core.holds.getValue(1).complete(Unit)
        withTimeout(5_000) { listOf(first, second, third).forEach { it.await() } }
        assertEquals("one pass for the first, one shared by the other two", 2, core.listings.get())
    }

    @Test
    fun `a file sealed after a pass's last listing gets another pass at once`() = runBlocking {
        core.pending += a
        // The pass lists a, uploads it, lists again (nothing new) - and right then b is sealed.
        core.afterListing = { n ->
            if (n == 2) {
                core.pending += b
                uploads.uploadNeeded()
            }
        }
        val tally = withTimeout(5_000) { uploads.pass(UploadKind.SCANS) }
        assertEquals("both went in the same worker run", UploadTally(succeeded = 2), tally)
        assertEquals(listOf("a", "b"), core.succeeded.toList())
    }

    @Test
    fun `a pass that throws answers its waiters with a typed failure, and the actor goes on`() = runBlocking {
        core.throwOnListing = 1
        val failed = withTimeout(5_000) { uploads.pass(UploadKind.SCANS) }
        assertEquals(1, failed.failed)
        assertTrue(failed.problem.orEmpty(), failed.problem.orEmpty().startsWith("the upload pass failed: the core went away"))
        core.pending += a
        assertEquals("the next pass runs", UploadTally(succeeded = 1), withTimeout(5_000) { uploads.pass(UploadKind.SCANS) })
    }
}
