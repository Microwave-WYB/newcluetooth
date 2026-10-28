package edu.ucsd.sysnet.cluetoothscanner.ui.v3.model

import androidx.annotation.StringRes
import edu.ucsd.sysnet.cluetoothscanner.R
import java.time.LocalDateTime

// Plain UI state for the v3 screens. Nothing here knows about BLE, GATT or storage:
// a ViewModel maps the real sources onto these classes later.

enum class Signal { Strong, Fair, Weak }

/** RSSI > -65 strong, -65..-80 fair, below -80 weak. */
fun signalOf(rssi: Int): Signal = when {
    rssi > -65 -> Signal.Strong
    rssi >= -80 -> Signal.Fair
    else -> Signal.Weak
}

enum class AdvKind(@StringRes val label: Int) { Legacy(R.string.adv_legacy), Extended(R.string.adv_extended) }

enum class RecorderPhase { Idle, Recording, Paused }

data class RecorderUiState(
    val phase: RecorderPhase = RecorderPhase.Idle,
    val elapsedSeconds: Int = 0,
    val sightings: Int = 0,
)

enum class AppTab(@StringRes val label: Int) {
    Scan(R.string.tab_scan),
    Discover(R.string.tab_discover),
    History(R.string.tab_history),
}

// ---- Devices -------------------------------------------------------------------------------

data class DeviceRowState(
    val id: String,
    val name: String?,
    val mac: String,
    val advKind: AdvKind,
    val connectable: Boolean,
    val rssi: Int,
    /** The last 60 s in 2 s buckets, oldest first, strongest RSSI per bucket; null = nothing heard in it. */
    val history: List<Int?>,
    /** Not heard for a while; the row stays where it is but is dimmed. */
    val stale: Boolean = false,
)

data class ScanUiState(
    val rows: List<DeviceRowState>,
    val recorder: RecorderUiState,
    val location: ScanLocation = ScanLocation.Unavailable,
    /**
     * Bumped with the rows of each sort (the initial one and every pull to refresh), in the same
     * emission, so the list scrolls to the top only once the sorted rows are on screen.
     */
    val sortVersion: Int = 0,
)

/** What the Scan subtitle says about the phone's own position. */
sealed interface ScanLocation {
    data object Unavailable : ScanLocation

    data class Fix(val lat: Double, val lon: Double, val accuracyMeters: Int?) : ScanLocation

    /** The last fix is older than a minute. */
    data class Stale(val minutes: Int) : ScanLocation
}

// ---- Device detail -------------------------------------------------------------------------

/** One row of the advertising table: a short label and its value; [bytes] values are shown in monospace. */
data class AdField(val label: String, val value: String, val bytes: Boolean = false)

/**
 * One AD structure as the detail shows it: the table rows it reads as ([fields]: a name as text, UUIDs,
 * the company and its data, a TX power, else the bytes). [data] stays the exact bytes as hex.
 */
data class AdStructureRow(
    val type: String,
    val typeName: String,
    val length: Int,
    val data: String,
    val fields: List<AdField> = listOf(AdField(typeName, data.chunked(2).joinToString(" ").lowercase(), bytes = true)),
)

/** One distinct advertisement a device sent: just its content. */
data class AdVariantState(
    val id: String,
    val structures: List<AdStructureRow>,
)

enum class StepStatus { Pending, Active, Done, Failed }

data class GattStepState(val label: String, val status: StepStatus, val detail: String? = null)

data class GattDescriptorState(val uuid: String, val name: String?, val value: String?, val error: String?)

data class GattCharState(
    val uuid: String,
    val name: String?,
    val properties: String,
    val value: String?,
    val error: String?,
    val descriptors: List<GattDescriptorState> = emptyList(),
)

data class GattServiceState(val uuid: String, val name: String?, val characteristics: List<GattCharState>)

sealed interface GattUiState {
    data object NotStarted : GattUiState
    data class InProgress(val steps: List<GattStepState>) : GattUiState
    data class Done(
        val steps: List<GattStepState>,
        val services: List<GattServiceState>,
        /** Outcome in words when it was not a clean finish, e.g. "Partial · Requires authentication". */
        val summary: String? = null,
    ) : GattUiState
}

data class DeviceDetailUiState(
    val device: DeviceRowState,
    /** The wiki product this device was recognised as, shown under its name. */
    val productName: String? = null,
    val variants: List<AdVariantState>,
    /** RSSI over the last minute in 1 s buckets, oldest first; null marks a bucket with no samples. */
    val chartSamples: List<Int?>,
    /** How long the device has been heard, for the "Heard for ..." caption on short-lived devices; null when unknown. */
    val chartHeardSeconds: Int? = null,
    val gatt: GattUiState,
    /** The matched wiki page (`<kind>--<slug>`, the wiki directory name); null when the device is not recognized. */
    val wikiPageId: String?,
    /** Non-null disables "Discover GATT" and says why. */
    val gattDisabledReason: String?,
    /** Where the device probably is, from its located samples; only on the maps, and only when it can be estimated. */
    val positionEstimate: PositionEstimateState? = null,
)

