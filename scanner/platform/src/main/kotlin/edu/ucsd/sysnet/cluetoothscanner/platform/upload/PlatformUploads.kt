package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import edu.ucsd.sysnet.cluetoothscanner.platform.failureHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * SPEC O-1 "Pending uploads", docs/v2-api.md §1.6, §4.5: [Uploads] over WorkManager and the v3 Firebase transport.
 *
 * - Each kind ([UploadKind]) has its own unique work and backoff, so a `gatt/` prefix the Storage rules refuse can
 *   neither delay nor drop scan uploads (as v3). A periodic run catches anything left.
 * - The work needs a network; "Wi-Fi only" ([wifiOnly]) makes it unmetered, and a change re-schedules it.
 * - [uploadNow] restarts the scan work at once, expedited where possible; [uploadNeeded] (`CoreEvent.UploadNeeded`)
 *   and [start] keep a run that is already queued.
 * - The workers find this instance through [UploadLink]; each run is one [UploadPassRunner] pass.
 * - [status] is `core.uploadStatus()`, read again after every pass and every scheduling.
 *
 * The unique work names are v3's, so an in-place upgrade replaces v3's queued work instead of running beside it.
 */
class PlatformUploads internal constructor(
    private val core: UploadCore,
    private val runner: UploadPassRunner,
    private val scheduler: UploadScheduler,
    private val scope: CoroutineScope,
    private val wifiOnly: Flow<Boolean>,
) : Uploads {
    constructor(
        context: Context,
        core: Deferred<CoreIngest>,
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        wifiOnly: Flow<Boolean>,
    ) : this(
        coreUploads(core, io),
        UploadPassRunner(coreUploads(core, io), FirebaseObjectStore(), { UploadPlace.of(context.applicationContext, it) }, io),
        WorkManagerUploadScheduler(context.applicationContext),
        scope,
        wifiOnly,
    )

    private val _status = MutableStateFlow(UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null))
    override val status: StateFlow<UploadStatus> = _status.asStateFlow()

    /** The last Wi-Fi-only setting seen; read by [uploadNow] and [uploadNeeded]. */
    private val wifiOnlyNow = MutableStateFlow(false)
    private val guard = failureHandler(TAG)

    /**
     * App start, once the core is open: the workers find this instance, the work is (re)scheduled with the stored
     * Wi-Fi setting, and every later change of it re-schedules (§4.5).
     */
    fun start() {
        UploadLink.uploads = this
        scope.launch(guard) {
            var first = true
            wifiOnly.distinctUntilChanged().collect { wifi ->
                wifiOnlyNow.value = wifi
                schedule(if (first) Schedule.Start else Schedule.SettingChanged, wifi)
                first = false
            }
        }
        refresh()
    }

    /** `CoreEvent.UploadNeeded`: a payload or GATT file was sealed. */
    fun uploadNeeded() {
        schedule(Schedule.Needed, wifiOnlyNow.value)
        refresh()
    }

    override fun uploadNow(): PlatformResult<Unit> = schedule(Schedule.Now, wifiOnlyNow.value).also { refresh() }

    /** One worker run of [kind]; the status follows it. */
    internal suspend fun pass(kind: UploadKind): UploadTally = runner.run(kind).also { refreshNow() }

    private fun schedule(why: Schedule, wifi: Boolean): PlatformResult<Unit> = try {
        when (why) {
            // v3's queued work used other worker classes: replace it once, keep a run of ours that is queued.
            Schedule.Start -> {
                scheduler.periodic(wifi, replace = true)
                UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Keep) }
            }
            Schedule.SettingChanged -> {
                scheduler.periodic(wifi, replace = false)
                UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Replace) }
            }
            Schedule.Needed -> UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Keep) }
            // A manual nudge restarts the scan work out of a long backoff; GATT keeps its own patience.
            Schedule.Now -> {
                scheduler.oneTime(UploadKind.SCANS, wifi, OneTime.ReplaceExpedited)
                scheduler.oneTime(UploadKind.GATT, wifi, OneTime.Keep)
            }
        }
        PlatformResult.Ok(Unit)
    } catch (failure: Exception) {
        runCatching { Log.w(TAG, "scheduling uploads failed", failure) }
        PlatformResult.Err(PlatformError.Unavailable(failure.message ?: failure::class.java.simpleName))
    }

    private fun refresh() {
        scope.launch(guard) { refreshNow() }
    }

    private suspend fun refreshNow() {
        when (val read = core.status()) {
            is PlatformResult.Ok -> _status.value = read.value
            is PlatformResult.Err -> Unit // the last known status stays
        }
    }

    private enum class Schedule { Start, SettingChanged, Needed, Now }

    private companion object {
        const val TAG = "PlatformUploads"
    }
}

