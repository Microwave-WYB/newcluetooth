package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pass runner over fakes: the bytes a session started with, the core's identity check, cleanup, failures. */
class UploadPassRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private inner class Core : UploadCore {
        val pending = mutableListOf<PendingFile>()
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        var prepares = 0
        var preparedAs: (String) -> Prepared = { id -> Prepared(id, ciphertext(id).path, pending.first { it.id == id }.objectPath) }
        var listFails = false
        var statusReads = 0

        override suspend fun status(): PlatformResult<UploadStatus> {
            statusReads++
            return PlatformResult.Ok(UploadStatus(pending.size, 0, failed.size, failed.lastOrNull()?.second))
        }

        override suspend fun pending() =
            if (listFails) PlatformResult.Err(PlatformError.Core("db locked")) else PlatformResult.Ok(pending.toList())

        override suspend fun prepare(id: String): PlatformResult<Prepared> {
            prepares++
            return PlatformResult.Ok(preparedAs(id))
        }

        override suspend fun markSucceeded(id: String): PlatformResult<Unit> {
            succeeded += id
            pending.removeAll { it.id == id }
            return PlatformResult.Ok(Unit)
        }

        override suspend fun markFailed(id: String, message: String): PlatformResult<Unit> {
            failed += id to message
            return PlatformResult.Ok(Unit)
        }
    }

    private class Store : ObjectStore {
        val puts = mutableListOf<Triple<String, ByteArray, String?>>()
        var next: (String?) -> StoreResult = { StoreResult.Stored }
        var exists: Boolean? = false

        override suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult {
            puts += Triple(objectPath, file.readBytes(), sessionUri)
            onSession("https://upload/session-${puts.size}")
            return next(sessionUri)
        }

        override suspend fun exists(objectPath: String) = exists
    }

    private class Sessions : UploadSessionStore {
        val map = mutableMapOf<String, UploadSession>()
        override fun get(id: String) = map[id]
        override fun put(id: String, session: UploadSession) {
            map[id] = session
        }

        override fun remove(id: String) {
            map.remove(id)
        }

        override fun ids() = map.keys.toSet()
    }

    private val core = Core()
    private val store = Store()
    private val sessions = Sessions()
    private val cache by lazy { folder.newFolder("upload-cache") }
    private val runner by lazy { UploadPassRunner(core, store, { UploadPlace(sessions, cache) }, Dispatchers.Unconfined) }

    /** The core's fresh ciphertext for [id]: different bytes at every prepare, as the real one. */
    private fun ciphertext(id: String) = File(folder.root, "core-$id-${core.prepares}.encrypted").apply { writeBytes("$id#${core.prepares}".toByteArray()) }

    private val scan = PendingFile("a", "scans/v3/a.parquet.encrypted")

    @Test
    fun `a fresh send uploads the core's ciphertext from the cache, then drops the cache and the session`() = runBlocking {
        core.pending += scan
        assertEquals(UploadTally(succeeded = 1), runner.run(UploadKind.SCANS))
        assertEquals("scans/v3/a.parquet.encrypted", store.puts.single().first)
        assertEquals("a#1", String(store.puts.single().second))
        assertEquals(listOf("a"), core.succeeded)
        assertFalse("the cached ciphertext is gone", File(cache, "a.encrypted").exists())
        assertNull("and the session", sessions.get("a"))
    }

    @Test
    fun `a failed send keeps the exact bytes and the session, and the next pass resumes them`() = runBlocking {
        core.pending += scan
        store.next = { StoreResult.Failed("http=503") }
        assertEquals(UploadTally(failed = 1), runner.run(UploadKind.SCANS))
        assertEquals("a" to "http=503", core.failed.single())
        val session = sessions.get("a") ?: error("no session kept")
        store.next = { StoreResult.Stored }
        runner.run(UploadKind.SCANS)
        val resumed = store.puts.last()
        assertEquals("the same session", session.uri, resumed.third)
        assertEquals("the same bytes, not a new ciphertext", "a#1", String(resumed.second))
        assertEquals("no second prepare", 1, core.prepares)
    }

    @Test
    fun `a file the core prepared for another object is never uploaded`() = runBlocking {
        core.pending += scan
        core.preparedAs = { id -> Prepared(id, ciphertext(id).path, "scans/v3/other.parquet.encrypted") }
        assertEquals(UploadTally(failed = 1), runner.run(UploadKind.SCANS))
        assertTrue(store.puts.isEmpty())
        assertTrue(core.failed.single().second.contains("other.parquet"))
    }

    @Test
    fun `orphaned sessions and cached files are discarded, a failed listing is a failed pass`() = runBlocking {
        sessions.put("gone", UploadSession("https://upload/old", File(cache, "gone.encrypted").apply { writeText("x") }.path))
        File(cache, "stray.encrypted").writeText("y")
        assertEquals(UploadTally(), runner.run(UploadKind.SCANS))
        assertTrue(sessions.ids().isEmpty())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        core.listFails = true
        assertEquals(UploadTally(failed = 1, problem = "the pending uploads could not be read: db locked"), runner.run(UploadKind.SCANS))
    }

    @Test
    fun `retries follow v3 - scans on failures, GATT also on denials`() {
        assertFalse(retries(UploadKind.SCANS, UploadTally(succeeded = 2, denied = 1)))
        assertTrue(retries(UploadKind.SCANS, UploadTally(failed = 1)))
        assertTrue(retries(UploadKind.GATT, UploadTally(denied = 1)))
        assertFalse(retries(UploadKind.GATT, UploadTally(succeeded = 3)))
    }

    private class Scheduler : UploadScheduler {
        val calls = mutableListOf<String>()
        var throws = false
        override fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime) {
            if (throws) throw IllegalStateException("WorkManager is not initialized")
            calls += "$kind $policy wifi=$wifiOnly"
        }

        override fun periodic(wifiOnly: Boolean, policy: Periodic) {
            if (throws) throw IllegalStateException("WorkManager is not initialized")
            calls += "periodic $policy wifi=$wifiOnly"
        }
    }

    private class Marks(var replaced: Boolean = false) : UploadMarks {
        override fun v3WorkReplaced() = replaced
        override fun markV3WorkReplaced() {
            replaced = true
        }
    }

    @Test
    fun `the first v2 start takes over v3's queued work once, later starts keep what is queued (#100 review)`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val scheduler = Scheduler()
        val marks = Marks()
        PlatformUploads(core, runner, scheduler, marks, scope, MutableStateFlow(false)).start()
        assertEquals(listOf("periodic Replace wifi=false", "SCANS Replace wifi=false", "GATT Replace wifi=false"), scheduler.calls)
        assertTrue(marks.replaced)
        scheduler.calls.clear()
        PlatformUploads(core, runner, scheduler, marks, scope, MutableStateFlow(false)).start()
        assertEquals(listOf("periodic Keep wifi=false", "SCANS Keep wifi=false", "GATT Keep wifi=false"), scheduler.calls)
        // A takeover that could not be scheduled is tried again at the next start.
        val failing = Scheduler().apply { throws = true }
        val fresh = Marks()
        PlatformUploads(core, runner, failing, fresh, scope, MutableStateFlow(false)).start()
        assertFalse(fresh.replaced)
        UploadLink.uploads = null
        scope.cancel()
    }

    @Test
    fun `scheduling - Wi-Fi changes re-schedule, upload now restarts both, failures show in the status`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val scheduler = Scheduler()
        val wifi = MutableStateFlow(false)
        val uploads = PlatformUploads(core, runner, scheduler, Marks(replaced = true), scope, wifi)
        uploads.start()
        assertTrue("the workers find it", UploadLink.uploads === uploads)
        scheduler.calls.clear()
        wifi.value = true
        assertEquals(listOf("periodic Update wifi=true", "SCANS Replace wifi=true", "GATT Replace wifi=true"), scheduler.calls)
        scheduler.calls.clear()
        assertEquals(PlatformResult.Ok(Unit), uploads.uploadNow())
        assertEquals(listOf("SCANS ReplaceExpedited wifi=true", "GATT Replace wifi=true"), scheduler.calls)
        scheduler.calls.clear()
        uploads.uploadNeeded()
        assertEquals(listOf("SCANS Keep wifi=true", "GATT Keep wifi=true"), scheduler.calls)
        scheduler.throws = true
        assertTrue("a scheduling failure is a typed result", uploads.uploadNow() is PlatformResult.Err)
        uploads.uploadNeeded()
        assertEquals("and the user sees it", "Uploads not scheduled: WorkManager is not initialized", uploads.status.value.lastError)
        scheduler.throws = false
        uploads.uploadNeeded()
        assertEquals("it clears once scheduling works", null, uploads.status.value.lastError)
        // The status follows a pass.
        core.pending += scan
        val reads = core.statusReads
        uploads.pass(UploadKind.SCANS)
        assertTrue(core.statusReads > reads)
        assertEquals(0, uploads.status.value.pendingCount)
        UploadLink.uploads = null
        scope.cancel()
    }

    @Test
    fun `a failure the core could not record shows in the status (#100 review)`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val recordingFails = object : UploadCore by core {
            override suspend fun markFailed(id: String, message: String): PlatformResult<Unit> = PlatformResult.Err(PlatformError.Core("db locked"))
        }
        val uploads = PlatformUploads(
            recordingFails, UploadPassRunner(recordingFails, store, { UploadPlace(sessions, cache) }, Dispatchers.Unconfined),
            Scheduler(), Marks(replaced = true), scope, MutableStateFlow(false),
        )
        core.pending += scan
        store.next = { StoreResult.Failed("http=503") }
        val tally = uploads.pass(UploadKind.SCANS)
        assertEquals(1, tally.failed)
        assertEquals("a failed (http=503) and the core could not record it: db locked", uploads.status.value.lastError)
        scope.cancel()
    }

    /** A store whose first upload waits until [release], recording overlaps and whether its bytes stayed on disk. */
    private class SlowStore : ObjectStore {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var puts = 0
        val bytesSeen = mutableListOf<String>()
        override suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult {
            active++
            maxActive = maxOf(maxActive, active)
            puts++
            onSession("https://upload/s$puts")
            if (puts == 1) release.await()
            // The file it streams is still there, unchanged, when the transfer ends.
            bytesSeen += if (file.isFile) String(file.readBytes()) else "<deleted>"
            active--
            return StoreResult.Stored
        }

        override suspend fun exists(objectPath: String) = false
    }

    @Test
    fun `two passes of one kind never overlap, so one cannot overwrite or delete the file the other streams (#100 review)`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val slow = SlowStore()
        val uploads = PlatformUploads(
            core, UploadPassRunner(core, slow, { UploadPlace(sessions, cache) }, Dispatchers.Default),
            Scheduler(), Marks(replaced = true), scope, MutableStateFlow(false),
        )
        core.pending += scan
        // The one-time and the periodic work start together over the same pending file.
        val first = scope.async { uploads.pass(UploadKind.SCANS) }
        while (slow.puts == 0) kotlinx.coroutines.delay(5)
        val second = scope.async { uploads.pass(UploadKind.SCANS) }
        kotlinx.coroutines.delay(200)
        assertEquals("the second waits for the first", 1, slow.puts)
        slow.release.complete(Unit)
        val tallies = listOf(first.await(), second.await())
        assertEquals("never two transfers at once", 1, slow.maxActive)
        assertEquals("the streamed file was intact", listOf("a#1"), slow.bytesSeen)
        assertEquals("the file went up once", listOf("a"), core.succeeded)
        assertEquals(1, tallies[0].succeeded)
        scope.cancel()
    }
}
