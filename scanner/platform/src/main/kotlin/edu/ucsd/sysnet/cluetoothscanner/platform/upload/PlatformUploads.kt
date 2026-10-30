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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.async

/**
 * SPEC O-1 "Pending uploads", docs/v2-api.md §1.6, §4.5: [Uploads] over WorkManager and the v3 Firebase transport.
 *
 * - Each kind ([UploadKind]) has its own unique work and backoff, so a `gatt/` prefix the Storage rules refuse can
 *   neither delay nor drop scan uploads (as v3). A periodic run catches anything left.
 * - The unique work names are v3's. v3's queued work under them runs v3 worker classes that cannot work in v2, so the
 *   first start in v2 replaces every one of them, once ([UploadMarks]); later starts keep what is queued.
 * - Passes of one kind never overlap: one actor per kind runs them in turn (the one-time and the periodic work both
 *   come here). Requests that queue during a pass share one pass after it; an [uploadNeeded] during a pass makes the
 *   actor run another, so a file sealed after a pass's last listing does not wait for the periodic run.
 * - The work needs a network; "Wi-Fi only" ([wifiOnly]) makes it unmetered, and a change re-schedules it.
 * - [uploadNow] restarts both kinds at once (scans expedited where possible).
 * - [status] is `core.uploadStatus()`, read again after every pass and every scheduling; its `lastError` also shows
 *   what the core cannot: work that could not be scheduled, a failure the core could not record.
 */
class PlatformUploads internal constructor(
    private val core: UploadCore,
    private val runner: UploadPassRunner,
    private val scheduler: UploadScheduler,
    private val marks: UploadMarks,
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
        PrefsUploadMarks(context.applicationContext),
        scope,
        wifiOnly,
    )

    /** What the status shows: the core's, and the platform's own problems (one writer at a time, lock-free update). */
    private data class Shown(val core: UploadStatus, val scheduleError: String? = null, val passProblem: String? = null)

    private val shown = MutableStateFlow(Shown(UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null)))
    override val status: StateFlow<UploadStatus> =
        shown.map { it.core.copy(lastError = it.scheduleError ?: it.passProblem ?: it.core.lastError) }
            .stateIn(scope, SharingStarted.Eagerly, shown.value.core)

    /** The last Wi-Fi-only setting seen; read by [uploadNow] and [uploadNeeded]. */
    private val wifiOnlyNow = MutableStateFlow(false)
    private val guard = failureHandler(TAG)

    /** What the kind's actor hears: a worker asks for a pass, or a worker that asked was stopped. */
    private sealed interface PassMessage {
        /** Answered with what the pass (or passes) it waited for did. */
        class Ask(val reply: CompletableDeferred<UploadTally>) : PassMessage

        /** The worker behind [reply] was stopped (WorkManager cancelled it): its pass must not run on for it. */
        class Withdraw(val reply: CompletableDeferred<UploadTally>) : PassMessage
    }

    private val inboxes: Map<UploadKind, Channel<PassMessage>> = UploadKind.entries.associateWith {
        Channel(Channel.UNLIMITED, onUndeliveredElement = { message -> if (message is PassMessage.Ask) message.reply.complete(STOPPED) })
    }

    /** Set by [uploadNeeded]: a file of this kind was sealed, maybe after the running pass listed for the last time. */
    private val sealedDuringPass: Map<UploadKind, AtomicBoolean> = UploadKind.entries.associateWith { AtomicBoolean(false) }

    init {
        for ((kind, inbox) in inboxes) {
            scope.launch(guard) { actor(kind, inbox) }.invokeOnCompletion { inbox.cancel() }
        }
    }

    /**
     * App start: the workers find this instance, the work is (re)scheduled with the stored Wi-Fi setting (v3's
     * replaced the first time), and every later change of the setting re-schedules (§4.5).
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
        sealedDuringPass.values.forEach { it.set(true) }
        schedule(Schedule.Needed, wifiOnlyNow.value)
        refresh()
    }

    override fun uploadNow(): PlatformResult<Unit> = schedule(Schedule.Now, wifiOnlyNow.value).also { refresh() }

    /**
     * One worker run of [kind]: its pass runs on the kind's actor, after any pass of that kind already running. When
     * WorkManager stops the worker, the pass stops too (and its transfer with it) unless another worker still waits
     * for it: a lost Wi-Fi constraint must not leave an upload running over cellular.
     */
    internal suspend fun pass(kind: UploadKind): UploadTally {
        val inbox = inboxes.getValue(kind)
        val reply = CompletableDeferred<UploadTally>()
        if (inbox.trySend(PassMessage.Ask(reply)).isFailure) return STOPPED
        return try {
            reply.await()
        } catch (cancelled: CancellationException) {
            inbox.trySend(PassMessage.Withdraw(reply))
            throw cancelled
        }
    }

    /** The pass running now, as the actor's child, and the workers still waiting for it. */
    private class Running(val job: Deferred<UploadTally>, val waiters: MutableList<CompletableDeferred<UploadTally>>)

    /**
     * The kind's single owner. It runs one pass at a time as its child: requests that arrive meanwhile queue and share
     * the next pass; a withdrawal leaves the queue, and when no live waiter remains the running pass is cancelled. A
     * pass that throws answers its waiters with a typed failure and the actor goes on.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // a completed Deferred's result or failure, read in onJoin
    private suspend fun actor(kind: UploadKind, inbox: Channel<PassMessage>) = supervisorScope {
        val queued = mutableListOf<CompletableDeferred<UploadTally>>()
        var running: Running? = null
        while (true) {
            val idle = running == null
            if (idle && queued.isNotEmpty()) {
                running = Running(async { passes(kind) }, queued.toMutableList())
                queued.clear()
            }
            val current = running
            select {
                inbox.onReceive { message ->
                    when (message) {
                        is PassMessage.Ask -> queued += message.reply
                        is PassMessage.Withdraw -> {
                            queued.remove(message.reply)
                            if (current != null && current.waiters.remove(message.reply) && current.waiters.isEmpty()) {
                                // Nobody waits any more: stop the pass and its transfer; the file stays pending.
                                current.job.cancel()
                            }
                        }
                    }
                }
                current?.job?.onJoin {
                    val answer = when (val failure = current.job.getCompletionExceptionOrNull()) {
                        null -> current.job.getCompleted()
                        is CancellationException -> STOPPED
                        else -> UploadTally(failed = 1, problem = "the upload pass failed: ${failure.message ?: failure::class.java.simpleName}")
                    }
                    current.waiters.forEach { it.complete(answer) }
                    running = null
                }
            }
        }
    }

    /** A pass, and another for each file sealed after its last listing (at most [MAX_EXTRA_PASSES] more). */
    private suspend fun passes(kind: UploadKind): UploadTally {
        val sealed = sealedDuringPass.getValue(kind)
        var tally = UploadTally()
        var extra = 0
        do {
            sealed.set(false)
            tally += runner.run(kind)
        } while (sealed.get() && extra++ < MAX_EXTRA_PASSES)
        shown.update { it.copy(passProblem = tally.problem) }
        refreshNow()
        return tally
    }

    private fun schedule(why: Schedule, wifi: Boolean): PlatformResult<Unit> = try {
        when (why) {
            Schedule.Start -> if (marks.v3WorkReplaced()) {
                scheduler.periodic(wifi, Periodic.Keep)
                UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Keep) }
            } else {
                // v3's queued work runs v3 worker classes that fail in v2 and retry forever: take every name over.
                scheduler.periodic(wifi, Periodic.Replace)
                UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Replace) }
                marks.markV3WorkReplaced()
            }
            Schedule.SettingChanged -> {
                scheduler.periodic(wifi, Periodic.Update)
                UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Replace) }
            }
            // A run already queued or running keeps going; a running one sees [sealedDuringPass].
            Schedule.Needed -> UploadKind.entries.forEach { scheduler.oneTime(it, wifi, OneTime.Keep) }
            // A manual nudge restarts both out of any backoff, scans expedited.
            Schedule.Now -> {
                scheduler.oneTime(UploadKind.SCANS, wifi, OneTime.ReplaceExpedited)
                scheduler.oneTime(UploadKind.GATT, wifi, OneTime.Replace)
            }
        }
        shown.update { it.copy(scheduleError = null) }
        PlatformResult.Ok(Unit)
    } catch (failure: Exception) {
        val why = failure.message ?: failure::class.java.simpleName
        runCatching { Log.w(TAG, "scheduling uploads failed", failure) }
        shown.update { it.copy(scheduleError = "Uploads not scheduled: $why") }
        PlatformResult.Err(PlatformError.Unavailable(why))
    }

    private fun refresh() {
        scope.launch(guard) { refreshNow() }
    }

    private suspend fun refreshNow() {
        when (val read = core.status()) {
            is PlatformResult.Ok -> shown.update { it.copy(core = read.value) }
            is PlatformResult.Err -> Unit // the last known status stays
        }
    }

    private enum class Schedule { Start, SettingChanged, Needed, Now }

    private companion object {
        const val TAG = "PlatformUploads"

        /** At most this many extra passes for files sealed during a pass (recording seals every few minutes). */
        const val MAX_EXTRA_PASSES = 5

        /** The answer when no pass can run (the process is shutting down): a failure, so WorkManager retries. */
        val STOPPED = UploadTally(failed = 1, problem = "the uploads stopped")
    }
}

