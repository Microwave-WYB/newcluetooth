package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import android.content.Context
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext

/** The core's upload calls (§2.8) as typed results; [coreUploads] adapts `CoreIngest`, whose calls still throw. */
internal interface UploadCore {
    suspend fun status(): PlatformResult<UploadStatus>
    suspend fun pending(): PlatformResult<List<PendingFile>>

    /** Encrypts [id] into the core's `upload-cache/` (fresh ciphertext every time, §1.6). */
    suspend fun prepare(id: String): PlatformResult<Prepared>
    suspend fun markSucceeded(id: String): PlatformResult<Unit>
    suspend fun markFailed(id: String, message: String): PlatformResult<Unit>
}

internal data class Prepared(val id: String, val ciphertextPath: String, val objectPath: String)

internal fun coreUploads(core: Deferred<CoreIngest>, io: CoroutineDispatcher): UploadCore = object : UploadCore {
    override suspend fun status() = call { it.uploadStatus() }
    override suspend fun pending() = call { core -> core.pendingUploads().map { PendingFile(it.payloadId, it.objectPath) } }
    override suspend fun prepare(id: String) = call { core -> core.prepareUpload(id).let { Prepared(it.payloadId, it.ciphertextPath, it.objectPath) } }
    override suspend fun markSucceeded(id: String) = call { it.markUploadSucceeded(id) }
    override suspend fun markFailed(id: String, message: String) = call { it.markUploadFailed(id, message) }

    private suspend fun <T> call(block: (CoreIngest) -> T): PlatformResult<T> = try {
        PlatformResult.Ok(withContext(io) { block(core.await()) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PlatformResult.Err(PlatformError.Core(failure.message ?: failure::class.java.simpleName))
    }
}

/** The object store (Firebase Storage in production). Never throws: every outcome is a [StoreResult]. */
internal interface ObjectStore {
    /**
     * Uploads [file] to [objectPath]. [sessionUri] continues an earlier resumable upload of the same bytes;
     * [onSession] gets the session URI as soon as the transfer has one.
     */
    suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult

    /** Whether the object is stored; null when the store could not say. */
    suspend fun exists(objectPath: String): Boolean?
}

internal sealed interface StoreResult {
    data object Stored : StoreResult

    /** The session in `sessionUri` can no longer be resumed. */
    data object Expired : StoreResult
    data class Denied(val message: String) : StoreResult
    data class Failed(val message: String) : StoreResult
}

/** Resumable-upload state of one file: the session URI and the exact ciphertext it was started with. */
internal data class UploadSession(val uri: String, val cachePath: String)

internal interface UploadSessionStore {
    fun get(id: String): UploadSession?
    fun put(id: String, session: UploadSession)
    fun remove(id: String)
    fun ids(): Set<String>
}

/**
 * Sessions in prefs, so a killed worker resumes. The same prefs file and format as v3 (`cluetooth_upload_sessions`,
 * `uri\ncachePath`), so an in-place upgrade resumes what v3 started. A prefs call that throws reads as no session.
 */
internal class PrefsUploadSessionStore(context: Context, name: String) : UploadSessionStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(id: String): UploadSession? = guarded(null) {
        prefs.getString(id, null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { UploadSession(it[0], it[1]) }
    }

    override fun put(id: String, session: UploadSession) = guarded(Unit) {
        prefs.edit().putString(id, "${session.uri}\n${session.cachePath}").apply()
    }

    override fun remove(id: String) = guarded(Unit) { prefs.edit().remove(id).apply() }

    override fun ids(): Set<String> = guarded(emptySet()) { prefs.all.keys.toSet() }

    private inline fun <T> guarded(fallback: T, call: () -> T): T = try {
        call()
    } catch (_: Exception) {
        fallback
    }
}

/** Where one kind keeps its sessions and cached ciphertext; v3's names, so the upgrade resumes in place. */
internal data class UploadPlace(val sessions: UploadSessionStore, val cacheDirectory: File) {
    companion object {
        fun of(context: Context, kind: UploadKind) = when (kind) {
            UploadKind.SCANS -> UploadPlace(PrefsUploadSessionStore(context, "cluetooth_upload_sessions"), File(context.filesDir, "upload-cache"))
            UploadKind.GATT -> UploadPlace(PrefsUploadSessionStore(context, "cluetooth_upload_sessions_gatt"), File(context.filesDir, "upload-cache-gatt"))
        }
    }
}
