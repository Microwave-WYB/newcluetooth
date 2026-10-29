package edu.ucsd.sysnet.cluetoothscanner.corebridge

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreAssets
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.api.core.PendingUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.api.core.StorageStats
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.core.generated.Core
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreListener
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ExportRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent as GCoreEvent
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LiveDelta as GLiveDelta
import edu.ucsd.sysnet.cluetoothscanner.core.generated.matchDevice as coreMatchDevice

/**
 * The real core behind :core-api (docs/v2-api.md §6.2). One instance per process; open it off the main thread
 * ([open] may migrate or recover a large DB, §1.2). Suspending calls are cancellable: a cancelled coroutine drops
 * the Rust future, and `Cancelled`/`Superseded` surface as `CancellationException`.
 */
class CoreBridge private constructor(private val core: Core) : CoreQueries, CoreStorage, CoreIngest, AutoCloseable {

    companion object {
        /** [F] Blocking: opens or migrates local.db. */
        fun open(config: CoreConfig, assets: CoreAssets): CoreBridge = call { CoreBridge(Core.open(config.g(), assets.g())) }

        /** The v2 API version the bridge was built against (§2). */
        const val API_VERSION = 8
    }

    // §6.2: the listener goes to a MutableSharedFlow(extraBufferCapacity = 4) with a blocking emit on ct-notify.
    private val liveFlow = MutableSharedFlow<LiveDelta>(extraBufferCapacity = 4)
    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 4)
    override val live: Flow<LiveDelta> = liveFlow.asSharedFlow()
    override val events: Flow<CoreEvent> = eventFlow.asSharedFlow()

    init {
        check(core.apiVersion().toInt() == API_VERSION) { "core API ${core.apiVersion()} != $API_VERSION" }
        core.setListener(object : CoreListener {
            override fun onLive(delta: GLiveDelta) = runBlocking { liveFlow.emit(delta.api()) }
            override fun onEvent(event: GCoreEvent) = runBlocking { eventFlow.emit(event.api()) }
        })
    }

    /** Commits, stops the core and frees the handle. */
    override fun close() {
        core.setListener(null)
        core.closeCore()
        core.close()
    }

    // ---- CoreQueries ----

    override suspend fun sessions(): List<SessionSummary> = call { core.sessions().map { it.api() } }
    override suspend fun currentSession(): SessionSummary? = core.currentSession()?.api()
    override suspend fun allSessionsSummary(): AllSessionsSummary = call { core.allSessionsSummary().api() }
    override suspend fun sessionView(scope: Scope): SessionView = call { core.sessionView(scope.g()).api(scope) }
    override suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String =
        call { core.routes(scope.g(), viewport?.g(), zoom) }
    override suspend fun deviceOrder(scope: Scope): DeviceKeys = call { core.deviceOrder(scope.g()).api() }
    override suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys =
        call { core.deviceKeys(scope.g(), filter.g()).api() }
    override suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow> =
        call { core.deviceRows(scope.g(), keys.toList()).map { it.api() } }
    override suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount> =
        call { core.categoryCounts(scope.g(), filter.g()).map { it.api() } }
    override suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup> =
        call { core.products(scope.g(), filter.g()).map { it.api() } }
    override suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail? =
        call { core.product(scope.g(), pageId, filter.g())?.api() }
    override suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail? = call { core.device(scope.g(), key)?.api() }
    override suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting> =
        call { core.deviceSightings(scope.g(), key).map { it.api() } }
    override suspend fun deviceChart(key: DeviceKey): LiveChart? = core.deviceChart(key)?.api()
    override suspend fun deviceGatt(key: DeviceKey): GattView? = call { core.deviceGatt(key)?.api() }
    override suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys? =
        call { core.search(scope.g(), query, area?.g())?.api() }
    override fun matchDevice(query: String, name: String?, addr: String): Boolean = coreMatchDevice(query, name, addr)
    override suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData =
        call { core.mapPoints(scope.g(), filter.g(), viewport.g(), zoom).api() }
    override suspend fun setLiveFocus(key: DeviceKey?) = core.setLiveFocus(key)

    // ---- CoreStorage ----

    override suspend fun storageStats(): StorageStats = call { core.storageStats().api() }
    override suspend fun prepareExport(scope: Scope, format: ExportFormat): PreparedExport =
        call { core.prepareExport(ExportRequest(scope.g(), format.g())).api() }
    override suspend fun acknowledgeExport(id: String) = call { core.acknowledgeExport(id) }
    override suspend fun deleteLocalData(scope: Scope, includeUnuploaded: Boolean): DeleteOutcome =
        call { core.deleteLocalData(scope.g(), includeUnuploaded).api() }

    // ---- CoreIngest ----

    override fun ingestScans(batch: List<ScanObservationInput>): IngestAck = call { core.ingestScans(batch.map { it.g() }).api() }
    override fun ingestLocation(fix: LocationFix) = core.ingestLocation(fix.g())
    override fun clearLocation() = core.clearLocation()
    override fun newGattDiscoveryId(): String = call { core.newGattDiscoveryId() }
    override suspend fun gattTarget(key: DeviceKey): GattTarget? = core.gattTarget(key)?.api()
    override fun ingestGatt(gattV1Json: String): GattView = call { core.ingestGatt(gattV1Json).api() }
    override fun startRecording(env: ScanEnvironment): String = call { core.startRecording(env.g()) }
    override fun pauseRecording() = call { core.pauseRecording() }
    override fun resumeRecording() = call { core.resumeRecording() }
    override fun stopRecording(): String = call { core.stopRecording() }
    override fun configureScan(env: ScanEnvironment) = call { core.configureScan(env.g()) }
    override fun recordingState(): CoreRecordingState = core.recordingState().api()
    override fun setDisplayFilter(filter: DisplayFilter) = core.setDisplayFilter(filter.g())
    override fun uploadStatus(): UploadStatus = core.uploadStatus().api()
    override fun pendingUploads(): List<PendingUpload> = core.pendingUploads().map { it.api() }
    override fun prepareUpload(id: String): PreparedUpload = call { core.prepareUpload(id).api() }
    override fun markUploadSucceeded(id: String) = call { core.markUploadSucceeded(id) }
    override fun markUploadFailed(id: String, message: String) = call { core.markUploadFailed(id, message) }
}
