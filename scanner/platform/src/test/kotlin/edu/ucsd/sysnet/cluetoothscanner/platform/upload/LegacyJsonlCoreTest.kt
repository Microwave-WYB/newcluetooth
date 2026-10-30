package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** §1.5: the pre-0.0.5 JSONL uploader, through the same pass reducer and runner as the payloads. */
class LegacyJsonlCoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val files by lazy { folder.newFolder("files") }
    private val quarantine by lazy { File(files, "quarantine/legacy") }
    private val sealed by lazy { folder.newFolder("sealed") }

    /** A stand-in for the sealed box: reversible and visibly different from the plaintext. */
    private val seal: (ByteArray) -> ByteArray = { "sealed:".toByteArray() + it }

    private val core by lazy { LegacyJsonlCore(files, quarantine, sealed, seal, Dispatchers.Unconfined) }

    private class Store : ObjectStore {
        val stored = mutableMapOf<String, ByteArray>()
        var failNext = false
        override suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult {
            onSession("https://upload/$objectPath")
            if (failNext) {
                failNext = false
                return StoreResult.Failed("http=503")
            }
            stored[objectPath] = file.readBytes()
            return StoreResult.Stored
        }

        override suspend fun exists(objectPath: String) = objectPath in stored
    }

    private val store = Store()
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
    private val runner by lazy { UploadPassRunner(core, store, { place }, Dispatchers.Unconfined) }

    private fun legacy(name: String, text: String = "{\"mac\":\"AA\"}\n") = File(files, name).apply { writeText(text) }

    @Test
    fun `v3's names - producers 0_0_1 to 0_0_4 only, under scans, stable across retries`() {
        assertEquals("scans/1700000000_pixel_0.0.3.jsonl.zst.encrypted", LegacyJsonlCore.legacyObjectPath("1700000000_pixel_0.0.3.jsonl.zst"))
        assertEquals("scans/a_0.0.4-rc1.jsonl.zst.encrypted", LegacyJsonlCore.legacyObjectPath("a_0.0.4-rc1.jsonl.zst"))
        for (bad in listOf("a_0.0.5.jsonl.zst", "a_0.1.0.jsonl.zst", "a_0.0.3.jsonl", "a_0.0.3.jsonl.uploaded", "x/a_0.0.3.jsonl.zst", "_0.0.3x.jsonl.zst")) {
            assertNull(bad, LegacyJsonlCore.legacyObjectPath(bad))
        }
    }

    @Test
    fun `a pass seals and uploads each legacy file, then renames it uploaded, keeping it`() = runBlocking {
        val file = legacy("1700000000_pixel_0.0.3.jsonl.zst", "rows")
        legacy("1700000001_pixel_0.0.2.jsonl.zst", "more")
        assertEquals(UploadTally(succeeded = 2), runner.run(UploadKind.SCANS))
        assertEquals("sealed:rows", String(store.stored.getValue("scans/1700000000_pixel_0.0.3.jsonl.zst.encrypted")))
        assertFalse("no longer pending", file.exists())
        assertEquals("kept, renamed as v3 did", "rows", File(files, "1700000000_pixel_0.0.3.jsonl.uploaded").readText())
        assertTrue("the sealed copies are gone", sealed.listFiles().orEmpty().isEmpty())
        assertEquals("nothing left", UploadTally(), runner.run(UploadKind.SCANS))
        assertEquals(PlatformResult.Ok(UploadStatus(0, 0, 0, null)), core.status())
    }

    @Test
    fun `a failed upload stays pending unchanged and the next pass resumes it`() = runBlocking {
        val file = legacy("1700000000_pixel_0.0.1.jsonl.zst", "rows")
        store.failNext = true
        assertEquals(UploadTally(failed = 1), runner.run(UploadKind.SCANS))
        assertEquals("rows", file.readText())
        assertEquals(1, (core.status() as PlatformResult.Ok).value.pendingCount)
        assertEquals(UploadTally(succeeded = 1), runner.run(UploadKind.SCANS))
        assertTrue(File(files, "1700000000_pixel_0.0.1.jsonl.uploaded").exists())
    }

    @Test
    fun `an unsupported legacy name is quarantined, never uploaded and never deleted`() = runBlocking {
        legacy("strange.jsonl.zst", "keep me")
        legacy("a_0.0.9.jsonl.zst", "keep me too")
        assertEquals(UploadTally(), runner.run(UploadKind.SCANS))
        assertTrue(store.stored.isEmpty())
        assertEquals("keep me", File(quarantine, "strange.jsonl.zst").readText())
        assertEquals("keep me too", File(quarantine, "a_0.0.9.jsonl.zst").readText())
    }

    @Test
    fun `the legacy files go with every scan pass, on the scans actor`() = runBlocking {
        legacy("1700000000_pixel_0.0.4.jsonl.zst", "rows")
        val empty = object : UploadCore {
            override suspend fun status() = PlatformResult.Ok(UploadStatus(0, 0, 0, null))
            override suspend fun pending() = PlatformResult.Ok(emptyList<PendingFile>())
            override suspend fun prepare(id: String) = PlatformResult.Ok(Prepared(id, "", ""))
            override suspend fun markSucceeded(id: String) = PlatformResult.Ok(Unit)
            override suspend fun markFailed(id: String, message: String) = PlatformResult.Ok(Unit)
        }
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val scheduler = object : UploadScheduler {
            override fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime) = Unit
            override fun periodic(wifiOnly: Boolean, policy: Periodic) = Unit
        }
        val marks = object : UploadMarks {
            override fun v3WorkReplaced() = true
            override fun markV3WorkReplaced() = Unit
        }
        val uploads = PlatformUploads(
            empty, UploadPassRunner(empty, store, { place }, Dispatchers.Unconfined), scheduler, marks, scope, MutableStateFlow(false), legacy = runner,
        )
        assertEquals("nothing for GATT", UploadTally(), uploads.pass(UploadKind.GATT))
        assertTrue(store.stored.isEmpty())
        assertEquals(UploadTally(succeeded = 1), uploads.pass(UploadKind.SCANS))
        assertEquals(setOf("scans/1700000000_pixel_0.0.4.jsonl.zst.encrypted"), store.stored.keys)
        scope.cancel()
    }
}
