//! Records and enums of the v2 core API (`docs/v2-api.md` §2).
//!
//! Names that the v3 `CluetoothCore` already uses in the generated Kotlin package carry a `V2` foreign name until
//! integration (§6.7) removes v3; the Rust names are the frozen contract names.

/// The 48-bit MAC as an integer (AA:BB:CC:DD:EE:FF → 0xAABBCCDDEEFF).
pub type DeviceKey = i64;

// ---- §2.1 Scope ----

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SessionScope {
    All,
    /// One session is a one-element list.
    Sessions {
        ids: Vec<String>,
    },
}

/// Selects which sessions take part; extensible with defaulted fields.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct SessionFilter {
    #[uniffi(default = None)]
    pub started_from_ms: Option<i64>,
    #[uniffi(default = None)]
    pub started_to_ms: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Scope {
    pub sessions: SessionScope,
    #[uniffi(default = None)]
    pub filter: Option<SessionFilter>,
}

// ---- §2.2 Shared types and filters ----

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct GeoPoint {
    pub lon: f64,
    pub lat: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct Bbox {
    pub west: f64,
    pub south: f64,
    pub east: f64,
    pub north: f64,
}

/// A packed key list: little-endian i64s. `:core-bridge` exposes it as a `LongArray`.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct DeviceKeys {
    pub le_i64: Vec<u8>,
    /// §2.6 snapshot sequence; 0 when frozen.
    pub seq: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Enum)]
pub enum Identification {
    #[default]
    Any,
    Identified,
    Unidentified,
}

