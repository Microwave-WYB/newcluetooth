package edu.ucsd.sysnet.cluetoothscanner.service

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File

internal fun interface ObjectFileUploader {
    suspend fun putFile(objectPath: String, localFile: File)
}

internal class FirebaseObjectFileUploader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : ObjectFileUploader {
    override suspend fun putFile(objectPath: String, localFile: File) {
        storage.reference.child(objectPath).putFile(Uri.fromFile(localFile)).await()
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
                uploader.putFile(prepared.objectPath, File(prepared.ciphertextPath))
                core.markUploadSucceeded(pending.payloadId)
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = error.message ?: error::class.java.simpleName
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
