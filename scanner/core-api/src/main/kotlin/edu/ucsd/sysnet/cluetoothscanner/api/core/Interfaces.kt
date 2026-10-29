// docs/v2-api.md §5.1: the core's interfaces. :core-bridge implements them over UniFFI, :core-fake over data.js.
package edu.ucsd.sysnet.cluetoothscanner.api.core

import kotlinx.coroutines.flow.Flow

/**
 * The UI's data interface. Every `suspend` call is cancellable: cancelling the coroutine interrupts the query, and
 * a superseded query (a newer one of the same §2 kind) ends in a `CancellationException`, so callers use
 * `mapLatest`/`collectLatest`.
 */
interface CoreQueries {
    /** §2.6: subscribe first, then query; ignore deltas with `seq` ≤ the snapshot's `seq`. */
    val live: Flow<LiveDelta>
    val events: Flow<CoreEvent>
    suspend fun sessions(): List<SessionSummary>
    suspend fun currentSession(): SessionSummary?
    suspend fun allSessionsSummary(): AllSessionsSummary
    suspend fun sessionView(scope: Scope): SessionView
    suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String
    suspend fun deviceOrder(scope: Scope): DeviceKeys
    suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys
    /** At most 200 keys per call, rows in the given order. */
    suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow>
    /** Faceted (D-3a): `filter.categories` is ignored, every other constraint applies. */
    suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount>
    suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup>
    /** A null [pageId] is the "Unidentified devices" card. */
    suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail?
    suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail?
    suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting>
    suspend fun deviceChart(key: DeviceKey): LiveChart?
    suspend fun deviceGatt(key: DeviceKey): GattView?
    /** Q-1/Q-2: null for a blank query; results in `deviceOrder` order. */
    suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys?
    /** Q-1, pure. */
    fun matchDevice(query: String, name: String?, addr: String): Boolean
    suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData
    suspend fun setLiveFocus(key: DeviceKey?)
}

/** SPEC O-1 items 8–11. */
interface CoreStorage {
    suspend fun storageStats(): StorageStats
    suspend fun prepareExport(scope: Scope, format: ExportFormat): PreparedExport
    suspend fun acknowledgeExport(id: String)
    /** [includeUnuploaded] is true only after the explicit second confirmation (§2.8). */
    suspend fun deleteLocalData(scope: Scope, includeUnuploaded: Boolean): DeleteOutcome
}

/**
 * The platform's interface (lane B only). Non-suspending calls block for at most one writer tick ([W] in §2) or
 * not at all ([N]); call them off the main thread. Failures throw [CoreException].
 */
interface CoreIngest {
    /** Throws [CoreException.Backpressure] when the queue stays full for 1 s; keep the batch and retry. */
    fun ingestScans(batch: List<ScanObservationInput>): IngestAck
    fun ingestLocation(fix: LocationFix)
    fun clearLocation()
    fun newGattDiscoveryId(): String
    suspend fun gattTarget(key: DeviceKey): GattTarget?
    fun ingestGatt(gattV1Json: String): GattView

    /** Returns the new session id. */
    fun startRecording(env: ScanEnvironment): String
    fun pauseRecording()
    fun resumeRecording()
    /** Returns the finished session id. */
    fun stopRecording(): String
    fun configureScan(env: ScanEnvironment)
    fun recordingState(): CoreRecordingState
    fun setDisplayFilter(filter: DisplayFilter)

    fun uploadStatus(): UploadStatus
    fun pendingUploads(): List<PendingUpload>
    fun prepareUpload(id: String): PreparedUpload
    fun markUploadSucceeded(id: String)
    fun markUploadFailed(id: String, message: String)
}

/**
 * §2.9 `CoreError`. `Cancelled` never surfaces: it becomes `CancellationException`.
 */
sealed class CoreException(message: String) : Exception(message) {
    class InvalidPayload(val detail: String) : CoreException("invalid payload: $detail")
    class InvalidConfig(val detail: String) : CoreException("invalid core configuration: $detail")
    class InputBatchTooLarge(val detail: String) : CoreException("input batch too large: $detail")
    class UploadState(val detail: String) : CoreException("upload state rejected the operation: $detail")
    class Encryption(val detail: String) : CoreException("encryption failed: $detail")
    class Io(val detail: String) : CoreException("I/O failed: $detail")
    class Parquet(val detail: String) : CoreException("Parquet failed: $detail")
    class Clock(val detail: String) : CoreException("clock failed: $detail")
    class Db(val detail: String) : CoreException("local database failed: $detail")
    /** An older build opened a newer local.db. */
    class SchemaTooNew(val found: Int, val supported: Int) :
        CoreException("local.db schema $found is newer than the supported $supported")
    class InvalidBundle(val detail: String) : CoreException("invalid wiki bundle: $detail")
    class Backpressure : CoreException("the ingest queue is full")
    class NotRecording : CoreException("no recording is active")
    class AlreadyRecording : CoreException("a recording is already active")
    class Closed : CoreException("the core is closed")
    /** A fault inside the core or its bindings (a Rust panic UniFFI caught, or a binding error). */
    class Internal(val detail: String) : CoreException("core fault: $detail")
}
