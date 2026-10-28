package edu.ucsd.sysnet.cluetoothscanner.service

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File

internal fun interface ObjectFileUploader {
    suspend fun putFile(objectPath: String, localFile: File)

    /**
     * Resumable variant. [sessionUri] continues an earlier upload of the same bytes; [onSession]
     * receives the session URI as soon as the transfer has one. Throws
     * [UploadSessionExpiredException] when [sessionUri] can no longer be resumed.
     */
    suspend fun putFileResumable(
        objectPath: String,
        localFile: File,
        sessionUri: String?,
        onSession: (String) -> Unit,
    ) = putFile(objectPath, localFile)

    /** True when the object is already stored (reads are allowed; overwrites are not). */
    suspend fun exists(objectPath: String): Boolean = false
}

internal class UploadSessionExpiredException(cause: Throwable? = null) : Exception(cause)

/**
 * The storage refused the object for authorization reasons (HTTP 401/403, `-13021`). Retrying
 * cannot help until rules or credentials change, so the file keeps its recorded error and the
 * rest of the run carries on; it is tried again on the next run.
 */
internal class UploadDeniedException(cause: Throwable) : Exception(cause.message, cause)

/** Scan payloads and GATT objects are uploaded in separate runs with separate retry policies. */
internal enum class UploadKind { SCANS, GATT }

internal fun isGattObject(objectPath: String) = objectPath.startsWith("gatt/")

/** Resumable-upload state of one payload: the session URI and the exact bytes it was started with. */
internal data class UploadSession(val uri: String, val cachePath: String)

internal interface UploadSessionStore {
    fun get(payloadId: String): UploadSession?
    fun put(payloadId: String, session: UploadSession)
    fun remove(payloadId: String)
    fun payloadIds(): Set<String>
}

/** Persists sessions so a killed worker resumes. Ciphertext is re-randomised by every Rust prepare,
 * so the uploaded bytes are copied to [cacheDirectory] and reused until the payload is acknowledged. */
internal class UploadResume(val sessions: UploadSessionStore, val cacheDirectory: File)

internal class FirebaseObjectFileUploader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : ObjectFileUploader {
    override suspend fun putFile(objectPath: String, localFile: File) {
        putFileResumable(objectPath, localFile, null) {}
    }

    override suspend fun putFileResumable(
        objectPath: String,
        localFile: File,
        sessionUri: String?,
        onSession: (String) -> Unit,
    ) {
        val reference = storage.reference.child(objectPath)
        val task = reference.putFile(
            Uri.fromFile(localFile),
            StorageMetadata.Builder().build(),
            sessionUri?.let(Uri::parse),
        )
        task.addOnProgressListener { snapshot ->
            snapshot.uploadSessionUri?.let { onSession(it.toString()) }
        }
        try {
            task.await()
        } catch (error: StorageException) {
            // A stale session is rejected with a client error other than an authorization denial.
            val clientError = error.httpResultCode in 400..499 &&
                error.httpResultCode !in setOf(401, 403, 408, 429)
            if (sessionUri != null && clientError) throw UploadSessionExpiredException(error)
            if (error.httpResultCode == 401 || error.httpResultCode == 403 ||
                error.errorCode == StorageException.ERROR_NOT_AUTHORIZED ||
                error.errorCode == StorageException.ERROR_NOT_AUTHENTICATED
            ) {
                throw UploadDeniedException(error)
            }
            throw error
        }
    }

    override suspend fun exists(objectPath: String): Boolean = try {
        storage.reference.child(objectPath).metadata.await()
        true
    } catch (error: StorageException) {
        if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw error
    }
}

internal fun uploadDiagnostic(error: Exception): String {
    val cause = (error as? UploadDeniedException)?.cause as? Exception ?: error
    val detail = cause.message ?: cause::class.java.simpleName
    return if (cause is StorageException) {
        "StorageException code=${cause.errorCode} http=${cause.httpResultCode}: $detail"
    } else {
        detail
    }
}

