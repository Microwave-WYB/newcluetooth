// docs/v2-api.md §5.1: the core's data models, 1:1 with the Rust records of §2 (camelCase).
package edu.ucsd.sysnet.cluetoothscanner.api.core

/** The 48-bit MAC as an integer (AA:BB:CC:DD:EE:FF → 0xAABBCCDDEEFF). */
typealias DeviceKey = Long

// ---- §2.1 Scope ----

sealed interface SessionScope {
    data object All : SessionScope
    /** One session is a one-element list. */
    data class Sessions(val ids: List<String>) : SessionScope
}

/** Selects which sessions take part; extensible with defaulted fields. */
data class SessionFilter(val startedFromMs: Long? = null, val startedToMs: Long? = null)

data class Scope(val sessions: SessionScope, val filter: SessionFilter? = null) {
    companion object {
        val All = Scope(SessionScope.All)
        fun session(id: String) = Scope(SessionScope.Sessions(listOf(id)))
    }
}

// ---- §2.2 Shared types and filters ----

data class GeoPoint(val lon: Double, val lat: Double)
data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double)

/** Keys in order; [seq] is the §2.6 snapshot sequence (0 when frozen). */
class DeviceKeys(val keys: LongArray, val seq: Long)

enum class Identification { Any, Identified, Unidentified }

/** Filters only hide rows (G-9); every field ANDs with the others. */
data class DeviceFilter(
    /** D-4 This area: the best point inside. */
    val area: Bbox? = null,
    val identification: Identification = Identification.Any,
    /** Any-of; empty means no constraint. */
    val categories: Set<String> = emptySet(),
    /** AND presets (D-3a). */
    val readable: Boolean = false, val trackers: Boolean = false, val vulnerable: Boolean = false,
    /** Nearby (live scopes only). */
    val inRangeOnly: Boolean = false,
    /** Product sheet (identified page). */
    val page: String? = null,
    /** Q-1 search: only matches (Q-5 map). */
    val query: String? = null,
)

enum class PageKind { Device, Platform, Identifier, Vulnerability }
data class PageRef(val id: String, val kind: PageKind, val title: String)
data class CategoryCount(val category: String, val label: String, val deviceCount: Int)
data class DisplayFilter(val hideUnnamedApple: Boolean)

// ---- §2.3 Lifecycle and recording ----

data class CoreConfig(
    val dataDirectory: String,
    /** 32 bytes, as v3. */
    val recipientPublicKey: ByteArray,
    val locationMaxAgeMs: Long = 5_000,
    val payloadMaxRows: Int = 100_000,
    val payloadMaxEstimatedBytes: Long = 10L * 1024 * 1024,
    val payloadMaxAgeMs: Long = 5 * 60 * 1_000,
    /** Staging append, then the matching SQLite commit (§1.2). */
    val durabilityIntervalMs: Long = 5_000,
    /** "Not in range" (C-5, D-2). */
    val staleAfterMs: Long = 20_000,
    /** Delta cadence; minimum 1000. */
    val liveIntervalMs: Long = 1_000,
)

/** [wikiBundleJson]: `assets/wiki-bundle.json` (bundle schema 2, §3.4); [sigNumbersTsv]: `assets/sig-numbers.tsv`. */
data class CoreAssets(val wikiBundleJson: String, val sigNumbersTsv: String)

/** Unchanged from v3 (`ScanEnvironment` in `payload.rs`). */
data class ScanEnvironment(
    val deviceModel: String, val androidRelease: String, val androidSdk: Int, val appVersion: String,
    val capExtAdv: Boolean, val capCodedPhy: Boolean, val capMaxAdvLen: Int,
    /** `all_supported` or `1m`. */
    val scanPhy: String,
    val scanLegacyOnly: Boolean,
)

enum class RecordingPhase { Idle, Recording, Paused }

/** Rust `RecordingState`. Elapsed = [activeBeforeMs] + (now − [runningSinceMs]); the UI ticks locally. */
data class CoreRecordingState(
    val phase: RecordingPhase, val sessionId: String?, val startedMs: Long?,
    val activeBeforeMs: Long, val runningSinceMs: Long?,
    /** Shown devices (display filter applied). */
    val deviceCount: Int, val distanceM: Double,
)

// ---- §2.4 Ingest ----

