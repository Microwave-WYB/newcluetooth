package edu.ucsd.sysnet.cluetoothscanner.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.lifecycle.asFlow
import java.util.concurrent.TimeUnit

class UploadService(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun scheduleUpload(forceUpload: Boolean = false) {
        if (!forceUpload && !isAutoUploadEnabled()) {
            Log.i(TAG, "Auto upload disabled; upload work not scheduled")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .addTag(UPLOAD_TAG)
            .apply {
                if (!forceUpload) {
                    setInitialDelay(UPLOAD_DELAY_MINUTES, TimeUnit.MINUTES)
                }
            }
            .build()

        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )

        Log.i(TAG, "Upload work scheduled (force: $forceUpload)")
    }

    fun getUploadStatus(): Flow<WorkInfo.State?> {
        return workManager.getWorkInfosForUniqueWorkLiveData(UploadWorker.WORK_NAME)
            .asFlow()
            .map { workInfos: List<WorkInfo> ->
                workInfos.firstOrNull()?.state
            }
    }

    fun cancelUpload() {
        workManager.cancelUniqueWork(UploadWorker.WORK_NAME)
        Log.i(TAG, "Upload work cancelled")
    }

    fun getUploadProgress(): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosByTagLiveData(UPLOAD_TAG).asFlow()
    }

    fun forceUpload() {
        scheduleUpload(forceUpload = true)
    }

    fun autoUploadNow() {
        if (isAutoUploadEnabled()) {
            scheduleUpload(forceUpload = true)
        } else {
            Log.i(TAG, "Auto upload disabled; immediate auto upload skipped")
        }
    }

    fun startPeriodicUpload() {
        if (!isAutoUploadEnabled()) {
            Log.i(TAG, "Auto upload disabled; periodic upload not started")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicUploadRequest = PeriodicWorkRequestBuilder<UploadWorker>(
            PERIODIC_UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(PERIODIC_UPLOAD_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_UPLOAD_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicUploadRequest
        )

        Log.i(TAG, "Periodic upload started (every $PERIODIC_UPLOAD_INTERVAL_MINUTES minutes)")
    }

    fun stopPeriodicUpload() {
        workManager.cancelUniqueWork(PERIODIC_UPLOAD_WORK_NAME)
        Log.i(TAG, "Periodic upload stopped")
    }

    fun isAutoUploadEnabled(): Boolean {
        return AppSettings.isAutoUploadEnabled(context)
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        AppSettings.setAutoUploadEnabled(context, enabled)
        if (enabled) {
            startPeriodicUpload()
            autoUploadNow()
        } else {
            cancelUpload()
            stopPeriodicUpload()
        }
    }

    companion object {
        private const val TAG = "UploadService"
        private const val UPLOAD_TAG = "upload_ble_data"
        private const val PERIODIC_UPLOAD_TAG = "periodic_upload_ble_data"
        private const val UPLOAD_DELAY_MINUTES = 15L
        private const val PERIODIC_UPLOAD_INTERVAL_MINUTES = 15L // WorkManager minimum interval is 15 minutes
        private const val PERIODIC_UPLOAD_WORK_NAME = "periodic_upload_ble_data"
    }
}