internal enum class OneTime { Keep, Replace, ReplaceExpedited }

/** The work queue: WorkManager in production. Its calls may throw (WorkManager not initialized); the caller guards. */
internal interface UploadScheduler {
    fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime)
    fun periodic(wifiOnly: Boolean, replace: Boolean)
}

internal class WorkManagerUploadScheduler(private val context: Context) : UploadScheduler {
    override fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime) {
        val request = when (kind) {
            UploadKind.SCANS -> OneTimeWorkRequestBuilder<ScanUploadWorker>()
            UploadKind.GATT -> OneTimeWorkRequestBuilder<GattUploadWorker>()
        }
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffSeconds(kind), TimeUnit.SECONDS)
            .apply {
                if (policy == OneTime.ReplaceExpedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        val existing = when (policy) {
            OneTime.Keep -> ExistingWorkPolicy.KEEP
            OneTime.Replace, OneTime.ReplaceExpedited -> ExistingWorkPolicy.REPLACE
        }
        WorkManager.getInstance(context).enqueueUniqueWork(workName(kind), existing, request)
    }

    override fun periodic(wifiOnly: Boolean, replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<ScanUploadWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SCAN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        val existing = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, existing, request)
    }

    private fun constraints(wifiOnly: Boolean) =
        Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    companion object {
        /** v3's unique work names. */
        fun workName(kind: UploadKind) = when (kind) {
            UploadKind.SCANS -> "upload_ble_data"
            UploadKind.GATT -> "upload_gatt_objects"
        }

        const val PERIODIC_NAME = "periodic_upload_ble_data"
        const val PERIODIC_MINUTES = 60L
        const val SCAN_BACKOFF_SECONDS = 30L

        /** GATT: 5 minutes up to WorkManager's 5 hour cap, so a refused `gatt/` prefix is retried patiently. */
        const val GATT_BACKOFF_SECONDS = 300L

        fun backoffSeconds(kind: UploadKind) = when (kind) {
            UploadKind.SCANS -> SCAN_BACKOFF_SECONDS
            UploadKind.GATT -> GATT_BACKOFF_SECONDS
        }
    }
}

/** The process link the workers find [PlatformUploads] through (set at app start). Lock-free. */
internal object UploadLink {
    private val ref = AtomicReference<PlatformUploads?>(null)
    var uploads: PlatformUploads?
        get() = ref.get()
        set(value) = ref.set(value)
}

/** Whether a finished pass asks WorkManager for a retry: scans retry transient failures, GATT also denials (as v3). */
internal fun retries(kind: UploadKind, tally: UploadTally) = when (kind) {
    UploadKind.SCANS -> tally.failed > 0
    UploadKind.GATT -> tally.failed + tally.denied > 0
}

/** One run of [kind]; no linked uploads yet (the process is still starting) is a retry. Never throws. */
internal suspend fun runUploadWork(kind: UploadKind): androidx.work.ListenableWorker.Result {
    val uploads = UploadLink.uploads ?: return androidx.work.ListenableWorker.Result.retry()
    return try {
        val tally = uploads.pass(kind)
        if (retries(kind, tally)) androidx.work.ListenableWorker.Result.retry() else androidx.work.ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { Log.e("UploadWork", "an upload run failed", failure) }
        androidx.work.ListenableWorker.Result.retry()
    }
}

class ScanUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runUploadWork(UploadKind.SCANS)
}

class GattUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runUploadWork(UploadKind.GATT)
}