/** Unchanged from v3 (`scan.rs`). */
data class ScanObservationInput(
    val addr: String, val rssi: Int?, val scannedAtMs: Long, val elapsedRealtimeNanos: Long,
    val raw: ByteArray, val localName: String?, val txPower: Int?, val isConnectable: Boolean?,
    val isLegacy: Boolean?, val primaryPhy: Int?, val secondaryPhy: Int?, val advSid: Int?,
    val advTxPower: Int?, val periodicInterval: Int?, val dataStatus: Int?, val addrType: Int?,
)

/** Unchanged from v3 (`location.rs`). */
data class LocationFix(
    val lat: Double, val lon: Double, val accuracyMeters: Double, val observedAtMs: Long,
    val elapsedRealtimeNanos: Long,
)

data class IngestAck(val accepted: Int, val rejected: Int, val ignoredNotRecording: Int)

data class GattTarget(
    val addr: String, val addrType: String?, val name: String?,
    val triggerRaw: ByteArray, val triggerRssi: Int?, val triggerScannedAtMs: Long,
    val triggerIsLegacy: Boolean?, val triggerPrimaryPhy: Int?, val triggerSecondaryPhy: Int?,
    val triggerAdvSid: Int?, val sessionId: String?,
)

// ---- §2.5 Query results ----

enum class SessionStatus { Recording, Paused, Completed, Interrupted }
enum class UploadState { Pending, Uploaded, Failed, Empty }
data class SessionSummary(val id: String, val status: SessionStatus, val startedMs: Long, val endedMs: Long?,
                          val activeMs: Long, val distanceM: Double, val deviceCount: Int, val upload: UploadState)
data class AllSessionsSummary(val sessionCount: Int, val deviceCount: Int, val bbox: Bbox?)
data class SessionView(val scope: Scope, val live: Boolean, val sessionIds: List<String>, val deviceCount: Int,
                       val frame: Bbox?, val route: List<GeoPoint>, val routesFaint: Boolean, val seq: Long)

/** C-5. [spark]: live only, 30 × 2 s buckets (C-6), one byte each: −RSSI clamped to 30..100, 0 = gap. */
data class DeviceRow(val key: DeviceKey, val addr: String, val name: String?, val page: PageRef?,
                     val categories: List<String>, val traits: List<String>, val readable: Boolean,
                     val vulnerable: Boolean, val icon: String?, val rssi: Int?, val inRange: Boolean, val spark: ByteArray?,
                     val best: GeoPoint?, val lastSeenMs: Long)

/** [id] is the page id or "unidentified". */
data class ProductCard(val id: String, val title: String, val kind: PageKind?, val icon: String?, val live: Boolean,
                       val count: Int, val inRange: Int, val lastSeenMs: Long)

/** Cards inside a group come in bundle page order; the UI keeps that and appends new ones (G-9). */
data class CategoryGroup(val category: String, val label: String, val products: List<ProductCard>)

/** D-6, D-7. [places]: distinct z17 tiles of the devices' best points (scope, filter). */
data class ProductDetail(val page: PageRef?, val title: String, val oneLine: String?, val categories: List<String>,
                         val traits: List<String>, val readable: Boolean, val vulnerabilities: List<PageRef>,
                         val wikiUrl: String?, val icon: String?, val iconCredit: IconCredit?,
                         val places: Int, val lastSeenMs: Long?, val deviceCount: Int)
data class IconCredit(val source: String, val license: String, val author: String?)

enum class AdvFormat { Legacy, Extended, Unknown }
data class AdStructure(val len: Int, val adType: Int, val data: ByteArray)

/** Always null in v2.0 (issue #39). */
data class DecoderOutput(val fields: List<DecodedField>)
data class DecodedField(val name: String, val value: FieldValue, val unit: String?, val lookup: String?)
sealed interface FieldValue {
    data class Bool(val v: Boolean) : FieldValue
    data class Number(val v: Double) : FieldValue
    data class Text(val v: String) : FieldValue
}

/** D-9, D-12; from the device's latest payload in scope. */
data class DeviceDetail(val key: DeviceKey, val addr: String, val name: String?, val page: PageRef?,
                        val categories: List<String>, val traits: List<String>, val readable: Boolean,
                        val vulnerable: Boolean, val vulnerabilities: List<PageRef>, val wikiUrl: String?,
                        val icon: String?, val rssi: Int?, val inRange: Boolean, val live: Boolean, val connectable: Boolean?,
                        val format: AdvFormat, val companyId: Int?, val companyName: String?, val serviceUuid: String?,
                        val txPower: Int?, val structures: List<AdStructure>, val tail: ByteArray?,
                        val decoded: DecoderOutput?, val lastSeenMs: Long)

