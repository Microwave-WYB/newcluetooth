package edu.ucsd.sysnet.cluetoothscanner.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import edu.ucsd.sysnet.cluetoothscanner.V3CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import edu.ucsd.sysnet.cluetoothscanner.utils.EncryptionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting upload worker")

            val v2Summary = uploadV2Payloads()
            val filesToUpload = findFilesToUpload()
            Log.i(TAG, "Found ${filesToUpload.size} supported legacy files to upload")

            var successCount = v2Summary.succeeded
            var failCount = v2Summary.failed

            filesToUpload.forEach { file ->
                try {
                    val success = processAndUploadFile(file)
                    if (success) {
                        successCount++
                        markFileAsUploaded(file)
                    } else {
                        failCount++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload file: ${file.name}", e)
                    failCount++
                }
            }

            Log.i(TAG, "Upload completed: $successCount success, $failCount failed")
            if (successCount > 0) {
                AppSettings.setLastUploadAtMs(context, System.currentTimeMillis())
            }

            // Denied objects (authorization) stay pending with their recorded error and do not
            // push the whole job into backoff; transient failures do.
            if (failCount > 0) {
                Result.retry()
            } else {
                Result.success()
            }

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Upload worker failed", e)
            Result.retry()
        }
    }

    private suspend fun uploadV2Payloads(): PayloadUploadSummary {
        val application = context.applicationContext as? V3CluetoothApplication
            ?: error("V3CluetoothApplication is required for the process-owned core")
        return application.repository.uploadPendingV2(
            FirebaseObjectFileUploader(),
            UploadResume(
                PrefsUploadSessionStore(context),
                File(context.filesDir, "upload-cache"),
            ),
        )
    }

    private fun findFilesToUpload(): List<File> {
        val pending = context.filesDir.listFiles { _, name ->
            name.endsWith(".jsonl.zst") && !name.endsWith(".uploaded")
        }?.toList() ?: emptyList()
        val quarantine = File(context.filesDir, "quarantine/legacy")
        return pending.filter { file ->
            if (legacyRemoteObjectName(file.name) != null) {
                true
            } else {
                quarantine.mkdirs()
                val destination = File(quarantine, file.name)
                val moved = file.renameTo(destination)
                Log.w(
                    TAG,
                    "Quarantined unsupported legacy pending file ${file.name}: $moved",
                )
                false
            }
        }
    }

    private suspend fun processAndUploadFile(file: File): Boolean {
        return try {
            // Read the compressed JSONL file (already compressed by StorageService)
            val compressedData = FileInputStream(file).use { fis ->
                fis.readBytes()
            }
            Log.d(TAG, "Read compressed file ${file.name}: ${compressedData.size} bytes")

            // Encrypt the compressed data in-memory only (never store encrypted data)
            val encryptedData = EncryptionUtils.encryptData(compressedData)
            Log.d(TAG, "Encrypted ${file.name}: ${compressedData.size} -> ${encryptedData.size} bytes")

            // Upload directly from memory (no temporary encrypted file)
            val uploadSuccess = uploadToStorage(encryptedData, file)

            if (uploadSuccess) {
                Log.i(TAG, "Successfully uploaded: ${file.name}")
                true
            } else {
                Log.e(TAG, "Failed to upload: ${file.name}")
                false
            }

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error processing file: ${file.name}", e)
            false
        }
    }

    private suspend fun uploadToStorage(encryptedData: ByteArray, originalFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val encryptedFileName = legacyRemoteObjectName(originalFile.name)
                    ?: throw IllegalArgumentException(
                        "Unsupported pending legacy filename: ${originalFile.name}",
                    )
                Log.i(TAG, "Uploading encrypted data to Firebase Storage: $encryptedFileName (${encryptedData.size} bytes)")

                val storageRef = FirebaseStorage.getInstance().reference
                val fileRef = storageRef.child("scans/$encryptedFileName")

                val uploadTask = fileRef.putBytes(encryptedData)
                uploadTask.await()

                Log.i(TAG, "Successfully uploaded to Firebase Storage: $encryptedFileName")
                true

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload to Firebase Storage: ${originalFile.name}", e)
                false
            }
        }
    }

    private fun markFileAsUploaded(file: File) {
        try {
            val uploadedFile = File(file.parent, "${file.nameWithoutExtension}.uploaded")
            val renamed = file.renameTo(uploadedFile)
            if (renamed) {
                Log.d(TAG, "Marked file as uploaded: ${file.name} -> ${uploadedFile.name}")
            } else {
                Log.e(TAG, "Failed to rename file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark file as uploaded: ${file.name}", e)
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_NAME = "upload_ble_data"
    }
}

/**
 * Uploads manual GATT discoveries only. It has its own unique work and backoff, so a `gatt/`
 * prefix the Storage rules do not allow yet (HTTP 403) can neither delay nor drop scan uploads.
 */
class GattUploadWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val application = context.applicationContext as? V3CluetoothApplication
                ?: error("V3CluetoothApplication is required for the process-owned core")
            val summary = application.repository.uploadPendingV2(
                FirebaseObjectFileUploader(),
                // Own session store and cache directory: the scan run's orphan cleanup can never
                // delete a GATT object's resumable session, nor the other way round.
                UploadResume(
                    PrefsUploadSessionStore(context, "cluetooth_upload_sessions_gatt"),
                    File(context.filesDir, "upload-cache-gatt"),
                ),
                UploadKind.GATT,
            )
            Log.i(TAG, "GATT upload: ${summary.succeeded} stored, ${summary.failed} failed, ${summary.denied} denied")
            if (summary.failed + summary.denied > 0) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "GATT upload worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "GattUploadWorker"
        const val WORK_NAME = "upload_gatt_objects"
    }
}
