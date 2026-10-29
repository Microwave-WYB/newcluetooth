package edu.ucsd.sysnet.cluetoothscanner.service

import android.content.Context
import android.util.Log
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import edu.ucsd.sysnet.cluetoothscanner.utils.CompressionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uploadService: UploadService? = null
) {

    private val recordBuffer = ConcurrentLinkedQueue<BleRecord>()
    private val currentFileSize = AtomicLong(0)
    private val bufferMutex = Mutex()

    private val _pendingRecords = MutableStateFlow(0)
    val pendingRecords: StateFlow<Int> = _pendingRecords.asStateFlow()

    private val _generatedFiles = MutableStateFlow<List<File>>(emptyList())
    val generatedFiles: StateFlow<List<File>> = _generatedFiles.asStateFlow()

    private val _legacyObservationCounts = MutableStateFlow<Map<String, ULong>>(emptyMap())
    val legacyObservationCounts: StateFlow<Map<String, ULong>> =
        _legacyObservationCounts.asStateFlow()

    private var flushJob: Job? = null
    private var timeRotationJob: Job? = null

    init {
        startPeriodicFlush()
        refreshFileList()
    }

    suspend fun addRecord(record: BleRecord) {
        recordBuffer.offer(record)
        _pendingRecords.value = recordBuffer.size

        // Check if we need to rotate based on estimated size
        val estimatedRecordSize = record.toJson().toByteArray().size
        val newSize = currentFileSize.addAndGet(estimatedRecordSize.toLong())

        if (newSize >= MAX_FILE_SIZE_BYTES) {
            flushToFile()
        }
    }

    private fun startPeriodicFlush() {
        timeRotationJob = scope.launch {
            while (true) {
                delay(FILE_ROTATION_INTERVAL_MS)
                if (recordBuffer.isNotEmpty()) {
                    flushToFile()
                }
            }
        }
    }

    private suspend fun flushToFile() {
        bufferMutex.withLock {
            if (recordBuffer.isEmpty()) return@withLock

            val recordsToFlush = mutableListOf<BleRecord>()
            while (recordBuffer.isNotEmpty()) {
                recordBuffer.poll()?.let { record ->
                    recordsToFlush.add(record)
                }
            }

            if (recordsToFlush.isNotEmpty()) {
                writeRecordsToFile(recordsToFlush)
                currentFileSize.set(0)
                _pendingRecords.value = recordBuffer.size
            }
        }
    }

    private suspend fun writeRecordsToFile(records: List<BleRecord>) {
        try {
            Log.d(TAG, "Starting writeRecordsToFile for ${records.size} records")

            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US).format(Date())
            val filename = "${timestamp}_${getDeviceId()}_${getAppVersion()}.jsonl.zst"
            val file = File(context.filesDir, filename)
            Log.d(TAG, "Target file: ${file.absolutePath}")

            Log.d(TAG, "Converting records to JSON...")
            val jsonlContent = records.joinToString("\n") { record ->
                try {
                    val json = record.toJson()
                    Log.v(TAG, "Record JSON: ${json.take(100)}...")
                    json
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to serialize record: $record", e)
                    throw e
                }
            }
            Log.d(TAG, "JSONL content size: ${jsonlContent.length} chars")

            Log.d(TAG, "Starting compression...")
            val compressedData = CompressionUtils.compressString(jsonlContent)
            Log.d(TAG, "Compressed data size: ${compressedData.size} bytes")

            Log.d(TAG, "Writing to file...")
            FileOutputStream(file).use { fos ->
                fos.write(compressedData)
                fos.flush()
            }
            Log.d(TAG, "File write completed")

            Log.i(TAG, "Wrote ${records.size} records to file: ${file.name} (${compressedData.size} bytes)")
            refreshFileList()

            // Trigger immediate automatic upload after file creation.
            uploadService?.autoUploadNow()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write records to file", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            Log.e(TAG, "Exception cause: ${e.cause}")
            e.printStackTrace()
            // Re-throw as RuntimeException to help with debugging
            throw RuntimeException("Storage write failed", e)
        }
    }

    fun refreshFileList() {
        val files = context.filesDir.listFiles { _, name ->
            name.endsWith(".jsonl.zst") || name.endsWith(".uploaded")
        }?.toList() ?: emptyList()

        _generatedFiles.value = files.sortedByDescending { it.lastModified() }
        scope.launch(Dispatchers.IO) {
            _legacyObservationCounts.value = files.associate { file ->
                val count = runCatching {
                    String(CompressionUtils.decompressData(file.readBytes()), Charsets.UTF_8)
                        .lineSequence()
                        .count { it.isNotBlank() }
                        .toULong()
                }.getOrDefault(0u)
                file.absolutePath to count
            }
        }
    }

    fun getStorageDirectory(): File {
        return context.filesDir
    }

    fun getFileStats(): Map<String, Any> {
        val files = _generatedFiles.value
        val totalSize = files.sumOf { it.length() }
        val totalRecords = _pendingRecords.value

        return mapOf(
            "totalFiles" to files.size,
            "totalSizeBytes" to totalSize,
            "pendingRecords" to totalRecords,
            "availableSpace" to context.filesDir.usableSpace
        )
    }

    fun deleteFile(file: File): Boolean {
        return try {
            val deleted = file.delete()
            if (deleted) {
                Log.i(TAG, "Deleted file: ${file.name}")
                refreshFileList()
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${file.name}", e)
            false
        }
    }

    fun deleteAllFiles(): Int {
        val files = _generatedFiles.value
        var deletedCount = 0

        files.forEach { file ->
            if (deleteFile(file)) {
                deletedCount++
            }
        }

        return deletedCount
    }

    suspend fun forceFlush() {
        flushToFile()
    }

    suspend fun exportJsonlZip(destinationUri: android.net.Uri): Int {
        forceFlush()
        refreshFileList()

        val files = _generatedFiles.value.sortedWith(
            compareBy<File> { it.lastModified() }.thenBy { it.name }
        )

        return withContext(Dispatchers.IO) {
            var fileCount = 0
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOutput ->
                    files.forEach { file ->
                        val jsonlBytes = CompressionUtils.decompressData(file.readBytes())
                        val entryName = file.name
                            .removeSuffix(".uploaded")
                            .removeSuffix(".zst")
                            .let { name ->
                                if (name.endsWith(".jsonl")) name else "$name.jsonl"
                            }

                        zipOutput.putNextEntry(ZipEntry(entryName))
                        zipOutput.write(jsonlBytes)
                        zipOutput.closeEntry()
                        fileCount++
                    }
                }
            } ?: throw IOException("Unable to open export destination")

            Log.i(TAG, "Exported $fileCount JSONL files to zip")
            fileCount
        }
    }

    private suspend fun getDeviceId(): String {
        return edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager.getDeviceId(context)
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun cleanup() {
        flushJob?.cancel()
        timeRotationJob?.cancel()

        // Final flush
        scope.launch {
            flushToFile()
        }
    }

    companion object {
        private const val TAG = "StorageService"
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10MB
        private const val FILE_ROTATION_INTERVAL_MS = 30 * 1000L // 30 seconds
    }
}