internal enum class OneTime { Keep, Replace, ReplaceExpedited }

internal enum class Periodic { Keep, Update, Replace }

/** The work queue: WorkManager in production. Its calls may throw (WorkManager not initialized); the caller guards. */
internal interface UploadScheduler {
    fun oneTime(kind: UploadKind, wifiOnly: Boolean, policy: OneTime)
    fun periodic(wifiOnly: Boolean, policy: Periodic)
}

/** Whether the first v2 start already replaced v3's queued work (prefs `cluetooth_uploads`). */
internal interface UploadMarks {
    fun v3WorkReplaced(): Boolean
    fun markV3WorkReplaced()
}

internal class PrefsUploadMarks(context: Context) : UploadMarks {
    private val prefs = context.getSharedPreferences("cluetooth_uploads", Context.MODE_PRIVATE)

    /** Unreadable reads as not replaced: replacing again is harmless, keeping v3's work is not. */
    override fun v3WorkReplaced(): Boolean = runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false)

    /** Committed before it returns: a crash right after must not replace v2's queued work again needlessly. */
    override fun markV3WorkReplaced() {
        prefs.edit().putBoolean(KEY, true).commit()
    }

    private companion object {
        const val KEY = "v3_work_replaced"
    }
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

    override fun periodic(wifiOnly: Boolean, policy: Periodic) {
        val request = PeriodicWorkRequestBuilder<ScanUploadWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SCAN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        val existing = when (policy) {
            Periodic.Keep -> ExistingPeriodicWorkPolicy.KEEP
            Periodic.Update -> ExistingPeriodicWorkPolicy.UPDATE
            Periodic.Replace -> ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        }
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
