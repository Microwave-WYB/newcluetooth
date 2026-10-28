package edu.ucsd.sysnet.cluetoothscanner.ui.v3.model

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

enum class AddressType(val label: String) {
    Unknown("地址类型未知"),
    Public("公共地址"),
    RandomStatic("随机静态地址"),
    ResolvablePrivate("可解析私有地址"),
    NonResolvablePrivate("不可解析私有地址"),
}

enum class AdvKind(val label: String) { Legacy("传统"), Extended("扩展") }

enum class RecorderPhase { Idle, Recording, Paused }

data class RecorderUiState(
    val phase: RecorderPhase = RecorderPhase.Idle,
    val elapsedSeconds: Int = 0,
    val sightings: Int = 0,
)

enum class AppTab(val label: String) { Scan("扫描"), Nearby("附近"), Recordings("录制") }

// ---- Devices -------------------------------------------------------------------------------

data class DeviceRowState(
    val id: String,
    val name: String?,
    val mac: String,
    val addressType: AddressType,
    val advKind: AdvKind,
    val connectable: Boolean,
    val rssi: Int,
    /** Recent RSSI samples, oldest first, for the row sparkline. */
    val history: List<Int>,
    /** Not heard for a while; the row stays where it is but is dimmed. */
    val stale: Boolean = false,
)

data class ScanUiState(
    val rows: List<DeviceRowState>,
    val recorder: RecorderUiState,
)

// ---- Device detail -------------------------------------------------------------------------

enum class ChartWindow(val label: String) { OneMinute("1分钟"), FiveMinutes("5分钟"), FifteenMinutes("15分钟") }

data class AdStructureRow(val type: String, val typeName: String, val length: Int, val data: String)

data class AdVariantState(
    val id: String,
    val pduType: String,
    val phy: String,
    val sid: Int?,
    val count: Int,
    val firstSeen: String,
    val lastSeen: String,
    val rssiMin: Int,
    val rssiMax: Int,
    val structures: List<AdStructureRow>,
)

enum class StepStatus { Pending, Active, Done, Failed }

data class GattStepState(val label: String, val status: StepStatus, val detail: String? = null)

data class GattCharState(
    val uuid: String,
    val name: String?,
    val properties: String,
    val value: String?,
    val error: String?,
)

data class GattServiceState(val uuid: String, val name: String?, val characteristics: List<GattCharState>)

sealed interface GattUiState {
    data object NotStarted : GattUiState
    data class InProgress(val steps: List<GattStepState>) : GattUiState
    data class Done(
        val steps: List<GattStepState>,
        val services: List<GattServiceState>,
        /** Outcome in words when it was not a clean finish, e.g. 部分完成 · 需要认证. */
        val summary: String? = null,
    ) : GattUiState
}

/** A wiki family record; only present for recognized devices. */
data class WikiUiState(
    val family: String,
    val summary: String,
    val facts: List<Pair<String, String>>,
    val confidence: String,
)

data class DeviceDetailUiState(
    val device: DeviceRowState,
    val variants: List<AdVariantState>,
    val chartWindow: ChartWindow,
    /** One RSSI sample per second inside [chartWindow], oldest first. */
    val chartSamples: List<Int>,
    val gatt: GattUiState,
    val wiki: WikiUiState?,
    /** Non-null disables 发现 GATT and says why. */
    val gattDisabledReason: String?,
)

enum class DetailTab(val label: String) { Advertising("广播"), Gatt("GATT"), Wiki("Wiki") }

// ---- Nearby --------------------------------------------------------------------------------

enum class CategoryIcon { Headphones, Watch, Beacon, Home, Car, Medical, Tracker, Unknown }

data class ProductItemState(
    val id: String,
    val name: String,
    val subtitle: String,
    /** Strongest RSSI right now, or null for a product seen earlier but not in range. */
    val strongestRssi: Int?,
)

data class NearbyCategoryState(
    val id: String,
    val title: String,
    val icon: CategoryIcon,
    val items: List<ProductItemState>,
)

data class NearbyUiState(
    val recorder: RecorderUiState,
    val categories: List<NearbyCategoryState>,
    /** Last row, unknown structures. */
    val unrecognized: NearbyCategoryState,
)

// ---- Recordings ----------------------------------------------------------------------------

enum class UploadStatus(val label: String) { Uploaded("已上传"), Uploading("上传中"), Failed("失败") }

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

enum class DeviceSort(val label: String) { Signal("按信号"), Recent("按时间"), Name("按名称") }

/** One line in the map sheet's list: a device (recording map) or a MAC (product map). */
data class MapListRowState(
    val id: String,
    val title: String,
    /** Shown in tight monospace after [detail] is not needed; null for rows without an address. */
    val mac: String?,
    val detail: String,
    val rssi: Int?,
    val lastSeenMs: Long = 0,
    /** Where the strongest sample was heard; used to filter the list to the visible map area. */
    val at: LatLon? = null,
)

data class MapUiState(
    val mode: MapMode,
    val title: String,
    val subtitle: String,
    val recorder: RecorderUiState,
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
)
