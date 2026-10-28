package edu.ucsd.sysnet.cluetoothscanner.service

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.lifecycle.asFlow
import java.util.concurrent.TimeUnit

/** "Upload now" seals the open payload, then queues an immediate upload even if sealing failed. */
internal suspend fun sealThenUpload(flush: suspend () -> Boolean, enqueueImmediate: () -> Unit) {
    try {
        flush()
    } finally {
        enqueueImmediate()
    }
}

/** Expedited work needs no foreground notification from API 31; older releases run it promptly anyway. */
internal fun expeditedUploadSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Uploads need a network; "Wi-Fi only" restricts that to unmetered networks. */
internal fun uploadNetworkType(wifiOnly: Boolean): NetworkType =
    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

class UploadService(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(uploadNetworkType(isWifiOnlyUpload()))
        .build()

    /** Unique one-time work, run as soon as the network allows and retried with exponential backoff. */
    private fun enqueueOneTime(policy: ExistingWorkPolicy, expedited: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UPLOAD_TAG)
            .apply {
                if (expedited && expeditedUploadSupported(Build.VERSION.SDK_INT)) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        workManager.enqueueUniqueWork(UploadWorker.WORK_NAME, policy, request)
    }

    /** Unique periodic work uploading anything left pending; WorkManager persists it across reboots. */
    private fun enqueuePeriodic(policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIODIC_UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(PERIODIC_UPLOAD_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_UPLOAD_WORK_NAME, policy, request)
    }

    /** Called when a payload is sealed and at startup; a run already queued or running is kept. */
    fun autoUploadNow() = enqueueOneTime(ExistingWorkPolicy.KEEP)

    /** Manual nudge: restarts the queued work (e.g. out of a long backoff), expedited where possible. */
    fun forceUpload() = enqueueOneTime(ExistingWorkPolicy.REPLACE, expedited = true)

    fun startPeriodicUpload() = enqueuePeriodic(ExistingPeriodicWorkPolicy.UPDATE)

    fun getUploadStatus(): Flow<WorkInfo.State?> {
        return workManager.getWorkInfosForUniqueWorkLiveData(UploadWorker.WORK_NAME)
            .asFlow()
            .map { workInfos: List<WorkInfo> ->
                workInfos.firstOrNull()?.state
            }
    }

    fun isWifiOnlyUpload(): Boolean = AppSettings.isWifiOnlyUpload(context)

    fun lastUploadAtMs(): Long? = AppSettings.lastUploadAtMs(context)

    fun setWifiOnlyUpload(enabled: Boolean) {
        AppSettings.setWifiOnlyUpload(context, enabled)
        enqueuePeriodic(ExistingPeriodicWorkPolicy.UPDATE)
        enqueueOneTime(ExistingWorkPolicy.REPLACE)
    }

    companion object {
        private const val UPLOAD_TAG = "upload_ble_data"
        private const val PERIODIC_UPLOAD_TAG = "periodic_upload_ble_data"
        private const val PERIODIC_UPLOAD_INTERVAL_MINUTES = 60L
        private const val PERIODIC_UPLOAD_WORK_NAME = "periodic_upload_ble_data"
        private const val BACKOFF_SECONDS = 30L
    }
}
