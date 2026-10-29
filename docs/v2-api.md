# Cluetooth Android v2: frozen interface (step 0)

This is the contract that the five v2 lanes build against. The product is defined by
`cluetooth-android/design/ui-v2/SPEC.md` (rule IDs such as `S-8` refer to it) and the HTML prototype next to it. This file
defines how the app is cut into components, what crosses each boundary, and who owns which files.

- **Frozen** means a lane may not change a signature here on its own. A change needs a PR to this file that the owner
  approves, and every lane that consumes the interface must review it.
- **Additive** changes (a new optional field with a default, or a new query) are allowed through the same PR, without
  re-freezing.
- **Owner decisions** recorded at the top of the task are not reopened here. They are restated where they shape an
  interface:
  - a fresh Compose UI;
  - a thin Kotlin layer;
  - no live mode;
  - a new Rust-side SQLite;
  - unchanged upload formats;
  - wiki pages in the browser;
  - Q-1 search in Rust;
  - UI-side list order;
  - session scopes (a list of sessions or All, with filters).

Contents:

1. [Architecture](#1-architecture)
2. [Rust core API](#2-rust-core-api)
3. [Local DB schema](#3-local-db-schema)
4. [Kotlin platform ↔ core](#4-kotlin-platform--core)
5. [UI state models](#5-ui-state-models)
6. [Lanes](#6-lanes)
7. [Open questions](#7-open-questions)

---

## 1. Architecture

### 1.1 Components

```
┌──────────────────────────── app process (edu.ucsd.sysnet.cluetoothscanner) ────────────────────────────┐
│                                                                                                         │
│  :app  MainActivity ── MainViewModel (session scope, sheet stack, search, layout mode, back order)       │
│     │        │                                                                                          │
│     │   ┌────┴───────────┬──────────────────┬───────────────────┐                                        │
│     │   :chrome (C)      :sheets (D)        :map (E)            :designsystem (C)                       │
│     │   recorder, side-  sheet stack,       MapLibre, style     tokens, type, icons,                    │
│     │   bar, search,     Discover/product/  layers, camera,     C-1…C-17 components                     │
│     │   settings, onb.   device sheets      selection, area                                             │
│     │        └───────────┴──────────┬───────┴───────────────────┘                                        │
│     │                 :core-api (A)  │  :platform-api (B)      ← pure-Kotlin interfaces + models       │
│     │                       │        │        │                                                          │
│     │                 :core-bridge (A)       :platform (B)                                               │
│     │                 UniFFI glue ◄──────────── BLE scanner, location, RecordingService (FGS),           │
│     │                       │      ingest      GATT runner, permissions, notifications,                  │
│     │                       │                  settings (DataStore), upload (WorkManager + Firebase)     │
│     │              libcluetooth_core.so (Rust, cluetooth-core/)                                          │
│     │              writer thread · notify thread · read pool                                             │
│     │              SQLite+R*Tree (local.db) · Parquet v3 writer · upload queue · matcher · export       │
└─────┴───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Rust core** (`cluetooth-core/`, lane A). It does all the heavy lifting:
  - session lifecycle including pause;
  - ingest of scan observations, location fixes and GATT results;
  - Parquet v3 rotation, sealing and encryption (unchanged), and the upload queue;
  - the new local DB, rule matching (wiki bundle), and every UI query;
  - the live state and live deltas;
  - search (Q-1), export, delete, and storage stats.
- **Kotlin platform** (`:platform`, lane B). Only what needs Android:
  - BLE scanning and GATT, and location;
  - the foreground recording service and notifications;
  - permissions and Bluetooth enablement;
  - settings storage, and the Firebase upload transport through WorkManager.
- **UI** (`:designsystem`, `:chrome`, `:sheets`, `:map`, `:app`; lanes C, D and E plus integration). This is Jetpack Compose,
  one adaptive UI (G-2). It reads data only through `:core-api` and `:platform-api`.

### 1.2 Processes and recording survival

There is **one process**. The recording must survive the UI going away. Here is how:

1. **Recording is not owned by the UI.** The Rust `Core` handle, the `Recorder` (B) and the scanner and location
   clients are process singletons created in `CluetoothApplication.onCreate`. They are not tied to an Activity,
   ViewModel or composition. The UI only observes them (`StateFlow`, live deltas) and sends commands.
2. **`RecordingService` is a started foreground service.**
   - Its type is `location|connectedDevice`, it calls `startForeground` within 5 s, and it keeps the default
     `stopWithTask="false"`. `Recorder.start()` starts it with `startForegroundService` while the Activity is visible
     (R-2, V-6 are always foreground starts, so FGS and while-in-use location restrictions are met).
   - The service holds the scan registration, the location request and a partial wake lock bounded at 12 h, as
     today.
   - The service has no reference to UI objects. With the Activity destroyed, the task swiped away, or the
     configuration changed, the process keeps foreground-service priority, and scanning, ingest, the Parquet writer
     and the DB keep running.
3. **The UI is disposable.** When `MainActivity` comes back, the UI rebuilds from queries plus a fresh live
   subscription (snapshot + deltas, §2.6). The ViewModel never caches state that the core or platform owns.
4. **UI memory is released when hidden.** On `onStop`, `:map` pauses MapLibre (`onStop`/`onLowMemory`), and on
   `onTrimMemory(UI_HIDDEN)` the app drops query caches. This keeps a budget phone's low-memory killer from
   preferring our foreground-service process.
5. **Process death is the only thing that stops a recording.** The causes are an OEM killer, OOM, or a crash.
   Because the process is gone, it is made durable and visible:
   - the core commits SQLite every tick (≤ 1 s, WAL, `synchronous=NORMAL`);
   - the core appends the Parquet staging WAL every `durability_interval_ms` (default 5 s), driven by the core's
     timer and no longer by Kotlin;
   - on the next start, `Core::open` marks the open session `Interrupted` at its last data point, and `:platform`
     posts the one-time "Recording interrupted" notice, as today.
6. **No automatic restart.** The service stays `START_NOT_STICKY`. A sticky restart from the background may not
   start a location foreground service, and it would not get while-in-use location (Android 12+/14+). It would record
   rows without positions, so the interrupted-session model is kept. A separate `:recorder` process is listed in §7.

### 1.3 Threads

| Thread | Owner | Does |
|---|---|---|
| main | Android | Compose, MapLibre, scan callbacks (posted off immediately) |
| `scan-batcher` | B: `Dispatchers.IO.limitedParallelism(1)` | builds batches of ≤ 64 observations / 250 ms and calls `ingest_scans` |
| `ct-writer` | A: Rust `std::thread` | the only DB writer and the Parquet writer; drains the ingest queue; 1 s tick: commit, live snapshot, delta |
| `ct-notify` | A | calls `CoreListener.on_live` / `on_event`; coalesces while Kotlin is busy |
| caller threads | UI coroutines on `Dispatchers.IO` | read queries on a pooled read connection (2 connections, WAL) or the live snapshot |
| WorkManager worker | B | `prepare_upload` → Firebase `putFile` → `mark_upload_*` |
| GATT runner | B: one coroutine on `Dispatchers.IO` + binder callbacks | at most one discovery at a time |

Rules:

- No core call may run on the main thread. `:core-bridge` exposes `suspend` functions that switch to
  `Dispatchers.IO`.
- Ingest calls only enqueue (§2.4).
- Queries never take the writer's lock. They read either SQLite snapshots (WAL readers) or the immutable live
  snapshot (`Arc`), which the writer swaps every tick.
- Upload, export and delete take the core's file lock. That lock is separate from the writer, so a slow encryption
  never stalls ingest. Today's single `Mutex<CoreInner>`, which runs Parquet I/O under the lock, is split by lane A.

### 1.4 Data flow

```
ScanCallback ─► ScanObservationInput ─► batcher ─► Core.ingest_scans ─► queue ─► ct-writer
LocationCallback ─► LocationFix ───────────────────► Core.ingest_location ───┘   │
                                                                                  ├─► Parquet staging WAL → seal → pending/scans/v3 (unchanged)
                                                                                  ├─► ble_adv_split, dedup payload, match once (new payloads only)
                                                                                  ├─► minute aggregates, session_devices, devices, cells (commit per tick)
                                                                                  └─► live snapshot + LiveDelta (≤ 1/s) ─► ct-notify ─► CoreListener
GATT runner ─► gatt/v1 JSON ─► Core.ingest_gatt ─► pending/gatt/v1 (unchanged) + gatt tables
WorkManager ─► pending_uploads → prepare_upload → Firebase putFile → mark_upload_succeeded (plaintext → archive, kept)
UI ─► Core queries(Scope, …) ─► SQLite (read pool) or live snapshot ─► Kotlin models
```

### 1.5 Data directory

The data directory is `filesDir/cluetooth-core/`, the same as v3, so the in-place upgrade keeps the pending queue.

- **Kept as is:** `pending/`, `archive/`, `upload-cache/`, `upload-state/`, `active-payload/`, `export-temp/`.
- **Pre-v2 session JSON:** `sessions/*.json` stays readable. Those sessions are not imported into the DB.
- **New:** `local.db` (+ `-wal`, `-shm`), the v2 DB (§3), and `scope-cache.db`, a disposable cache of union scopes
  (§3.5) that is wiped on open.

First v2 launch:

1. `:platform` records the ids from `pending_uploads()` at that moment, as `legacy_pending` in DataStore.
2. The normal upload worker drains them.
3. When none of those ids is pending any more, `:platform` deletes `databases/local-data.db` and its `-wal`, `-shm`
   and `-journal` files. This is the v3 Kotlin store, which is not migrated.
4. The pre-0.0.5 JSONL uploader is ported unchanged.

---

## 2. Rust core API

UniFFI 0.32 with proc-macros, as today (`uniffi::setup_scaffolding!()`). The bindings are generated into package
`edu.ucsd.sysnet.cluetoothscanner.core.generated`, and `API_VERSION` becomes **8**. The new object is `Core`.

During the lanes, the v3 `CluetoothCore` object stays compiling so the old `:app` keeps building. Lane A may add but
not remove until integration, which deletes it (§6.6).

Notation is Rust proc-macro pseudocode. The tag after each call gives its threading model:

| Tag | Meaning | Kotlin |
|---|---|---|
| **[N]** | Non-blocking: memory only or an enqueue; returns in µs | any background thread |
| **[W]** | Applied by the writer; the call blocks until the writer has applied it (≤ 1 tick) | `Dispatchers.IO` |
| **[Q]** | Read query on the caller thread, from the read pool or the live snapshot; concurrent | `Dispatchers.IO` |
| **[F]** | File I/O under the file lock (upload, export, delete); may take seconds | `Dispatchers.IO` / worker |

### 2.1 Scope (owner input: multi-select and filters later)

**Every session-scoped query takes a `Scope`**, never a bare session id.

```rust
#[derive(uniffi::Enum)]
pub enum SessionScope {
    All,                                   // every session, including the current one
    Sessions { ids: Vec<String> },         // session UUIDs; one session = a one-element list
}

#[derive(uniffi::Record)]
pub struct SessionFilter {                 // selects which sessions of the scope take part; extensible
    #[uniffi(default = None)] pub started_from_ms: Option<i64>,  // session started at or after
    #[uniffi(default = None)] pub started_to_ms: Option<i64>,    // session started before
    // later: min duration, a place bbox, uploaded state … (additive fields with defaults)
}

#[derive(uniffi::Record)]
pub struct Scope {
    pub sessions: SessionScope,
    #[uniffi(default = None)] pub filter: Option<SessionFilter>,
}
```

The semantics are the same for every query:

- **Resolution.** The session set is `sessions ∩ filter`.
  - Empty sessions never take part (L-2).
  - A filter on `Sessions{ids}` narrows the list.
  - The filter selects whole sessions. It never cuts sightings inside a session.
- **Union, as in All sessions (V-5).**
  - Each device appears once, at its strongest point across the resolved sessions (M-3).
  - Its RSSI is the strongest seen, and its name is the latest non-empty name.
  - Its product follows the rule in §3.4, applied over the union.
  - Products, counts, the area and search are computed over the union.
  - Routes: a one-session scope has one route with Start and End. A multi-session scope, or `All`, draws every
    route faintly with no Start or End (M-8; `SessionView.routes_faint = true`).
- **Live.** A scope is live only when it is exactly `Sessions{[current_id]}` with no filter and that session is
  recording or paused (V-2). Every other scope is frozen (V-3, V-4, V-5), including `All` and unions that contain the
  current session: its queries return a snapshot as of the call.
- **Validation.** Unknown ids are ignored. A scope that resolves to no session returns empty results, not an
  error.
- **Execution.** The core materializes each distinct non-trivial scope once into `scope-cache.db` (§3.5) and caches
  up to 4. `All` without a filter and a single session use their own tables directly.

The first UI version only builds `Scope(All)` and `Scope(Sessions([id]))`. Multi-select and filters need no API
change.

### 2.2 Shared types

```rust
pub type DeviceKey = i64;  // the 48-bit MAC as an integer (AA:BB:CC:DD:EE:FF → 0xAABBCCDDEEFF)

#[derive(uniffi::Record)] pub struct GeoPoint { pub lon: f64, pub lat: f64 }
#[derive(uniffi::Record)] pub struct Bbox { pub west: f64, pub south: f64, pub east: f64, pub north: f64 }

/// A packed key list: little-endian i64s. :core-bridge exposes it as a LongArray (no boxing for 10^5 keys).
#[derive(uniffi::Record)] pub struct DeviceKeys { pub le_i64: Vec<u8>, pub seq: u64 }
// seq: the live sequence the result is consistent with (0 for frozen scopes); see §2.6

#[derive(uniffi::Record)]
pub struct DeviceFilter {                       // "filters only hide rows" (G-9)
    #[uniffi(default = None)] pub area: Option<Bbox>,          // D-4 This area: the best point inside
    #[uniffi(default = None)] pub category: Option<String>,    // C-3 chip; "unidentified" allowed
    #[uniffi(default = None)] pub product: Option<String>,     // product sheet; "unidentified" allowed
    #[uniffi(default = false)] pub in_range_only: bool,        // Nearby (live scopes only)
}

#[derive(uniffi::Record)] pub struct Category { pub id: String, pub label: String, pub icon: String }
// ids/icons: unidentified/unknown, audio/headphones, lighting/bulb, sensors/thermo, trackers/tag,
// wearables/watch, vehicles/car, medical/medical (C-3 order; §7 Q1 on mapping wiki categories)

#[derive(uniffi::Record)] pub struct ProductRef { pub id: String, pub name: String, pub category: String }
```

Display filter (G-12):

```rust
#[derive(uniffi::Record)] pub struct DisplayFilter { pub hide_unnamed_apple: bool }
```

- The filter is core state, set by `:platform` from the setting.
- It applies to every list, count, map payload and delta. It never affects ingest, Parquet or upload.

### 2.3 Lifecycle and recording

```rust
#[derive(uniffi::Object)] pub struct Core { /* … */ }

#[derive(uniffi::Record)]
pub struct CoreConfig {
    pub data_directory: String,
    pub recipient_public_key: Vec<u8>,          // 32 bytes, as today
    pub location_max_age_ms: u64,               // 5000: a fix younger than this is attached to a sighting
    pub payload_max_rows: u32, pub payload_max_estimated_bytes: u64, pub payload_max_age_ms: u64, // 100k / 10 MiB / 5 min
    pub durability_interval_ms: u64,            // 5000: Parquet staging WAL append
    pub stale_after_ms: u64,                    // 20000: a live device is "not in range" after this (C-5, D-2)
    pub live_interval_ms: u64,                  // 1000: delta cadence; minimum 1000
}

#[derive(uniffi::Record)]
pub struct CoreAssets {
    pub wiki_bundle_json: String,               // assets/wiki-bundle.json (schema 1)
    pub sig_numbers_tsv: String,                // assets/sig-numbers.tsv (company, uuid16, appearance, adtype); §7 Q4
}

#[derive(uniffi::Record)]
pub struct ScanEnvironment { /* unchanged from v3: device_model, android_release, android_sdk, app_version,
                                cap_ext_adv, cap_coded_phy, cap_max_adv_len, scan_phy, scan_legacy_only */ }

#[derive(uniffi::Enum)] pub enum RecordingPhase { Idle, Recording, Paused }

#[derive(uniffi::Record)]
pub struct RecordingState {
    pub phase: RecordingPhase,
    pub session_id: Option<String>,
    pub started_ms: Option<i64>,
    pub active_before_ms: i64,                  // active time accumulated before the current run
    pub running_since_ms: Option<i64>,          // wall clock of the last start/resume; None while paused
    pub device_count: u32,                      // shown devices (display filter applied)
    pub distance_m: f64,
}
// elapsed = active_before_ms + (now − running_since_ms); the UI ticks it locally (R-3)

#[uniffi::export]
impl Core {
    #[uniffi::constructor]
    pub fn open(config: CoreConfig, assets: CoreAssets) -> Result<Arc<Core>, CoreError>;
    // [F] Opens or migrates local.db, closes any session left open (status Interrupted), reconciles uploads,
    //     seals a recovered staging WAL, starts ct-writer/ct-notify, schedules a re-match if the rules key changed.
    pub fn close(&self);                                             // [W] flush, seal, stop threads
    pub fn api_version(&self) -> u32;                                // [N] 8

    pub fn set_listener(&self, listener: Option<Arc<dyn CoreListener>>);  // [N]
    pub fn set_display_filter(&self, filter: DisplayFilter);              // [N] → CoreEvent::DataInvalidated
    pub fn set_live_focus(&self, key: Option<DeviceKey>);                 // [N] the device whose chart rides on deltas (D-10)

    pub fn start_recording(&self, env: ScanEnvironment) -> Result<String, CoreError>;  // [W] new session UUIDv7; error if not Idle
    pub fn pause_recording(&self) -> Result<(), CoreError>;          // [W] R-6; ingest is ignored while paused
    pub fn resume_recording(&self) -> Result<(), CoreError>;         // [W] same session (no new session on resume)
    pub fn stop_recording(&self) -> Result<String, CoreError>;       // [W] seals the payload, simplifies the route,
                                                                     //     finalizes counts; returns the session id (R-7)
    pub fn configure_scan(&self, env: ScanEnvironment) -> Result<(), CoreError>; // [W] footer change mid-session
                                                                     //     (e.g. Coded PHY toggled): seals the active payload
    pub fn recording_state(&self) -> RecordingState;                 // [N]
}
```

Notes:

- The environment goes into `start_recording`, which removes v3's hidden "`configure_scan` first" ordering.
- Pause and resume are recorded as intervals in `session_pauses`. Active time excludes pauses.
- `stop_recording` returns once the session is finalized. The `ScheduleUpload` event follows.

### 2.4 Ingest

```rust
#[derive(uniffi::Record)]
pub struct ScanObservationInput { /* unchanged from v3 (scan.rs): addr, rssi, scanned_at_ms, elapsed_realtime_nanos,
                                     raw, local_name, tx_power, is_connectable, is_legacy, primary_phy, secondary_phy,
                                     adv_sid, adv_tx_power, periodic_interval, data_status, addr_type */ }
#[derive(uniffi::Record)]
pub struct LocationFix { pub lat: f64, pub lon: f64, pub accuracy_meters: f64,
                         pub observed_at_ms: i64, pub elapsed_realtime_nanos: u64 }   // unchanged

#[derive(uniffi::Record)]
pub struct IngestAck { pub accepted: u32, pub rejected: u32, pub ignored_not_recording: u32 }

impl Core {
    pub fn ingest_scans(&self, batch: Vec<ScanObservationInput>) -> Result<IngestAck, CoreError>;
    // [N] Validates (v3 rules: ≤ 64 rows, ≤ 1 MiB, raw ≤ 1650 B) and enqueues. Blocks at most 1 s when the writer
    //     queue (256 batches) is full, then returns CoreError::Backpressure; the caller keeps the batch and retries.
    pub fn ingest_location(&self, fix: LocationFix);                 // [N] fix ring + route (accuracy ≤ 100 m, ≥ 5 m step or 30 s)
    pub fn clear_location(&self);                                    // [N] location lost: no fix attached any more

    pub fn new_gatt_discovery_id(&self) -> Result<String, CoreError>;            // [N] UUIDv7
    pub fn gatt_target(&self, key: DeviceKey) -> Option<GattTarget>;              // [Q] replaces Kotlin's trigger cache
    pub fn ingest_gatt(&self, gatt_v1_json: String) -> Result<GattView, CoreError>;
    // [W] Validates gatt/v1 (unchanged schema), stores pending/gatt/v1 (unchanged), parses it into
    //     gatt_discoveries/gatt_attributes, returns the view the device sheet shows. Idempotent per id.
}

#[derive(uniffi::Record)]
pub struct GattTarget {                      // what the gatt/v1 "target" needs, from the device's latest payload
    pub addr: String, pub addr_type: Option<String>, pub name: Option<String>,
    pub trigger_raw: Vec<u8>, pub trigger_rssi: Option<i16>, pub trigger_scanned_at_ms: i64,
    pub trigger_is_legacy: Option<bool>, pub trigger_primary_phy: Option<i32>,
    pub trigger_secondary_phy: Option<i32>, pub trigger_adv_sid: Option<i32>,
    pub session_id: Option<String>,          // the recording session, if any (gatt/v1 session_id)
}
```

The writer's work per observation:

1. Validate, then stage the Parquet row (lat/lon from the fix ring, unchanged).
2. Split the raw bytes with `ble_adv_split` only for a new `sha256` (an LRU of 50k hashes, then the DB).
3. A new payload inserts its structures and runs the matcher once.
4. Update the in-memory open-minute aggregates, the session devices and the live rings.
5. Every tick (`live_interval_ms`), in one transaction:
   - upsert the touched `sighting_minutes`, `session_devices`, `devices`, `device_cells` and `product_totals`;
   - add the route points;
   - update session counters.
6. Then swap the live snapshot and emit the delta.

### 2.5 Queries (SPEC B)

```rust
impl Core {
    pub fn categories(&self) -> Vec<Category>;                                     // [N] C-3 order, Unidentified first
    pub fn sessions(&self) -> Result<Vec<SessionSummary>, CoreError>;              // [Q] finished, newest first, no empty (L-2)
    pub fn current_session(&self) -> Option<SessionSummary>;                       // [N]
    pub fn all_sessions_summary(&self) -> Result<AllSessionsSummary, CoreError>;   // [Q] O(1) counters
    pub fn session_view(&self, scope: Scope) -> Result<SessionView, CoreError>;    // [Q] getRecording without points
    pub fn routes(&self, scope: Scope, viewport: Option<Bbox>, zoom: f64) -> Result<String, CoreError>;
        // [Q] GeoJSON FeatureCollection of LineStrings for the "route" source, simplified for zoom;
        //     multi-session scopes: only routes whose bbox meets the viewport (sessions_rtree)
    pub fn device_order(&self, scope: Scope) -> Result<DeviceKeys, CoreError>;
        // [Q] every shown device of the scope, sorted ONCE by RSSI desc (live: current reading; frozen: strongest),
        //     ties by key. The UI keeps this as its order state (G-9).
    pub fn device_keys(&self, scope: Scope, filter: DeviceFilter) -> Result<DeviceKeys, CoreError>;
        // [Q] the keys passing the filter, in device_order order. The UI intersects with its own order.
    pub fn device_rows(&self, scope: Scope, keys: Vec<DeviceKey>) -> Result<Vec<DeviceRow>, CoreError>;
        // [Q] rows for a window of keys (LazyColumn paging, ≤ 200 per call), in the given order; unknown keys skipped
    pub fn products(&self, scope: Scope, filter: DeviceFilter) -> Result<Vec<CategoryGroup>, CoreError>;
        // [Q] getProducts: Nearby = in_range_only; This area = area. category/product fields are ignored.
    pub fn product(&self, scope: Scope, product_id: String, area: Option<Bbox>) -> Result<Option<ProductDetail>, CoreError>;
        // [Q] getProduct; its devices: device_keys(scope, {product, area}) + device_rows
    pub fn device(&self, scope: Scope, key: DeviceKey) -> Result<Option<DeviceDetail>, CoreError>;  // [Q] getDevice
    pub fn device_sightings(&self, scope: Scope, key: DeviceKey) -> Result<Vec<Sighting>, CoreError>;
        // [Q] getDeviceSightings: one located point per sighting minute (the minute's best sample), ≤ 10 000, newest kept
    pub fn device_chart(&self, key: DeviceKey) -> Option<LiveChart>;               // [N] D-10, live only
    pub fn device_gatt(&self, key: DeviceKey) -> Result<Option<GattView>, CoreError>;  // [Q] the last discovery (D-13.7)
    pub fn search(&self, scope: Scope, query: String, area: Option<Bbox>) -> Result<Option<DeviceKeys>, CoreError>;
        // [Q] Q-1/Q-2: None for a blank query; matches in device_order order; area = best point inside
    pub fn map_points(&self, scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: f64) -> Result<MapData, CoreError>;
        // [Q] the "devices"/"density" sources for the viewport (+50 % margin); never the whole scope (§3.6)
}

#[uniffi::export] pub fn match_device(query: String, name: Option<String>, addr: String) -> bool;  // [N] Q-1, pure
```

Result records (each maps 1:1 to the Kotlin model in §5.1):

```rust
#[derive(uniffi::Enum)] pub enum SessionStatus { Recording, Paused, Completed, Interrupted }
#[derive(uniffi::Enum)] pub enum UploadState { Pending, Uploaded, Failed, Empty }

#[derive(uniffi::Record)]
pub struct SessionSummary {            // L-2 row: "23 min · 1.8 km · 214 devices"; "Started 7:12 PM · 26 devices"
    pub id: String, pub status: SessionStatus, pub started_ms: i64, pub ended_ms: Option<i64>,
    pub active_ms: i64, pub distance_m: f64, pub device_count: u32, pub upload: UploadState,
}
#[derive(uniffi::Record)]
pub struct AllSessionsSummary { pub session_count: u32, pub device_count: u32, pub bbox: Option<Bbox> }

#[derive(uniffi::Record)]
pub struct SessionView {               // getRecording(id) / "all", minus points
    pub scope: Scope, pub live: bool, pub session_ids: Vec<String>,   // resolved sessions
    pub device_count: u32, pub frame: Option<Bbox>,                   // M-10: route + best points
    pub route: Vec<GeoPoint>,          // one-session scope: ≤ 2048 simplified points; empty otherwise (use routes())
    pub routes_faint: bool,            // M-8: true for multi-session scopes and All
    pub seq: u64,
}

#[derive(uniffi::Record)]
pub struct DeviceRow {                 // C-5
    pub key: DeviceKey, pub addr: String, pub name: Option<String>,
    pub product: Option<ProductRef>, pub category: String,           // "unidentified" without a product
    pub company_id: Option<u16>, pub apple_unnamed: bool,
    pub rssi: Option<i16>,             // live: current reading; frozen: strongest
    pub in_range: bool,                // live: not stale; frozen: always true (stale only dims live rows)
    pub spark: Option<Vec<u8>>,        // live only: 30 samples over the last 60 s (C-6), encoding below
    pub best: Option<GeoPoint>, pub last_seen_ms: i64,
}

#[derive(uniffi::Record)]
pub struct ProductCard {               // C-7
    pub id: String, pub name: String, pub category: String,
    pub live: bool, pub count: u32, pub in_range: u32, pub last_seen_ms: i64,
}
#[derive(uniffi::Record)] pub struct CategoryGroup { pub category: Category, pub products: Vec<ProductCard> }
// Groups in C-3 category order with "unidentified" last as one card (D-2). Cards inside a group come in bundle
// page order: the initial order. The UI keeps it and appends new cards (G-9).

#[derive(uniffi::Record)]
pub struct ProductDetail {             // D-6, D-7
    pub id: String, pub identified: bool, pub name: String, pub category: String,
    pub description: Option<String>,   // bundle page one_line; "Devices no wiki family matches yet." for unidentified
    pub wiki_url: Option<String>,      // https://wirelesswiki.org/p/<id>
    pub places: u32,                   // distinct z17 cells of the devices' best points in scope (and area)
    pub last_seen_ms: Option<i64>, pub device_count: u32,
}

#[derive(uniffi::Enum)] pub enum AdvFormat { Legacy, Extended, Unknown }
#[derive(uniffi::Record)] pub struct AdStructure { pub len: u8, pub ad_type: u8, pub data: Vec<u8> }

#[derive(uniffi::Record)]
pub struct DeviceDetail {              // D-9, D-12; from the device's latest payload in scope
    pub key: DeviceKey, pub addr: String, pub name: Option<String>,
    pub product: Option<ProductRef>, pub wiki_url: Option<String>,
    pub rssi: Option<i16>, pub in_range: bool, pub live: bool,
    pub connectable: Option<bool>, pub format: AdvFormat,
    pub company_id: Option<u16>, pub company_name: Option<String>,   // SIG table
    pub service_uuid: Option<String>,  // first service / service-data UUID (16-bit uppercase "FE95", 128-bit lowercase)
    pub tx_power: Option<i16>,         // AD 0x0A
    pub structures: Vec<AdStructure>,  // D-12 raw table, order and duplicates kept
    pub tail: Option<Vec<u8>>,         // non-zero remainder after the last structure (ble_adv_split), if any
    pub last_seen_ms: i64,
}

#[derive(uniffi::Record)] pub struct Sighting { pub at: GeoPoint, pub rssi: i16, pub seen_ms: i64 }
#[derive(uniffi::Record)] pub struct LiveChart { pub key: DeviceKey, pub end_ms: i64, pub samples: Vec<u8>, pub rssi: Option<i16> }
// D-10: 60 one-second buckets ending at end_ms; the end marker uses rssi (= the row's dBm number)

#[derive(uniffi::Enum)] pub enum MapMode { Points, Cells }
#[derive(uniffi::Record)]
pub struct MapData {
    pub mode: MapMode, pub count: u32,  // devices represented
    pub geojson: String,               // FeatureCollection. Points: {addr, rssi}. Cells: {point_count, point_count_abbreviated}
    pub seq: u64,
}
```

Encoding of `spark` and chart `samples`:

- One byte per bucket, with value = −RSSI clamped to 30..100 (C-6 y range). **0 is a gap.**
- A spark bucket is 2 s: the max of its two 1 s buckets.
- A 1 s bucket holds the max RSSI received in that second.

Strings shown in the UI come from the UI layer (`:chrome`, `:sheets`). The core returns numbers, ids and raw names
only, never formatted sentences. The one exception is GATT values (§2.7).

### 2.6 Live deltas

```rust
#[uniffi::export(callback_interface)]
pub trait CoreListener: Send + Sync {
    fn on_live(&self, delta: LiveDelta);   // called on ct-notify; may block (core coalesces meanwhile)
    fn on_event(&self, event: CoreEvent);
}

#[derive(uniffi::Record)]
pub struct LiveDelta {
    pub session_id: String,
    pub seq: u64,                          // +1 per delivered delta, per session; starts at 1
    pub at_ms: i64,
    pub state: RecordingState,             // device_count, distance, pause
    pub changed: Vec<DeviceLive>,          // per-key value deltas, only keys whose shown values changed
    pub appended: Vec<DeviceRow>,          // devices new to the session (or newly shown), arrival order
    pub products: Vec<ProductCard>,        // changed or new cards (by id); a new id is appended by the UI
    pub route_appended: Vec<GeoPoint>,
    pub fix: Option<FixSummary>,           // R-4 "lat, lon ±N m"
    pub chart: Option<LiveChart>,          // the focused key (set_live_focus), full 60 buckets
}

#[derive(uniffi::Record)]
pub struct DeviceLive {
    pub key: DeviceKey,
    pub rssi: Option<i16>, pub in_range: bool, pub spark: Vec<u8>,   // always sent for a changed key
    pub name: Option<String>,              // Some only when it changed (names never revert to None)
    pub product: Option<ProductRef>,       // Some only when it changed
    pub best: Option<GeoPoint>,            // Some only when the strongest point moved (map update)
}
#[derive(uniffi::Record)] pub struct FixSummary { pub lat: f64, pub lon: f64, pub accuracy_m: f64, pub age_ms: i64 }

#[derive(uniffi::Enum)]
pub enum CoreEvent {
    RecordingChanged { state: RecordingState },
    SessionsChanged,                       // a session finished, was deleted or interrupted: re-query lists
    DataInvalidated { reason: String },    // display filter change, re-match done, delete: re-query the open scope
    UploadNeeded,                          // a payload or GATT file was sealed
    StorageWarning { message: String },
    PersistenceDegraded { message: String },
    RematchProgress { done: u64, total: u64 },
}
```

**Shape.** The delta carries per-key value updates plus appends. There are never removals: a device never leaves a
session. A newly named Apple device becomes shown and arrives in `appended`.

**Rate.** One delta per `live_interval_ms` (1 s, never faster) while the scope's session is recording and something
changed. Staleness transitions count as changes, so the 1 s tick also emits pure `in_range` flips.

- While paused, deltas stop after the one carrying `state.phase = Paused`.
- With no listener set, nothing is built.

**Coalescing.** While `on_live` is still running, the writer merges the next ticks into one pending delta. The merge
keeps the latest values per key, concatenates appends and keeps one `seq`, so Kotlin never sees a gap and the core
never queues more than one delta.

**Snapshot protocol.** The UI subscribes first, then queries (`session_view`, `device_order`, `products`). It ignores
deltas whose `seq` ≤ the snapshot's `seq`, and applies later ones in order.

**Budget.** At 300 devices in range, about 300 × (30 B spark + 20 B) ≈ 15 KB/s through UniFFI. The map applies `best`
changes at most every 5 s (lane E throttle).

### 2.7 GATT view (D-13)

```rust
#[derive(uniffi::Enum)] pub enum GattStepKind { Connecting, DiscoveringServices, ReadingCharacteristics, Disconnected }
#[derive(uniffi::Enum)] pub enum GattStepState { Done, Running, Failed }
#[derive(uniffi::Enum)] pub enum GattFailure { TimedOut, DisconnectedUnexpectedly, ConnectionFailed, Cancelled, Other { status: i32 } }

#[derive(uniffi::Record)]
pub struct GattStep {
    pub kind: GattStepKind, pub state: GattStepState,
    pub duration_ms: Option<u32>,          // Connecting detail "0.8 s"
    pub services: Option<u32>,             // "N services"
    pub read: Option<u32>, pub readable: Option<u32>,   // "m/n"
    pub failure: Option<GattFailure>,
}
#[derive(uniffi::Record)]
pub struct GattView {
    pub discovery_id: String, pub key: DeviceKey, pub started_ms: i64,
    pub ok: bool, pub steps: Vec<GattStep>, pub services: Vec<GattServiceView>,
}
#[derive(uniffi::Record)] pub struct GattServiceView { pub uuid: String, pub name: Option<String>, pub characteristics: Vec<GattCharView> }
#[derive(uniffi::Record)]
pub struct GattCharView {
    pub uuid: String, pub name: Option<String>,
    pub properties: Vec<String>,           // gatt/v1 property names, in gatt/v1 order
    pub value_text: Option<String>,        // display-only decode: UTF-8 text, Battery Level "78 %",
                                           // Appearance name (SIG), else lowercase hex; None = no value
}
```

`steps` is derived from the stored discovery as follows:

- `stage_reached`, `error` and `timings_ms` give the steps; MTU is folded into Connecting.
- `outcome = complete` gives `ok = true`.
- `partial` and `failed` stop at the failed step. `cancelled` is not shown as a result: D-13.8 cancels silently.

Failure mapping:

| gatt/v1 | GattFailure |
|---|---|
| `disconnect.initiator = timeout` or status −1 | `TimedOut` |
| `initiator = remote` after connect | `DisconnectedUnexpectedly` |
| connect status 133 and others before connect | `ConnectionFailed` |
| anything else | `Other{status}` |

`:platform` produces the same `GattView` shape while a run is in progress (§4.3).

### 2.8 Storage, delete, export, upload

```rust
#[derive(uniffi::Record)]
pub struct StorageStats {
    pub db_bytes: u64, pub archive_bytes: u64, pub pending_bytes: u64, pub pending_count: u32,
    pub pre_v2_bytes: u64,                 // sessions/*.json + archives of sessions not in local.db
    pub session_count: u32,
}
#[derive(uniffi::Enum)] pub enum DeleteOutcome { Deleted, DeletedPendingKept { pending: u32 }, NotFound, Refused { reason: String } }
#[derive(uniffi::Enum)] pub enum ExportFormat { Jsonl, Parquet }      // unchanged
#[derive(uniffi::Record)] pub struct ExportRequest { pub scope: Scope, pub format: ExportFormat }
#[derive(uniffi::Record)] pub struct PreparedExport { /* unchanged: export_id, local_path, suggested_file_name, file_count, size_bytes */ }
#[derive(uniffi::Record)]
pub struct UploadStatus { pub pending_count: u32, pub pending_bytes: u64, pub failed_count: u32, pub last_error: Option<String> }

impl Core {
    pub fn storage_stats(&self) -> Result<StorageStats, CoreError>;                // [Q]
    pub fn delete_sessions(&self, scope: Scope) -> Result<DeleteOutcome, CoreError>;
    // [F]+[W] User-initiated only. Refuses the recording session. Removes DB rows and archived Parquet/GATT files at
    //     once; files still pending upload stay queued and are deleted right after their upload (tombstone), so data
    //     is never lost before it reaches the server. Recomputes devices/cells/totals for the affected keys.
    pub fn prepare_export(&self, request: ExportRequest) -> Result<PreparedExport, CoreError>;  // [F] ZIP as today
    pub fn acknowledge_export(&self, export_id: String) -> Result<(), CoreError>;              // [F]
    pub fn upload_status(&self) -> UploadStatus;                                   // [N] O-1 "Pending uploads"
    pub fn pending_uploads(&self) -> Vec<PendingUpload>;                           // [N] unchanged record
    pub fn prepare_upload(&self, id: String) -> Result<PreparedUpload, CoreError>; // [F] unchanged
    pub fn mark_upload_succeeded(&self, id: String) -> Result<(), CoreError>;      // [F] plaintext → archive (kept)
    pub fn mark_upload_failed(&self, id: String, message: String) -> Result<(), CoreError>;  // [F]
}
```

- Nothing is pruned automatically.
- Parquet is kept after upload, in `archive/`.
- Every deletion goes through `delete_sessions`, which takes the same `Scope` as the queries, so "delete these" works
  with multi-select later.

### 2.9 Errors

```rust
#[derive(uniffi::Error)]
pub enum CoreError {
    InvalidPayload { detail: String }, InvalidConfig { detail: String }, InputBatchTooLarge { detail: String },
    UploadState { detail: String }, Encryption { detail: String }, Io { detail: String }, Parquet { detail: String },
    Clock { detail: String },                                   // the v3 variants, unchanged
    Db { detail: String },                                      // SQLite error
    SchemaTooNew { found: u32, supported: u32 },                // an older build opened a newer local.db (read-only refusal)
    Backpressure,                                               // ingest queue full; retry the same batch
    NotRecording,                                               // pause/resume/stop without a session
    AlreadyRecording,
    Closed,                                                     // call after close()
}
```

Retry classes, as in v3:

- `Io`, `Parquet`, `Clock` and `Backpressure` are retryable.
- `InvalidPayload` drops the row; its count goes into `IngestAck.rejected`.

### 2.10 SPEC B → core

| SPEC B | Core | Owner on the Kotlin side |
|---|---|---|
| `getCategories()` | `categories()` | `:core-api` |
| `getRecordings()` | `sessions()` | `:core-api` |
| `getCurrentRecording()` | `current_session()` + `recording_state()` | `:core-api` |
| `getRecording(id)` / `"all"` | `session_view(scope)` + `routes(scope, …)` + `map_points(scope, …)` | `:core-api` |
| `getAllSessionsView()` | `all_sessions_summary()` | `:core-api` |
| `getSessionDevices(id)` | `device_order(scope)` + `device_rows(scope, keys)` + deltas | `:core-api` |
| `getProducts(mode, id)` | `products(scope, {in_range_only, area})` | `:core-api` |
| `getProduct(id)` | `product(scope, id, area)` + `device_keys(scope, {product})` | `:core-api` |
| `getDevice(addr)` | `device(scope, key)` + `device_chart(key)` | `:core-api` |
| `getDeviceSightings(addr)` | `device_sightings(scope, key)` | `:core-api` |
| `getDeviceGatt` / `discoverGatt` | `device_gatt(key)` / `GattRunner.discover` (B) → `ingest_gatt` | `:core-api` / `:platform-api` |
| `searchDevices(q, scope, bbox?)` / `matchDevice` | `search(scope, q, area)` / `match_device` | `:core-api` |
| `getLocationStatus()` | (not core) `LocationStatusSource` | `:platform-api` |
| `getMyLocation()` | (not core) `LocationStatusSource.myLocation` | `:platform-api` |
| `startRecording()` / `stopRecording()` | `Recorder.start/stop` (B) → `start_recording` / `stop_recording` | `:platform-api` |
| `getSettings()` | (not core) `SettingsStore` + `upload_status()` | `:platform-api` |

The core needs no location-status input: P-5 is computed by `:platform` from permission and provider state and fix
age. The core sees only fixes.

### 2.11 Q-1 test vectors (from the prototype gallery)

Both Rust (`match_device`) and the Kotlin fake run these vectors.

| query | name | addr | match |
|---|---|---|---|
| `qt` | QT-Sensor | AA:BB:CC:00:11:22 | true |
| `qt` | SmartQt | AA:BB:CC:00:11:22 | true |
| `qt` | aqtb | AA:BB:CC:00:11:22 | true |
| `qt` | Other | AA:BB:CC:00:11:22 | false |
| `4b4b` | No name | 72:CD:4C:4B:4B:6F | true |
| `4B:4B` | No name | 72:CD:4C:4B:4B:6F | true |
| `4b-4b` | No name | 72:CD:4C:4B:4B:6F | true |
| `7d3f` | No name | 40:E7:7D:3F:7A:CD | true |
| `ab` | Sensor | AB:CD:EF:01:02:03 | false |
| `ab` | Abbey Road | 11:22:33:44:55:66 | true |
| `"  "` | QT-Sensor | AA:BB:CC:00:11:22 | false |
| `no` | *(None)* | 11:22:33:44:55:66 | false *(added: the placeholder is never matched)* |

Lowercasing is Unicode-aware, as in JS `toLowerCase`, and so is trimming (JS `trim`). The core registers the same
function as SQLite function `match_device(q, name, addr)` so the same code runs in SQL.

---

## 3. Local DB schema

The file is `filesDir/cluetooth-core/local.db`, using `rusqlite` with feature `bundled`. The bundled SQLite is ≥ 3.45
and has R*Tree, so it does not depend on API 24's 3.9. An open-time test asserts that `rtree` exists.

The DB is opened with `PRAGMA journal_mode=WAL; synchronous=NORMAL; foreign_keys=ON; temp_store=MEMORY`. One writer
connection belongs to `ct-writer`, and two read connections form the pool.

### 3.1 Alignment with the research DB

| Research (Postgres, `docs/schema.md`) | Local (SQLite) |
|---|---|
| `ad_structures(id, ad_type, data, local_name, company_id, uuids)` unique `(ad_type, data)` | same columns; the generated columns are computed in Rust at insert with the same rules (§3.2) |
| `payloads(id, sha256, structure_ids[], zero_padding, tail, first/last_seen, sightings)` | `payloads` + `payload_structures(payload_id, ord, structure_id)` (no arrays); the `payload_fields` columns are materialized |
| `sighting_minutes` keyed by `(minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)` | same key minus `uploader_id` (one producer); nullable key columns stored as `-1` (SQLite UNIQUE treats NULLs as distinct) |
| best sample by `sighting_sample_score` (rssi/20 − 2·log10(max(acc ?? 50, 1))), then accuracy, then earlier | **same function and tie order** (provisional coefficients shared; one Rust fn) |
| `gatt_discoveries`, `gatt_attributes` | same columns (text enums, `raw` = the gatt/v1 JSON) |
| (none) | `sessions`, `session_pauses`, `route_points`, `session_devices`, `devices`, `device_cells`, `product_totals` + R*Trees: app-side indexes |

The map point is a different thing from the minute's best sample:

- The **minute's best sample** uses the research score, so phone and server rows agree.
- A **device's map point** is its strongest located sighting (M-3). It is tracked per sample at ingest.

### 3.2 DDL (schema version 1)

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
-- keys: rules_key ("<bundle rules_version>+m<engine>"), bundle_version, devices_all, devices_shown, created_by

CREATE TABLE ad_structures (
  id         INTEGER PRIMARY KEY,
  ad_type    INTEGER NOT NULL,
  data       BLOB    NOT NULL,              -- data bytes only (no length/type)
  local_name TEXT,                          -- types 8/9: UTF-8, trailing NULs stripped, NULL if empty/invalid
  company_id INTEGER,                       -- type 0xFF with ≥ 2 bytes: LE u16
  uuids      TEXT,                          -- types 2-7, 0x16/0x20/0x21: research notation, sorted, comma-joined
  UNIQUE (ad_type, data)
);

CREATE TABLE payloads (
  id            INTEGER PRIMARY KEY,
  sha256        BLOB    NOT NULL UNIQUE,
  zero_padding  INTEGER NOT NULL DEFAULT 0,
  tail          BLOB,
  local_name    TEXT,                       -- payload_fields.local_name (type 9 before 8, first)
  company_id    INTEGER,                    -- first manufacturer data
  service_uuid  TEXT,                       -- first service or service-data UUID
  is_apple      INTEGER NOT NULL,           -- any structure with company_id 76
  product_id    TEXT,                       -- matcher result (§3.4); NULL = unidentified
  match_key     TEXT    NOT NULL,           -- meta.rules_key it was matched with
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL DEFAULT 0,
  CHECK (tail IS NULL OR zero_padding = 0)
);
CREATE TABLE payload_structures (
  payload_id   INTEGER NOT NULL REFERENCES payloads(id),
  ord          INTEGER NOT NULL,
  structure_id INTEGER NOT NULL REFERENCES ad_structures(id),
  PRIMARY KEY (payload_id, ord)
) WITHOUT ROWID;
CREATE INDEX payloads_rematch ON payloads(match_key, id);

CREATE TABLE sessions (
  id          INTEGER PRIMARY KEY,
  uuid        TEXT    NOT NULL UNIQUE,      -- UUIDv7 = Parquet footer scan_session_id = gatt/v1 session_id
  status      INTEGER NOT NULL,             -- 0 recording, 1 paused, 2 completed, 3 interrupted
  started_ms  INTEGER NOT NULL, ended_ms INTEGER, active_ms INTEGER NOT NULL DEFAULT 0,
  distance_m  REAL    NOT NULL DEFAULT 0,
  device_count INTEGER NOT NULL DEFAULT 0, device_count_shown INTEGER NOT NULL DEFAULT 0,
  sightings   INTEGER NOT NULL DEFAULT 0,
  min_lon REAL, min_lat REAL, max_lon REAL, max_lat REAL,   -- frame: route + best points
  route_simplified BLOB                     -- ≤ 2048 points, delta-encoded i32 1e-6°, written at stop
);
CREATE INDEX sessions_started ON sessions(started_ms DESC);
CREATE TABLE session_pauses (session_id INTEGER NOT NULL REFERENCES sessions(id), from_ms INTEGER NOT NULL,
  to_ms INTEGER, PRIMARY KEY (session_id, from_ms)) WITHOUT ROWID;
CREATE TABLE route_points (session_id INTEGER NOT NULL REFERENCES sessions(id), t_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, PRIMARY KEY (session_id, t_ms)) WITHOUT ROWID;
CREATE VIRTUAL TABLE sessions_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);  -- route bbox per session

CREATE TABLE sighting_minutes (
  id            INTEGER PRIMARY KEY,
  session_id    INTEGER NOT NULL REFERENCES sessions(id),
  minute        INTEGER NOT NULL,           -- unix ms / 60000
  addr          INTEGER NOT NULL,           -- DeviceKey
  payload_id    INTEGER NOT NULL REFERENCES payloads(id),
  addr_type     INTEGER NOT NULL DEFAULT -1, is_legacy INTEGER NOT NULL DEFAULT -1,
  primary_phy   INTEGER NOT NULL DEFAULT -1, secondary_phy INTEGER NOT NULL DEFAULT -1,
  adv_sid       INTEGER NOT NULL DEFAULT -1, data_status INTEGER NOT NULL DEFAULT -1,   -- -1 = null
  sightings     INTEGER NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
  rssi_min INTEGER, rssi_max INTEGER, rssi_sum INTEGER NOT NULL DEFAULT 0, rssi_count INTEGER NOT NULL DEFAULT 0,
  tx_power INTEGER, is_connectable INTEGER, adv_tx_power INTEGER, periodic_interval INTEGER,
  min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL,
  best_lat REAL, best_lon REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER, best_score REAL,
  UNIQUE (session_id, minute, addr, payload_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)
);
CREATE INDEX sighting_minutes_device ON sighting_minutes(addr, session_id, minute);

CREATE TABLE session_devices (               -- one row per device per session: the map point and list row
  id             INTEGER PRIMARY KEY,        -- = session_devices_rtree.id
  session_id     INTEGER NOT NULL REFERENCES sessions(id),
  addr           INTEGER NOT NULL,
  name           TEXT,                       -- latest non-empty local name in the session
  company_id     INTEGER, is_apple INTEGER NOT NULL,
  product_id     TEXT, category TEXT NOT NULL,              -- §3.4
  latest_payload_id INTEGER NOT NULL,
  first_seen_ms  INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max       INTEGER,                    -- strongest, located or not (list RSSI, order)
  best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,  -- strongest located
  tile17         INTEGER,                    -- (x17 << 17) | y17 of the best point (places, cells)
  shown          INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED,       -- G-12
  UNIQUE (session_id, addr)
);
CREATE INDEX session_devices_order   ON session_devices(session_id, rssi_max DESC, addr);
CREATE INDEX session_devices_product ON session_devices(session_id, product_id);
CREATE VIRTUAL TABLE session_devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE devices (                       -- All sessions: the union over every session, one row per addr
  addr INTEGER PRIMARY KEY,                  -- = devices_rtree.id
  name TEXT, company_id INTEGER, is_apple INTEGER NOT NULL,
  product_id TEXT, category TEXT NOT NULL, latest_payload_id INTEGER NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, session_count INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max INTEGER, best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,
  best_session_id INTEGER, tile17 INTEGER,
  shown INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED
);
CREATE INDEX devices_order   ON devices(rssi_max DESC, addr);
CREATE INDEX devices_product ON devices(product_id, tile17);
CREATE INDEX devices_named   ON devices(name, addr) WHERE name IS NOT NULL;   -- search covering index
CREATE VIRTUAL TABLE devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE device_cells (                  -- All: pre-aggregated map cells, maintained per tick
  z INTEGER NOT NULL,                        -- 5, 8, 11, 14
  category TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL,
  n_all INTEGER NOT NULL, n_shown INTEGER NOT NULL, sum_lon REAL NOT NULL, sum_lat REAL NOT NULL,
  PRIMARY KEY (z, x, y, category)
) WITHOUT ROWID;
CREATE TABLE product_totals (                -- All without area: card counts
  product_id TEXT PRIMARY KEY,               -- 'unidentified' row included
  category TEXT NOT NULL, devices_all INTEGER NOT NULL, devices_shown INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE gatt_discoveries (
  id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, session_id INTEGER REFERENCES sessions(id),
  addr INTEGER NOT NULL, addr_type TEXT, trigger_payload_id INTEGER REFERENCES payloads(id), trigger_rssi INTEGER,
  started_ms INTEGER NOT NULL, ended_ms INTEGER, lat REAL, lon REAL, accuracy REAL,
  outcome TEXT NOT NULL, stage_reached TEXT NOT NULL, error_stage TEXT, error_status INTEGER, error_name TEXT,
  disconnect_initiator TEXT, disconnect_status INTEGER, mtu INTEGER, phy_tx INTEGER, phy_rx INTEGER,
  connect_ms INTEGER, mtu_ms INTEGER, discover_ms INTEGER, read_ms INTEGER, profile_hash TEXT,
  raw TEXT NOT NULL                          -- the exact gatt/v1 JSON
);
CREATE INDEX gatt_discoveries_addr ON gatt_discoveries(addr, started_ms DESC);
CREATE TABLE gatt_attributes (
  discovery_id INTEGER NOT NULL REFERENCES gatt_discoveries(id) ON DELETE CASCADE,
  attribute_id INTEGER NOT NULL, parent_attribute_id INTEGER, kind TEXT NOT NULL, uuid TEXT NOT NULL,
  is_primary INTEGER, instance INTEGER, properties TEXT, value BLOB, read_status TEXT,
  PRIMARY KEY (discovery_id, attribute_id)
) WITHOUT ROWID;

CREATE TABLE deleted_sessions (uuid TEXT PRIMARY KEY, deleted_ms INTEGER NOT NULL) WITHOUT ROWID;
-- tombstones: pending files of these sessions are deleted right after their upload
```

### 3.3 Migration and versioning

- **Versioning.** `PRAGMA user_version` holds the schema version, and this document defines version 1.
- **Migrations** are an ordered list of `N → N+1` SQL steps compiled into the Rust library and kept forever. Each
  step runs in one transaction with `foreign_keys` deferred.
- **Forward only, never destructive.** No step drops user data. A schema change that needs it adds new tables and
  backfills them.
- **Newer file.** An older build that opens a newer file gets `CoreError::SchemaTooNew`. The UI shows read-only
  failure text, and the file is untouched.
- **Corruption.** `SQLITE_CORRUPT`/`NOTADB` at open moves the file aside to `local.db.corrupt-<ms>`, which is never
  deleted, creates a fresh DB and emits `StorageWarning`.
- **Re-match.** When `meta.rules_key` differs from the loaded bundle, which happens on an app update, `ct-writer`
  re-matches payloads in batches of 2,000 per tick (`payloads_rematch`). It then recomputes `session_devices`,
  `devices`, `device_cells` and `product_totals` for the affected keys, emitting `RematchProgress` and finally
  `DataInvalidated`.
- **Rebuild.** The archive Parquet still holds every sighting with its position. A future
  `rebuild_sightings(session)` could regenerate the sighting tables. `sessions`, `session_pauses`, `route_points` and
  the GATT tables live only in the DB and are always migrated, never rebuilt.
- **Pre-v2 data** is not imported (§1.5).

### 3.4 Product and category of a device

1. **Payload → product.**
   - `RuleMatcher.match_advertisement(raw, addr, addr_type)` returns the matching page ids in bundle order.
   - The payload's product is the first match whose page `kind` is `device`, else `platform`, else `identifier`.
   - It is matched once per new payload, and again on a re-match.
2. **Device → product** over a scope: the product of the device's matched payload with the most sightings in the
   scope. Ties go to the lower payload id. With no matched payload, the device is unidentified. This is one rule for
   live, frozen, union and re-match. A device whose product changes keeps its list position (G-9).
3. **Product → display category.** The category comes from a mapping of the bundle's page categories onto the
   C-3 chip categories. The mapping is produced by the bundle exporter as `display_category` per page; see §7 Q1.
4. **Wiki URL**: `https://wirelesswiki.org/p/<page id>`. The description comes from the page's `one_line`.

### 3.5 SPEC queries in SQL

`:shown` is `CASE WHEN :hide_unnamed_apple THEN shown ELSE 1 END`. `D(scope)` is the device relation of a scope:

| Scope | `D(scope)` | Spatial index |
|---|---|---|
| `All`, no filter | `devices` | `devices_rtree` |
| one session | `session_devices WHERE session_id = ?` | `session_devices_rtree` |
| live current session | the live snapshot (memory), not SQL | linear over the live devices (≤ 20k) |
| anything else | table `scopes.d_<h>` in a scratch DB `scope-cache.db` attached to every read connection, cached for up to 4 scopes and dropped on `SessionsChanged` and `DataInvalidated` | `scopes.r_<h>` R*Tree |

A union's table is built once per scope by the first query that needs it, under a scope-cache mutex. Temp tables
are per connection, so it uses an attached scratch file that every read connection sees. The builder does one
ordered scan of `session_devices` over `(session_id IN :ids)` and folds rows per `addr` in Rust:

- `rssi_max` and the strongest located point are the maxima;
- `name` is the latest non-empty name, and `is_apple` is any;
- first and last seen are the min and max;
- the product is recomputed per §3.4 from `sighting_minutes` for those sessions.

It then bulk-inserts the result into `scopes.d_<h>` and fills `scopes.r_<h>` from the best points. `local.db`'s writer is
never blocked.

| SPEC | SQL (frozen scope; the live scope runs the same logic over the snapshot) |
|---|---|
| L-2 `sessions()` | `SELECT … FROM sessions WHERE status IN (2,3) AND device_count > 0 ORDER BY started_ms DESC` (count column is `device_count_shown` when hiding) |
| `all_sessions_summary()` | `meta.devices_all/devices_shown` + `SELECT count(*) FROM sessions WHERE device_count > 0` |
| `session_view` frame | `sessions.min_*/max_*` (one), union of them (several), `meta`/`devices_rtree` root bbox (All) |
| `routes(scope, viewport, zoom)` | one: `route_simplified`; several: `SELECT id FROM sessions_rtree WHERE max_lon>=:w AND min_lon<=:e AND max_lat>=:s AND min_lat<=:n` ∩ scope, each simplified to the zoom's tolerance |
| G-9 `device_order` | `SELECT addr FROM D WHERE :shown ORDER BY rssi_max DESC, addr` (covering index) |
| D-3/D-4 `device_keys(filter)` | `… WHERE :shown [AND category=:c] [AND product_id IS :p] [AND id IN (SELECT id FROM rtree WHERE <bbox>)] ORDER BY rssi_max DESC, addr` |
| `device_rows(keys)` | `SELECT … FROM D WHERE addr IN (rarray(:keys))` (≤ 200 keys), returned in the given order |
| D-2 `products(All, all, no area)` | `SELECT product_id, category, devices_shown, last_seen_ms FROM product_totals WHERE devices_shown > 0` |
| D-2/D-4 `products(scope, area)` | `SELECT coalesce(product_id,'unidentified'), category, count(*), max(last_seen_ms) FROM D JOIN rtree USING(id) WHERE <bbox> AND :shown GROUP BY 1` |
| D-7 `product` places | `SELECT count(DISTINCT tile17) FROM D WHERE product_id IS :p AND :shown [AND <bbox>]` (`devices_product` covers All) |
| D-9/D-12 `device` | `D` row + `payloads` + `payload_structures ⋈ ad_structures ORDER BY ord` for `latest_payload_id` (+ `sighting_minutes` latest row for `is_connectable`, `is_legacy`) |
| `device_sightings` | `SELECT minute, best_lon, best_lat, max(best_rssi), best_seen_ms FROM sighting_minutes WHERE addr=:k AND session_id IN (:ids) AND best_lat IS NOT NULL GROUP BY session_id, minute ORDER BY minute DESC LIMIT 10000` |
| D-13.7 `device_gatt` | `SELECT … FROM gatt_discoveries WHERE addr=:k AND outcome <> 'cancelled' ORDER BY started_ms DESC LIMIT 1` + its attributes |
| Q-1/Q-2 `search` | `SELECT addr FROM D [JOIN rtree … <bbox>] WHERE :shown AND match_device(:q, name, printf('%012X', addr)) ORDER BY rssi_max DESC, addr` |
| M-2/M-3 `map_points` | §3.6 |

### 3.6 Cost of "All sessions" with years of data

**Sizing assumption, chosen as heavy use:**

- 3 years at about 1 h of recording a day gives 1,000 sessions.
- 1.0 M `session_devices` rows and 600 k distinct devices, since random addresses mostly don't repeat.
- 2 M payloads and 15 M `sighting_minutes` rows.
- `local.db` is about 2.5 GB, plus the Parquet archive.

The DB holds aggregates only and grows with recording time, never with app use.

**Budgets.** These are targets on a Moto G-class phone (Cortex-A53/A55, eMMC). Lane A asserts host budgets of
one fifth of these on a seeded synthetic DB of this size (§6.1).

| Query | Plan | Rows touched | Budget |
|---|---|---|---|
| `sessions()` | `sessions_started` index | 1,000 | 15 ms |
| `all_sessions_summary()` | `meta` counters | O(1) | 1 ms |
| `session_view(All)` | `meta` + R*Tree root | O(1) | 2 ms |
| `routes(All, viewport, z)` | `sessions_rtree` ∩ viewport, LOD-simplified (≤ 20k points total) | sessions in view | 40 ms |
| `map_points(All, viewport, z)` | see below | ≤ 10,001 or ≤ 2,000 cells | 60 ms |
| `products(All)` no area | `product_totals` | ≤ pages (≈ 10³) | 10 ms |
| `products(All, area)` | R*Tree → join → `GROUP BY` | k in area | 3 µs × k (50 k: 150 ms; 300 k country view: ≈ 1 s) |
| `device_order(All)` | covering index scan, packed bytes | 600 k → 4.8 MB `LongArray` | 600 ms, once per view, only when the list view or search needs it |
| `device_keys(All, area)` | R*Tree + sort | k | 50 ms at k = 10 k |
| `device_rows(200 keys)` | rowid lookups | 200 | 5 ms |
| `device(key)`, `device_sightings` | PK + `sighting_minutes_device` | the device's minutes | 10 ms |
| `search(All, q)` | `devices_named` + `devices_rtree`/addr scan in Rust | 600 k | 300 ms (debounced 150 ms), with area ≤ 30 ms |
| union scope build | `GROUP BY addr` over the selected sessions | Σ session devices | 1 s per 300 k rows, once, cached |

`map_points` never loads the scope, only the viewport, and it aggregates when the viewport is dense:

1. Count up to the cap: `SELECT count(*) FROM (SELECT 1 FROM rtree WHERE <viewport+50 %> LIMIT 10001)`.
2. If the count is ≤ 10,000, build **Points**: join to `D`, apply the filter and `:shown`, and serialize GeoJSON in
   Rust (≈ 2 ms per 10 k). MapLibre clusters them natively (M-4).
3. Otherwise build **Cells**. Pick the coarsest `device_cells` level whose cell size is ≥ 1/16 of the viewport side,
   then `SELECT x, y, sum(n_shown), sum(sum_lon)/sum(n_all), … FROM device_cells WHERE z = :z AND x BETWEEN … AND y
   BETWEEN … [AND category = :c] GROUP BY x, y`, which returns ≤ 2,000 rows. The features carry
   `point_count`/`point_count_abbreviated` and are drawn by layers cloned from `clusters`/`cluster-count` on the
   source `cells` (lane E, §6.5).
4. Product and search filters have no cells. For them, `GROUP BY tile17 >> s` runs over `devices_product` or the
   search result: 50 k rows ≈ 150 ms.

On a budget phone the GeoJSON is handed to `GeoJsonSource.setGeoJson(String)` and parsed in native code, so no JVM
object is created per point.

**Live session.** Everything is served from the live snapshot in memory: ≤ 20 k devices × ~300 B. The `device_order`
for the live view is a sort of the in-range devices (≤ 5 ms).

---

## 4. Kotlin platform ↔ core

### 4.1 Scan callback → ingest

```kotlin
// :platform  (lane B)
internal class ScanIngest(private val core: CoreIngest, private val scope: CoroutineScope) {
    private val queue = Channel<ScanObservation>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun onScanResult(r: ScanResult)   // main/binder thread: convert (v3 rules: RSSI/txPower 127 → null,
                                      // addressType on API 35+, raw ≤ 1650 B) and trySend; never blocks
    // single consumer on Dispatchers.IO.limitedParallelism(1): batch ≤ 64 or 250 ms → core.ingestScans(batch)
    // Backpressure → keep the batch, retry after 100 ms; drops (overflow) are counted into RecorderState.dropped
}
```

- **Scanning runs only while recording and not paused** (V-1). Pause stops the scan and the RECORDING location
  request.
- **ScanSettings** are as today:
  - `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, `setLegacy(false)`;
  - PHY `ALL_SUPPORTED` when "Scan LE Coded PHY" is on, else `LE_1M`;
  - one empty `ScanFilter`.
- **Restart policy** is kept from v3:
  - the scan restarts every 60 s;
  - while the screen is off, the restart is skipped until the scan has run 20 min;
  - a Coded PHY change applies at the next restart ("applies within a minute", O-1.4), with
    `core.configureScan(env)` called before the restart.
- **Bluetooth off** during a recording: the session stays open and the scanner is deferred
  (`RecorderState.scannerDeferred`). It resumes when Bluetooth comes back.

### 4.2 Location → ingest and status

- **Provider.** `FusedLocationProviderClient`, falling back to `LocationManager` (as today).
  - The RECORDING profile (2 s, minimum 1 s, high accuracy) runs while recording.
  - The FOREGROUND profile (balanced, 10 s) runs while the UI is started and nothing is recording. It feeds only
    `myLocation` and the status (M-9, P-5), never the core.
- **Into the core.** Every RECORDING fix goes to `core.ingestLocation(fix)`. A disabled provider or a fix without
  accuracy calls `core.clearLocation()`.
- **Location status.** `LocationStatusSource.status: StateFlow<LocationStatus>` (P-5) is computed from:
  - the permission (`ACCESS_FINE_LOCATION`) → `Denied`, action `Allow`;
  - the provider on or off (`LocationManager.isLocationEnabled` plus a `PROVIDERS_CHANGED` receiver registered at
    runtime) → `Off`, action `TurnOn`, which resolves through `SettingsClient.checkLocationSettings`, falling back to
    `ACTION_LOCATION_SOURCE_SETTINGS`;
  - no first fix for more than 30 s after the request → `Waiting`;
  - a last fix older than 2 min → `Stale(ageMin)`.

  The thresholds are `LOCATION_WARN_NO_FIX_MS = 30_000` and `LOCATION_WARN_STALE_MS = 120_000`. The status is
  re-evaluated every 5 s while warning-capable.

### 4.3 GATT runner contract (D-13)

```kotlin
// :platform-api
interface GattRunner {
    /** Cold flow: collecting starts one discovery of [key]; cancelling the collector cancels it (D-13.8).
     *  Emits progress views; the last emission is the final view. The runner itself saves the gatt/v1 result
     *  through CoreIngest.ingestGatt (also for cancelled runs: outcome "cancelled", never shown). */
    fun discover(key: DeviceKey): Flow<GattView>
}
```

How the runner maps to SPEC D-13 (the engine is ported from v3: `GattEngine`, timeouts and retries unchanged):

| D-13 row | Engine stages | Running detail | Done detail | Failure reasons |
|---|---|---|---|---|
| Connecting… | CONNECT (+ MTU, folded) | spinner | `duration_ms` ("0.8 s") | `TimedOut` (10 s connect timeout), `ConnectionFailed` (status 133 after the one retry, 62, 257 …) |
| Discovering services… | DISCOVER | spinner | `services` ("N services") | `TimedOut` (15 s), `DisconnectedUnexpectedly` (remote disconnect) |
| Reading characteristics | READ | `read/readable` ("m/n") | "n/n" | `TimedOut` (30 s total or a stall), `DisconnectedUnexpectedly` |
| Disconnected | DISCONNECT | spinner | none | (never fails visibly: a local disconnect timeout still counts as done) |

The flow:

- **Before running**, check `BLUETOOTH_CONNECT` and that Bluetooth is on. If not, emit one failed Connecting step
  with `Other(status = -4)`, and the UI shows the onboarding piece (O-2).
- **One discovery** runs at a time process-wide. Collecting a second `discover` cancels the first.
- **A target** must exist: `core.gattTarget(key)` must not be null. A device never scanned locally cannot be
  connected. GATT is allowed with or without a recording. While recording, `session_id` is the current session.
- **After the final step**, call `core.ingestGatt(json)` and emit the `GattView` it returns, so the live and the
  stored views are identical. On a save failure, keep it in `filesDir/gatt-pending` and retry as today.

### 4.4 Recording state exposed to the UI

```kotlin
// :platform-api
interface Recorder {
    val state: StateFlow<RecorderState>
    suspend fun start(): Result<String>          // permissions/Bluetooth checked; starts RecordingService; core.startRecording
    suspend fun pause(); suspend fun resume()
    suspend fun stop(): Result<String>           // returns the finished session id (R-7, V-3)
}
data class RecorderState(
    val phase: Phase,                            // Idle, Starting, Recording, Paused, Stopping
    val sessionId: String?,
    val activeBeforeMs: Long, val runningSinceMs: Long?,   // the UI ticks elapsed locally (R-3)
    val deviceCount: Int,                        // shown devices, from deltas
    val scannerDeferred: Boolean,                // Bluetooth off / scanner unavailable
    val error: String?,                          // start failure (shown as a toast; no snackbar on success)
) { enum class Phase { Idle, Starting, Recording, Paused, Stopping } }
```

- `Recorder` is a process singleton in the same process as the UI (§1.2), so there is no binding or AIDL.
- `RecordingService` observes it and updates its notification:
  - at most every 5 s;
  - text "Recording · 12:34 · 26 devices";
  - actions Pause/Resume and Stop, as in v3; tapping it opens the app.
- After a process restart, `Recorder` reads `core.recordingState()`, which is `Idle` once `open` has marked the
  session interrupted.

### 4.5 Settings, upload and other platform interfaces

```kotlin
// :platform-api
data class Settings(val userId: String?, val recordOnOpen: Boolean, val wifiOnlyUpload: Boolean,
                    val scanCodedPhy: Boolean, val hideUnnamedApple: Boolean, val appVersion: String)
interface SettingsStore {                  // DataStore<Preferences> "cluetooth_settings"; migrates cluetooth_prefs
    val settings: StateFlow<Settings>      // (user_id, wifi_only_upload, scan_coded_phy) once via SharedPreferencesMigration
    suspend fun update(transform: (Settings) -> Settings)
}
// defaults: recordOnOpen = true (O-2.4 default ON), wifiOnlyUpload = false (v3 default), scanCodedPhy = true (v3),
//           hideUnnamedApple = true (G-12)
interface Uploads {                        // O-1.5
    val status: StateFlow<UploadStatus>    // from core.uploadStatus(), refreshed on UploadNeeded and after each run
    fun uploadNow()                        // expedited one-time UploadWorker (REPLACE), respects Wi-Fi only
}
interface Permissions {                    // O-2
    val state: StateFlow<PermissionState>  // nearby devices, precise location, notifications; bluetooth on/off
    fun requestLauncherContract(): ActivityResultContract<Array<String>, Map<String, Boolean>>
    fun openAppSettings(); fun requestEnableBluetooth(activity: ComponentActivity)
}
interface LocationStatusSource { val status: StateFlow<LocationStatus>; val myLocation: StateFlow<MyLocation?> }
```

Other platform behaviour:

- `hideUnnamedApple` changes call `core.setDisplayFilter`.
- `wifiOnlyUpload` changes re-enqueue the work with `NetworkType.UNMETERED`/`CONNECTED`.
- The upload transport is unchanged: Firebase Storage `putFile` with a resumable session, `exists()` on retry, and
  401/403 kept pending. The legacy JSONL uploader is ported as is.

---

## 5. UI state models

All Kotlin types below live in `:core-api` (data from the core) or `:platform-api` (platform), in packages
`edu.ucsd.sysnet.cluetoothscanner.api.core` and `….api.platform`. They are plain Kotlin with no Android
dependencies, so fakes and JVM tests use them directly. `:core-bridge` maps the UniFFI records 1:1 onto them; field
names are the camelCase of §2.

### 5.1 Data models (`:core-api`)

```kotlin
typealias DeviceKey = Long

sealed interface SessionScope {                      // §2.1
    data object All : SessionScope
    data class Sessions(val ids: List<String>) : SessionScope     // one session = listOf(id)
}
data class SessionFilter(val startedFromMs: Long? = null, val startedToMs: Long? = null)  // extensible
data class Scope(val sessions: SessionScope, val filter: SessionFilter? = null) {
    companion object { val All = Scope(SessionScope.All); fun session(id: String) = Scope(SessionScope.Sessions(listOf(id))) }
}

data class GeoPoint(val lon: Double, val lat: Double)
data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double)
class DeviceKeys(val keys: LongArray, val seq: Long)
data class DeviceFilter(val area: Bbox? = null, val category: String? = null,
                        val product: String? = null, val inRangeOnly: Boolean = false)

data class Category(val id: String, val label: String, val icon: CategoryIcon)
enum class CategoryIcon { Headphones, Bulb, Thermo, Tag, Watch, Car, Medical, Unknown }
data class ProductRef(val id: String, val name: String, val category: String)

enum class SessionStatus { Recording, Paused, Completed, Interrupted }
enum class UploadState { Pending, Uploaded, Failed, Empty }
data class SessionSummary(val id: String, val status: SessionStatus, val startedMs: Long, val endedMs: Long?,
                          val activeMs: Long, val distanceM: Double, val deviceCount: Int, val upload: UploadState)
data class AllSessionsSummary(val sessionCount: Int, val deviceCount: Int, val bbox: Bbox?)
data class SessionView(val scope: Scope, val live: Boolean, val sessionIds: List<String>, val deviceCount: Int,
                       val frame: Bbox?, val route: List<GeoPoint>, val routesFaint: Boolean, val seq: Long)

data class DeviceRow(val key: DeviceKey, val addr: String, val name: String?, val product: ProductRef?,
                     val category: String, val companyId: Int?, val appleUnnamed: Boolean, val rssi: Int?,
                     val inRange: Boolean, val spark: ByteArray?, val best: GeoPoint?, val lastSeenMs: Long)
data class ProductCard(val id: String, val name: String, val category: String, val live: Boolean,
                       val count: Int, val inRange: Int, val lastSeenMs: Long)
data class CategoryGroup(val category: Category, val products: List<ProductCard>)
data class ProductDetail(val id: String, val identified: Boolean, val name: String, val category: String,
                         val description: String?, val wikiUrl: String?, val places: Int,
                         val lastSeenMs: Long?, val deviceCount: Int)
enum class AdvFormat { Legacy, Extended, Unknown }
data class AdStructure(val len: Int, val adType: Int, val data: ByteArray)
data class DeviceDetail(val key: DeviceKey, val addr: String, val name: String?, val product: ProductRef?,
                        val wikiUrl: String?, val rssi: Int?, val inRange: Boolean, val live: Boolean,
                        val connectable: Boolean?, val format: AdvFormat, val companyId: Int?,
                        val companyName: String?, val serviceUuid: String?, val txPower: Int?,
                        val structures: List<AdStructure>, val tail: ByteArray?, val lastSeenMs: Long)
data class Sighting(val at: GeoPoint, val rssi: Int, val seenMs: Long)
data class LiveChart(val key: DeviceKey, val endMs: Long, val samples: ByteArray, val rssi: Int?)
enum class MapMode { Points, Cells }
data class MapData(val mode: MapMode, val count: Int, val geojson: String, val seq: Long)

enum class GattStepKind { Connecting, DiscoveringServices, ReadingCharacteristics, Disconnected }
enum class GattStepState { Done, Running, Failed }
sealed interface GattFailure { data object TimedOut : GattFailure; data object DisconnectedUnexpectedly : GattFailure
    data object ConnectionFailed : GattFailure; data object Cancelled : GattFailure; data class Other(val status: Int) : GattFailure }
data class GattStep(val kind: GattStepKind, val state: GattStepState, val durationMs: Int?, val services: Int?,
                    val read: Int?, val readable: Int?, val failure: GattFailure?)
data class GattView(val discoveryId: String, val key: DeviceKey, val startedMs: Long, val ok: Boolean,
                    val steps: List<GattStep>, val services: List<GattServiceView>)
data class GattServiceView(val uuid: String, val name: String?, val characteristics: List<GattCharView>)
data class GattCharView(val uuid: String, val name: String?, val properties: List<String>, val valueText: String?)

data class LiveDelta(val sessionId: String, val seq: Long, val atMs: Long, val state: CoreRecordingState,
                     val changed: List<DeviceLive>, val appended: List<DeviceRow>, val products: List<ProductCard>,
                     val routeAppended: List<GeoPoint>, val fix: FixSummary?, val chart: LiveChart?)
data class DeviceLive(val key: DeviceKey, val rssi: Int?, val inRange: Boolean, val spark: ByteArray,
                      val name: String?, val product: ProductRef?, val best: GeoPoint?)
data class FixSummary(val lat: Double, val lon: Double, val accuracyM: Double, val ageMs: Long)
// CoreRecordingState, CoreEvent, StorageStats, UploadStatus, PreparedExport, DeleteOutcome: 1:1 with §2

/** The UI's data interface (lanes C, D, E consume; A implements in :core-bridge; :core-fake for development). */
interface CoreQueries {
    val live: Flow<LiveDelta>; val events: Flow<CoreEvent>
    fun categories(): List<Category>
    suspend fun sessions(): List<SessionSummary>
    suspend fun currentSession(): SessionSummary?
    suspend fun allSessionsSummary(): AllSessionsSummary
    suspend fun sessionView(scope: Scope): SessionView
    suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String
    suspend fun deviceOrder(scope: Scope): DeviceKeys
    suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys
    suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow>
    suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup>
    suspend fun product(scope: Scope, productId: String, area: Bbox?): ProductDetail?
    suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail?
    suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting>
    suspend fun deviceChart(key: DeviceKey): LiveChart?
    suspend fun deviceGatt(key: DeviceKey): GattView?
    suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys?
    fun matchDevice(query: String, name: String?, addr: String): Boolean
    suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData
    suspend fun setLiveFocus(key: DeviceKey?)
}
interface CoreStorage { suspend fun storageStats(): StorageStats; suspend fun deleteSessions(scope: Scope): DeleteOutcome
    suspend fun prepareExport(scope: Scope, format: ExportFormat): PreparedExport; suspend fun acknowledgeExport(id: String) }
interface CoreIngest { /* platform only: ingestScans, ingestLocation, clearLocation, newGattDiscoveryId, gattTarget,
    ingestGatt, startRecording, pause/resume/stopRecording, configureScan, recordingState, setDisplayFilter, uploads */ }
```

### 5.2 Platform models (`:platform-api`)

```kotlin
data class LocationStatus(val state: State, val warn: Boolean, val ageMin: Int? = null, val action: Action? = null) {
    enum class State { Ok, Denied, Off, Waiting, Stale }      // P-5; the message text lives in :chrome
    enum class Action { Allow, TurnOn }
}
data class MyLocation(val at: GeoPoint, val accuracyM: Double, val ageMs: Long)
data class PermissionState(val nearby: Perm, val location: Perm, val notifications: Perm, val bluetoothOn: Boolean)
enum class Perm { Granted, Denied, PermanentlyDenied, NotRequired }
// RecorderState, Settings, UploadStatus: §4.4, §4.5
```

### 5.3 Screen states and UI-side state

Each lane owns its UI-side state. The data comes only from §5.1 and §5.2.

```kotlin
// ---- :app (integration) ----
enum class LayoutMode { Compact, Medium, Expanded }          // G-2; tablet = Medium || Expanded
data class MainUiState(
    val layout: LayoutMode,
    val scope: Scope?,                                       // null = first run (V-6); v1 UI: Scope.All or Scope.session(id)
    val view: SessionView?,
    val recorder: RecorderUi,                                // from Recorder.state + :chrome's expansion state
    val location: LocationStatus, val myLocation: MyLocation?,
    val sidebarOpen: Boolean,                                // phone L-1; tablet T-6 swaps the panel content
    val page: Page?,                                         // G-14 order: popover > page > sheet > sidebar > recorder > search
    val search: SearchState?,
    val locationPopoverOpen: Boolean,
)
sealed interface Page { data object Settings : Page; data class SearchPage(val state: SearchPageState) : Page }

/** G-9: the one ordered-keys state. Built once from deviceOrder (sorted once by RSSI); updated only by appends;
 *  filters intersect, never reorder. Survives scope-independent changes; rebuilt on V-7. */
class OrderedKeys(initial: LongArray) {
    fun append(keys: LongArray)                              // live appended, area or search new matches
    fun visible(passing: LongSet): LongArray                 // order ∩ passing, order kept
}

// ---- :chrome (lane C) ----
data class RecorderUi(val form: Form, val paused: Boolean, val elapsedMs: Long, val deviceCount: Int,
                      val fix: FixSummary?, val route: List<GeoPoint>) { enum class Form { Fab, Capsule, Panel } }  // R-1..R-9, T-7
data class SessionsListState(                                 // L-2, T-6
    val current: SessionSummary?, val all: AllSessionsSummary,
    val days: List<DayGroup>,                                // "Today · Oct 28", "Yesterday · Oct 27", "Oct 25"
    val selected: Scope?,                                    // v1: a single selection; the row model is selection-ready
)
data class DayGroup(val label: String, val sessions: List<SessionSummary>)
data class SearchPageState(val query: String, val area: Bbox?, val results: OrderedKeys?, val searchedEverywhere: Boolean)  // Q-3, Q-4, T-5
data class SettingsUi(val settings: Settings, val uploads: UploadStatus)                 // O-1
data class OnboardingUi(val step: Int, val permissions: PermissionState, val userId: String,
                        val userIdValid: Boolean, val autoRecord: Boolean)              // O-2

// ---- :sheets (lane D) ----
enum class SheetHeight { Collapsed, Half, Full, Dismissed }  // S-5; tablet uses Open (= Full) / Collapsed (T-2)
sealed interface SheetKind { data object Discover : SheetKind; data class Product(val id: String) : SheetKind
                             data class Device(val key: DeviceKey) : SheetKind }
data class SheetEntry(                                       // S-4, S-7, S-8, G-10: everything restored on pop
    val id: Long, val kind: SheetKind, val height: SheetHeight,
    val scroll: ScrollSnapshot,                              // first visible index + offset
    val camera: CameraSnapshot?,                             // S-17 / S-8: the map camera to restore on reveal
    val content: SheetContentState,
)
sealed interface SheetContentState {
    data class Discover(val view: View, val segment: Segment, val chip: String?,  // D-1..D-5
                        val order: OrderedKeys?, val cardOrder: Map<String, List<String>>,
                        val area: AreaState?) : SheetContentState {
        enum class View { Categories, List }; enum class Segment { Nearby, All, ThisArea }
    }
    data class Product(val detail: ProductDetail?, val order: OrderedKeys?) : SheetContentState      // D-6..D-8
    data class Device(val detail: DeviceDetail?, val tab: Tab, val gatt: GattUi,
                      val chart: LiveChart?) : SheetContentState { enum class Tab { Advertising, Gatt } } // D-9..D-14
}
data class AreaState(val bbox: Bbox, val order: OrderedKeys)                // D-4: kept order, new appended
sealed interface GattUi {                                    // D-13
    data object Idle : GattUi; data object Confirm : GattUi
    data class Running(val view: GattView) : GattUi; data class Failed(val view: GattView) : GattUi
    data class Done(val view: GattView) : GattUi
}
data class SheetStack(val entries: List<SheetEntry>)         // entries[0] is Discover and is never popped
data class SearchState(                                      // Q-5..Q-7, owned by :sheets, read by :map and :chrome
    val query: String, val area: Bbox?, val order: OrderedKeys,
    val reference: CameraSnapshot, val movedAway: Boolean,   // Q-6 via M-11
    val saved: SheetEntry,                                   // the Discover entry before search (Q-7)
)

// ---- :map (lane E) ----
data class CameraSnapshot(val center: GeoPoint, val zoom: Double, val bearing: Double = 0.0)
data class MapUiState(
    val sources: MapSources,                                 // route, ends, density/devices (MapData), sightings, me
    val selection: DeviceKey?,                               // M-6
    val routesFaint: Boolean,                                // M-8: from SessionView.routesFaint
    val coverInsets: Insets,                                 // the sheet/panel cover for M-10 padding and P-4's free area
    val showLocate: Boolean, val showWarning: Boolean,       // P-4, P-5
)
interface MapEvents {                                        // :map → owners
    fun onUserGestureStart()                                 // P-6 collapse
    fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double)   // D-4, Q-6, data reload
    fun onDeviceTap(key: DeviceKey); fun onEmptyTap()        // M-7
}
```

The scope seam:

- `Scope` flows unchanged from the sidebar selection through `MainUiState.scope` into every query. Nothing in the
  UI branches on "single id" except labels: a one-element scope gets its start time as the title.
- **Multi-select and filters** later touch only `:chrome` (selection UI) and `MainViewModel` (building `Scope` with
  more ids and a filter). The union rendering already exists: `SessionView.routesFaint`, frozen rows, and products
  over the union.

---

## 6. Lanes

### Gradle modules

Namespaces are `edu.ucsd.sysnet.cluetoothscanner.<module>`. The applicationId stays `edu.ucsd.sysnet.cluetoothscanner`.

| Module | Type | Owner | Depends on |
|---|---|---|---|
| `:core-api` | Kotlin/JVM library | A (PR A0) | none |
| `:core-fake` | Kotlin/JVM library | A (PR A0) | `:core-api` |
| `:core-bridge` | Android library (UniFFI bindings + `.so`) | A | `:core-api` |
| `:platform-api` | Kotlin/JVM library | B (PR B0) | `:core-api` |
| `:platform-fake` | Kotlin/JVM library | B (PR B0) | `:platform-api` |
| `:platform` | Android library | B | `:platform-api`, `:core-api` |
| `:designsystem` | Android library (Compose) | C | none (reads `design/ui-v2/tokens.json` at build) |
| `:chrome` | Android library (Compose) | C | `:designsystem`, `:core-api`, `:platform-api` |
| `:sheets` | Android library (Compose) | D | `:designsystem`, `:core-api`, `:platform-api` |
| `:map` | Android library (Compose + MapLibre 12.x) | E | `:designsystem`, `:core-api` (reads `design/ui-v2/map-style.json` at build) |
| `:gallery` | Android app, debug only, `….gallery` | B0 creates the shell; each lane owns `gallery/src/main/kotlin/…/lanes/<Lane>Gallery.kt` | everything + fakes |
| `:app` | Android app | integration (§6.6) | everything |

- **During the lanes, the old `:app` (v3) stays untouched** and keeps building and checking.
- B0 adds the new modules to `settings.gradle.kts` with empty builds. After that, each lane edits only its own
  modules' `build.gradle.kts`.
- `gradle/libs.versions.toml` is shared. Lanes add entries only through additive lines, and a conflict means rebase,
  not overwrite.
- Tokens and the map style are generated by `cluetooth-android/design/ui-v2/export.mjs` (`mise run
  //cluetooth-android:design-export`; `design-export-check` runs in `check`). C and E generate code or assets from
  the JSON at build time and never copy values by hand (G-1).

### 6.1 Lane A: Rust core

- **Scope:**
  - Q-1, Q-2 and all of SPEC B except location, settings and the GATT transport;
  - data for G-9 (initial order), G-12 (counts and lists), V-1 (no ingest outside recording) and V-5 (union);
  - data for L-2 (counts, distance, active time, empty sessions hidden), D-2, D-3, D-4 (area via bbox), D-7 (places,
    last), D-12 (details, raw table), D-13.7 (stored result), C-6 and D-10 (samples), M-3 (strongest point), M-8
    (routes), O-1.5 (pending uploads);
  - the §2.1 scope semantics (union, materialization).
- **Owns:**
  - `cluetooth-core/**` and its docs;
  - `cluetooth-android/core-api/**`, `core-fake/**` and `core-bridge/**`;
  - `docs/payload-schema-v2.md` (the loss-window wording) and the core sections of `docs/local-data.md` (rewritten
    for v2).
- **Provides:**
  - the §2 UniFFI API;
  - `CoreQueries`, `CoreStorage` and `CoreIngest` implemented in `:core-bridge` (suspend functions on IO; the listener
    goes to a `MutableSharedFlow(extraBufferCapacity = 4)` with a blocking `emit` on `ct-notify`);
  - `:core-fake`.
- **Consumes:** the wiki bundle, `sig-numbers.tsv` (§7 Q4) and the bundle `display_category` (§7 Q1). Until those
  land, it uses the fallback mapping in `core-fake`.
- **Fakes:** for its own tests, Rust fixtures (seeded synthetic sessions plus `test-fixtures/`). It delivers
  `:core-fake` to the others.
  - `:core-fake` is a deterministic port of `data.js`: the same seed, products, devices and sessions.
  - It includes a live simulator emitting 1 Hz deltas with RSSI wander, staleness at 20 s and an append every 5 s.
  - It has demo states equivalent to `?state=1..4|first`, and it implements the scope union (§2.1).
- **Done when:**
  - `mise run //cluetooth-core:check` is green, including the existing schema v2/v3, producer-fixture, matcher and
    upload tests, unchanged. Payload v3 and gatt/v1 bytes must not change.
  - New tests pass for:
    - `ble_adv_split` and generated-column parity with the server (shared vectors, cross-checked against
      `cluetooth_sync.ble.parse_raw`);
    - `payload_raw` round-trip;
    - minute-merge parity with `sighting_sample_better`;
    - the Q-1 vectors (§2.11);
    - each §2.5 query on a fixture, for one session, multi-session and All, including union semantics and the
      display filter;
    - delta cadence ≤ 1/s, coalescing, seq and snapshot consistency;
    - pause/resume active time, interrupted recovery after a simulated kill, and the re-match;
    - migration from version 0 to 1 and the refusal of a newer schema, R*Tree presence and corrupt-file handling;
    - delete with pending tombstones.
  - A bench (`cargo run --example bench_queries`) on a synthetic 3-year DB (§3.6 sizes) meets ⅕ of the §3.6
    budgets on the host.
  - The `:core-bridge` JVM tests call the host-built library through the real bindings, as v3's
    `testDebugUnitTest` does.
- **Merge order:** A0 on day 1 (first), then A after B0 and alongside the others, before integration.

### 6.2 Lane B: Kotlin platform

- **Scope:**
  - V-1 (scan only while recording), V-6 (record on open trigger), the R-2/R-6/R-7 actions, P-5 (status source),
    M-9 (location source);
  - O-1 storage and actions (Upload now, Wi-Fi only, Coded PHY, hide Apple → core), O-2 plumbing (permissions,
    Bluetooth, user ID validation `^[a-z0-9]{1,9}$`);
  - the D-13 runner, steps, failures and cancel (D-13.8);
  - the notifications, the §1.5 first-launch cleanup, and the legacy uploader.
- **Owns:**
  - `cluetooth-android/platform-api/**`, `platform-fake/**`, `platform/**` and `gallery/` (shell only);
  - `settings.gradle.kts` (B0 only);
  - the shared `AndroidManifest` fragments of `:platform` (service, permissions; merged into `:app`).
- **Provides:** `Recorder`, `LocationStatusSource`, `SettingsStore`, `Uploads`, `Permissions`, `GattRunner` and
  `PlatformGraph` (a manual DI container built in `CluetoothApplication`).
- **Consumes:** `CoreIngest` and `CoreStorage` (`:core-api`). It develops against `:core-fake`'s `FakeCoreIngest`,
  which records calls.
- **Fakes it delivers:**
  - `FakeRecorder`, which drives `:core-fake`'s live simulator;
  - `FakeLocationStatus` (`ok|denied|off|waiting|stale`) and `FakeSettings`;
  - `FakeGattRunner`, which follows `data.js discoverGatt`: non-connectable devices time out at Connecting, and
    "ELK-BLEDOM" disconnects while reading;
  - `FakePermissions`.
- **Done when:**
  - unit tests (JVM, Robolectric where needed) pass for:
    - batch building (64 / 250 ms, backpressure retry, overflow count);
    - the location-status state machine (30 s, 2 min, provider and permission transitions);
    - GATT stage→step and failure mapping, using the ported v3 engine tests with the fake `GattLink`;
    - settings migration from `cluetooth_prefs`;
    - the legacy cleanup ordering (`local-data.db` is deleted only after the recorded pending ids are uploaded);
    - Wi-Fi-only work constraints;
  - `mise run //cluetooth-android:check` is green;
  - a device smoke test (manual, documented in `cluetooth-android/README.md`) passes: record 10 min with the screen
    off, swipe the task away, and recording continues with the notification and data. Kill the process with
    `am kill`, relaunch, and the "Recording interrupted" notice appears.
- **Merge order:** B0 on day 1 (first, with A0), then B before integration.

### 6.3 Lane C: design system, components and chrome

- **Scope:**
  - G-1, G-3 to G-8, G-13a, and C-1 to C-17;
  - R-1 to R-9 (the recorder control and its morphs; the R-5 mini map comes from E) and T-7;
  - L-1 to L-3 and T-6 (the sessions list, ☰↔X morph);
  - Q-3, Q-4, Q-5 (field morph and search bar), the Q-6 chip view, Q-7 (bar text reopens the page), T-4 and T-5;
  - O-1 and O-2 (the screens);
  - P-5's popover content (the text per `LocationStatus.State`).
  - The sessions list renders single selection only. **Multi-select UI is not in scope** (§5.3 seam).
- **Owns:** `cluetooth-android/designsystem/**` (generated `Tokens.kt` from `tokens.json`, Inter font, icons from
  `app.js ICONS` as `ImageVector`s, components), `chrome/**` and `gallery/…/lanes/CGallery.kt`.
- **Provides:**
  - `CluetoothTheme` (light/dark, tokens) and every C-n composable (stateless);
  - the composables `RecorderControl(ui, actions)`, `Sidebar(state, onSelect, onSettings)`,
    `SearchPage(state, onPick)`, `SearchBar`, `SearchThisAreaChip`, `SettingsPage`, `Onboarding`,
    `LocationPopover`;
  - the formatting helpers (relative time, durations "1 h 5 min", distance, "−56 dBm").
- **Consumes:** `:core-api` models and the `:platform-api` models and interfaces, using fakes from both.
- **Done when:**
  - a token test proves that every `tokens.json` entry is generated and that no hex or dp literal exists outside
    generated code (a lint/grep check);
  - Roborazzi screenshot tests pass on the JVM for every component and chrome state, light and dark, at 412×915 and
    1024×768, matching the prototype gallery rule by rule;
  - the recorder morph tests pass (capsule ↔ panel geometry: Stop concentric with the corner, R-4 gaps);
  - `RecorderControl` keeps 44 dp touch targets;
  - reduced motion makes every transition instant;
  - every text matches SPEC wording (G-8) through string tests.
- **Merge order:** C1 (`:designsystem`) first after A0/B0, because D and E need it. C2 (`:chrome`) before
  integration.

### 6.4 Lane D: sheet system and contents

- **Scope:**
  - S-1 to S-17 (S-17 through E's camera API);
  - D-1 to D-14 (D-14's camera moves requested from E);
  - G-9 (`OrderedKeys`), G-10, G-11, G-13 (slop), G-14 (items 3 and 6 of the back order, via a handler the app
    calls);
  - T-1 to T-3 and T-9 (the panel states);
  - Q-5 (Discover in search state: list only, "N result(s) [in this area]"), Q-6 (the reorder rule on "Search this
    area"), Q-7 (restore);
  - V-7 (stack reset on a scope change).
  - Scope-agnostic: a multi-session scope renders exactly like All sessions. **No multi-select UI.**
- **Owns:** `cluetooth-android/sheets/**` and `gallery/…/lanes/DGallery.kt`.
- **Provides:**
  - `SheetHost(stack, scope, live, …)` for phone and the tablet panel;
  - `SheetStackController` (push/pop/replace, snap, restore);
  - `SearchController` (`SearchState`), `DiscoverSheet`, `ProductSheet` and `DeviceSheet`;
  - a `MapRequests` callback interface that E implements: `frame(points)`, `focusDevice(key, minZoom = 18.2,
    avoidCover)`, `restore(camera)`, `setFilter(DeviceFilter)` and `setSelection(key)`.
- **Consumes:** `CoreQueries` (`:core-fake`), `GattRunner` (`FakeGattRunner`), and `:designsystem` components.
- **Done when:** Compose UI tests pass on the JVM (Robolectric) for:
  - snap states and peek height;
  - the flick that moves exactly one state (S-14);
  - S-10a 1:1 tracking, asserting `Δoffset == Δpointer` for every move event, slop included, and sheet + scroll =
    finger at Full;
  - S-13 hand-off;
  - S-8 pop sequences (all four triggers) restoring height, scroll, view, filters and camera;
  - S-7: hidden sheets are not composed;
  - S-12 and S-16 (a height that is fixed under tab, view and GATT changes);
  - the G-9 property tests: initial order by RSSI, appends only, filters never reorder, area keep-then-append;
  - the D-13 state machine with `FakeGattRunner`, including cancel on pop;
  - the Q-7 restore;
  - screenshot tests of every sheet state, light and dark, phone and tablet.
- **Merge order:** after C1. Before or alongside E; integration needs both.

### 6.5 Lane E: map

- **Scope:**
  - M-1 to M-11, the D-8 framing, D-14 and S-17 camera execution;
  - P-4 (locate logic and button), P-5 (warning button placement), P-6;
  - D-4 and Q-6 triggers (camera idle by user), T-8, and the R-5 mini map (`MapSnapshotter` image, refreshed when
    the panel opens and every 10 s, so there is no second live map on budget phones).
  - Scope-agnostic: it uses `SessionView.routesFaint` and `routes(scope, …)`. **No multi-select UI.**
- **Owns:** `cluetooth-android/map/**` (style assets generated from `map-style.json` at build) and
  `gallery/…/lanes/EGallery.kt`.
- **Provides:**
  - `CluetoothMap(state, events: MapEvents)`, an implementation of D's `MapRequests`, and `RouteMiniMap(route)`;
  - the cells layers `cells` and `cell-count`, cloned at runtime from `clusters` and `cluster-count` with source
    `cells`. Their paint is identical, and a tap zooms by +2 (M-4 equivalent);
  - the GeoJSON source updates:
    - devices and density from `MapData` on camera idle, scope or filter change;
    - live `best` changes batched to at most one update per 5 s;
    - sightings on selection.
- **Consumes:** `CoreQueries.mapPoints/routes/deviceSightings/sessionView` (`:core-fake`), `LocationStatusSource`
  (`FakeLocationStatus`), and `:designsystem` (FloatButton, colors for non-style UI).
- **Done when:**
  - unit tests pass for:
    - style loading: layer ids and order exactly as in `map-style.json` per theme;
    - selection properties equal `selectionProps` (M-6);
    - M-11 moved-away;
    - P-4 visibility (40 dp from the free-area center, user zoom, app moves ignored);
    - M-10 padding math (phone and tablet);
    - the `byUser` classification of camera moves;
  - an instrumented test on the `cluetooth-test` AVD covers: tap hit box 8 dp, cluster tap zoom + 0.2, empty tap,
    and Points ↔ Cells switching on a 50 k-device fake;
  - a frame-time check while panning 10 k points (JankStats, p95 < 32 ms on the AVD).
- **Merge order:** after C1, alongside D.

### 6.6 Integration (after A, B, C, D and E are merged)

The integration owner is a sixth step, not a lane. It owns `cluetooth-android/app/**` and removes the v3 code.

1. **Replace the old `:app`.**
   - The new `MainActivity`, `CluetoothApplication` (opens `Core`, builds `PlatformGraph`) and `MainViewModel`.
   - G-2 layout mode, the G-14 back dispatcher, the P-1 to P-3 composition, V-1 to V-7 (launch decision, scope
     switching with V-7 resets), T-9, and the R-5 action (collapse, close page, fit route).
   - It deletes the v3 UI, `local/` (LocalStore), `repository/`, the trigger cache, `CoreGateway` and the v3 Kotlin
     bindings generation.
2. **Delete the v3 `CluetoothCore` object** in Rust and raise `API_VERSION` to 8 everywhere.
3. **Swap the fakes** for `:core-bridge` and `:platform` in `:app`. The fakes remain in `:gallery` and tests.
4. **Manifest.** Same applicationId, a higher versionCode, the service and permissions from `:platform`.
5. **Docs.** Update `docs/recording-model.md` (no live mode, pause semantics) and `docs/local-data.md` (v2 DB). In
   `CLAUDE.md`/`AGENTS.md`, update the cluetooth-android and cluetooth-core layout lines.

### 6.7 Verification

1. **Checks.** `mise run check` is green, and `mise run //cluetooth-android:build` builds an APK named
   `cluetooth-0.1.0-debug-<sha8>.apk`, never overwriting an existing file.
2. **Rule by rule against the prototype**, on the `cluetooth-test` AVD at 412×915 and on a 1024×768 tablet AVD, light
   and dark. Every SPEC rule ID is ticked in a checklist committed as `cluetooth-android/design/ui-v2/VERIFY.md`, and
   each list is watched for 60 s (G-9).
3. **On the test phone** `R58M81J3YJV`, plus one Moto G-class phone:
   - a 30 min outdoor recording with the screen off and the task swiped away;
   - `logcat -b crash` is empty;
   - frame time while recording with more than 300 devices (JankStats p95 < 32 ms);
   - memory under 250 MB PSS;
   - the All sessions view on a 3-year synthetic DB pushed to the device (the §3.6 budgets).
4. **In-place upgrade.** Install the current v3 build, record with uploads blocked so pending Parquet exists, then
   install v2. The pending files upload as v3 objects, `local-data.db` is deleted only after them, and the server
   ingests them (`cluetooth --list-only` shows them succeeded).
5. **Data parity.** Re-ingest one recorded session's uploaded objects on a test server DB and compare its
   `sighting_minutes` with the phone's `sighting_minutes` rows: identical keys, counts and best samples.

### 6.8 Merge order summary

```
day 1:   A0 (:core-api, :core-fake)  ─┐
         B0 (skeleton, :platform-api, :platform-fake, :gallery shell) ─┤
next:    C1 (:designsystem) ───────────┤
parallel: A (core + bridge) · B (platform) · C2 (:chrome) · D (:sheets) · E (:map)
then:    integration (:app, v3 removal) → verification
```

Each lane merges into `feat/v2` through its own PR. It may be rebased on A0/B0/C1, and it never edits another
lane's files.

---

## 7. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | The wiki bundle has 19 page categories (a page may have several). C-3 has 7 chip categories. How do pages map to chips? | The bundle exporter emits `display_category` from an explicit table: asset-tracker → trackers; audio-device → audio; lighting-control → lighting; environmental-monitor → sensors; wearable → wearables; vehicle-equipment, fleet-telematics → vehicles; medical-device → medical. The other 12 go to a new **"Other"** chip after Medical (icon: a generic device glyph). A multi-category page takes its first mapped category. |
| Q2 | Should recording run in a separate `:recorder` process, so the OS can kill the UI process alone? | **No.** Keep one process (§1.2). A second process needs a second core instance on the same files, AIDL for commands and deltas, and roughly 40 MB more RAM on budget phones. The foreground service already keeps the process alive without the UI. |
| Q3 | SPEC has no UI for storage stats, delete and export. The API exists (§2.8). Where should it go? | Add three rows to O-1 in the next SPEC round: "Storage" (sizes), "Export all", and "Delete…" (per session, from the sidebar row's long-press later). v2.0 ships the API with no UI. |
| Q4 | `sig-numbers.tsv` (company, service and characteristic names) exists only on the unmerged PR #36 branch. | Cherry-pick `tools/sig-numbers.py` and the asset into `feat/v2` as part of B0. The core parses it (`CoreAssets.sig_numbers_tsv`). |
| Q5 | Pause semantics. SPEC R-6 says "pauses recording". | Pause stops scanning and the precise location request; the service and notification stay. The elapsed time excludes paused time. A resume continues the same session. This is the assumption in §2.3 and §4.1. |

Future note: a server-side database dashboard that reuses the HTML prototype is deferred until after a few Android
iterations.