data class Sighting(val at: GeoPoint, val rssi: Int, val seenMs: Long)

/** D-10: 60 one-second buckets ending at [endMs], encoded as [DeviceRow.spark]. */
data class LiveChart(val key: DeviceKey, val endMs: Long, val samples: ByteArray, val rssi: Int?)

enum class MapMode { Points, Cells }

/** [geojson]: Points `{addr, rssi}`; Cells `{point_count, point_count_abbreviated}`. */
data class MapData(val mode: MapMode, val count: Int, val geojson: String, val seq: Long)

// ---- §2.6 Live deltas ----

data class LiveDelta(
    val sessionId: String, val seq: Long, val atMs: Long,
    val state: CoreRecordingState,
    /** Per-key value deltas, only changed keys. */
    val changed: List<DeviceLive>,
    /** New (or newly shown) devices, arrival order. */
    val appended: List<DeviceRow>,
    /** Changed or new cards; a new id is appended by the UI. */
    val products: List<ProductCard>,
    val routeAppended: List<GeoPoint>, val fix: FixSummary?,
    /** The focused key. */
    val chart: LiveChart?,
)

/** [name], [page] and [best] are non-null only when they changed. */
data class DeviceLive(val key: DeviceKey, val rssi: Int?, val inRange: Boolean, val spark: ByteArray,
                      val name: String?, val page: PageRef?, val best: GeoPoint?)

data class FixSummary(val lat: Double, val lon: Double, val accuracyM: Double, val ageMs: Long)

sealed interface CoreEvent {
    data class RecordingChanged(val state: CoreRecordingState) : CoreEvent
    /** A session finished, was deleted or interrupted. */
    data object SessionsChanged : CoreEvent
    /** Display filter, re-match done, delete: re-query the open scope. */
    data class DataInvalidated(val reason: String) : CoreEvent
    /** A payload or GATT file was sealed. */
    data object UploadNeeded : CoreEvent
    /** Also covers persistence failures. */
    data class StorageWarning(val message: String) : CoreEvent
}

// ---- §2.7 GATT view ----

enum class GattStepKind { Connecting, DiscoveringServices, ReadingCharacteristics, Disconnected }
enum class GattStepState { Done, Running, Failed }
sealed interface GattFailure {
    data object TimedOut : GattFailure
    data object DisconnectedUnexpectedly : GattFailure
    data object ConnectionFailed : GattFailure
    data object Cancelled : GattFailure
    data class Other(val status: Int) : GattFailure
}
data class GattStep(val kind: GattStepKind, val state: GattStepState, val durationMs: Int?,
                    val services: Int?, val read: Int?, val readable: Int?, val failure: GattFailure?)
data class GattView(val discoveryId: String, val key: DeviceKey, val startedMs: Long, val ok: Boolean,
                    val steps: List<GattStep>, val services: List<GattServiceView>)
data class GattServiceView(val uuid: String, val name: String?, val characteristics: List<GattCharView>)

/** [valueText] is display-only: UTF-8 text, Battery Level "78 %", Appearance (SIG), else hex. */
data class GattCharView(val uuid: String, val name: String?, val properties: List<String>, val valueText: String?)

// ---- §2.8 Storage, export, delete, upload ----

/** "Map cache" comes from :map (`MapCacheInfo`), not the core. */
data class StorageStats(val databaseBytes: Long, val rawFilesBytes: Long, val pendingCount: Int,
                        val pendingBytes: Long, val sessionCount: Int)

enum class ExportFormat { ParquetRaw, Csv, SqliteSnapshot }
data class PreparedExport(val exportId: String, val localPath: String, val suggestedFileName: String,
                          val fileCount: Long, val sizeBytes: Long)
sealed interface DeleteOutcome {
    data class Deleted(val sessions: Int) : DeleteOutcome
    data class DeletedPendingKept(val pending: Int) : DeleteOutcome
    data class Refused(val reason: String) : DeleteOutcome
}
data class UploadStatus(val pendingCount: Int, val pendingBytes: Long, val failedCount: Int, val lastError: String?)

/** Unchanged from v3 (`payload.rs`). */
data class PendingUpload(val payloadId: String, val localPath: String, val objectPath: String,
                         val rowCount: Long, val sizeBytes: Long)

/** Unchanged from v3 (`payload.rs`). */
data class PreparedUpload(val payloadId: String, val ciphertextPath: String, val objectPath: String,
                          val plaintextSizeBytes: Long, val ciphertextSizeBytes: Long)