/** [failed] is worth a WorkManager retry; [denied] objects wait for rules or credentials. */
internal data class PayloadUploadSummary(
    val succeeded: Int,
    val failed: Int,
    val denied: Int = 0,
)

internal class PayloadUploadCoordinator(
    private val core: CoreGateway,
    private val uploader: ObjectFileUploader,
    private val resume: UploadResume?,
    private val kind: UploadKind = UploadKind.SCANS,
) {
    constructor(core: CoreGateway, uploader: ObjectFileUploader) : this(core, uploader, null)

    suspend fun uploadPending(): PayloadUploadSummary {
        var succeeded = 0
        var failed = 0
        var denied = 0
        val allPending = core.pendingUploads()
        // Sessions of the other kind are not orphans: only a finished upload discards them.
        discardOrphanedSessions(allPending.map { it.payloadId }.toSet())
        for (pending in allPending.filter { isGattObject(it.objectPath) == (kind == UploadKind.GATT) }) {
            try {
                transfer(pending.payloadId, pending.objectPath)
                core.markUploadSucceeded(pending.payloadId)
                discard(pending.payloadId)
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = uploadDiagnostic(error)
                try {
                    core.markUploadFailed(pending.payloadId, diagnostic)
                } catch (_: Exception) {
                    // The original operation is still the useful WorkManager failure.
                }
                if (error is UploadDeniedException) denied++ else failed++
            }
        }
        return PayloadUploadSummary(succeeded = succeeded, failed = failed, denied = denied)
    }

    private suspend fun transfer(payloadId: String, objectPath: String) {
        try {
            try {
                send(payloadId, objectPath)
            } catch (expired: UploadSessionExpiredException) {
                discard(payloadId)
                send(payloadId, objectPath)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Production rules deny overwrite: if an earlier attempt was accepted but not
            // acknowledged locally, the retry fails although the object is already stored.
            val stored = try {
                uploader.exists(objectPath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!stored) throw error
        }
    }

    private suspend fun send(payloadId: String, objectPath: String) {
        if (resume == null) {
            val prepared = prepare(payloadId, objectPath)
            uploader.putFile(objectPath, File(prepared.ciphertextPath))
            return
        }
        val saved = resume.sessions.get(payloadId)?.takeIf { File(it.cachePath).isFile }
        if (saved != null) {
            uploader.putFileResumable(objectPath, File(saved.cachePath), saved.uri) { uri ->
                resume.sessions.put(payloadId, UploadSession(uri, saved.cachePath))
            }
            return
        }
        val prepared = prepare(payloadId, objectPath)
        resume.cacheDirectory.mkdirs()
        val cached = File(resume.cacheDirectory, "$payloadId.encrypted")
        File(prepared.ciphertextPath).copyTo(cached, overwrite = true)
        resume.sessions.remove(payloadId)
        uploader.putFileResumable(objectPath, cached, null) { uri ->
            resume.sessions.put(payloadId, UploadSession(uri, cached.path))
        }
    }

    private fun prepare(payloadId: String, objectPath: String): GatewayPreparedUpload {
        val prepared = core.prepareUpload(payloadId)
        require(prepared.payloadId == payloadId) { "Rust prepared a mismatched payload identity" }
        require(prepared.objectPath == objectPath) { "Rust prepared a mismatched object path" }
        return prepared
    }

    private fun discard(payloadId: String) {
        resume ?: return
        resume.sessions.get(payloadId)?.let { File(it.cachePath).delete() }
        File(resume.cacheDirectory, "$payloadId.encrypted").delete()
        resume.sessions.remove(payloadId)
    }

    private fun discardOrphanedSessions(pendingIds: Set<String>) {
        resume ?: return
        (resume.sessions.payloadIds() - pendingIds).forEach(::discard)
        resume.cacheDirectory.listFiles()?.forEach { file ->
            if (file.name.removeSuffix(".encrypted") !in pendingIds) file.delete()
        }
    }
}
