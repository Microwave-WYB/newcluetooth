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
    /** The process scope (main thread; work that blocks hops to [io]). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO

    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.1.0"

    /** §1.2.2: started by [start]; it opens the core on Dispatchers.IO. */
    val core: Deferred<CoreHandle> = scope.async(io, start = CoroutineStart.LAZY) { CoreBinding.open(app, scope) }
    private val ingest: Deferred<CoreIngest> = scope.async(start = CoroutineStart.LAZY) { core.await().ingest }

    internal val placeholders by lazy { PlatformPlaceholders(app, appVersion) }
    val settings get() = placeholders.settings
    val permissions get() = placeholders.permissions
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
        emitAll(core.await().queries.events.filterIsInstance<CoreEvent.RecordingChanged>().map { it.state })
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
        ingest.start()
        platformRecorder // RecordingService finds it through RecordingServiceLink (§4.4)
        scope.launch {
            val handle = core.await()
            platformRecorder.restore()
            placeholders.checkInterruptedRecording(handle.queries)
            // G-12: the display filter follows the setting; it never affects ingest or upload
            settings.settings.map { it.hideUnnamedApple }.distinctUntilChanged().collect { hide ->
                kotlinx.coroutines.withContext(io) { handle.ingest.setDisplayFilter(DisplayFilter(hide)) }
            }
        }
    }
}