/// Filters only hide rows (G-9); every field ANDs with the others.
#[derive(Clone, Debug, Default, PartialEq, uniffi::Record)]
pub struct DeviceFilter {
    /// D-4 This area: the best point inside.
    #[uniffi(default = None)]
    pub area: Option<Bbox>,
    /// §2.2 gives it `default = Any`; UniFFI 0.32's Kotlin generator cannot emit an enum default, so the bridge
    /// always passes it (the `:core-api` Kotlin record keeps the default).
    pub identification: Identification,
    /// Any-of (a set); empty means no constraint.
    #[uniffi(default = [])]
    pub categories: Vec<String>,
    /// Preset: the matched page has a decoder.js.
    #[uniffi(default = false)]
    pub readable: bool,
    /// Preset: the matched page is a tracker (§3.4).
    #[uniffi(default = false)]
    pub trackers: bool,
    /// Preset: the matched page links a vulnerability page.
    #[uniffi(default = false)]
    pub vulnerable: bool,
    /// Nearby (live scopes only).
    #[uniffi(default = false)]
    pub in_range_only: bool,
    /// Product sheet (identified page).
    #[uniffi(default = None)]
    pub page: Option<String>,
    /// Q-1 search: only matches (Q-5 map).
    #[uniffi(default = None)]
    pub query: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PageKind {
    Device,
    Platform,
    Identifier,
    Vulnerability,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PageRef {
    pub id: String,
    pub kind: PageKind,
    pub title: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CategoryCount {
    pub category: String,
    pub label: String,
    pub device_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct DisplayFilter {
    pub hide_unnamed_apple: bool,
}

// ---- §2.3 Lifecycle and recording ----

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
#[uniffi(name = "CoreConfigV2")]
pub struct CoreConfig {
    pub data_directory: String,
    /// 32 bytes, as v3.
    pub recipient_public_key: Vec<u8>,
    /// 5000.
    pub location_max_age_ms: u64,
    /// 100k.
    pub payload_max_rows: u32,
    /// 10 MiB.
    pub payload_max_estimated_bytes: u64,
    /// 5 min.
    pub payload_max_age_ms: u64,
    /// 5000: staging append, then the matching SQLite commit (§1.2).
    pub durability_interval_ms: u64,
    /// 20000: "not in range" (C-5, D-2).
    pub stale_after_ms: u64,
    /// 1000: delta cadence; minimum 1000.
    pub live_interval_ms: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CoreAssets {
    /// `assets/wiki-bundle.json`, bundle schema 2 (§3.4).
    pub wiki_bundle_json: String,
    /// `assets/sig-numbers.tsv` (company, uuid16, appearance, adtype).
    pub sig_numbers_tsv: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RecordingPhase {
    Idle,
    Recording,
    Paused,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RecordingState {
    pub phase: RecordingPhase,
    pub session_id: Option<String>,
    pub started_ms: Option<i64>,
    /// Elapsed = before + (now − since); the UI ticks locally.
    pub active_before_ms: i64,
    pub running_since_ms: Option<i64>,
    /// Shown devices (display filter applied).
    pub device_count: u32,
    pub distance_m: f64,
    /// SPEC R-10: paused because storage is full; resume needs free space above the floor.
    pub paused_for_storage: bool,
}

// ---- §2.4 Ingest ----

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct IngestAck {
    pub accepted: u32,
    pub rejected: u32,
    pub ignored_not_recording: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattTarget {
    pub addr: String,
    pub addr_type: Option<String>,
    pub name: Option<String>,
    pub trigger_raw: Vec<u8>,
    pub trigger_rssi: Option<i16>,
    pub trigger_scanned_at_ms: i64,
    pub trigger_is_legacy: Option<bool>,
    pub trigger_primary_phy: Option<i32>,
    pub trigger_secondary_phy: Option<i32>,
    pub trigger_adv_sid: Option<i32>,
    pub session_id: Option<String>,
}

// ---- §2.5 Query results ----

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SessionStatus {
    Recording,
    Paused,
    Completed,
    Interrupted,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum UploadState {
    Pending,
    Uploaded,
    Failed,
    Empty,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SessionSummary {
    pub id: String,
    pub status: SessionStatus,
    pub started_ms: i64,
    pub ended_ms: Option<i64>,
    pub active_ms: i64,
    pub distance_m: f64,
    pub device_count: u32,
    pub upload: UploadState,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AllSessionsSummary {
    pub session_count: u32,
    pub device_count: u32,
    pub bbox: Option<Bbox>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SessionView {
    pub scope: Scope,
    pub live: bool,
    pub session_ids: Vec<String>,
    pub device_count: u32,
    pub frame: Option<Bbox>,
    pub route: Vec<GeoPoint>,
    pub routes_faint: bool,
    pub seq: u64,
}

/// C-5.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeviceRow {
    pub key: DeviceKey,
    pub addr: String,
    pub name: Option<String>,
    /// The matched page (§3.5); `None` = unidentified.
    pub page: Option<PageRef>,
    /// The page's categories, in page order.
    pub categories: Vec<String>,
    pub traits: Vec<String>,
    /// The page has a decoder.js.
    pub readable: bool,
    /// The page links a vulnerability page.
    pub vulnerable: bool,
    /// The page's product icon (asset path, §3.4); `None` → the category icon.
    pub icon: Option<String>,
    /// Live: current; frozen: strongest.
    pub rssi: Option<i16>,
    /// Live: not stale; frozen: true.
    pub in_range: bool,
    /// Live only: 30 × 2 s buckets (C-6).
    pub spark: Option<Vec<u8>>,
    pub best: Option<GeoPoint>,
    pub last_seen_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ProductCard {
    /// Page id or "unidentified".
    pub id: String,
    pub title: String,
    pub kind: Option<PageKind>,
    pub icon: Option<String>,
    pub live: bool,
    pub count: u32,
    pub in_range: u32,
    pub last_seen_ms: i64,
}

/// Cards inside a group come in bundle page order (the initial order); the UI keeps that and appends new ones (G-9).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CategoryGroup {
    pub category: String,
    pub label: String,
    pub products: Vec<ProductCard>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct IconCredit {
    pub source: String,
    pub license: String,
    pub author: Option<String>,
}

/// D-6, D-7.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ProductDetail {
    pub page: Option<PageRef>,
    pub title: String,
    pub one_line: Option<String>,
    pub categories: Vec<String>,
    pub traits: Vec<String>,
    pub readable: bool,
    pub vulnerabilities: Vec<PageRef>,
    pub wiki_url: Option<String>,
    /// C-7/D-7 hero image; the credit is shown with it.
    pub icon: Option<String>,
    pub icon_credit: Option<IconCredit>,
    /// Distinct z17 tiles of the devices' best points (scope, filter).
    pub places: u32,
    pub last_seen_ms: Option<i64>,
    pub device_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AdvFormat {
    Legacy,
    Extended,
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AdStructure {
    pub len: u8,
    pub ad_type: u8,
    pub data: Vec<u8>,
}

/// Mirrors `kb-interface` (DECODER_INTERFACE_VERSION 1); always `None` in v2.0 (issue #39).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DecoderOutput {
    pub fields: Vec<DecodedField>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DecodedField {
    pub name: String,
    pub value: FieldValue,
    pub unit: Option<String>,
    pub lookup: Option<String>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum FieldValue {
    Bool { v: bool },
    Number { v: f64 },
    Text { v: String },
}

/// D-9, D-12; from the device's latest payload in scope.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeviceDetail {
    pub key: DeviceKey,
    pub addr: String,
    pub name: Option<String>,
    pub page: Option<PageRef>,
    pub categories: Vec<String>,
    pub traits: Vec<String>,
    pub readable: bool,
    pub vulnerable: bool,
    pub vulnerabilities: Vec<PageRef>,
    pub wiki_url: Option<String>,
    pub icon: Option<String>,
    pub rssi: Option<i16>,
    pub in_range: bool,
    pub live: bool,
    pub connectable: Option<bool>,
    pub format: AdvFormat,
    /// D-12 Details "Company".
    pub company_id: Option<u16>,
    pub company_name: Option<String>,
    pub service_uuid: Option<String>,
    pub tx_power: Option<i16>,
    pub structures: Vec<AdStructure>,
    pub tail: Option<Vec<u8>>,
    /// Always `None` in v2.0; filled by issue #39 without an API change.
    pub decoded: Option<DecoderOutput>,
    pub last_seen_ms: i64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Sighting {
    pub at: GeoPoint,
    pub rssi: i16,
    pub seen_ms: i64,
}

/// D-10: 60 one-second buckets ending at `end_ms`; one byte per bucket, −RSSI clamped to 30..100, 0 = gap.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct LiveChart {
    pub key: DeviceKey,
    pub end_ms: i64,
    pub samples: Vec<u8>,
    pub rssi: Option<i16>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum MapMode {
    Points,
    Cells,
}

/// `geojson`: Points `{addr, rssi}`, with `addr` in the uppercase colon form `AA:BB:CC:DD:EE:FF`; Cells
/// `{point_count, point_count_abbreviated}`.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct MapData {
    pub mode: MapMode,
    pub count: u32,
    pub geojson: String,
    pub seq: u64,
}

// ---- §2.6 Live deltas ----

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct LiveDelta {
    pub session_id: String,
    pub seq: u64,
    pub at_ms: i64,
    pub state: RecordingState,
    /// Per-key value deltas, only changed keys.
    pub changed: Vec<DeviceLive>,
    /// New (or newly shown) devices, arrival order.
    pub appended: Vec<DeviceRow>,
    /// Changed or new cards; a new id is appended by the UI.
    pub products: Vec<ProductCard>,
    pub route_appended: Vec<GeoPoint>,
    pub fix: Option<FixSummary>,
    /// The focused key.
    pub chart: Option<LiveChart>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeviceLive {
    pub key: DeviceKey,
    pub rssi: Option<i16>,
    pub in_range: bool,
    pub spark: Vec<u8>,
    /// Some only when changed (never reverts).
    pub name: Option<String>,
    /// Some only when changed; with it the row's flags and categories are re-read.
    pub page: Option<PageRef>,
    /// Some only when the strongest point moved.
    pub best: Option<GeoPoint>,
}

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct FixSummary {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
    pub age_ms: i64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum CoreEvent {
    RecordingChanged {
        state: RecordingState,
    },
    /// A session finished, was deleted or interrupted.
    SessionsChanged,
    /// Display filter, re-match done, delete: re-query the open scope.
    DataInvalidated {
        reason: String,
    },
    /// A payload or GATT file was sealed.
    UploadNeeded,
    /// Also covers persistence failures.
    StorageWarning {
        message: String,
    },
    /// SPEC R-10: writing stopped and the recording paused; `dropped_scans` buffered rows could not be stored.
    StorageFull {
        free_bytes: u64,
        dropped_scans: u64,
    },
}

/// SPEC R-10: the space each storage-full action would free, and the free space now.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Reclaimable {
    pub free_bytes: u64,
    /// `delete_uploaded_raw`: uploaded raw files (`archive/`).
    pub uploaded_raw_bytes: u64,
    /// `clear_local_database`: an estimate; the call returns what it actually freed.
    pub local_database_bytes: u64,
}

// ---- §2.7 GATT view ----

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GattStepKind {
    Connecting,
    DiscoveringServices,
    ReadingCharacteristics,
    Disconnected,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GattStepState {
    Done,
    Running,
    Failed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GattFailure {
    TimedOut,
    DisconnectedUnexpectedly,
    ConnectionFailed,
    Cancelled,
    Other { status: i32 },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattStep {
    pub kind: GattStepKind,
    pub state: GattStepState,
    pub duration_ms: Option<u32>,
    pub services: Option<u32>,
    pub read: Option<u32>,
    pub readable: Option<u32>,
    pub failure: Option<GattFailure>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattView {
    pub discovery_id: String,
    pub key: DeviceKey,
    pub started_ms: i64,
    pub ok: bool,
    pub steps: Vec<GattStep>,
    pub services: Vec<GattServiceView>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattServiceView {
    pub uuid: String,
    pub name: Option<String>,
    pub characteristics: Vec<GattCharView>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattCharView {
    pub uuid: String,
    pub name: Option<String>,
    pub properties: Vec<String>,
    /// Display-only: UTF-8 text, Battery Level "78 %", Appearance (SIG), else hex.
    pub value_text: Option<String>,
}

// ---- §2.8 Storage, export, delete, upload ----

/// "Map cache" comes from `:map` (`MapCacheInfo`), not the core.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct StorageStats {
    /// `local.db` + `-wal` + `-shm`.
    pub database_bytes: u64,
    /// Pending + archive Parquet and gatt JSON, pre-v2 files included.
    pub raw_files_bytes: u64,
    /// Not uploaded yet (the delete warning).
    pub pending_count: u32,
    pub pending_bytes: u64,
    pub session_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
#[uniffi(name = "ExportFormatV2")]
pub enum ExportFormat {
    ParquetRaw,
    Csv,
    SqliteSnapshot,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ExportRequest {
    pub scope: Scope,
    pub format: ExportFormat,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
#[uniffi(name = "PreparedExportV2")]
pub struct PreparedExport {
    pub export_id: String,
    pub local_path: String,
    pub suggested_file_name: String,
    pub file_count: u64,
    pub size_bytes: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum DeleteOutcome {
    Deleted { sessions: u32 },
    DeletedPendingKept { pending: u32 },
    Refused { reason: String },
}

#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct UploadStatus {
    pub pending_count: u32,
    pub pending_bytes: u64,
    pub failed_count: u32,
    pub last_error: Option<String>,
}