data class PositionEstimateState(val at: LatLon, val accuracyMeters: Int?)

enum class DetailTab(@StringRes val label: Int) {
    Advertising(R.string.detail_tab_advertising),
    Gatt(R.string.detail_tab_gatt),
}

// ---- Discover ------------------------------------------------------------------------------

/** One Material icon per wiki category (the product's first); the stand-in until the wiki has images. */
enum class CategoryIcon { Headphones, Watch, Beacon, Home, Light, Sensor, Power, Car, Medical, Tracker, Unknown }

/** The Discover page's two lists. */
enum class DiscoverMode(@StringRes val label: Int) {
    Nearby(R.string.discover_mode_nearby),
    All(R.string.discover_mode_all),
}

/** One product, once, whatever its categories. */
data class ProductRowState(
    /** The wiki page id. */
    val id: String,
    val name: String,
    val icon: CategoryIcon,
    /** "In range · 3 devices" or "Last seen 2 h ago". */
    val supporting: String,
    /** Strongest RSSI right now, or null when not in range. */
    val strongestRssi: Int?,
)

data class DiscoverUiState(
    val recorder: RecorderUiState,
    /** In range now, strongest first. */
    val nearby: List<ProductRowState>,
    /** Everything seen, most recent first. */
    val all: List<ProductRowState>,
)

/** The product page's header: what the wiki says about it. */
data class ProductHeaderState(
    val id: String,
    val title: String,
    /** The wiki's one-line description. */
    val description: String,
    val icon: CategoryIcon,
)

// ---- Recordings ----------------------------------------------------------------------------

enum class UploadStatus(@StringRes val label: Int) {
    Uploaded(R.string.upload_uploaded),
    Uploading(R.string.upload_uploading),
    Failed(R.string.upload_failed),

    /** The core has not reported this recording's upload state. */
    Unknown(R.string.upload_unknown),

    /** The recording had no rows to upload. */
    Empty(R.string.upload_empty),
}

data class RecordingSummaryState(
    val id: String,
    val startedAt: LocalDateTime,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val deviceCount: Int,
    val upload: UploadStatus,
    val note: String?,
)

data class RecordingDayState(val label: String, val recordings: List<RecordingSummaryState>)

data class RecordingsUiState(
    val recorder: RecorderUiState,
    /** Pinned on top while recording. */
    val current: RecordingSummaryState?,
    val days: List<RecordingDayState>,
)

// ---- Maps ----------------------------------------------------------------------------------

data class LatLon(val lat: Double, val lon: Double)

data class SightingPoint(val at: LatLon, val rssi: Int)

enum class MapMode { Recording, Product }

enum class DeviceSort(@StringRes val label: Int) {
    Signal(R.string.map_sort_by_signal),
    Recent(R.string.map_sort_by_recency),
    Name(R.string.map_sort_by_name),
}

/** One line in the map sheet's list: a device (recording map) or a MAC (product map). */
data class MapListRowState(
    val id: String,
    val title: String,
    /** Shown in tight monospace after [detail] is not needed; null for rows without an address. */
    val mac: String?,
    val detail: String,
    val rssi: Int?,
    val lastSeenMs: Long = 0,
    /** No name is known; shown in the muted "No name" style and sorted last by name. */
    val unnamed: Boolean = false,
    /** Where the strongest sample was heard; used to filter the list to the visible map area. */
    val at: LatLon? = null,
)

data class MapUiState(
    val mode: MapMode,
    val title: String,
    val subtitle: String,
    val route: List<LatLon>,
    val rows: List<MapListRowState>,
    val sort: DeviceSort,
    val query: String = "",
    val searching: Boolean = false,
    /** Product map: the product's points across recordings (no route). */
    val productPoints: List<SightingPoint>,
    val selected: DeviceDetailUiState?,
    val selectedPoints: List<SightingPoint>,
    val estimate: LatLon?,
    val estimateAccuracyMeters: Int? = null,
    val showAreaChip: Boolean,
    /** The chip is on: the list shows only what is inside the visible map area. */
    val areaOnly: Boolean = false,
    val heatmap: Boolean,
    val heat: List<LatLon>,
    /** Why the map is empty (no fixes, nothing seen), shown over it; null otherwise. */
    val emptyNote: String? = null,
    /** Shown instead of the device count, e.g. while a recording has saved no minute yet. */
    val listHint: String? = null,
)

// ---- Settings ------------------------------------------------------------------------------

data class SettingsUiState(
    val userId: String? = null,
    val autoRecord: Boolean = false,
    val wifiOnly: Boolean = false,
    val codedPhy: Boolean = true,
    /** Recordings not uploaded yet (pending or failed). */
    val pendingUploads: Int = 0,
    /** Something to upload: a pending recording or rows not sealed yet. */
    val canUploadNow: Boolean = false,
    val version: String = "",
)
