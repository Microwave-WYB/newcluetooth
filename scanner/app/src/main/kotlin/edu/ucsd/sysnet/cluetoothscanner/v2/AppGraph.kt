package edu.ucsd.sysnet.cluetoothscanner.v2

import android.app.Application
import android.os.SystemClock
import java.io.File
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.map.MapLibreCacheInfo
import edu.ucsd.sysnet.cluetoothscanner.platform.export.AndroidExportShare
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.FilePendingDiscoveryStore
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.GattDiscoveryManager
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.GattLocation
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.PlatformGattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.androidGattLinks
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.gattAvailable
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.gattDeviceInfo
import edu.ucsd.sysnet.cluetoothscanner.platform.location.LocationClient
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.InterruptedRecordingNotice
import edu.ucsd.sysnet.cluetoothscanner.platform.settings.AndroidPermissions
import edu.ucsd.sysnet.cluetoothscanner.platform.settings.DataStoreSettings
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.AndroidForegroundHost
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.AndroidRecordingGrants
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.PlatformRecorder
import edu.ucsd.sysnet.cluetoothscanner.platform.scan.BleScanner
import edu.ucsd.sysnet.cluetoothscanner.platform.scan.ScanIngest
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapCacheInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * §1.2: the process singletons, never tied to an Activity, ViewModel or composition. The core opens on IO
 * ([core]); the recorder, scanner and location client are :platform's and await the same core.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(private val app: Application) {
    private val io = Dispatchers.IO
    private val base = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** §1.2.2: the core lifecycle (Opening → Ready | Failed, Retry); it opens the core on Dispatchers.IO. */
    val core: CoreOpener = CoreOpener(base, io, open = { CoreBinding.open(app, scope) })

    /**
     * The process scope (main thread; work that blocks hops to [io]). It has its own handler: a failure in it is
     * logged and ends only that coroutine; it never touches the core's state, which means only "the open failed".
     */
    val scope: CoroutineScope = CoroutineScope(
        base.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("AppGraph", "uncaught in the process scope", e) },
    )

    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.1.0"

    private val ingest: Deferred<CoreIngest> get() = core.ingest

    internal val placeholders by lazy { PlatformPlaceholders() }
    /** O-1 / V-6 (#53): DataStore `cluetooth_settings`, migrated once from v3's prefs; V-6 awaits [DataStoreSettings.loaded]. */
    val settings: DataStoreSettings by lazy { DataStoreSettings(app, scope, appVersion) }
    /** O-2 (#53): the grants; the Activity refreshes it on resume and after a request. */
    val permissions: AndroidPermissions by lazy { AndroidPermissions(app) }
    val uploads get() = placeholders.uploads
    /** O-1 item 10 (#68): the share sheet and Downloads; it acknowledges exports through the core. */
    val exportShare: AndroidExportShare by lazy { AndroidExportShare(app, ::acknowledgeExport, scope, io) }
    /**
     * §4.3, D-13 (#65): manual GATT over the core's `gattTarget`/`ingestGatt` (#67), after the `BLUETOOTH_CONNECT` and
     * Bluetooth-on checks; unsaved results wait in `filesDir/gatt-pending`.
     */
    val gattRunner: PlatformGattRunner by lazy {
        PlatformGattRunner(
            core = ingest,
            io = io,
            manager = { store ->
                GattDiscoveryManager(
                    scope, store, FilePendingDiscoveryStore(File(app.filesDir, "gatt-pending")), gattDeviceInfo(appVersion),
                    elapsedMs = SystemClock::elapsedRealtime,
                )
            },
            available = { gattAvailable(app) },
            links = androidGattLinks(app) { addr -> scanner.devices[addr] },
            location = { locationClient.myLocation.value?.let { GattLocation.validOrNull(it.at.lat, it.at.lon, it.accuracyM) } },
        )
    }
    /** R-10: the core's storage-full event and cleanups (#71). */
    val storageCleanup: StorageCleanup by lazy { CoreStorageCleanup { core.ready() } }

    private val locationClient by lazy { LocationClient(app, ingest, scope, LocationClient.processStarted(scope)) }
    val location: LocationStatusSource get() = locationClient
    private val scanIngest by lazy { ScanIngest(ingest, scope, io.limitedParallelism(1)) }
    private val scanner by lazy {
        BleScanner(app, scanIngest, ingest, { settings.settings.value.scanCodedPhy }, appVersion, scope, io)
    }
    private val coreStates: Flow<CoreRecordingState> = flow {
        emitAll(core.ready().queries.events.filterIsInstance<CoreEvent.RecordingChanged>().map { it.state })
    }
    val recorder: Recorder by lazy { platformRecorder }
    private val platformRecorder by lazy {
        PlatformRecorder(
            ingest, scanner, locationClient, AndroidForegroundHost(app), AndroidRecordingGrants(app),
            { scanner.environment() }, io, scope, coreStates,
            // R-10: pause on the core's CoreEvent.StorageFull
            storageFull = storageCleanup.storageFull.map { it.n },
            // #79: storing kept failing for 60 s (not for space): the same pause, with the core's reason
            storageFailed = flow { emitAll(core.ready().queries.events) }.filterIsInstance<CoreEvent.StorageFailed>().map { it.message },
        )
    }
    val mapCache: MapCacheInfo by lazy { MapLibreCacheInfo(app) }

    /** `Application.onCreate`: start opening the core, then restore a recording and post the interrupted notice. */
    fun start() {
        core.start()
        platformRecorder // RecordingService finds it through RecordingServiceLink (§4.4)
        // each step is guarded on its own: one that fails is logged and skipped, the others still run
        scope.launch {
            val handle = core.ready()
            guarded(MainTask.Platform, ::startFailed) { platformRecorder.restore() }
            // §4.5: the exports an earlier process shared (or failed to acknowledge) are acknowledged now
            guarded(MainTask.Platform, ::startFailed) { exportShare.acknowledgeStale() }
            // §4.3: GATT results an earlier process could not store
            guarded(MainTask.Platform, ::startFailed) { gattRunner.recover() }
            // §1.2.6 (#53): one "Recording interrupted" notice for sessions Core::open marked interrupted
            guarded(MainTask.Platform, ::startFailed) { InterruptedRecordingNotice(app).check { handle.queries.sessions() } }
            // G-12: the display filter follows the setting; it never affects ingest or upload
            settings.settings.map { it.hideUnnamedApple }.distinctUntilChanged().collect { hide ->
                guarded(MainTask.Platform, ::startFailed) {
                    kotlinx.coroutines.withContext(io) { handle.ingest.setDisplayFilter(DisplayFilter(hide)) }
                }
            }
        }
    }

    /** `CoreStorage.acknowledgeExport` still throws (lane A converts :core-api); here it becomes a typed result. */
    private suspend fun acknowledgeExport(id: String): PlatformResult<Unit> = try {
        core.ready().storage.acknowledgeExport(id)
        PlatformResult.Ok(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PlatformResult.Err(PlatformError.Core(failure.message ?: failure::class.java.simpleName))
    }

    private fun startFailed(task: MainTask, error: Throwable) {
        android.util.Log.w("AppGraph", "start step failed ($task)", error)
    }
}
