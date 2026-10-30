package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The v3 Firebase transport (§4.5 "The upload transport is the v3 one"): resumable `putFile`, an expired session as a
 * client error other than authorization, a denial as HTTP 401/403 or not authorized/authenticated. Every outcome is a
 * [StoreResult]; a cancelled coroutine cancels the upload task.
 */
internal class FirebaseObjectStore(private val storage: () -> FirebaseStorage = FirebaseStorage::getInstance) : ObjectStore {
    override suspend fun put(objectPath: String, file: File, sessionUri: String?, onSession: (String) -> Unit): StoreResult {
        val task = try {
            storage().reference.child(objectPath)
                .putFile(Uri.fromFile(file), StorageMetadata.Builder().build(), sessionUri?.let(Uri::parse))
                .also { upload -> upload.addOnProgressListener { snapshot -> snapshot.uploadSessionUri?.let { runCatching { onSession(it.toString()) } } } }
        } catch (failure: Exception) {
            return StoreResult.Failed(failure.describe())
        }
        return when (val outcome = task.outcome { task.cancel() }) {
            null -> StoreResult.Stored
            is StorageException -> classify(outcome, sessionUri != null)
            else -> StoreResult.Failed(outcome.describe())
        }
    }

    override suspend fun exists(objectPath: String): Boolean? {
        val task = try {
            storage().reference.child(objectPath).metadata
        } catch (_: Exception) {
            return null
        }
        return when (val outcome = task.outcome {}) {
            null -> true
            is StorageException -> if (outcome.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else null
            else -> null
        }
    }

    private fun classify(error: StorageException, resuming: Boolean): StoreResult {
        val http = error.httpResultCode
        if (resuming && http in 400..499 && http !in setOf(401, 403, 408, 429)) return StoreResult.Expired
        val denied = http == 401 || http == 403 ||
            error.errorCode == StorageException.ERROR_NOT_AUTHORIZED || error.errorCode == StorageException.ERROR_NOT_AUTHENTICATED
        val diagnostic = "StorageException code=${error.errorCode} http=$http: ${error.describe()}"
        return if (denied) StoreResult.Denied(diagnostic) else StoreResult.Failed(diagnostic)
    }

    /** Waits for [this] task: null on success, else its failure; cancelling the caller runs [cancel]. */
    private suspend fun <T> Task<T>.outcome(cancel: () -> Unit): Exception? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { done ->
            val failure = when {
                done.isCanceled -> CancellationException("the upload task was cancelled")
                done.isSuccessful -> null
                else -> done.exception ?: IllegalStateException("the task failed without an exception")
            }
            continuation.resume(failure)
        }
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }

    private fun Throwable.describe() = message ?: this::class.java.simpleName
}
