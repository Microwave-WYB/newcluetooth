package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stands in for v3's workers: in v2 they fail the V3CluetoothApplication cast and retry forever. */
class StaleV3Worker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.retry()
}

/**
 * #100 review: v3 queued its work under the names v2 reuses. The first v2 start must replace every one of them (a
 * KEEP would keep v3's worker, which never succeeds in v2), once; real WorkManager, stale jobs enqueued as v3 left them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V3WorkTakeoverTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    private val core = object : UploadCore {
        override suspend fun status() = PlatformResult.Ok(UploadStatus(0, 0, 0, null))
        override suspend fun pending() = PlatformResult.Ok(emptyList<PendingFile>())
        override suspend fun prepare(id: String) = PlatformResult.Ok(Prepared(id, "", ""))
        override suspend fun markSucceeded(id: String) = PlatformResult.Ok(Unit)
        override suspend fun markFailed(id: String, message: String) = PlatformResult.Ok(Unit)
    }

    @Before
    fun workManager() {
        // One serial background thread: WorkManager's enqueues and the queries below run in submission order.
        val serial = Executors.newSingleThreadExecutor()
        WorkManager.initialize(context, Configuration.Builder().setExecutor(serial).setTaskExecutor(serial).build())
    }

    @After
    fun stop() {
        UploadLink.uploads = null
        scope.cancel()
    }

    private fun live(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }

    private fun uploads() = PlatformUploads(
        core,
        UploadPassRunner(core, object : ObjectStore {
            override suspend fun put(objectPath: String, file: java.io.File, sessionUri: String?, onSession: (String) -> Unit) = StoreResult.Stored
            override suspend fun exists(objectPath: String) = false
        }, { UploadPlace.of(context, it) }, Dispatchers.Unconfined),
        WorkManagerUploadScheduler(context),
        PrefsUploadMarks(context),
        scope,
        flowOf(false),
    )

    @Test
    fun `the first v2 start replaces v3's stale jobs under every shared name, and only once`() {
        val manager = WorkManager.getInstance(context)
        val later = 1L
        for (name in listOf("upload_ble_data", "upload_gatt_objects")) {
            manager.enqueueUniqueWork(
                name, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<StaleV3Worker>().setInitialDelay(later, TimeUnit.DAYS).build(),
            ).result.get()
        }
        manager.enqueueUniquePeriodicWork(
            "periodic_upload_ble_data", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StaleV3Worker>(1, TimeUnit.HOURS).setInitialDelay(later, TimeUnit.DAYS).build(),
        ).result.get()
        val stale = StaleV3Worker::class.java.name

        uploads().start()

        val names = mapOf(
            "upload_ble_data" to ScanUploadWorker::class.java.name,
            "upload_gatt_objects" to GattUploadWorker::class.java.name,
            "periodic_upload_ble_data" to ScanUploadWorker::class.java.name,
        )
        val ours = mutableMapOf<String, Set<java.util.UUID>>()
        for ((name, worker) in names) {
            val all = manager.getWorkInfosForUniqueWork(name).get()
            assertTrue("$name: v3's job is gone", live(name).none { stale in it.tags })
            assertTrue("$name: ours is queued", all.any { worker in it.tags })
            ours[name] = all.filter { worker in it.tags }.map { it.id }.toSet()
        }

        // A later start keeps what v2 queued (KEEP): no new jobs replace ours.
        uploads().start()
        for ((name, worker) in names) {
            val now = manager.getWorkInfosForUniqueWork(name).get().filter { worker in it.tags }.map { it.id }.toSet()
            assertEquals("$name: kept", ours[name], now)
        }
    }
}
