package edu.ucsd.sysnet.cluetoothscanner.v2

import android.app.Application
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.map.MapLibreCacheInfo
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

    /** The process scope (main thread; work that blocks hops to [io]). A failure in it moves the core to Failed. */
    val scope: CoroutineScope = CoroutineScope(base.coroutineContext + core.handler)

    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.1.0"

    private val ingest: Deferred<CoreIngest> get() = core.ingest

    internal val placeholders by lazy { PlatformPlaceholders() }
    /** O-1 / V-6 (#53): DataStore `cluetooth_settings`, migrated once from v3's prefs; V-6 awaits [DataStoreSettings.loaded]. */
    val settings: DataStoreSettings by lazy { DataStoreSettings(app, scope, appVersion) }
    /** O-2 (#53): the grants; the Activity refreshes it on resume and after a request. */
    val permissions: AndroidPermissions by lazy { AndroidPermissions(app) }
    val uploads get() = placeholders.uploads
    val exportShare get() = placeholders.exportShare
    val gattRunner get() = placeholders.gattRunner

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
        )
    }
    val mapCache: MapCacheInfo by lazy { MapLibreCacheInfo(app) }

    /** `Application.onCreate`: start opening the core, then restore a recording and post the interrupted notice. */
    fun start() {
        core.start()
        platformRecorder // RecordingService finds it through RecordingServiceLink (§4.4)
        scope.launch {
            val handle = core.ready()
            platformRecorder.restore()
            // §1.2.6 (#53): one "Recording interrupted" notice for sessions Core::open marked interrupted
            runCatching { InterruptedRecordingNotice(app).check { handle.queries.sessions() } }
            // G-12: the display filter follows the setting; it never affects ingest or upload
            settings.settings.map { it.hideUnnamedApple }.distinctUntilChanged().collect { hide ->
                kotlinx.coroutines.withContext(io) { handle.ingest.setDisplayFilter(DisplayFilter(hide)) }
            }
        }
    }
}
