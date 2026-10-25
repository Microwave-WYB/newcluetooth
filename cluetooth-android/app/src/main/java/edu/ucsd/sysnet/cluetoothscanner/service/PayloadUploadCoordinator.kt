package edu.ucsd.sysnet.cluetoothscanner.service

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File

internal fun interface ObjectFileUploader {
    suspend fun putFile(objectPath: String, localFile: File)

    /** True when the object is already stored (reads are allowed; overwrites are not). */
    suspend fun exists(objectPath: String): Boolean = false
}

internal class FirebaseObjectFileUploader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : ObjectFileUploader {
    override suspend fun putFile(objectPath: String, localFile: File) {
        storage.reference.child(objectPath).putFile(Uri.fromFile(localFile)).await()
    }

    override suspend fun exists(objectPath: String): Boolean = try {
        storage.reference.child(objectPath).metadata.await()
        true
    } catch (error: StorageException) {
        if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw error
    }
}

internal fun uploadDiagnostic(error: Exception): String {
    val detail = error.message ?: error::class.java.simpleName
    return if (error is StorageException) {
        "StorageException code=${error.errorCode} http=${error.httpResultCode}: $detail"
    } else {
        detail
    }
}

internal data class PayloadUploadSummary(
    val succeeded: Int,
    val failed: Int,
)

internal class PayloadUploadCoordinator(
    private val core: CoreGateway,
    private val uploader: ObjectFileUploader,
) {
    suspend fun uploadPending(): PayloadUploadSummary {
        var succeeded = 0
        var failed = 0
        for (pending in core.pendingUploads()) {
            try {
                val prepared = core.prepareUpload(pending.payloadId)
                require(prepared.payloadId == pending.payloadId) {
                    "Rust prepared a mismatched payload identity"
                }
                require(prepared.objectPath == pending.objectPath) {
                    "Rust prepared a mismatched object path"
                }
                try {
                    uploader.putFile(prepared.objectPath, File(prepared.ciphertextPath))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Production rules deny overwrite: if an earlier attempt was accepted but not
                    // acknowledged locally, the retry fails although the object is already stored.
                    val stored = try {
                        uploader.exists(prepared.objectPath)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!stored) throw error
                }
                core.markUploadSucceeded(pending.payloadId)
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
                failed++
            }
        }
        return PayloadUploadSummary(succeeded = succeeded, failed = failed)
    }
}
