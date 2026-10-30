# Cluetooth Android v2: frozen interface (step 0)

This is the contract that the five v2 lanes build against. The product is defined by
`scanner/design/ui-v2/SPEC.md` (rule IDs such as `S-8` refer to it) and the HTML prototype next to it. This file
defines how the app is cut into components, what crosses each boundary, and who owns which files.

- **Frozen** means a lane may not change a signature here on its own. A change needs a PR to this file that the owner
  approves, and every lane that consumes the interface must review it.
- **Additive** changes (a new optional field with a default, or a new query) go through the same PR, without
  re-freezing.

### Owner decisions this contract implements

| Topic | Decision |
|---|---|
| UI | A fresh Jetpack Compose UI, one adaptive layout for phone and tablet, copying the prototype 1:1 per SPEC; it must run well on budget phones (Moto G class). |
| Layers | A thin Kotlin platform layer; the Rust core does the heavy lifting (§1). |
| Scanning | No live mode: scanning happens only while recording. |
| Local data | A new SQLite DB inside Rust, aligned with the research DB. Nothing is pruned automatically; deletion is user-initiated. |
| Plaintext | Local Parquet and gatt JSON stay plaintext and are kept after upload (§1.6). |
| Old data | The v3 local DB is not migrated: pending v3 Parquet uploads first, then `local-data.db` is deleted (§1.5). |
| Uploads | Payload v3 and gatt/v1 are unchanged, with the same applicationId (in-place upgrade). |
| Wiki | Pages open in the external browser; the app bundles rules and the catalog (§3.4). |
| Search | Search follows Q-1 in Rust; area queries take a bbox. |
| List order | Lists keep a stable order as UI-side state of ordered keys (G-9). |
| Scopes | Every session-scoped query takes a `Scope`, so multi-select and filters can come later (§2.1). |
| Process | One process; no separate `:recorder` process (former open question Q2). |
| Settings, storage | Settings gets a "Storage" group (SPEC O-1 items 8–11): usage breakdown, Export with a scope, and Delete local data. |
| Delete | Un-uploaded data is kept until it has uploaded; losing it needs an explicit second confirmation (§2.8). |
| SIG numbers | `sig-numbers.tsv` and its generator are copied from the PR #36 branch in B0 (former Q4). |
| Pause | Pause stops scanning and the precise location request, keeps the foreground service, excludes paused time from the elapsed time, and resumes the same session (former Q5). |
| Categories, filters | Categories are data-driven from the wiki. Filtering lives only in the Discover header's filter button and the Filter sheet (SPEC D-1, D-3a); there is no chip row (§2.2). Trackers means `asset-tracker`. |
| Icons | Category icons come from an app-side table of the 21 wiki categories to Material Symbols names, with a generic fallback. Product icons come from wiki pages, through Gitea #190 (§3.4). |
| Export | Three formats: raw Parquet v3 with gatt/v1 JSON, CSV converted directly from that Parquet (with trailing `session_id` and `source_file` columns), and an aggregated SQLite snapshot (§2.8). |
| Decoding | Decoding (`decoder.js` via QuickJS) is deferred to issue #39. v2.0 only carries the `readable` flag and an always-empty `decoded` field. |

SPEC on `design/ui-v2-prototype` covers these decisions. This PR carries a copy synced at `12975d4`:

- D-1, the header filter button;
- D-3a, the Filter sheet (no chip row);
- O-1 items 8–11, the Storage group;
- T-2, the tablet panel's Open and Partial states;
- the `getCategoryCounts` row in B.

The copy is that commit's `scanner/design/ui-v2/` without `shots/`, plus the single-source map style refactor
(`map-style.js`, `app.js`) and the generated `tokens.json` and `map-style.json`.

Contents:

1. [Architecture](#1-architecture)
2. [Rust core API](#2-rust-core-api)
3. [Local DB schema and catalog](#3-local-db-schema-and-catalog)
4. [Kotlin platform ↔ core](#4-kotlin-platform--core)
5. [UI state models](#5-ui-state-models)
6. [Lanes](#6-lanes)
7. [Open questions](#7-open-questions)

---

## 1. Architecture

### 1.1 Components

```
┌──────────────────────────── app process (edu.ucsd.sysnet.cluetoothscanner) ────────────────────────────┐
│  :app  MainActivity ── MainViewModel (scope, wiring, layout mode, back order)                           │
│        │                                                                                                │
│   :chrome (C)        :sheets (D)          :map (E)           ← feature modules: never depend on each other│
│   recorder, side-    sheet stack, Discover/ MapLibre, style                                             │
│   bar, search,       product/device sheets, layers, camera,                                             │
│   row, settings,     Filter sheet          selection, area                                              │
│   onboarding                                                                                            │
│        └───────────────┬──────────────────────┘                                                         │
│   foundation:  :designsystem (C1)  :ui-contract (U0)  :core-api (A0)  :platform-api (B0)                │
│                                                                                                         │
│   :core-bridge (A) UniFFI glue ◄──── ingest ──── :platform (B) BLE scanner, location, RecordingService,  │
│        │                                          GATT runner, permissions, notifications, settings,    │
│        │                                          upload (WorkManager + Firebase)                       │
│   libcluetooth_core.so (Rust, cluetooth-core/): writer thread · notify thread · read pool               │
│   SQLite+R*Tree (local.db) · Parquet v3 writer · upload queue · rule matcher + catalog · export        │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Rust core** (`cluetooth-core/`, lane A). It does all the heavy lifting:
  - session lifecycle including pause;
  - ingest of scan observations, location fixes and GATT results;
  - Parquet v3 rotation, sealing and encryption at upload time (unchanged), and the upload queue;
  - the new local DB, the catalog and rule matching (wiki bundle);
  - every UI query, the live state and the live deltas;
  - search (Q-1), filters and category counts, export, delete, and storage stats.
- **Kotlin platform** (`:platform`, lane B). Only what needs Android:
  - BLE scanning and GATT, and location;
  - the foreground recording service and notifications;
  - permissions and Bluetooth enablement;
  - settings storage, and the Firebase upload transport through WorkManager.
- **UI** (lanes C, D and E plus integration). This is Jetpack Compose. It reads data only through `:core-api` and
  `:platform-api`, and the UI lanes talk to each other only through `:ui-contract` (§6.0).

### 1.2 Processes and recording survival

There is **one process** (owner decision). The recording must survive the UI going away. Here is how:

1. **Recording is not owned by the UI.** The Rust `Core` handle, the `Recorder` (B) and the scanner and location
   clients are process singletons. They live in a lazily built `AppGraph` that `CluetoothApplication` creates; they
   are never tied to an Activity, ViewModel or composition.
2. **The core opens off the main thread.**
   - `Application.onCreate` only starts `AppGraph.core: Deferred<Core>`, which calls `Core.open` on
     `Dispatchers.IO` (it may migrate or recover a large DB).
   - The Activity keeps the system splash screen until the core is ready.
   - `RecordingService` awaits the same `Deferred`. Nothing touches the core on the main thread.
3. **`RecordingService` is a started foreground service.**
   - Its type is `location|connectedDevice`, and it keeps the default `stopWithTask="false"`.
   - `Recorder.start()` checks the `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` and fine location grants **before**
     `startForegroundService`/`startForeground`, because Android 14 throws a `SecurityException` for a
     `connectedDevice` service without them.
   - The start happens while the Activity is visible (R-2, V-6), so the restrictions on foreground services and on
     while-in-use location are met.
   - **Pause keeps the service in the foreground.** The notification's Resume action relies on that.
   - The service holds the scan registration, the location request and a partial wake lock bounded at 12 h. It holds
     no UI references.
4. **The UI is disposable.** With the Activity destroyed, the task swiped away, or the configuration changed, the
   process keeps foreground-service priority. When `MainActivity` comes back, it rebuilds from queries plus a fresh
   live subscription (§2.6).
5. **UI memory is released when hidden.** On `onStop`, `:map` stops MapLibre, and on `onTrimMemory(UI_HIDDEN)` the
   app drops query caches. This keeps a budget phone's low-memory killer from preferring the process.
6. **Process death is the only thing that stops a recording** (an OEM killer, OOM, or a crash). It is made durable
   and consistent (§2.4):
   - every `durability_interval_ms` (5 s), the writer first appends the Parquet staging WAL, then commits to SQLite
     exactly the observations that append covered;
   - so after a kill, the phone DB never holds a row the server will not receive;
   - on the next start, `Core::open` marks the open session `Interrupted` at its last committed data, and
     `:platform` posts the one-time "Recording interrupted" notice.
7. **No automatic restart.** The service stays `START_NOT_STICKY`. A sticky restart from the background may not
   start a location foreground service, and it would not get while-in-use location.

### 1.3 Threads

| Thread | Owner | Does |
|---|---|---|
| main | Android | Compose, MapLibre, scan callbacks (posted off immediately) |
| `scan-batcher` | B: `Dispatchers.IO.limitedParallelism(1)` | batches ≤ 64 observations / 250 ms and calls `ingest_scans` |
| `ct-writer` | A: Rust `std::thread` | the only DB and Parquet writer: owns the store, the pending buffers and the live state; drains its inbox (ingest, recording, GATT, ticks); appends staging and commits every `durability_interval_ms` |
| `ct-tick` | A | sends the writer the commit tick every `durability_interval_ms`, so a recording commits (and flips staleness) even when no scans arrive |
| `ct-notify` | A | owns the listener; calls `CoreListener.on_live` / `on_event`; coalesces while Kotlin is busy |
| `ct-read` | A | the one reader: owns the read connection and the latest immutable snapshot from the writer, and runs every query ([P] and [V]) in arrival order |
| caller coroutines | UI on `Dispatchers.IO` | await async exports; cancelling the coroutine cancels the query |
| WorkManager worker | B | `prepare_upload` → Firebase `putFile` → `mark_upload_*` |
| GATT runner | B: one coroutine + binder callbacks | at most one discovery at a time |

Rules:

- No core call runs on the main thread.
- Ingest calls only enqueue.
- No locks (AGENTS.md): each thread owns its state, and threads talk through queues, with a one-shot reply when a
  caller needs an answer.
- Queries never wait for the writer. The reader reads SQLite in WAL mode with the immutable snapshot the writer
  sends it (display filter, live session, current RSSI); a call that changes what reads see (the display filter,
  start, stop, ingest) returns only after the writer has queued its snapshot, so a later query sees it.
- One reader is enough until measurements show queueing latency; only then does it become a pool.
- Upload, export and delete run off the writer, so encryption never stalls ingest.

### 1.4 Data flow

```
ScanCallback ─► ScanObservationInput ─► batcher ─► Core.ingest_scans ─► queue ─► ct-writer
LocationCallback ─► LocationFix ───────────────────► Core.ingest_location ───┘   │
                                                     every 1 s: live snapshot + LiveDelta (≤ 1/s) ─► ct-notify ─► CoreListener
                                                     every 5 s: staging WAL append ─► SQLite commit of exactly those rows:
                                                        new payloads: ble_adv_split, dedup, match the page once;
                                                        sighting_minutes, session_devices, session_device_payloads, devices, cells
                                                     on rotation or stop: seal → pending/scans/v3/… .parquet (plaintext)
GATT runner ─► gatt/v1 JSON ─► Core.ingest_gatt ─► pending/gatt/v1/… .json (plaintext) + gatt tables
WorkManager ─► pending_uploads → prepare_upload (encrypt to upload-cache/) → putFile → mark_upload_succeeded
               (plaintext moves to archive/, ciphertext deleted)
UI ─► async Core queries(Scope, DeviceFilter, …) ─► read pool or live snapshot ─► Kotlin models
```

### 1.5 Data directory and first launch

The data directory is `filesDir/cluetooth-core/`, the same as v3, so the in-place upgrade keeps the queue.

| Path | Contents | Lifetime |
|---|---|---|
| `pending/scans/v3/…/<uuid>.parquet` (`v2/` for older) | sealed plaintext payloads awaiting upload | until upload succeeds, then moved to `archive/` |
| `pending/gatt/v1/…/<uuid>.json` | plaintext gatt/v1 awaiting upload | same |
| `archive/sessions/<session>/scans/…`, `archive/gatt/v1/…` | uploaded plaintext | kept; removed only by Delete local data or "Delete uploaded raw files" (§2.4.1) |
| `upload-cache/<object path>.encrypted` | ciphertext | transient (§1.6) |
| `upload-state/`, `active-payload/`, `export-temp/` | as v3; `upload-state/archive-deleted/<session>` marks a session whose uploaded files were deleted (R-10), so it stays Uploaded | as v3 |
| `sessions/*.json` | pre-v2 session metadata | kept; these sessions are not imported into the DB |
| `local.db` (+ `-wal`, `-shm`) | the v2 DB (§3) | kept; user deletion only |

First v2 launch. The rule: v2 deletes only what it has verifiably taken over or already uploaded, and keeps
everything else.

- v3's pending payloads are in the same `pending_uploads()` list, so the normal upload worker drains them; the core
  archives each one after its upload, as for v2's own.
- v3's upload sessions, cache directories and WorkManager work are taken over under the same names (the first start
  replaces v3's queued work once); orphaned sessions and cached ciphertext are discarded at every pass.
- The pre-0.0.5 JSONL files are uploaded and renamed `.uploaded` (kept, as v3 did); unsupported names move to
  `quarantine/legacy/` (kept).
- `databases/local-data.db` (the v3 Kotlin store) is **kept**: it holds recordings, routes and sighting history that
  exist nowhere else on the phone, nothing in it is uploaded, and v2 does not import it. The earlier plan to delete
  it after the v3 payloads drained is open for the owner's decision.

A v3 recording that is still open is recovered by `Core::open`:

- its staging WAL is sealed as a v3 payload;
- its `sessions/<id>.json` is marked interrupted, as v3 does.

The pre-0.0.5 JSONL uploader is ported unchanged (`LegacyJsonlCore`, through the same pass reducer and runner as the
payloads, on the scans actor; sealed with the same recipient key).

### 1.6 Plaintext locally, ciphertext only at upload

- **Plaintext, kept permanently.** Local Parquet files and gatt/v1 JSON are plaintext. They follow the
  keep-everything rule: pending until uploaded, then archived.
- **Ciphertext is transient.** It exists only in `upload-cache/`:
  - `prepare_upload` regenerates it from the plaintext on every attempt (`core.rs` `prepare_upload`, about lines
    646–705);
  - it is deleted after a successful upload, after a failed one, and as an orphan at reconcile.
- **Export reads the plaintext directly** (§2.8). Nothing is decrypted.
- **Verified in v3 and kept in v2.** On success, the plaintext is *renamed* into `archive/`:
  - scans: `core.rs` `archive_payload`, about lines 1170–1203;
  - gatt: `gatt.rs` `archive`, about lines 633–646.

  The pending copy is removed only when an identical archive copy already exists. The only other plaintext removal
  is the user's delete.
- **v2 rule:** no code path except `delete_local_data` removes plaintext. Lane A tests that after
  `mark_upload_succeeded` the archived file exists with the same SHA-256 as before the upload, and that the
  ciphertext is gone.

---

## 2. Rust core API

UniFFI 0.32 with proc-macros, as today. The bindings are generated into package
`edu.ucsd.sysnet.cluetoothscanner.core.generated`, and `API_VERSION` becomes **8**. The new object is `Core`.

During the lanes, the v3 `CluetoothCore` object stays compiling for the old `:app`. Lane A may add but not remove
until integration (§6.7).

`rusqlite` features: `bundled`, `array` (for `rarray`), `functions` (for `match_device` in SQL), and `window`.

Notation is proc-macro pseudocode. The tag after each call gives its threading model:

| Tag | Meaning | Kotlin |
|---|---|---|
| **[N]** | Non-blocking: memory or an enqueue | any background thread |
| **[W]** | Applied by the writer; returns when applied (≤ 1 tick) | `Dispatchers.IO` |
| **[P]** | Point query: `async`, on `ct-read`; cheap (≤ 20 ms); cancellable | `suspend` |
| **[V]** | Viewport or filter query: `async`, on `ct-read`; **cancellable** (below) | `suspend` |
| **[F]** | File I/O under the file lock (upload, export, delete); `async` and cancellable where marked | `suspend` / worker |

**Cancellation.** Every [P], [V] and [F] query is an `async` UniFFI export. The query is a job in `ct-read`'s
queue, and the future awaits its one-shot reply, so no async runtime is needed. Cancellation is the caller
dropping the future:

- **Not started:** the reader skips the job when its reply has been dropped.
- **In flight:** dropping the future sets the query's own cancel flag. The reader's progress handler checks it every
  1,000 VM steps and stops the query with `CoreError::Cancelled`. The flag belongs to that query alone, so no other
  query is ever hit, and a drop can't fall between the reader's look and the first step (as `sqlite3_interrupt`,
  a no-op while nothing runs, could).
- **There is no `Superseded`** and no per-kind latest-wins in the core. Callers that only want the newest result
  (the camera, search) use `mapLatest`/`collectLatest`: cancelling the previous coroutine drops its future, which
  cancels its query as above.
- **Kotlin side.** `:core-bridge` maps `Cancelled` to `CancellationException`.
- **Test fallback.** Lane A's DoD includes a test that cancellation reaches SQLite. If UniFFI's Kotlin cancel path
  does not drop the future, an explicit `CancelToken` object (`fn cancel(&self)`) is added as a trailing parameter.
  That is an additive change.

### 2.1 Scope

Every session-scoped query and action takes a `Scope`, never a bare session id.

```rust
#[derive(uniffi::Enum)]
pub enum SessionScope { All, Sessions { ids: Vec<String> } }   // one session = a one-element list

#[derive(uniffi::Record)]
pub struct SessionFilter {                 // selects which sessions take part; extensible with defaulted fields
    #[uniffi(default = None)] pub started_from_ms: Option<i64>,
    #[uniffi(default = None)] pub started_to_ms: Option<i64>,
}

#[derive(uniffi::Record)]
pub struct Scope { pub sessions: SessionScope, #[uniffi(default = None)] pub filter: Option<SessionFilter> }
```

- **Resolution.** The session set is `sessions ∩ filter`.
  - Empty sessions never take part.
    - Exception (V-3): a scope of exactly one session, `Scope.session(id)` with no filter, resolves to that session
      even with 0 devices. `session_view` then has its route and frame (Start/End per M-8), and the device lists are
      empty. All sessions, several sessions, any filter, and `sessions()` still leave empty sessions out.
  - The filter selects whole sessions and never cuts sightings inside a session.
  - Unknown ids are ignored, and an empty resolution returns empty results.
- **Union, as in All sessions (V-5).**
  - Each device appears once, at its strongest point across the resolved sessions.
  - Its RSSI is the strongest seen, and its name is the latest non-empty name.
  - Its page follows §3.5, applied over the union.
  - Counts, categories, filters, the area and search are computed over the union.
  - Routes: a one-session scope has one route with Start and End. A multi-session scope, or `All`, draws every
    route faintly with no Start or End (`SessionView.routes_faint`).
- **Live.** A scope is live only when it is exactly `Sessions{[current]}` with no filter and that session is
  recording or paused. Every other scope is frozen, including `All` and unions that contain the current session.
- **Execution.**
  - `All` without a filter uses `devices`.
  - One session uses `session_devices`.
  - Any other scope is computed **on the fly** per query (§3.6). There is no cache in v2.0. The multi-select feature
    adds one if its scopes are large, with no API change.
- **The first UI** builds only `Scope(All)` and `Scope(Sessions([id]))`.

### 2.2 Shared types and filters

```rust
pub type DeviceKey = i64;  // the 48-bit MAC as an integer (AA:BB:CC:DD:EE:FF → 0xAABBCCDDEEFF)

#[derive(uniffi::Record)] pub struct GeoPoint { pub lon: f64, pub lat: f64 }
#[derive(uniffi::Record)] pub struct Bbox { pub west: f64, pub south: f64, pub east: f64, pub north: f64 }

/// A packed key list: little-endian i64s. :core-bridge exposes it as a LongArray (no boxing for 10^5 keys).
#[derive(uniffi::Record)] pub struct DeviceKeys { pub le_i64: Vec<u8>, pub seq: u64 }   // seq: §2.6 (0 when frozen)

#[derive(uniffi::Enum)] pub enum Identification { Any, Identified, Unidentified }

#[derive(uniffi::Record)]
pub struct DeviceFilter {                       // filters only hide rows (G-9); every field ANDs with the others
    #[uniffi(default = None)]  pub area: Option<Bbox>,            // D-4 This area: best point inside
    #[uniffi(default = Any)]   pub identification: Identification, // Filter sheet
    #[uniffi(default = [])]    pub categories: Vec<String>,       // any-of (a set); empty = no constraint
    #[uniffi(default = false)] pub readable: bool,                // preset: the matched page has a decoder.js
    #[uniffi(default = false)] pub trackers: bool,                // preset: the matched page is a tracker (§3.4)
    #[uniffi(default = false)] pub vulnerable: bool,              // preset: the matched page links a vulnerability page
    #[uniffi(default = false)] pub in_range_only: bool,           // Nearby (live scopes only)
    #[uniffi(default = None)]  pub page: Option<String>,          // product sheet (identified page)
    #[uniffi(default = None)]  pub query: Option<String>,         // Q-1 search: only matches (Q-5 map)
}

#[derive(uniffi::Enum)] pub enum PageKind { Device, Platform, Identifier, Vulnerability }
#[derive(uniffi::Record)] pub struct PageRef { pub id: String, pub kind: PageKind, pub title: String }
#[derive(uniffi::Record)] pub struct CategoryCount { pub category: String, pub label: String, pub device_count: u32 }
#[derive(uniffi::Record)] pub struct DisplayFilter { pub hide_unnamed_apple: bool }
```

The filter model (SPEC D-1, D-3):

- **Where filtering lives.** Only in the Discover header's filter button and the **Filter sheet** (D-3a); there is
  no chip row. The sheet holds, in order:
  - the Readable, Trackers and Vulnerable switches (AND-combined);
  - Identification: [Any | Identified | Unidentified];
  - Categories, an any-of checklist;
  - Clear.

  Changes apply live, and the button shows a count badge while any filter is active.
- **Faceted counts.** `category_counts` applies every other active constraint (the switches, Identification, the
  area, Nearby and the Apple filter) but not the category facet, so a count always equals the resulting list
  length.
- **Product sheet.** An identified product sets `page`. The "Unidentified devices" card sets
  `identification = Unidentified`.
- **Reset.** Filters reset when the session view (scope) changes (V-7).
- **Search ignores the filters.** In search state the UI queries with `{query, area}` only, and the saved filter
  comes back unchanged when search ends (D-3a, Q-7).
- **Apple filter.** Every list, count, `category_counts` result, map payload and delta respects the display filter
  (G-12). It is core state, set by `:platform` from the setting, and never affects ingest, Parquet or upload.

### 2.3 Lifecycle and recording

```rust
#[derive(uniffi::Object)] pub struct Core { /* … */ }

#[derive(uniffi::Record)]
pub struct CoreConfig {
    pub data_directory: String,
    pub recipient_public_key: Vec<u8>,          // 32 bytes, as today
    pub location_max_age_ms: u64,               // 5000
    pub payload_max_rows: u32, pub payload_max_estimated_bytes: u64, pub payload_max_age_ms: u64, // 100k / 10 MiB / 5 min
    pub durability_interval_ms: u64,            // 5000: staging append, then the matching SQLite commit (§1.2)
    pub stale_after_ms: u64,                    // 20000: "not in range" (C-5, D-2)
    pub live_interval_ms: u64,                  // 1000: delta cadence; minimum 1000
}
#[derive(uniffi::Record)]
pub struct CoreAssets {
    pub wiki_bundle_json: String,               // assets/wiki-bundle.json, bundle schema 2 (§3.4)
    pub sig_numbers_tsv: String,                // assets/sig-numbers.tsv (company, uuid16, appearance, adtype)
}
#[derive(uniffi::Record)]
pub struct ScanEnvironment { /* unchanged from v3: device_model, android_release, android_sdk, app_version,
                                cap_ext_adv, cap_coded_phy, cap_max_adv_len, scan_phy, scan_legacy_only */ }

#[derive(uniffi::Enum)] pub enum RecordingPhase { Idle, Recording, Paused }
#[derive(uniffi::Record)]
pub struct RecordingState {
    pub phase: RecordingPhase, pub session_id: Option<String>, pub started_ms: Option<i64>,
    pub active_before_ms: i64, pub running_since_ms: Option<i64>,  // elapsed = before + (now − since); UI ticks locally
    pub device_count: u32, pub distance_m: f64,                   // shown devices (display filter applied)
    pub paused_for_storage: bool,                                 // SPEC R-10 pause (§2.4.1)
    pub storage_failure: Option<String>,                          // paused after 60 s of failures (§2.4)
}

#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    fn on_live(&self, delta: LiveDelta);        // on ct-notify; may block (the core coalesces meanwhile)
    fn on_event(&self, event: CoreEvent);
}

#[uniffi::export]
impl Core {
    #[uniffi::constructor]
    pub fn open(config: CoreConfig, assets: CoreAssets) -> Result<Arc<Core>, CoreError>;
    // [F] blocking; Kotlin calls it on Dispatchers.IO (§1.2). Opens or migrates local.db, marks a session left
    //     open as Interrupted, reconciles uploads, seals a recovered staging WAL (v3 or v2 staging alike), starts
    //     the threads, and schedules a background re-match if the rules key changed (§3.3).
    pub fn close(&self);                                             // [W] append + commit, seal, stop threads
    pub fn api_version(&self) -> u32;                                // [N] 8
    pub fn set_listener(&self, listener: Option<Arc<dyn CoreListener>>);  // [N]
    pub fn set_display_filter(&self, filter: DisplayFilter);              // [N] → CoreEvent::DataInvalidated
    pub fn set_live_focus(&self, key: Option<DeviceKey>);                 // [N] chart rides on deltas (D-10)

    pub fn start_recording(&self, env: ScanEnvironment) -> Result<String, CoreError>;  // [W] new UUIDv7
    pub fn pause_recording(&self) -> Result<(), CoreError>;          // [W] append + commit; ingest ignored while paused
    pub fn resume_recording(&self) -> Result<(), CoreError>;         // [W] same session; after a storage-full
                                                                     //     pause Err(StorageFull) below the floor
    pub fn stop_recording(&self) -> Result<String, CoreError>;       // [W] append + commit, seal, simplify route,
                                                                     //     finalize counts; returns the session id
    pub fn configure_scan(&self, env: ScanEnvironment) -> Result<(), CoreError>; // [W] footer change mid-session
    pub fn recording_state(&self) -> RecordingState;                 // [N]
}
```

The environment goes into `start_recording`, which removes v3's hidden "`configure_scan` first" ordering. Pauses are
recorded in `session_pauses`.

### 2.4 Ingest

```rust
#[derive(uniffi::Record)] pub struct ScanObservationInput { /* unchanged from v3 scan.rs */ }
#[derive(uniffi::Record)] pub struct LocationFix { /* unchanged: lat, lon, accuracy_meters, observed_at_ms, elapsed_realtime_nanos */ }
#[derive(uniffi::Record)] pub struct IngestAck { pub accepted: u32, pub rejected: u32, pub ignored_not_recording: u32 }

impl Core {
    pub fn ingest_scans(&self, batch: Vec<ScanObservationInput>) -> Result<IngestAck, CoreError>;
    // [W] validates (v3 rules: ≤ 64 rows, ≤ 1 MiB, raw ≤ 1650 B) and buffers until the next commit.
    //     Answers after the payload took the batch. Err(Backpressure) when the payload refused it (not for space),
    //     or while commits keep failing and the buffer is at its cap: keep the batch and retry.
    //     Err(StorageFull) for lack of space or while paused for storage (§2.4.1): drop and count it.
    pub fn ingest_location(&self, fix: LocationFix);                 // [N] fix ring + route (≤ 100 m accuracy, ≥ 5 m or 30 s)
    pub fn clear_location(&self);                                    // [N]
    pub fn new_gatt_discovery_id(&self) -> Result<String, CoreError>;          // [N]
    pub async fn gatt_target(&self, key: DeviceKey) -> Option<GattTarget>;      // [P] trigger adv from the latest payload
    pub fn ingest_gatt(&self, gatt_v1_json: String) -> Result<GattView, CoreError>;
    // [W] validates gatt/v1 (unchanged), writes pending/gatt/v1 (plaintext), fills gatt tables, returns the view
}
#[derive(uniffi::Record)]
pub struct GattTarget {
    pub addr: String, pub addr_type: Option<String>, pub name: Option<String>,
    pub trigger_raw: Vec<u8>, pub trigger_rssi: Option<i16>, pub trigger_scanned_at_ms: i64,
    pub trigger_is_legacy: Option<bool>, pub trigger_primary_phy: Option<i32>,
    pub trigger_secondary_phy: Option<i32>, pub trigger_adv_sid: Option<i32>,
    pub session_id: Option<String>,
}
```

The writer, per observation and then per interval:

1. **Validate and buffer.** The Parquet row (lat/lon from the fix ring, unchanged) goes into the staging buffer.
2. **New payloads.** Only for a new `sha256` (an LRU of 50k hashes, then the DB): run `ble_adv_split`, insert the
   structures, and match the page once (§3.4).
3. **Live state.** Update the in-memory open-minute aggregates, the session devices, the per-device payload counts
   and the live rings.
4. **Every 1 s.** Swap the live snapshot and emit the delta. This is from memory; nothing is committed yet.
5. **Every `durability_interval_ms`, and on pause, stop and close:**
   - append the staging WAL (Parquet durability);
   - then, in **one SQLite transaction**, upsert the aggregates of exactly the observations that append covered
     (`sighting_minutes`, `session_devices`, `session_device_payloads`, `devices`, `device_cells`, `page_totals`),
     the route points and the session counters.

   If the append fails, nothing is committed and `StorageWarning` is emitted. Rows are never committed ahead of
   Parquet.

Frozen views of a scope that includes the current session lag ≤ 5 s. The live view always reads memory.

**Bounded buffer.** The pending buffer is capped at 20,000 rows or 16 MiB (the v3 input estimate). A batch that
would pass the cap commits at once. A commit that fails for another reason keeps its batch (StorageWarning) and
backs off: the next interval commit waits the interval, doubled per failure, at most 60 s. While commits fail and
the buffer is at its cap, `ingest_scans` returns `Backpressure`, so memory stays below the cap plus one batch.

**Staging before the ack.** `ingest_scans` answers only after the Parquet payload took the batch. A refusal for lack
of space (`ENOSPC`) is `StorageFull` and the R-10 pause; any other refusal is `Backpressure` (keep the batch,
retry) with a `StorageWarning`, and the batch is not buffered. When staging or committing keeps failing for 60 s,
writing stops: the buffer is dropped (counted), the recording pauses with `storage_failure`, and
`CoreEvent::StorageFailed { message, dropped_scans }` is emitted. Resume tries again.

**Upload states.** The session list's upload state comes from the payload core's known files. When reading them
fails, the last states read stand (with a `StorageWarning`); before any read, `sessions()` returns the error.
`Empty` means only a session with no payload: local-only, recorded before the Parquet staging (no backfill).

#### 2.4.1 Storage full (SPEC R-10)

```rust
#[derive(uniffi::Record)] pub struct Reclaimable { pub free_bytes: u64, pub uploaded_raw_bytes: u64, pub local_database_bytes: u64 }

impl Core {
    pub async fn reclaimable(&self) -> Result<Reclaimable, CoreError>;     // [W] free space and what each action frees
                                                                           //     (the database figure is an estimate)
    pub async fn delete_uploaded_raw(&self) -> Result<u64, CoreError>;     // [W] archive/ only; bytes freed
    pub async fn clear_local_database(&self) -> Result<u64, CoreError>;    // [W] every session but the current one;
                                                                           //     files kept; vacuums; bytes freed
}
```

- **Stop writing.** Every commit tick reads the data volume's free space (`statvfs`). Below 200 MB, or when a
  commit fails with `SQLITE_FULL`/`ENOSPC`, the writer stops writing: the pending rows are dropped and counted, the
  recording pauses (`paused_for_storage`), the session stays open, and `CoreEvent::StorageFull { free_bytes,
  dropped_scans }` is emitted. Everything committed before stays. A stop whose commit fails for lack of space still
  finishes the session at its committed data.
- **While paused for storage** `ingest_scans` returns `Err(StorageFull)`.
- **Resume** is the UI's call. It returns `Err(StorageFull)` while free space is still below the floor; above it,
  recording continues in the same session. A user pause is not a storage pause: its resume needs no free space.
- **Delete uploaded raw files** removes the regular files under `archive/` (uploaded plaintext). `pending/` and the
  upload markers are never touched. The payload core forgets the deleted files and marks their sessions, which stay
  Uploaded in the session list; exporting a session's raw data needs the files, so it fails for those sessions.
- **Clear local database** deletes every session except the current one with its rows, the GATT discoveries outside
  it and the payloads nothing references any more, recomputes the devices, then `VACUUM`s. No file is touched, so
  un-uploaded raw files still upload. When both actions are chosen, run Delete uploaded raw files first: the vacuum
  needs some free room.

### 2.5 Queries (SPEC B)

```rust
impl Core {
    pub async fn sessions(&self) -> Result<Vec<SessionSummary>, CoreError>;            // [P] finished, newest first, no empty
    pub fn current_session(&self) -> Option<SessionSummary>;                           // [N]
    pub async fn all_sessions_summary(&self) -> Result<AllSessionsSummary, CoreError>; // [P] counters
    pub async fn discovery_rate(&self, session_id: String) -> Result<DiscoveryRate, CoreError>; // [P] R-4a (§2.5.1)
    pub async fn session_view(&self, scope: Scope) -> Result<SessionView, CoreError>;  // [P] getRecording minus points
    pub async fn routes(&self, scope: Scope, viewport: Option<Bbox>, zoom: f64) -> Result<String, CoreError>;
        // [V Routes] GeoJSON LineStrings for "route", simplified for zoom; multi-session: routes meeting the viewport
    pub async fn device_order(&self, scope: Scope) -> Result<DeviceKeys, CoreError>;
        // [P] every shown device, sorted ONCE by RSSI desc (live: current; frozen: strongest), ties by key (G-9)
    pub async fn device_keys(&self, scope: Scope, filter: DeviceFilter) -> Result<DeviceKeys, CoreError>;
        // [V AreaKeys] keys passing the filter, in device_order order; the UI intersects with its own order
    pub async fn device_rows(&self, scope: Scope, keys: Vec<DeviceKey>) -> Result<Vec<DeviceRow>, CoreError>;
        // [P] ≤ 200 keys per call (LazyColumn paging), in the given order
    pub async fn category_counts(&self, scope: Scope, filter: DeviceFilter) -> Result<Vec<CategoryCount>, CoreError>;
        // [V AreaKeys] getCategoryCounts: every category present among the filtered devices, device_count desc then
        //     label; faceted (D-3a): computed with filter.categories ignored, all other constraints applied, so a
        //     count equals the list length after checking it; a device counts once in each category of its page
    pub async fn products(&self, scope: Scope, filter: DeviceFilter) -> Result<Vec<CategoryGroup>, CoreError>;
        // [V AreaProducts] getProducts: groups by the page's FIRST category, groups by device count desc,
        //     "unidentified" last as one card (D-2); filter.page is ignored
    pub async fn product(&self, scope: Scope, page_id: Option<String>, filter: DeviceFilter)
        -> Result<Option<ProductDetail>, CoreError>;                               // [P] None page = Unidentified devices
    pub async fn device(&self, scope: Scope, key: DeviceKey) -> Result<Option<DeviceDetail>, CoreError>;  // [P]
    pub async fn device_sightings(&self, scope: Scope, key: DeviceKey) -> Result<Vec<Sighting>, CoreError>; // [P] ≤ 10 000
    pub async fn device_chart(&self, key: DeviceKey) -> Option<LiveChart>;          // [P] D-10, live only
    pub async fn device_gatt(&self, key: DeviceKey) -> Result<Option<GattView>, CoreError>;  // [P] D-13.7
    pub async fn search(&self, scope: Scope, query: String, area: Option<Bbox>) -> Result<Option<DeviceKeys>, CoreError>;
        // [V Search] Q-1/Q-2; None for a blank query; device_order order
    pub async fn map_points(&self, scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: f64) -> Result<MapData, CoreError>;
        // [V MapPoints] "devices"/"density" for the viewport (+50 %: the bbox padded by a quarter of its width
        //     and height on each side), never the whole scope (§3.7); filter.query limits it to search matches (Q-5)
}
#[uniffi::export] pub fn match_device(query: String, name: Option<String>, addr: String) -> bool;  // [N] Q-1, pure
```

Result records (each maps 1:1 to §5.1):

```rust
#[derive(uniffi::Enum)] pub enum SessionStatus { Recording, Paused, Completed, Interrupted }
#[derive(uniffi::Enum)] pub enum UploadState { Pending, Uploaded, Failed, Empty }
#[derive(uniffi::Record)]
pub struct SessionSummary { pub id: String, pub status: SessionStatus, pub started_ms: i64, pub ended_ms: Option<i64>,
    pub active_ms: i64, pub distance_m: f64, pub device_count: u32, pub upload: UploadState }
#[derive(uniffi::Record)] pub struct AllSessionsSummary { pub session_count: u32, pub device_count: u32, pub bbox: Option<Bbox> }
#[derive(uniffi::Record)]
pub struct SessionView { pub scope: Scope, pub live: bool, pub session_ids: Vec<String>, pub device_count: u32,
    pub frame: Option<Bbox>, pub route: Vec<GeoPoint>, pub routes_faint: bool, pub seq: u64 }

#[derive(uniffi::Record)]
pub struct DeviceRow {                 // C-5
    pub key: DeviceKey, pub addr: String, pub name: Option<String>,
    pub page: Option<PageRef>,         // the matched page (§3.5); None = unidentified
    pub categories: Vec<String>,       // the page's categories, page order
    pub traits: Vec<String>,           // the page's traits
    pub readable: bool,                // the page has a decoder.js
    pub vulnerable: bool,              // the page links a vulnerability page
    pub icon: Option<String>,          // the page's product icon (asset path, §3.4); None → the category icon
    pub rssi: Option<i16>,             // live: current; frozen: strongest
    pub in_range: bool,                // live: not stale; frozen: true
    pub spark: Option<Vec<u8>>,        // always None: rows have no sparkline (C-5/C-6, ab39eb5); kept for compatibility
    pub best: Option<GeoPoint>, pub last_seen_ms: i64,
}
#[derive(uniffi::Record)]
pub struct ProductCard { pub id: String /* page id or "unidentified" */, pub title: String, pub kind: Option<PageKind>,
    pub icon: Option<String>, pub live: bool, pub count: u32, pub in_range: u32, pub last_seen_ms: i64 }
#[derive(uniffi::Record)] pub struct CategoryGroup { pub category: String, pub label: String, pub products: Vec<ProductCard> }
// Cards inside a group come in bundle page order (the initial order); the UI keeps that and appends new ones (G-9).

#[derive(uniffi::Record)]
pub struct ProductDetail {             // D-6, D-7
    pub page: Option<PageRef>, pub title: String, pub one_line: Option<String>,
    pub categories: Vec<String>, pub traits: Vec<String>, pub readable: bool,
    pub vulnerabilities: Vec<PageRef>, pub wiki_url: Option<String>,
    pub icon: Option<String>, pub icon_credit: Option<IconCredit>,   // C-7/D-7 hero image; credit shown with it
    pub places: u32,                   // distinct z17 tiles of the devices' best points (scope, filter)
    pub last_seen_ms: Option<i64>, pub device_count: u32,
}

#[derive(uniffi::Record)] pub struct IconCredit { pub source: String, pub license: String, pub author: Option<String> }

#[derive(uniffi::Enum)] pub enum AdvFormat { Legacy, Extended, Unknown }
#[derive(uniffi::Record)] pub struct AdStructure { pub len: u8, pub ad_type: u8, pub data: Vec<u8> }
#[derive(uniffi::Record)]
pub struct DeviceDetail {              // D-9, D-12; from the device's latest payload in scope
    pub key: DeviceKey, pub addr: String, pub name: Option<String>,
    pub page: Option<PageRef>, pub categories: Vec<String>, pub traits: Vec<String>,
    pub readable: bool, pub vulnerable: bool, pub vulnerabilities: Vec<PageRef>, pub wiki_url: Option<String>,
    pub icon: Option<String>,
    pub rssi: Option<i16>, pub in_range: bool, pub live: bool,
    pub connectable: Option<bool>, pub format: AdvFormat,
    pub company_id: Option<u16>, pub company_name: Option<String>, // D-12 Details "Company"
    pub service_uuid: Option<String>, pub tx_power: Option<i16>,
    pub structures: Vec<AdStructure>, pub tail: Option<Vec<u8>>,
    pub decoded: Option<DecoderOutput>, // always None in v2.0; filled by issue #39 without an API change
    pub last_seen_ms: i64,
}
// DecoderOutput / DecodedField / FieldValue mirror cluetooth-core/kb-interface (DECODER_INTERFACE_VERSION 1):
#[derive(uniffi::Record)] pub struct DecoderOutput { pub fields: Vec<DecodedField> }
#[derive(uniffi::Record)] pub struct DecodedField { pub name: String, pub value: FieldValue, pub unit: Option<String>, pub lookup: Option<String> }
#[derive(uniffi::Enum)] pub enum FieldValue { Bool { v: bool }, Number { v: f64 }, Text { v: String } }

#[derive(uniffi::Record)] pub struct Sighting { pub at: GeoPoint, pub rssi: i16, pub seen_ms: i64 }
#[derive(uniffi::Record)] pub struct LiveChart { pub key: DeviceKey, pub end_ms: i64, pub samples: Vec<u8>, pub rssi: Option<i16> }
#[derive(uniffi::Enum)] pub enum MapMode { Points, Cells }
#[derive(uniffi::Record)] pub struct MapData { pub mode: MapMode, pub count: u32, pub geojson: String, pub seq: u64 }
// geojson: Points {addr, rssi}; Cells {point_count, point_count_abbreviated}
```

In every `MapData.geojson`, the feature property `addr` is the uppercase colon form `AA:BB:CC:DD:EE:FF`, the same as
`DeviceRow.addr` and `data.js`.

Encoding of `samples`:

- One byte per bucket, with value = −RSSI clamped to 30..100; **0 is a gap.**
- A 1 s bucket holds the max RSSI of that second (by scan time).
- The chart has 60 one-second buckets ending at `end_ms`.

The chart (D-10):

- **Source.** The writer keeps, per device of the live session, an in-memory ring of the last 60 s at 1 s
  resolution, committed rows or not. The reader sees immutable snapshots of it. Nothing is stored in `local.db`, so
  the chart starts empty after a restart.
- **Bounds.** A device not heard for 60 s is dropped. At most 2,000 devices keep a ring; past that, the one heard
  longest ago goes.
- **Answers.**
  - `device_chart(key)` is `Some` while a session is live (recording or paused), for a device of that session.
    Seconds without a sighting are gaps, and so is paused time. `rssi` is the latest reading while not stale.
  - It is `None` when no session is live, so after Stop.
- **Deltas.** `set_live_focus(key)` makes that device's chart ride on every delta (§2.6).
- **No sparklines.** Rows and deltas carry no sparkline (C-5/C-6, ab39eb5): `DeviceRow.spark` is `None` and
  `DeviceLive.spark` is empty.

The core returns numbers, ids, raw names, page titles and vocabulary labels. UI wording lives in `:designsystem`
(G-8).

### 2.5.1 Discovery rate (SPEC R-4a)

```rust
#[derive(uniffi::Record)] pub struct DiscoveryBin { pub start_ms: i64, pub new_devices: u32 }
#[derive(uniffi::Record)] pub struct DiscoveryRun { pub start_ms: i64, pub end_ms: Option<i64> }   // None: open
#[derive(uniffi::Record)]
pub struct DiscoveryRate { pub seq: u64, pub bin_ms: u32 /* 5 000 */, pub bins: Vec<DiscoveryBin>, pub runs: Vec<DiscoveryRun> }
#[derive(uniffi::Record)] pub struct DiscoveryUpdate { pub bins: Vec<DiscoveryBin>, pub runs: Vec<DiscoveryRun> }
```

- **Runs.** `runs` are the recording's running intervals in wall-clock ms: from the start to the first pause, from
  each resume to the next pause, and so on. The open run (recording now) has `end_ms = None`. Paused time lies
  between runs, so the chart leaves it out and breaks the line at each pause. A stop while paused adds no run.
- **Bins.** A device counts once, in the 5 s bin of its first sighting in the session (`session_devices.first_seen_ms`).
  The chart (R-4a, 51b8ce79) sums a trailing 30 s window over these bins, the filling one included.
  - Bins are per run, starting at that run's own start (`start_ms = run.start_ms + k × 5 000`).
  - A first sighting outside every run counts in the run before it.
  - Bins are sparse: a missing bin is 0.
  - Devices the display filter hides are not counted, as in the session's device count.
- **Snapshot.** Everything comes from `local.db`, so a restart rebuilds the same bins. An interrupted session's last
  run ends where it was interrupted. `seq` is the live `seq` while the session is live (§2.6 snapshot protocol),
  else 0. An unknown session has no runs and no bins.
- **Deltas.** While live, `LiveDelta.discovery` carries the bins that changed or are new, each a total that replaces
  the bin with the same start, plus the runs now. A bin that was sent and is now missing goes out as a total of 0:
  the display filter turned on, or a late first sighting moved its device to an earlier bin. It is `None` when
  nothing changed. The empty snapshot plus every delta always equals a fresh read.

### 2.6 Live deltas

```rust
#[derive(uniffi::Record)]
pub struct LiveDelta {
    pub session_id: String, pub seq: u64, pub at_ms: i64,
    pub state: RecordingState,
    pub changed: Vec<DeviceLive>,          // per-key value deltas, only changed keys
    pub appended: Vec<DeviceRow>,          // new (or newly shown) devices, arrival order
    pub products: Vec<ProductCard>,        // changed or new cards; a new id is appended by the UI
    pub route_appended: Vec<GeoPoint>, pub fix: Option<FixSummary>,
    pub chart: Option<LiveChart>,          // the focused key
    pub discovery: Option<DiscoveryUpdate>, // R-4a: changed/new bin totals and the runs now (§2.5.1)
}
#[derive(uniffi::Record)]
pub struct DeviceLive {
    pub key: DeviceKey, pub rssi: Option<i16>, pub in_range: bool, pub spark: Vec<u8>, // always empty (C-5/C-6)
    pub name: Option<String>,              // Some only when changed (never reverts)
    pub page: Option<PageRef>,             // Some only when changed; with it the row's flags/categories are re-read
    pub best: Option<GeoPoint>,            // Some only when the strongest point moved
}
#[derive(uniffi::Record)] pub struct FixSummary { pub lat: f64, pub lon: f64, pub accuracy_m: f64, pub age_ms: i64 }

#[derive(uniffi::Enum)]
pub enum CoreEvent {
    RecordingChanged { state: RecordingState },
    SessionsChanged,                       // a session finished, was deleted or interrupted
    DataInvalidated { reason: String },    // display filter, re-match done, delete: re-query the open scope
    UploadNeeded,                          // a payload or GATT file was sealed
    StorageWarning { message: String },    // also covers persistence failures
    StorageFull { free_bytes: u64, dropped_scans: u64 },  // SPEC R-10: writing stopped, recording paused (§2.4.1)
    StorageFailed { message: String, dropped_scans: u64 }, // 60 s of staging/commit failures: stopped, paused (§2.4)
}
```

- **Shape.** Per-key value updates plus appends; there are never removals.
- **Rate.**
  - At most one delta per second, only while the live session is recording and something changed. Staleness flips
    count as changes.
  - While paused, deltas stop after the one that carries `Paused`.
  - With no listener set, nothing is built.
- **Coalescing.** While `on_live` runs, the following ticks merge into one pending delta: latest values per key,
  appends concatenated, one `seq`.
- **Snapshot protocol.** The UI subscribes first, then queries. It ignores deltas with `seq` ≤ the snapshot's `seq`.
- **Filters on live views.** A changed row may start or stop passing a filter (`in_range` under Nearby, a page change
  under a category or preset). The UI re-runs `device_keys` for the active filter at most every 2 s while live and
  appends new passing keys. Order never changes (G-9).
- **Budget.** At 300 devices in range, about 15 KB/s through UniFFI. The map applies `best` changes at most every
  5 s.

### 2.7 GATT view (D-13)

```rust
#[derive(uniffi::Enum)] pub enum GattStepKind { Connecting, DiscoveringServices, ReadingCharacteristics, Disconnected }
#[derive(uniffi::Enum)] pub enum GattStepState { Done, Running, Failed }
#[derive(uniffi::Enum)] pub enum GattFailure { TimedOut, DisconnectedUnexpectedly, ConnectionFailed, Cancelled, Other { status: i32 } }
#[derive(uniffi::Record)]
pub struct GattStep { pub kind: GattStepKind, pub state: GattStepState, pub duration_ms: Option<u32>,
    pub services: Option<u32>, pub read: Option<u32>, pub readable: Option<u32>, pub failure: Option<GattFailure> }
#[derive(uniffi::Record)]
pub struct GattView { pub discovery_id: String, pub key: DeviceKey, pub started_ms: i64, pub ok: bool,
    pub steps: Vec<GattStep>, pub services: Vec<GattServiceView> }
#[derive(uniffi::Record)] pub struct GattServiceView { pub uuid: String, pub name: Option<String>, pub characteristics: Vec<GattCharView> }
#[derive(uniffi::Record)]
pub struct GattCharView { pub uuid: String, pub name: Option<String>, pub properties: Vec<String>,
    pub value_text: Option<String> }   // display-only: UTF-8 text, Battery Level "78 %", Appearance (SIG), else hex
```

`steps` is derived from `stage_reached`, `error` and `timings_ms`; MTU is folded into Connecting. `complete` gives
`ok`. `partial` and `failed` stop at the failed step, and `cancelled` is never shown.

Failure mapping:

The status is `error.status`, else `disconnect.status`; 0 (success) is no status. `:platform` uses the same table
(#99).

| gatt/v1 | GattFailure |
|---|---|
| `outcome = cancelled` | `Cancelled` |
| `disconnect.initiator = timeout` or status −1 | `TimedOut` |
| any other status: Android's (133, 62, 19, …) or the engine's (`GATT_INTERNAL_ERROR` −6, …) | `Other{status}` |
| no status, `error.stage = connect` | `ConnectionFailed` |
| no status, `initiator = remote` | `DisconnectedUnexpectedly` |
| no status, anything else | `Other{133}` |

### 2.8 Storage, export, delete, upload (SPEC O-1 items 8–11)

```rust
#[derive(uniffi::Record)]
pub struct StorageStats {
    pub database_bytes: u64,               // local.db + -wal + -shm
    pub raw_files_bytes: u64,              // pending + archive Parquet and gatt JSON, pre-v2 files included
    pub pending_count: u32, pub pending_bytes: u64,   // not uploaded yet (the delete warning)
    pub session_count: u32,
}   // "map cache" comes from :map (MapCacheInfo, §5.3), not the core

#[derive(uniffi::Enum)] pub enum ExportFormat { ParquetRaw, Csv, SqliteSnapshot }
#[derive(uniffi::Record)] pub struct ExportRequest { pub scope: Scope, pub format: ExportFormat }
#[derive(uniffi::Record)] pub struct PreparedExport { pub export_id: String, pub local_path: String,
    pub suggested_file_name: String, pub file_count: u64, pub size_bytes: u64 }
#[derive(uniffi::Enum)] pub enum DeleteOutcome { Deleted { sessions: u32 }, DeletedPendingKept { pending: u32 }, Refused { reason: String } }
#[derive(uniffi::Record)] pub struct UploadStatus { pub pending_count: u32, pub pending_bytes: u64, pub failed_count: u32, pub last_error: Option<String> }

impl Core {
    pub async fn storage_stats(&self) -> Result<StorageStats, CoreError>;          // [P]
    pub async fn prepare_export(&self, request: ExportRequest) -> Result<PreparedExport, CoreError>;  // [F] cancellable
    pub fn acknowledge_export(&self, export_id: String) -> Result<(), CoreError>;  // [F] deletes export-temp/<id>
    pub async fn delete_local_data(&self, scope: Scope, include_unuploaded: bool) -> Result<DeleteOutcome, CoreError>;
    // [F]+[W] user-initiated only; refuses while recording if the scope contains the current session.
    //   Removes DB rows and the scope's archived plaintext (All: also pre-v2 files and sessions/*.json).
    //   include_unuploaded = false (the default): pending files stay queued and are removed right after upload
    //     (tombstones), so nothing is lost.
    //   include_unuploaded = true: pending files are removed now and never uploaded (second confirmation only).
    pub fn upload_status(&self) -> UploadStatus;                                   // [N]
    pub fn pending_uploads(&self) -> Vec<PendingUpload>;                           // [N] unchanged
    pub fn prepare_upload(&self, id: String) -> Result<PreparedUpload, CoreError>; // [F] unchanged: encrypts to upload-cache/
    pub fn mark_upload_succeeded(&self, id: String) -> Result<(), CoreError>;      // [F] plaintext → archive; ciphertext deleted
    pub fn mark_upload_failed(&self, id: String, message: String) -> Result<(), CoreError>;  // [F]
}
```

**Export formats (owner, final; all three ship in v2.0).** Every export is one ZIP in `export-temp/<id>/`, read from
plaintext with no decryption. Its `manifest.json` lists every file with its SHA-256, plus the format, the scope, the
app version and `exported_at`.

| Format | Contents |
|---|---|
| `ParquetRaw` | The scope's Parquet files **byte-identical** to the plaintext on disk (pending and archive; v3, and v2-schema files of pre-v2 sessions under `All`), as `scans/<session>/<uuid>.parquet`. The scope's gatt/v1 JSON files byte-identical, as `gatt/<uuid>.json` (`All` includes discoveries made outside a session). |
| `Csv` | A **direct conversion of the same plaintext Parquet**, row for row and column for column; it is not built from SQLite. The gatt/v1 JSON is bundled as in `ParquetRaw`. |
| `SqliteSnapshot` | `cluetooth-snapshot.sqlite` with only the research-aligned tables for the scope: `ad_structures`, `payloads`, `payload_structures`, `sighting_minutes`, `sessions`, `session_pauses`, `route_points`, `gatt_discoveries`, `gatt_attributes`, plus `meta` (export facts, schema version). It is built with `ATTACH` + `INSERT … SELECT` filtered to the scope's sessions and the payloads they reference, then `VACUUM`. It has no app-side tables and covers v2 sessions only. |

CSV rules:

- **Columns.** Every CSV has the same columns:
  - the 18 Parquet v3 columns, in order, with `scanned_at_ms` (the original epoch milliseconds) inserted right after
    `scanned_at`;
  - then two trailing columns, always present:
    - `session_id`, the session UUID (empty for a payload without a session);
    - `source_file`, the Parquet file's path inside the ZIP's `scans/` tree, e.g.
      `scans/<session>/<uuid>.parquet`.
- **Values.**
  - `scanned_at` is ISO-8601 UTC with milliseconds (`2026-10-28T19:12:03.123Z`).
  - Binary (`raw`) is lowercase hex.
  - Booleans are `true`/`false`, and floats use the shortest round-trip decimal.
  - Null is an empty field.
  - v2-schema files leave the eight v3 columns empty.
- **Encoding.** UTF-8 without a BOM, RFC 4180 quoting, CRLF line ends, and one header row per file.
- **Files.** A one-session scope gives one CSV per Parquet file (`scans/<session>/<uuid>.csv`). A multi-session
  scope or `All` gives one concatenated `scans.csv` with a single header.

Delete and pending data (O-1 item 11):

1. The user chooses the scope (one session or All), then confirms.
2. **The default keeps un-uploaded data.** The call is `include_unuploaded = false`: un-uploaded files stay queued,
   upload, and are removed afterwards.
3. Only when `pending_count > 0` and the user explicitly confirms losing that data in a **second step** does the UI
   call `include_unuploaded = true`.

Export hands the ZIP to the system share sheet or saves it to Downloads (`ExportShare`, lane B).

### 2.9 Errors

```rust
#[derive(uniffi::Error)]
pub enum CoreError {
    InvalidPayload { detail: String }, InvalidConfig { detail: String }, InputBatchTooLarge { detail: String },
    UploadState { detail: String }, Encryption { detail: String }, Io { detail: String }, Parquet { detail: String },
    Clock { detail: String },                       // v3 variants, unchanged
    Db { detail: String },
    SchemaTooNew { found: u32, supported: u32 },    // an older build opened a newer local.db
    InvalidBundle { detail: String },               // wiki bundle schema/vocabulary violation
    Backpressure, NotRecording, AlreadyRecording, Closed,
    StorageFull { free_bytes: u64 },                // SPEC R-10 (§2.4.1)
    Cancelled,                                      // §2 cancellation; Kotlin maps it to CancellationException
}
```

### 2.10 SPEC B → core

| SPEC B | Core / platform |
|---|---|
| `getCategoryCounts(scope, opts)` | `category_counts(scope, filter)` (data-driven, faceted; there is no fixed list) |
| `getRecordings()` / `getCurrentRecording()` | `sessions()` / `current_session()` + `recording_state()` |
| `getRecording(id)` / `"all"` | `session_view(scope)` + `routes(scope, …)` + `map_points(scope, …)` |
| `getAllSessionsView()` | `all_sessions_summary()` |
| `getSessionDevices(id)` | `device_order(scope)` + `device_keys(scope, filter)` + `device_rows(scope, keys)` + deltas |
| `getProducts(mode, id)` | `products(scope, filter{in_range_only, area, …})` |
| `getProduct(id)` | `product(scope, page, filter)` + `device_keys(scope, {page | identification})` |
| `getDevice(addr)` | `device(scope, key)` + `device_chart(key)` |
| `getDeviceSightings(addr)` | `device_sightings(scope, key)` |
| `getDeviceGatt` / `discoverGatt` | `device_gatt(key)` / `GattRunner.discover` (B) → `ingest_gatt` |
| `searchDevices` / `matchDevice` | `search(scope, q, area)` / `match_device`; the map uses `filter.query` |
| `getLocationStatus()` / `getMyLocation()` | `LocationStatusSource` (B); the core needs no location status |
| `startRecording()` / `stopRecording()` | `Recorder` (B) → `start_recording` / `stop_recording` |
| `getSettings()` | `SettingsStore` (B) + `upload_status()` + `storage_stats()` |

### 2.11 Q-1 test vectors

Both Rust and the Kotlin fake run these vectors, which come from the prototype gallery.

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

Lowercasing and trimming are Unicode-aware, as in JS. The same Rust function is registered as SQL
`match_device(q, name, addr)`.

---

## 3. Local DB schema and catalog

The file is `filesDir/cluetooth-core/local.db`, opened with `rusqlite` (features: `bundled`, `array`,
`functions`, `window`) on SQLite ≥ 3.45 with R*Tree. An open-time test asserts that `rtree` and `rarray` are
available.

The DB runs with `PRAGMA journal_mode=WAL; synchronous=NORMAL; foreign_keys=ON; temp_store=MEMORY`. It has one
writer connection (`ct-writer`) and one read connection (`ct-read`, §1.3).

### 3.1 Alignment with the research DB

| Research (Postgres, `docs/schema.md`) | Local (SQLite) |
|---|---|
| `ad_structures(id, ad_type, data, local_name, company_id, uuids)` unique `(ad_type, data)` | same; the generated columns are computed in Rust at insert with the same rules |
| `payloads(id, sha256, structure_ids[], zero_padding, tail, first/last_seen, sightings)` | `payloads` + `payload_structures(payload_id, ord, structure_id)`; the `payload_fields` columns are materialized |
| `sighting_minutes` key `(minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)` | same minus `uploader_id`; nullable key columns stored as `-1` (SQLite UNIQUE treats NULLs as distinct) |
| best sample: `sighting_sample_score` = rssi/20 − 2·log10(max(acc ?? 50, 1)), then accuracy, then earlier | the same function and tie order (one Rust fn; the coefficients are provisional on both sides) |
| `gatt_discoveries`, `gatt_attributes` | same columns (`raw` = the exact gatt/v1 JSON) |
| (none) | app-side: `sessions`, `session_pauses`, `route_points`, `session_devices`, `session_device_payloads`, `devices`, `device_cells`, `page_totals`, R*Trees |

The minute's best sample uses the research score. A device's map point is its strongest located sighting (M-3).

### 3.2 DDL (schema version 1)

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
-- rules_key ("<bundle rules_version>+m<engine>"), bundle_version, wiki_commit, devices_all, devices_shown

CREATE TABLE ad_structures (
  id INTEGER PRIMARY KEY, ad_type INTEGER NOT NULL, data BLOB NOT NULL,
  local_name TEXT,                          -- types 8/9: UTF-8, trailing NULs stripped, NULL if empty/invalid
  company_id INTEGER,                       -- type 0xFF with ≥ 2 bytes: LE u16
  uuids TEXT,                               -- types 2-7, 0x16/0x20/0x21: research notation, sorted, comma-joined
  UNIQUE (ad_type, data)
);
CREATE TABLE payloads (
  id INTEGER PRIMARY KEY, sha256 BLOB NOT NULL UNIQUE,
  zero_padding INTEGER NOT NULL DEFAULT 0, tail BLOB,
  local_name TEXT, company_id INTEGER, service_uuid TEXT,     -- materialized payload_fields
  is_apple INTEGER NOT NULL,                                 -- any structure with company_id 76
  page_id TEXT,                                              -- matched page (§3.5); NULL = unidentified
  match_key TEXT NOT NULL,                                   -- meta.rules_key used
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL DEFAULT 0,
  CHECK (tail IS NULL OR zero_padding = 0)
);
CREATE TABLE payload_structures (payload_id INTEGER NOT NULL REFERENCES payloads(id), ord INTEGER NOT NULL,
  structure_id INTEGER NOT NULL REFERENCES ad_structures(id), PRIMARY KEY (payload_id, ord)) WITHOUT ROWID;
CREATE INDEX payloads_rematch ON payloads(match_key, id);

CREATE TABLE sessions (
  id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE,         -- UUIDv7 = Parquet scan_session_id = gatt/v1 session_id
  status INTEGER NOT NULL,                                   -- 0 recording, 1 paused, 2 completed, 3 interrupted
  started_ms INTEGER NOT NULL, ended_ms INTEGER, active_ms INTEGER NOT NULL DEFAULT 0,
  distance_m REAL NOT NULL DEFAULT 0, device_count INTEGER NOT NULL DEFAULT 0, device_count_shown INTEGER NOT NULL DEFAULT 0,
  sightings INTEGER NOT NULL DEFAULT 0, min_lon REAL, min_lat REAL, max_lon REAL, max_lat REAL,
  route_simplified BLOB                                      -- ≤ 2048 points, delta-encoded i32 1e-6°, at stop
);
CREATE INDEX sessions_started ON sessions(started_ms DESC);
CREATE TABLE session_pauses (session_id INTEGER NOT NULL REFERENCES sessions(id), from_ms INTEGER NOT NULL,
  to_ms INTEGER, PRIMARY KEY (session_id, from_ms)) WITHOUT ROWID;
CREATE TABLE route_points (session_id INTEGER NOT NULL REFERENCES sessions(id), t_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, PRIMARY KEY (session_id, t_ms)) WITHOUT ROWID;
CREATE VIRTUAL TABLE sessions_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);   -- route bbox per session

CREATE TABLE sighting_minutes (
  id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES sessions(id),
  minute INTEGER NOT NULL, addr INTEGER NOT NULL, payload_id INTEGER NOT NULL REFERENCES payloads(id),
  addr_type INTEGER NOT NULL DEFAULT -1, is_legacy INTEGER NOT NULL DEFAULT -1,
  primary_phy INTEGER NOT NULL DEFAULT -1, secondary_phy INTEGER NOT NULL DEFAULT -1,
  adv_sid INTEGER NOT NULL DEFAULT -1, data_status INTEGER NOT NULL DEFAULT -1,     -- -1 = null
  sightings INTEGER NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
  rssi_min INTEGER, rssi_max INTEGER, rssi_sum INTEGER NOT NULL DEFAULT 0, rssi_count INTEGER NOT NULL DEFAULT 0,
  tx_power INTEGER, is_connectable INTEGER, adv_tx_power INTEGER, periodic_interval INTEGER,
  min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL,
  best_lat REAL, best_lon REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER, best_score REAL,
  UNIQUE (session_id, minute, addr, payload_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)
);
CREATE INDEX sighting_minutes_device  ON sighting_minutes(addr, session_id, minute);
CREATE INDEX sighting_minutes_payload ON sighting_minutes(payload_id);              -- re-match, delete

CREATE TABLE session_devices (
  id INTEGER PRIMARY KEY,                                    -- = session_devices_rtree.id
  session_id INTEGER NOT NULL REFERENCES sessions(id), addr INTEGER NOT NULL,
  name TEXT, company_id INTEGER, is_apple INTEGER NOT NULL,
  page_id TEXT,                                              -- §3.5 over this session
  latest_payload_id INTEGER NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max INTEGER,
  best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,   -- strongest located
  tile17 INTEGER,                                            -- (x17 << 17) | y17 of the best point
  shown INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED,            -- G-12
  UNIQUE (session_id, addr)
);
CREATE INDEX session_devices_order ON session_devices(session_id, rssi_max DESC, addr, shown, page_id);  -- covering
CREATE INDEX session_devices_addr  ON session_devices(addr);                         -- delete, All recompute
CREATE VIRTUAL TABLE session_devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE session_device_payloads (                       -- per-device payload counts: the §3.5 page rule
  session_id INTEGER NOT NULL, addr INTEGER NOT NULL, payload_id INTEGER NOT NULL,
  sightings INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
  PRIMARY KEY (session_id, addr, payload_id)
) WITHOUT ROWID;
CREATE INDEX sdp_addr    ON session_device_payloads(addr, payload_id);
CREATE INDEX sdp_payload ON session_device_payloads(payload_id);

CREATE TABLE devices (                                       -- All: one row per addr over every session
  addr INTEGER PRIMARY KEY,                                  -- = devices_rtree.id
  name TEXT, company_id INTEGER, is_apple INTEGER NOT NULL, page_id TEXT, latest_payload_id INTEGER NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, session_count INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max INTEGER, best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,
  best_session_id INTEGER, tile17 INTEGER,
  shown INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED
);
CREATE INDEX devices_order ON devices(rssi_max DESC, addr, shown, page_id);         -- covering: order + filters
CREATE INDEX devices_page  ON devices(page_id, tile17, shown);
CREATE INDEX devices_named ON devices(name, addr, shown) WHERE name IS NOT NULL;     -- search
CREATE VIRTUAL TABLE devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE device_cells (                                  -- All, unfiltered: pre-aggregated map cells
  z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, -- z in (5, 8, 11, 14)
  n_all INTEGER NOT NULL, n_shown INTEGER NOT NULL, sum_lon REAL NOT NULL, sum_lat REAL NOT NULL,
  PRIMARY KEY (z, x, y)
) WITHOUT ROWID;
CREATE TABLE page_totals (                                   -- All, unfiltered, no area: card counts
  page_id TEXT PRIMARY KEY,                                  -- 'unidentified' included
  devices_all INTEGER NOT NULL, devices_shown INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE gatt_discoveries (
  id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, session_id INTEGER REFERENCES sessions(id),
  addr INTEGER NOT NULL, addr_type TEXT, trigger_payload_id INTEGER REFERENCES payloads(id), trigger_rssi INTEGER,
  started_ms INTEGER NOT NULL, ended_ms INTEGER, lat REAL, lon REAL, accuracy REAL,
  outcome TEXT NOT NULL, stage_reached TEXT NOT NULL, error_stage TEXT, error_status INTEGER, error_name TEXT,
  disconnect_initiator TEXT, disconnect_status INTEGER, mtu INTEGER, phy_tx INTEGER, phy_rx INTEGER,
  connect_ms INTEGER, mtu_ms INTEGER, discover_ms INTEGER, read_ms INTEGER, profile_hash TEXT, raw TEXT NOT NULL
);
CREATE INDEX gatt_discoveries_addr ON gatt_discoveries(addr, started_ms DESC);
CREATE TABLE gatt_attributes (
  discovery_id INTEGER NOT NULL REFERENCES gatt_discoveries(id) ON DELETE CASCADE,
  attribute_id INTEGER NOT NULL, parent_attribute_id INTEGER, kind TEXT NOT NULL, uuid TEXT NOT NULL,
  is_primary INTEGER, instance INTEGER, properties TEXT, value BLOB, read_status TEXT,
  PRIMARY KEY (discovery_id, attribute_id)
) WITHOUT ROWID;

CREATE TABLE deleted_sessions (uuid TEXT PRIMARY KEY, deleted_ms INTEGER NOT NULL) WITHOUT ROWID;
-- tombstones for include_unuploaded = false: pending files of these sessions are removed right after upload
```

Issue #39 adds the decoder cache, `payload_decodes(payload_id, page_id, decoder_sha256, output_json)`, as migration
1 → 2. Nothing in v2.0 reads or writes it.

### 3.3 Migration and versioning

- **Versioning.** `PRAGMA user_version` holds the schema version. Migrations are an ordered list of `N → N+1` steps
  compiled into Rust and kept forever. Each step runs in one transaction.
- **Forward only and never destructive.** A newer file opened by an older build returns `SchemaTooNew`, and the file
  is untouched.
- **Corruption** at open moves the file aside to `local.db.corrupt-<ms>`, which is never deleted, starts fresh and
  emits `StorageWarning`.
- **Re-match.** When `meta.rules_key` differs from the loaded bundle, `ct-writer` re-matches payloads in batches of
  2,000 per interval (`payloads_rematch`). It then recomputes the page of the affected devices through
  `sdp_payload`/`sdp_addr`, refreshes `devices`, `page_totals` and `device_cells`, and emits `DataInvalidated` when
  done.
- **Rebuild.** The archive Parquet still holds every sighting with its position, so a future rebuild of the sighting
  tables is possible. `sessions`, `session_pauses`, `route_points` and the GATT tables live only in the DB and are
  migrated, never rebuilt.
- **Pre-v2 data** is not imported.

### 3.4 Catalog: the wiki bundle (schema 2)

The source of truth is the Gitea repo `y2wei/wirelesswiki`, `main`, at `d1b543b` or later:

- the `schema/` front-matter schemas;
- `traits.schema.json`;
- the vendored `kb-interface` files;
- `wiki-site/src/schema.ts`.

The page kinds that matter are **device, platform, identifier and vulnerability**. Organization and topic pages are
not exported.

The exporter `scanner/tools/wiki-bundle` (lane A) reads a checkout and writes
`app/src/main/assets/wiki-bundle.json`:

```jsonc
{
  "schema": 2,
  "version": "2026-10-28+d1b543b1",            // export date + wiki commit sha8
  "wiki_commit": "d1b543b…",                   // full sha
  "rules_version": "<sha16 over every rule>",
  "decoder_interface_version": 1,              // kb-interface DECODER_INTERFACE_VERSION
  "vocabularies": {                            // from device.schema.json and traits.schema.json, in schema order
    "categories": [{ "id": "asset-tracker", "title": "Asset tracker", "description": "…" }],   // 21 today
    "contexts":   [{ "id": "home", "title": "Home", "description": "…" }],                    // 6 today
    "traits":     [{ "id": "rotating-address", "title": "Rotating address", "description": "…" }]  // 9 today
  },
  "pages": [{
    "id": "device--karr-bt",                   // the directory name = wiki route /p/<id>
    "kind": "device",                          // device | platform | identifier | vulnerability
    "entity": "family/karr-bt",                // or null
    "title": "…", "one_line": "…",
    "categories": ["vehicle-equipment"],       // front matter order; [] for identifier/vulnerability
    "contexts": [], "traits": ["identifier-in-name"],
    "rule": { /* rule.json, validated against rule.schema.json */ },   // null when the page has none
    "decoder": { "sha256": "…", "source": "<decoder.js verbatim>" },   // null when none
    "vulnerabilities": ["vulnerability--cve-2026-18411"],              // pages referenced as kb:vulnerability/<key>
    "icon": {                                                          // optional (Gitea #190); null when none
      "asset": "wiki-icons/device--karr-bt.png",                       // copied by the exporter into app assets
      "source": "https://…", "license": "CC-BY-4.0", "author": "…"
    }
  }]
}
```

Exporter rules. Each failure exits non-zero and names the page:

- Every category, context and trait must be in its vocabulary.
- Every `kb:vulnerability/<key>` must resolve to a `vulnerability--<key>` page in the bundle.
- Every rule must validate against `rule.schema.json`.
- `decoder.js` is bundled **verbatim** with its hash. v2.0 never executes it, and `decoder.tests.yaml` is not run
  (issue #39 adds both).
- `label_zh` and `legacy-mapping.json` are dropped, since the UI is English only.
- A page `icon` (Gitea #190) must have its file, a source and a licence. The exporter copies the file to
  `app/src/main/assets/wiki-icons/<page id>.<ext>` and writes that asset path. Until #190 lands, every `icon` is
  `null`.

How the core uses the bundle:

- **Matching.** It loads the bundle once and compiles the rules with the existing `RuleMatcher`, which accepts schema
  2 because unknown page fields are ignored. It keeps an in-memory page table: kind, title, categories, traits,
  `readable = decoder != null`, the vulnerabilities, and the derived sets below.
- **`readable`** is true when the matched page has a decoder.
- **`vulnerable`** is true when the matched page has at least one vulnerability.
- **`trackers`** is true when the matched page's categories contain one of `TRACKER_CATEGORIES = ["asset-tracker"]`
  (owner decision), one constant in the core.
- **Filters** become page-id sets, so `categories`, `readable`, `trackers`, `vulnerable` and `identification` turn
  into `page_id IN rarray(:pages)` or `page_id IS NULL` (§3.6).
- **Category labels** are the vocabulary titles.
- **Icons.** Each row, card and detail carries the page's `icon` asset path, or `None`; the UI then falls back to the
  category icon. Category icons are an app-side table in `:designsystem` (lane C), using Material Symbols names:

| Category | Symbol | Category | Symbol |
|---|---|---|---|
| asset-tracker | `sell` | personal-care | `self_care` |
| positioning-beacon | `sensors` | medical-device | `medical_services` |
| fleet-telematics | `local_shipping` | reader-terminal | `point_of_sale` |
| vehicle-equipment | `directions_car` | printer | `print` |
| wearable | `watch` | power-energy | `bolt` |
| audio-device | `headphones` | environmental-monitor | `thermostat` |
| telephony | `call` | display | `tv` |
| camera | `photo_camera` | input-peripheral | `keyboard` |
| security-and-access | `lock` | sports-equipment | `fitness_center` |
| lighting-control | `lightbulb` | traffic-control | `traffic` |
| smart-appliance | `kitchen` | *(unidentified)* | `help` (C-17 circled question mark) |
| *(any other id)* | `devices_other` (the generic fallback) | | |

  A product with several categories uses its first category's icon. Lane C draws them in the C-17 stroke style.
- **The wiki URL** is `https://wirelesswiki.org/p/<page id>`.

### 3.5 Page of a payload and of a device

1. **Payload → page.** `match_advertisement(raw, addr, addr_type)` returns the matching page ids in bundle order. The
   payload's page is the first match of kind `device`, else `platform`, else `identifier`. Vulnerability pages have
   no rules. The match runs once per new payload and again on a re-match.
2. **Device → page** over a scope: the page of the device's matched payload with the most sightings in the scope,
   taken from `session_device_payloads`. Ties go to the lower payload id. With no matched payload, the device is
   unidentified. A device whose page changes keeps its list position (G-9).
3. **Categories in the views.**
   - The categories view (D-2) shows a product under its page's **first** category only.
   - Category filters and `category_counts` use **all** of the page's categories (any-of).

### 3.6 SPEC queries in SQL

**Device relation.** `D(scope)` is:

- `devices` for `All` with no filter;
- `session_devices WHERE session_id = ?` for one session;
- the live snapshot for the live scope;
- `U(:ids)` for any other scope, computed per query.

`U(:ids)` uses window functions over `session_devices` and `session_device_payloads` of the resolved sessions:

```sql
WITH sd AS (SELECT * FROM session_devices WHERE session_id IN rarray(:ids)),
     best AS (SELECT addr, best_lon, best_lat, best_rssi, tile17,
                     row_number() OVER (PARTITION BY addr ORDER BY best_rssi DESC, best_seen_ms) r
              FROM sd WHERE best_lat IS NOT NULL),
     nm AS (SELECT addr, name, row_number() OVER (PARTITION BY addr ORDER BY last_seen_ms DESC) r
            FROM sd WHERE name IS NOT NULL),
     pg AS (SELECT addr, p.page_id, row_number() OVER (PARTITION BY addr ORDER BY sum(s.sightings) DESC, s.payload_id) r
            FROM session_device_payloads s JOIN payloads p ON p.id = s.payload_id
            WHERE s.session_id IN rarray(:ids) AND p.page_id IS NOT NULL GROUP BY addr, s.payload_id)
SELECT sd.addr, max(rssi_max) rssi_max, max(is_apple) is_apple, … FROM sd … GROUP BY sd.addr
```

The area filter for `U` compares `best_lon/best_lat` directly; there is no R*Tree.

**Filter predicate.** `F` is:

```
:shown AND [page_id IN rarray(:pages)] AND [page_id IS NULL] AND [<area>] AND [in_range] AND [match_device(:q, name, hex(addr))]
```

- `:shown` is `CASE WHEN :hide_unnamed_apple THEN shown ELSE 1 END`.
- `:pages` is the intersection of the page sets of `categories` (union of each category's pages), `readable`,
  `trackers`, `vulnerable`, `page` and `identification = Identified`.
- `identification = Unidentified` is `page_id IS NULL`, and it gives an empty result if any page constraint is also
  set.

| SPEC | SQL (frozen; the live scope runs the same logic over the snapshot) |
|---|---|
| L-2 `sessions()` | `SELECT … FROM sessions WHERE status IN (2,3) AND device_count > 0 ORDER BY started_ms DESC` (`device_count_shown` when hiding) |
| `all_sessions_summary()` | `meta` counters + `count(*) FROM sessions WHERE device_count > 0` |
| `routes` | one: `route_simplified`; several or All: `sessions_rtree` ∩ viewport ∩ scope, simplified per zoom |
| G-9 `device_order` | `SELECT addr FROM D WHERE :shown ORDER BY rssi_max DESC, addr` (index-only on `*_order`) |
| D-3/D-4 `device_keys` | `SELECT addr FROM D [JOIN rtree] WHERE F ORDER BY rssi_max DESC, addr` (index-only unless area or query) |
| `category_counts` | `SELECT page_id, count(*) FROM D WHERE F' GROUP BY page_id` (F' = F without categories), then fold pages → categories in Rust |
| D-2/D-4 `products` | All, no area or filter: `page_totals`; else `SELECT coalesce(page_id,'unidentified'), count(*), max(last_seen_ms) FROM D WHERE F GROUP BY 1` |
| D-7 `product` places | `SELECT count(DISTINCT tile17) FROM D WHERE F` (`devices_page` covers All) |
| D-9/D-12 `device` | `D` row + `payloads` + `payload_structures ⋈ ad_structures ORDER BY ord` for `latest_payload_id` + its latest `sighting_minutes` row |
| `device_sightings` | `SELECT minute, best_lon, best_lat, max(best_rssi), best_seen_ms FROM sighting_minutes WHERE addr=:k AND session_id IN (…) AND best_lat IS NOT NULL GROUP BY session_id, minute ORDER BY minute DESC LIMIT 10000` |
| D-13.7 `device_gatt` | `… FROM gatt_discoveries WHERE addr=:k AND outcome <> 'cancelled' ORDER BY started_ms DESC LIMIT 1` + attributes |
| Q-1/Q-2 `search` | `device_keys` with `F` = `:shown AND match_device(:q, name, hex(addr)) [AND area]` |
| M-2/M-3 `map_points` | §3.7 |

### 3.7 Cost of "All sessions" with years of data

**Sizing assumption, chosen as heavy use:**

- 3 years at about 1 h of recording a day gives 1,000 sessions.
- 1.0 M `session_devices` rows and about 1.5 M `session_device_payloads` rows.
- 600 k distinct devices.
- 2 M payloads and 15 M `sighting_minutes` rows.
- `local.db` is about 2.7 GB.

**Budgets.** Targets on a Moto G-class phone (Cortex-A53/A55, eMMC). Lane A's bench asserts **⅕ of these on the
reference host**: CachyOS on `cachyos`, 16 cores, 29 GB RAM, NVMe, release build, warm page cache.

| Query | Plan | Rows | Phone budget |
|---|---|---|---|
| `sessions()` | `sessions_started` | 1,000 | 15 ms |
| `all_sessions_summary()` | `meta` counters | O(1) | 1 ms |
| `routes(All, viewport, z)` | `sessions_rtree` ∩ viewport, LOD | sessions in view | 40 ms |
| `map_points(All, viewport, z)` | see below | ≤ 10,001, or ≤ 2,000 cells | 60 ms |
| `map_points` with a filter, wide view | `GROUP BY tile17 >> s` over the filtered set (`devices_page`) | pages' devices | 3 µs × n (50 k: 150 ms) |
| `products(All)`, unfiltered | `page_totals` | ≤ pages | 10 ms |
| `products` / `category_counts` (All, area or filter) | R*Tree or `devices_page` → `GROUP BY page_id` | k | 3 µs × k (50 k: 150 ms; 300 k country view: ≈ 1 s, cancellable) |
| `device_order(All)` | **index-only** scan of `devices_order` (with `shown`) | 600 k → 4.8 MB `LongArray` | 500 ms, once per view, only when list or search needs it |
| `device_keys(All, filter)` | index-only `devices_order` with `page_id IN …` | 600 k entries | 500 ms |
| `device_keys(All, area)` | R*Tree + sort | k | 50 ms at 10 k |
| `device_rows(200)`, `device`, `device_sightings` | PK and index lookups | small | 5–10 ms |
| `search(All, q)` | `devices_named` + addr scan in Rust | 600 k | 300 ms (debounced 150 ms; a newer search cancels the older); with area ≤ 30 ms |
| one session (any query) | `session_devices` (≤ 20 k rows) and its R*Tree | ≤ 20 k | ≤ 30 ms |
| union `U(:ids)`, any query | window scan of the sessions' `session_devices` + `session_device_payloads` | Σ rows | ≈ 4 µs × Σ rows: 10 sessions (≈ 5 k devices, 8 k payload rows) ≈ 50 ms; 100 sessions ≈ 0.5 s; a year (≈ 350 k rows) ≈ 2–3 s **per query**. The UI does not build these in v2.0; multi-select must add a cache, which is not an API change. |

`map_points` loads only the viewport, never the scope:

1. Count up to the cap: `SELECT count(*) FROM (SELECT 1 FROM rtree WHERE <viewport+50 %> LIMIT 10001)`. `U` counts
   by a bbox predicate on the best point.
2. If the count is ≤ 10,000, build **Points**: join `D`, apply `F`, and serialize GeoJSON in Rust. MapLibre
   clusters them natively (M-4).
3. If it is larger, build **Cells**:
   - **All, unfiltered:** pick the coarsest `device_cells` level whose cell is ≥ 1/16 of the viewport side, then
     `SELECT x, y, sum(n_shown|n_all), … FROM device_cells WHERE z = :z AND x/y in range GROUP BY x, y`, which
     returns ≤ 2,000 rows.
   - **Any other scope or any filter:** `SELECT tile17 >> s AS t, count(*), avg(best_lon), avg(best_lat) FROM D
     WHERE F AND <viewport> GROUP BY t`, over **that scope's** devices. `device_cells` holds All only and is never
     used for another scope.
4. Cells are drawn by layers cloned from `clusters`/`cluster-count` on source `cells` (lane E).

The GeoJSON goes to `GeoJsonSource.setGeoJson(String)` and is parsed natively.

---

## 4. Kotlin platform ↔ core

### 4.1 Scan callback → ingest

```kotlin
// :platform (lane B)
internal class ScanIngest(private val core: CoreIngest, private val scope: CoroutineScope) {
    private val queue = Channel<ScanObservation>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun onScanResult(r: ScanResult)   // main/binder thread: convert (v3 rules: RSSI/txPower 127 → null,
                                      // addressType on API 35+, raw ≤ 1650 B) and trySend; never blocks
    // single consumer on Dispatchers.IO.limitedParallelism(1): batch ≤ 64 or 250 ms → core.ingestScans(batch);
    // Backpressure → keep the batch, retry after 100 ms; overflow drops are counted (RecorderState.dropped)
}
```

- **Scanning runs only while recording and not paused** (V-1). Pause stops the scan and the RECORDING location
  request, and the service stays in the foreground.
- **ScanSettings** are as in v3:
  - `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, `setLegacy(false)`;
  - PHY `ALL_SUPPORTED` when "Scan LE Coded PHY" is on, else `LE_1M`;
  - one empty `ScanFilter`.
- **Restart policy**, as in v3:
  - the scan restarts every 60 s;
  - while the screen is off, the restart is skipped until the scan has run 20 min;
  - a Coded PHY change applies at the next restart, with `core.configureScan(env)` called first.
- **Bluetooth off** while recording: the session stays open and the scanner is deferred (`scannerDeferred`).

### 4.2 Location → ingest and status

- **Provider.** `FusedLocationProviderClient`, falling back to `LocationManager`.
  - RECORDING (2 s, minimum 1 s, high accuracy) while recording and not paused.
  - FOREGROUND (balanced, 10 s) while the UI is started otherwise. It feeds only `myLocation` and the status (M-9,
    P-5), never the core.
- **Into the core.** RECORDING fixes go to `core.ingestLocation`. A lost provider or a fix without accuracy calls
  `core.clearLocation()`.
- **`LocationStatusSource.status`** (P-5):
  - permission → `Denied` (action Allow);
  - provider off → `Off` (action TurnOn, which resolves through `SettingsClient`, falling back to
    `ACTION_LOCATION_SOURCE_SETTINGS`);
  - no first fix for more than 30 s → `Waiting`;
  - a last fix older than 2 min → `Stale(ageMin)`.

  The status is re-evaluated every 5 s while it can warn.

### 4.3 GATT runner contract (D-13)

```kotlin
// :platform-api
interface GattRunner {
    /** Cold flow: collecting starts one discovery of [key]; cancelling the collector cancels it (D-13.8).
     *  Emits progress views; the last is final. The runner saves the gatt/v1 result itself through
     *  CoreIngest.ingestGatt (cancelled runs too: outcome "cancelled", never shown). */
    fun discover(key: DeviceKey): Flow<GattView>
}
```

| D-13 row | Engine stages (v3 `GattEngine`, timeouts unchanged) | Running | Done | Failures |
|---|---|---|---|---|
| Connecting… | CONNECT (+ MTU folded) | spinner | "0.8 s" (`duration_ms`) | `TimedOut` (10 s), `ConnectionFailed` (133 after one retry, 62, 257 …) |
| Discovering services… | DISCOVER | spinner | "N services" | `TimedOut` (15 s), `DisconnectedUnexpectedly` |
| Reading characteristics | READ | "m/n" | "n/n" | `TimedOut` (30 s total or a stall), `DisconnectedUnexpectedly` |
| Disconnected | DISCONNECT | spinner | none | none visible |

Before a run:

- Check `BLUETOOTH_CONNECT` and that Bluetooth is on. If either is missing, emit a failed Connecting step with
  `Other(GATT_UNAVAILABLE)` (−4); the UI shows the onboarding piece.
- `core.gattTarget(key)` must exist. If it doesn't, emit a failed Connecting step with `Other(GATT_NO_TARGET)` (−5).
- Both codes are named constants in `:platform-api`, next to `GattRunner`. Neither precondition failure connects or
  writes a gatt/v1 object.
- `GATT_INTERNAL_ERROR` (−6), also in `:platform-api`, is the status of a discovery whose engine itself failed (a link
  call threw mid-discovery). It is stored in gatt/v1 as the error's status, and never reads as `GATT_UNAVAILABLE`.
  The engine's other codes are −1 (timed out), −2 (process death) and −3 (read queue stalled).
- Failures (§2.7): a known status, Android's (133, 62, 19, …) or an engine code, is `Other(status)`, so the UI can name
  it. `ConnectionFailed` and `DisconnectedUnexpectedly` remain only when no status is known; `TimedOut` and
  `Cancelled` are unchanged.

During and after:

- One discovery runs at a time process-wide; a new `discover` that passed the preconditions cancels the previous
  one and starts after it ended. One owner (`GattAdmission`) orders start against cancel: a discovery cancelled
  while it is still starting never connects, and one waiting for its turn never runs.
- GATT works with or without a recording.
- After the final step, the runner calls `core.ingestGatt(json)` and emits the returned view. A failed save is kept
  in `filesDir/gatt-pending` and retried.

### 4.4 Recording state exposed to the UI

```kotlin
// :platform-api. Every fallible call returns a typed result and never throws (AGENTS.md, ab19387):
sealed interface PlatformResult<out T> { data class Ok<out T>(val value: T); data class Err(val error: PlatformError) }
sealed interface PlatformError { val message: String }   // MissingGrants(permissions), ServiceRefused, Busy, NotRecording,
                                                         // Core(message), InvalidUserId(userId), Io(message), Unavailable(message)
interface Recorder {
    val state: StateFlow<RecorderState>
    suspend fun start(): PlatformResult<String>   // grants checked (§1.2.3) → startForegroundService → core.startRecording
    suspend fun pause(): PlatformResult<Unit>     // the service stays in the foreground while paused
    suspend fun resume(): PlatformResult<Unit>    // Err(Core) when the core refuses, e.g. storage still full (R-10)
    suspend fun stop(): PlatformResult<String>    // the finished session id (R-7, V-3)
}
data class RecorderState(
    val phase: Phase, val sessionId: String?,
    val activeBeforeMs: Long, val runningSinceMs: Long?,   // the UI ticks elapsed locally (R-3)
    val deviceCount: Int, val scannerDeferred: Boolean, val dropped: Long, val error: String?,
    val pause: PauseReason? = null,   // non-null exactly while Paused
) {
    enum class Phase { Idle, Starting, Recording, Paused, Stopping }
    sealed interface PauseReason { data object User; data class StorageFull(val freeBytes: Long)   // R-10
                                   data class StorageFailed(val message: String) }   // storing failed for 60 s (#79)
}
```

- **Storage full (R-10).** While the core has paused the recording for storage, `phase` is `Paused` and `pause` is
  `StorageFull(freeBytes)`. A Resume the core accepts (from the app or the notification), or a Stop, ends it. So the
  storage-full dialog closes when `pause` is no longer `StorageFull`.
- **Storage failed.** When staging or committing keeps failing for 60 s for another reason
  (`CoreEvent.StorageFailed`), the recording pauses the same way, with `pause = StorageFailed(message)`. Resume makes
  the core try again.

`Recorder` is an in-process singleton, with no binding and no AIDL. `RecordingService` observes it:

- it updates the notification at most every 5 s ("Recording · 12:34 · 26 devices"), always ending on the latest
  state (a held-back update shows when the window opens); while recording, Android ticks the elapsed time itself
  (a chronometer from `runningSinceMs − activeBeforeMs`), and the device count follows the core's live deltas;
- the actions are Pause/Resume and Stop, and tapping the notification opens the app.

After a process restart, `Recorder` reads `core.recordingState()`.

Ordering (`scanner/platform/FOREGROUND_SERVICE.md` has the details):

- **Start.** `start()` waits up to 5 s for `RecordingService` to confirm `startForeground`, and only then calls
  `core.startRecording`. If Android refuses, or the confirmation times out, no session is created, and the state is
  `Idle` with `error`.
- **Pause and stop.** Before `core.pauseRecording()` or `core.stopRecording()`, the scanner stops, the queued scan
  callbacks drain (a main-looper fence), and the batcher flushes into `ingestScans`. So no observation of the
  running session is ignored as not recording.

### 4.5 Settings, upload, permissions

```kotlin
// :platform-api
data class Settings(val userId: String?, val recordOnOpen: Boolean, val wifiOnlyUpload: Boolean,
                    val scanCodedPhy: Boolean, val hideUnnamedApple: Boolean, val appVersion: String,
                    val onboardingDone: Boolean = false)   // set by Done in O-2
interface SettingsStore {       // DataStore "cluetooth_settings"; one-time SharedPreferencesMigration from cluetooth_prefs
    val settings: StateFlow<Settings>
    suspend fun update(transform: (Settings) -> Settings): PlatformResult<Unit>   // Err(InvalidUserId) / Err(Io); nothing written
}   // defaults: recordOnOpen = true, wifiOnlyUpload = false, scanCodedPhy = true, hideUnnamedApple = true
interface Uploads { val status: StateFlow<UploadStatus>; fun uploadNow(): PlatformResult<Unit> }      // O-1 "Pending uploads"
interface ExportShare { fun share(export: PreparedExport): PlatformResult<Unit>
                        suspend fun saveToDownloads(export: PreparedExport): PlatformResult<Unit> }  // O-1 item 10; acknowledges after
interface Permissions {
    val state: StateFlow<PermissionState>
    fun requestLauncherContract(): ActivityResultContract<Array<String>, Map<String, Boolean>>
    fun openAppSettings(): PlatformResult<Unit>; fun requestEnableBluetooth(activity: ComponentActivity): PlatformResult<Unit>
}   // Err(Unavailable) when Android cannot launch the activity
interface LocationStatusSource { val status: StateFlow<LocationStatus>; val myLocation: StateFlow<MyLocation?>
                                 fun turnOn(activity: ComponentActivity): PlatformResult<Unit> }   // P-5 "Turn on" (additive)
```

- `Permissions` names the permissions behind each `PermissionState` field: `Permissions.nearby(sdk)`,
  `Permissions.location()` and `Permissions.notifications(sdk)` (additive). Each O-2 "Allow" launches its own group.
  An empty input array requests every permission still missing.
- `LocationStatusSource.turnOn(activity)` is P-5's "Turn on". It opens the Play services location settings dialog
  (`SettingsClient`), falling back to `ACTION_LOCATION_SOURCE_SETTINGS`. "Allow" goes through `Permissions`
  (additive, coordinator-approved). The interface default opens `ACTION_LOCATION_SOURCE_SETTINGS`, so existing
  implementations (lane E's test doubles) keep compiling.
- Because of `Permissions`, `:platform-api` and `:platform-fake` are Android libraries (§6.0). Every other type is plain
  Kotlin, and the fakes run in JVM unit tests.

- `onboardingDone` is not migrated from `cluetooth_prefs`. An upgrader from v3 therefore sees the v2 onboarding (O-2)
  once.
- `ExportShare` acknowledgement:
  - `share()` cannot know when the receiving app has finished reading. It acknowledges the previous shared export at
    the next export, or at the next app start.
  - `saveToDownloads()` acknowledges right after the copy. That copy goes through MediaStore Downloads on API 29+ and
    the public Downloads directory on API 24–28. A failed copy is `Err(Io)` and the export stays pending.
  - An acknowledgement the core fails stays pending and is retried at the next export or app start (`ExportLedger`,
    a pure reducer; `AndroidExportShare` is its runner).
- `hideUnnamedApple` changes call `core.setDisplayFilter`.
- `wifiOnlyUpload` changes re-enqueue the work as `UNMETERED`/`CONNECTED`.
- The upload transport is the v3 one. `PlatformUploads` (`:platform`, `upload/`) implements `Uploads`:
  - one upload pass per kind (scans, `gatt/`) is `UploadMachine`, a pure reducer with v3's coordinator rules: list
    the pending files until nothing new appears, resume a saved session or prepare and send fresh, send fresh once
    after an expired session, count an object a failed send stored anyway as stored, and mark each file succeeded
    or failed (authorization denials apart). `UploadPassRunner` runs it with every core, store and file call as a
    typed result;
  - each kind has its own unique work and backoff (scans 30 s, GATT 5 min), plus an hourly periodic run; "Upload
    now" restarts the scan work expedited, `CoreEvent.UploadNeeded` and app start keep queued work;
  - v3's unique work names, session prefs and cache directories are reused. v3's queued work under those names runs
    v3 worker classes that cannot work in v2, so the first v2 start replaces every one of them, once (prefs
    `cluetooth_uploads`); later starts keep what is queued. v3's resumable sessions resume;
  - passes of one kind never overlap: one actor per kind runs them in turn (one-time and periodic work alike), and a
    file sealed during a pass gets another pass right after it (at most 5 more). A worker WorkManager stops (a lost
    Wi-Fi constraint, quota, a replacing "Upload now") stops its pass and the transfer with it, unless another worker
    still waits for that pass; the file stays pending. A pass that throws is a failed pass, and the actor goes on;
  - `status` is `core.uploadStatus()`, read again after every pass; its `lastError` also shows what the core cannot
    ("Uploads not scheduled: …", a failure the core could not record).
- The pre-0.0.5 JSONL files go with every scan pass (§1.5).
- `sig-numbers.tsv` is loaded from assets into `CoreAssets`.

---

## 5. UI state models

These are Kotlin, in packages `edu.ucsd.sysnet.cluetoothscanner.api.core` (`:core-api`), `….api.platform`
(`:platform-api`) and `….ui.contract` (`:ui-contract`). They are plain Kotlin (JVM modules), so fakes and tests use
them directly. `:core-bridge` maps the UniFFI records 1:1, with field names in camelCase.

### 5.1 Data models (`:core-api`)

```kotlin
typealias DeviceKey = Long

sealed interface SessionScope { data object All : SessionScope; data class Sessions(val ids: List<String>) : SessionScope }
data class SessionFilter(val startedFromMs: Long? = null, val startedToMs: Long? = null)
data class Scope(val sessions: SessionScope, val filter: SessionFilter? = null) {
    companion object { val All = Scope(SessionScope.All); fun session(id: String) = Scope(SessionScope.Sessions(listOf(id))) }
}
data class GeoPoint(val lon: Double, val lat: Double)
data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double)
class DeviceKeys(val keys: LongArray, val seq: Long)

enum class Identification { Any, Identified, Unidentified }
data class DeviceFilter(
    val area: Bbox? = null, val identification: Identification = Identification.Any,
    val categories: Set<String> = emptySet(),                 // any-of
    val readable: Boolean = false, val trackers: Boolean = false, val vulnerable: Boolean = false,  // AND presets
    val inRangeOnly: Boolean = false, val page: String? = null, val query: String? = null,
)
enum class PageKind { Device, Platform, Identifier, Vulnerability }
data class PageRef(val id: String, val kind: PageKind, val title: String)
data class CategoryCount(val category: String, val label: String, val deviceCount: Int)

enum class SessionStatus { Recording, Paused, Completed, Interrupted }
enum class UploadState { Pending, Uploaded, Failed, Empty }
data class SessionSummary(val id: String, val status: SessionStatus, val startedMs: Long, val endedMs: Long?,
                          val activeMs: Long, val distanceM: Double, val deviceCount: Int, val upload: UploadState)
data class AllSessionsSummary(val sessionCount: Int, val deviceCount: Int, val bbox: Bbox?)
data class SessionView(val scope: Scope, val live: Boolean, val sessionIds: List<String>, val deviceCount: Int,
                       val frame: Bbox?, val route: List<GeoPoint>, val routesFaint: Boolean, val seq: Long)

data class DeviceRow(val key: DeviceKey, val addr: String, val name: String?, val page: PageRef?,
                     val categories: List<String>, val traits: List<String>, val readable: Boolean,
                     val vulnerable: Boolean, val icon: String?, val rssi: Int?, val inRange: Boolean, val spark: ByteArray?,
                     val best: GeoPoint?, val lastSeenMs: Long)
data class ProductCard(val id: String, val title: String, val kind: PageKind?, val icon: String?, val live: Boolean,
                       val count: Int, val inRange: Int, val lastSeenMs: Long)
data class CategoryGroup(val category: String, val label: String, val products: List<ProductCard>)
data class ProductDetail(val page: PageRef?, val title: String, val oneLine: String?, val categories: List<String>,
                         val traits: List<String>, val readable: Boolean, val vulnerabilities: List<PageRef>,
                         val wikiUrl: String?, val icon: String?, val iconCredit: IconCredit?,
                         val places: Int, val lastSeenMs: Long?, val deviceCount: Int)
data class IconCredit(val source: String, val license: String, val author: String?)
enum class AdvFormat { Legacy, Extended, Unknown }
data class AdStructure(val len: Int, val adType: Int, val data: ByteArray)
data class DecoderOutput(val fields: List<DecodedField>)                     // always null in v2.0 (issue #39)
data class DecodedField(val name: String, val value: FieldValue, val unit: String?, val lookup: String?)
sealed interface FieldValue { data class Bool(val v: Boolean) : FieldValue; data class Number(val v: Double) : FieldValue
                              data class Text(val v: String) : FieldValue }
data class DeviceDetail(val key: DeviceKey, val addr: String, val name: String?, val page: PageRef?,
                        val categories: List<String>, val traits: List<String>, val readable: Boolean,
                        val vulnerable: Boolean, val vulnerabilities: List<PageRef>, val wikiUrl: String?,
                        val icon: String?, val rssi: Int?, val inRange: Boolean, val live: Boolean, val connectable: Boolean?,
                        val format: AdvFormat, val companyId: Int?, val companyName: String?, val serviceUuid: String?,
                        val txPower: Int?, val structures: List<AdStructure>, val tail: ByteArray?,
                        val decoded: DecoderOutput?, val lastSeenMs: Long)
data class Sighting(val at: GeoPoint, val rssi: Int, val seenMs: Long)
data class LiveChart(val key: DeviceKey, val endMs: Long, val samples: ByteArray, val rssi: Int?)
enum class MapMode { Points, Cells }
data class MapData(val mode: MapMode, val count: Int, val geojson: String, val seq: Long)
// GattStepKind, GattStepState, GattFailure, GattStep, GattView, GattServiceView, GattCharView,
// LiveDelta, DeviceLive, FixSummary, CoreRecordingState, CoreEvent, StorageStats, UploadStatus,
// ExportFormat, PreparedExport, DeleteOutcome: 1:1 with §2.

interface CoreQueries {                 // the UI's data interface; suspend = cancellable
    val live: Flow<LiveDelta>; val events: Flow<CoreEvent>
    suspend fun sessions(): List<SessionSummary>; suspend fun currentSession(): SessionSummary?
    suspend fun allSessionsSummary(): AllSessionsSummary
    suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate>  // R-4a; LiveDelta.discovery: DiscoveryUpdate?
    suspend fun sessionView(scope: Scope): SessionView
    suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String
    suspend fun deviceOrder(scope: Scope): DeviceKeys
    suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys
    suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow>
    suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount>
    suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup>
    suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail?
    suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail?
    suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting>
    suspend fun deviceChart(key: DeviceKey): LiveChart?
    suspend fun deviceGatt(key: DeviceKey): GattView?
    suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys?
    fun matchDevice(query: String, name: String?, addr: String): Boolean
    suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData
    suspend fun setLiveFocus(key: DeviceKey?)
}
interface CoreStorage {
    suspend fun storageStats(): StorageStats
    suspend fun prepareExport(scope: Scope, format: ExportFormat): PreparedExport
    suspend fun acknowledgeExport(id: String)
    suspend fun deleteLocalData(scope: Scope, includeUnuploaded: Boolean): DeleteOutcome
    // SPEC R-10 (§2.4.1), typed results (AGENTS.md): CoreResult<T> = Ok(value) | Err(CoreException)
    suspend fun reclaimable(): CoreResult<Reclaimable>
    suspend fun deleteUploadedRaw(): CoreResult<Long>
    suspend fun clearLocalDatabase(): CoreResult<Long>
}
interface CoreIngest { /* platform only: ingestScans, ingestLocation, clearLocation, newGattDiscoveryId, gattTarget,
    ingestGatt, start/pause/resume/stopRecording, configureScan, recordingState, setDisplayFilter, uploads */ }
```

### 5.2 Platform models (`:platform-api`)

```kotlin
data class LocationStatus(val state: State, val warn: Boolean, val ageMin: Int? = null, val action: Action? = null) {
    enum class State { Ok, Denied, Off, Waiting, Stale }; enum class Action { Allow, TurnOn } }
data class MyLocation(val at: GeoPoint, val accuracyM: Double, val ageMs: Long)
data class PermissionState(val nearby: Perm, val location: Perm, val notifications: Perm, val bluetoothOn: Boolean)
enum class Perm { Granted, Denied, PermanentlyDenied, NotRequired }
// RecorderState, Settings, Recorder, GattRunner, Uploads, ExportShare, Permissions, LocationStatusSource: §4
```

### 5.3 Shared UI contract (`:ui-contract`, merged first as U0)

These are the types more than one UI lane uses. They are the only way C, D and E talk to each other.

```kotlin
/** G-9: the one ordered-keys state. Built once from deviceOrder (sorted once by RSSI); changes only by appends;
 *  filters intersect, never reorder. Rebuilt on V-7. */
class OrderedKeys(initial: LongArray) {
    val size: Int
    fun append(keys: LongArray)                    // live appends, area or search new matches (dedup)
    fun visible(passing: DeviceKeys): LongArray    // order ∩ passing, order kept
}
data class CameraSnapshot(val center: GeoPoint, val zoom: Double, val bearing: Double = 0.0)
/** M-11 (used by Q-6): zoom differs by ≥ 1, or the centre moved > 25 % of the viewport on either axis, measured on
 *  screen (Web Mercator). The one copy; D's search state and E's map both call it. App camera moves are never passed. */
fun movedAway(reference: CameraSnapshot, current: CameraSnapshot, viewport: Bbox): Boolean
/** The camera showing [viewport] at [zoom]: centred on the viewport's screen (Web Mercator) centre. */
fun viewportCamera(viewport: Bbox, zoom: Double): CameraSnapshot
data class CoverInsets(val top: Int, val bottom: Int, val left: Int, val right: Int)   // dp the sheet/panel/chrome cover

/** Search state (Q-5..Q-7); D owns the instance, C and E read it. */
data class SearchState(val query: String, val area: Bbox?, val order: OrderedKeys,
                       val reference: CameraSnapshot, val movedAway: Boolean,
                       val savedFilter: DeviceFilter)   // search ignores filters; restored when search ends (D-3a)

/** What D asks of the map (S-17, D-8, D-14, Q-5); E implements it, :app connects them. */
interface MapRequests {
    fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean)   // the top sheet's point set
    fun frame(bbox: Bbox)                                                 // D-8 / M-10
    fun focusDevice(key: DeviceKey, minZoom: Double = 18.2)               // D-14, avoids the cover
    fun setSelection(key: DeviceKey?)                                     // M-6
    fun camera(): CameraSnapshot; fun restore(camera: CameraSnapshot)     // S-8
    fun setCover(insets: CoverInsets)
}
/** What the map reports (M-7, P-6, D-4, Q-6); :app routes them to D (sheets) and C (the "Search this area" chip). */
interface MapEvents {
    fun onUserGestureStart()                                // P-6: D collapses the top sheet
    fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double)
    fun onDeviceTap(key: DeviceKey); fun onEmptyTap()
}
interface MapCacheInfo { suspend fun sizeBytes(): Long }    // O-1 Storage "map cache" (E implements)
```

### 5.4 Screen states and lane-owned UI state

```kotlin
// ---- :app (integration) ----
enum class LayoutMode { Compact, Medium, Expanded }                      // G-2
data class MainUiState(
    val layout: LayoutMode,
    val scope: Scope?,                       // null = first run (V-6); v2.0 builds Scope.All or Scope.session(id)
    val view: SessionView?, val recorder: RecorderState,
    val location: LocationStatus, val myLocation: MyLocation?,
    val sidebarOpen: Boolean, val page: Page?, val locationPopoverOpen: Boolean,
)
sealed interface Page { data object Settings : Page; data object SearchPage : Page }

// ---- :chrome (lane C) ----
data class RecorderUi(val form: Form, val paused: Boolean, val elapsedMs: Long, val deviceCount: Int,
                      val fix: FixSummary?, val route: List<GeoPoint>) { enum class Form { Fab, Capsule, Panel } }
data class SessionsListState(val current: SessionSummary?, val all: AllSessionsSummary, val days: List<DayGroup>,
                             val selected: Scope?)          // v2.0: single selection only
data class DayGroup(val label: String, val sessions: List<SessionSummary>)
data class SearchPageState(val query: String, val area: Bbox?, val results: OrderedKeys?, val searchedEverywhere: Boolean)
data class SettingsUi(val settings: Settings, val uploads: UploadStatus, val storage: StorageUi)
data class StorageUi(val databaseBytes: Long, val rawFilesBytes: Long, val mapCacheBytes: Long?, val pendingCount: Int,
                     val exportScope: Scope, val exportFormat: ExportFormat?, val exporting: Boolean,
                     val deleteConfirm: DeleteConfirm?,       // O-1 items 8–11
                     val deleteScope: Scope = exportScope)    // item 11's own scope (additive); never changes Export's
/** Confirm: the scope's deletion, keeping un-uploaded data; LoseUnuploaded: the explicit second step. */
enum class DeleteConfirm { Confirm, LoseUnuploaded }
data class OnboardingUi(val step: Int, val permissions: PermissionState, val userId: String,
                        val userIdValid: Boolean, val autoRecord: Boolean)

// ---- :sheets (lane D) ----
enum class SheetHeight { Collapsed, Half, Full, Dismissed }               // phone (S-5)
enum class PanelHeight { Open, Partial }                                  // tablet (T-2); a saved Collapsed maps to Partial
sealed interface SheetKind { data object Discover : SheetKind; data class Product(val pageId: String?) : SheetKind
                             data class Device(val key: DeviceKey) : SheetKind; data object Filter : SheetKind }
data class SheetEntry(val id: Long, val kind: SheetKind, val height: SheetHeight, val panel: PanelHeight, val scroll: ScrollSnapshot,
                      val camera: CameraSnapshot?, val content: SheetContentState)
sealed interface SheetContentState {
    data class Discover(val view: View, val segment: Segment, val filter: DeviceFilter,   // filter incl. presets
                        val order: OrderedKeys?, val cardOrder: Map<String, List<String>>,
                        val area: AreaState?) : SheetContentState {
        enum class View { Categories, List }; enum class Segment { Nearby, All, ThisArea } }
    data class Product(val detail: ProductDetail?, val order: OrderedKeys?) : SheetContentState
    data class Device(val detail: DeviceDetail?, val tab: Tab, val gatt: GattUi, val chart: LiveChart?) : SheetContentState {
        enum class Tab { Advertising, Gatt } }
    /** D-3a: changes apply live; rowOrder is fixed when the sheet opens; counts update in place (0 stays, dimmed). */
    data class Filter(val rowOrder: List<String>, val counts: Map<String, Int>) : SheetContentState
}
data class AreaState(val bbox: Bbox, val order: OrderedKeys)
sealed interface GattUi { data object Idle : GattUi; data object Confirm : GattUi
    data class Running(val view: GattView) : GattUi; data class Failed(val view: GattView) : GattUi
    data class Done(val view: GattView) : GattUi }
data class SheetStack(val entries: List<SheetEntry>)                     // entries[0] = Discover, never popped

// ---- :map (lane E) ----
data class MapUiState(val selection: DeviceKey?, val routesFaint: Boolean, val cover: CoverInsets,
                      val showLocate: Boolean, val showWarning: Boolean)
```

The scope seam:

- `Scope` flows from the sidebar into every query. Nothing in the UI branches on "single id" except labels.
- **Multi-select and session filters** later touch only `:chrome` (selection UI) and `MainViewModel` (building the
  `Scope`). The union rendering exists already: `SessionView.routesFaint`, frozen rows, and counts over the union.
- **No lane builds multi-select UI in v2.0.**
- `DeviceFilter` (including presets and categories) resets to the default on every scope change (V-7).

---

## 6. Lanes

### 6.0 Gradle modules and dependency rule

Namespaces are `edu.ucsd.sysnet.cluetoothscanner.<module>`. The applicationId stays `edu.ucsd.sysnet.cluetoothscanner`.

| Module | Type | Owner | Depends on |
|---|---|---|---|
| `:core-api` | Kotlin/JVM | A (A0-api) | none |
| `:ui-contract` | Kotlin/JVM | integration owner (U0, a transcription of §5.3) | `:core-api` |
| `:platform-api` | Android lib (types only; `Permissions` names `ActivityResultContract`/`ComponentActivity`) | B (B0) | `:core-api` |
| `:designsystem` | Android lib (Compose) | C (C1) | none (reads `design/ui-v2/tokens.json` at build) |
| `:core-fake` | Kotlin/JVM | A (A0-fake) | `:core-api` |
| `:platform-fake` | Android lib (JVM unit tests, no Robolectric) | B (B0) | `:platform-api` |
| `:core-bridge` | Android lib (UniFFI + `.so`) | A | `:core-api` |
| `:platform` | Android lib | B | `:platform-api`, `:core-api` |
| `:chrome` | Android lib (Compose) | C | `:designsystem`, `:ui-contract`, `:core-api`, `:platform-api` |
| `:sheets` | Android lib (Compose) | D | `:designsystem`, `:ui-contract`, `:core-api`, `:platform-api` |
| `:map` | Android lib (Compose + MapLibre 12.x) | E | `:designsystem`, `:ui-contract`, `:core-api`, `:platform-api` (reads `map-style.json` at build) |
| `:gallery` | Android app, debug only | B0 creates the shell and the registry with stubs for all five lane files `lanes/{A,B,C,D,E}Gallery.kt`; each lane owns its file | all + fakes |
| `:app` | Android app | integration (§6.7) | all |

The dependency rule:

- **Feature modules** (`:core-bridge`, `:platform`, `:chrome`, `:sheets`, `:map`) never depend on each other or on
  another lane's fake.
- They depend only on **foundation modules**: the contracts (`:core-api`, `:platform-api`, `:ui-contract`) and
  `:designsystem`.
- Foundations merge before any feature lane needs them (§6.9).
- `:platform-fake` does not depend on `:core-fake`. Its `FakeRecorder` takes callbacks, which `:gallery` and tests
  wire to `:core-fake`'s live simulator.
- **During the lanes, the old `:app` (v3) stays untouched** and keeps building and checking.
- `gradle/libs.versions.toml` is shared, with additive lines only.
- Tokens and the map style come from `scanner/design/ui-v2/export.mjs` (`mise run
  //scanner:design-export`; the `design-export-check` task runs in `check`). C and E generate code or
  assets from the JSON at build time and never copy values by hand.

### 6.1 Rule ownership: one accountable lane per SPEC rule

The accountable lane answers for the rule at verification. Named sub-parts are what other lanes supply through the
contracts. "I" is integration.

| Rule | Accountable | Sub-parts supplied by others |
|---|---|---|
| G-1 | C | E: map paint from `map-style.json` |
| G-2 | I | none |
| G-3, G-5, G-6, G-7, G-13a | C | none |
| G-4 | C | A: RSSI numbers |
| G-8 | C (all UI strings in `:designsystem`) | D, E: use those strings |
| G-9 | D (`OrderedKeys` in `:ui-contract`) | A: initial order and appends; C: search page uses `OrderedKeys` |
| G-10, G-11, G-13 | D | none |
| G-12 | A (core-side filter on every list, count and map) | B: setting → `set_display_filter`; C: Settings row |
| G-14 | I (back dispatcher) | C: close popover, page, sidebar, recorder; D: `handleBack()` for sheets and search |
| C-1 to C-17 (C-17 incl. the category icon table, §3.4) | C | A: C-6 samples; A: page `icon` assets |
| P-1, P-2, P-3 | I | C: FAB and float buttons; E: map |
| P-4 | E | B: my location |
| P-5 | E (button and placement) | B: status; C: popover content |
| P-6 | D (collapses the top sheet) | E: `onUserGestureStart`; I: wiring |
| R-1 to R-4, R-6 to R-9 | C | B: actions |
| R-5 | C (tap behaviour) | E: `RouteMiniMap`; I: fit-route wiring |
| S-1 to S-16 (incl. S-10a) | D | none |
| S-17 | D (what the map shows) | E: executes `MapRequests` |
| D-1 (incl. the filter button and its count badge), D-1a, D-2 to D-12 | D | A: data |
| D-3a Filter sheet | D | A: `category_counts`, filters, the page sets |
| D-13 | D (state machine, UI) | B: `GattRunner`; A: stored result |
| D-14 | D | E: camera |
| M-1 to M-8, M-10, M-11 | E | A: points, routes, sightings |
| M-9 | E | B: location source |
| Q-1, Q-2 | A | none |
| Q-3, Q-4 | C | A: search |
| Q-5 | D (search state, Discover in search mode) | C: search bar; E: `filter.query` on the map |
| Q-6 | D (re-run and order rule) | E: M-11 test and idle events; C: chip view |
| Q-7 | D | C: bar text tap |
| L-1, L-2, L-3 | C | A: summaries |
| V-1 | B | none |
| V-2 to V-5 | I | C, D, E: render |
| V-6 | I | B: setting and `Recorder` |
| V-7 | I | D: stack and filter reset; E: refit |
| O-1 (incl. the Storage group, items 8–11) | C | B: settings, uploads, `ExportShare` (share sheet or Downloads); A: stats, export, delete; E: `MapCacheInfo` |
| O-2 | C | B: permissions, Bluetooth, user id |
| T-1, T-2 (Open/Partial; a user pan or zoom moves Open to Partial), T-3 | D | E: `onUserGestureStart`; I: wiring |
| T-4, T-5, T-6, T-7 | C | none |
| T-8 | E | none |
| T-9 | I | D: panel state mapping |
| B (data boundary) | A | B: location, settings, recording rows |

### 6.2 Lane A: Rust core

- **Scope.** The accountable rules in §6.1. Beyond those:
  - the scope semantics (§2.1);
  - filters and `category_counts`;
  - the catalog and the bundle exporter, schema 2 (§3.4);
  - export in three formats, storage stats and delete (§2.8);
  - cancellable queries;
  - the plaintext rule (§1.6).
  - **Not in v2.0:** decoder execution (rquickjs) and `decoder.tests.yaml`. Those are issue #39. `decoded` stays
    `None`.
- **Owns:**
  - `cluetooth-core/**`;
  - `scanner/core-api/**`, `core-fake/**` and `core-bridge/**`;
  - `scanner/tools/wiki-bundle/**` and the `wiki-bundle.json` asset;
  - the core parts of `docs/local-data.md` and `docs/payload-schema-v2.md` (loss window).
- **Provides:** the §2 API, and `CoreQueries`, `CoreStorage` and `CoreIngest` in `:core-bridge`. The listener goes to
  a `MutableSharedFlow(extraBufferCapacity = 4)` with a blocking `emit` on `ct-notify`.
- **`:core-fake`** is a deterministic port of `data.js`, with the seed and data mapped onto pages and categories
  from the bundle. It includes a live simulator (1 Hz, staleness at 20 s, an append every 5 s), the demo states,
  unions, filters and `category_counts`.
- **Done when:**
  - `mise run //cluetooth-core:check` is green, with the v3 payload, matcher and upload tests unchanged. Payload v3
    and gatt/v1 bytes must not change.
  - New tests pass for:
    - `ble_adv_split` and generated-column parity with `cluetooth_sync.ble.parse_raw`;
    - `payload_raw` round-trip;
    - minute-merge parity with `sighting_sample_better`;
    - the Q-1 vectors;
    - every §2.5 query for one session, a union and All, with filters, presets, `category_counts` facets and the
      display filter;
    - cells for one session and for a filtered scope (they never use All's cells);
    - delta cadence, coalescing, seq and snapshot;
    - commit gating: after a kill between appends, the DB rows ⊆ the staged Parquet rows;
    - pause and resume;
    - recovery of an interrupted session, including an **upgrade with a v3 recording still open**, whose staging
      WAL is sealed as v3;
    - re-match;
    - migrations, `SchemaTooNew`, `rtree`/`rarray` present, and corruption handling;
    - plaintext kept after upload (SHA-256 unchanged) and ciphertext removed;
    - the three export formats: ParquetRaw byte-identical, CSV round-trip equal to the Parquet values, and the
      snapshot schema equal to the research-aligned DDL;
    - delete with and without un-uploaded data;
    - cancellation: a cancelled coroutine interrupts SQLite within 50 ms, and a dropped query that has not
      started never runs;
    - the bundle exporter (Node tests): vocabulary violations fail, vulnerability links resolve, decoders are
      bundled verbatim.
  - The bench `cargo run --release --example bench_queries` on a synthetic 3-year DB meets ⅕ of the §3.7 budgets on
    the reference host.
  - The `:core-bridge` JVM tests go through the real bindings.
- **Merge:** A0-api first (day 1), A0-fake after U0 and B0, then A.

### 6.3 Lane B: Kotlin platform

- **Scope.** The accountable rules in §6.1. Beyond those:
  - the sub-parts for O-1 and O-2 (settings, uploads, `ExportShare`, permissions, Bluetooth);
  - the D-13 runner;
  - the notifications;
  - the first-launch cleanup (§1.5) and the legacy uploader.
- **Owns:**
  - `scanner/platform-api/**`, `platform-fake/**` and `platform/**`;
  - the `gallery/` shell and registry;
  - `scanner/tools/sig-numbers.py` and `app/src/main/assets/sig-numbers.tsv`, copied in B0 from the
    PR #36 branch.
- **Provides:** `Recorder`, `LocationStatusSource`, `SettingsStore`, `Uploads`, `ExportShare`, `Permissions`,
  `GattRunner`, and the lazily built `AppGraph` pieces.
- **Develops against** `FakeCoreIngest` and `FakeCoreStorage` (`:core-fake`, in tests only; `:platform` itself
  depends only on `:core-api`).
- **Fakes it delivers:**
  - `FakeRecorder` (callbacks);
  - `FakeLocationStatus` (`ok|denied|off|waiting|stale`);
  - `FakeSettings`;
  - `FakeGattRunner`, which follows `data.js`: non-connectable devices time out, and "ELK-BLEDOM" disconnects while
    reading;
  - `FakePermissions`.
- **Done when:**
  - JVM and Robolectric tests pass for:
    - batching and backpressure;
    - the location-status thresholds;
    - GATT step and failure mapping, using the ported v3 engine tests;
    - the settings migration from `cluetooth_prefs`;
    - the cleanup ordering;
    - Wi-Fi-only constraints;
    - grants checked before `startForeground` (a missing grant gives no service start and an error state);
    - pause keeping the foreground service;
  - `mise run //scanner:check` is green;
  - the documented device smoke test passes: 10 min with the screen off and the task swiped away, then `am kill` →
    "Recording interrupted".
- **Merge:** B0 after A0-api and U0 (day 1), then B.

### 6.4 Lane C: design system, components and chrome

- **Scope.** The accountable rules in §6.1. That includes:
  - the O-1 Storage group (items 8–11): usage with the database / raw files / map cache breakdown, Export with a
    scope (one session or All) and a format, and Delete local data with a scope, a confirmation, and the explicit
    second step to lose un-uploaded data;
  - the category icon table (§3.4), with page icons taking precedence;
  - no chip row in Discover (C-3 is still used by "Search this area", Q-6).
  - **No multi-select UI.**
- **Owns:** `scanner/designsystem/**` (generated `Tokens.kt`, Inter, icons from `app.js ICONS`,
  components, all UI strings), `chrome/**` and `gallery/…/lanes/CGallery.kt`.
- **Provides:** `CluetoothTheme`, every C-n composable, `CategoryIcon(categoryId, pageIcon)`, `RecorderControl`, `Sidebar`, `SearchPage`,
  `SearchBar`, `SearchThisAreaChip`, `SettingsPage` (with `StorageGroup`), `Onboarding`, `LocationPopover`, and
  formatting helpers.
- **Done when:**
  - a token test proves every `tokens.json` entry is generated, with no hex or dp literals outside generated code;
  - Roborazzi screenshot tests pass on the JVM for every component and screen, light and dark, at 412×915 and
    1024×768, matching the prototype gallery;
  - the recorder morph geometry tests pass;
  - 44 dp touch targets hold;
  - reduced motion makes transitions instant;
  - the string tests (G-8) pass.
- **Merge:** C1 (`:designsystem`) right after the contracts, then C2 (`:chrome`).

### 6.5 Lane D: sheet system and contents

- **Scope.** The accountable rules in §6.1. That includes:
  - the D-1 filter button (count badge) and the **D-3a Filter sheet**: the Readable, Trackers and Vulnerable
    switches, Identification, the Categories checklist with faceted `category_counts`, and Clear. Changes apply
    live, and the row order is fixed while the sheet is open;
  - search ignoring the filters and restoring them (`SearchState.savedFilter`);
  - T-2 Open/Partial on the tablet, including a user pan moving Open to Partial;
  - P-6's collapse on `MapEvents.onUserGestureStart`;
  - the filter reset on scope change.
  - Scope-agnostic; **no multi-select UI.**
- **Owns:** `scanner/sheets/**` and `gallery/…/lanes/DGallery.kt`.
- **Provides:** `SheetHost` (phone and tablet panel), `SheetStackController`, `SearchController`, `DiscoverSheet`,
  `ProductSheet`, `DeviceSheet` (no Decoded block in v2.0), `FilterSheet`, and `handleBack()`.
- **Consumes:** `MapRequests` (`:ui-contract`), `CoreQueries` (`:core-fake`), `GattRunner` (`FakeGattRunner`) and
  `:designsystem`.
- **Done when:** Robolectric Compose tests pass for:
  - snap states;
  - a flick moves exactly one state;
  - S-10a 1:1 tracking (`Δoffset == Δpointer` per move, slop included; sheet + scroll = finger at Full);
  - the S-13 hand-off;
  - the four S-8 pop paths restoring height, scroll, view, filters and camera;
  - S-7 (hidden sheets are not composed);
  - S-12 and S-16;
  - the G-9 property tests (presets, categories and area only hide; appends only);
  - the Filter sheet: faceted counts equal the resulting list length, any-of categories, AND switches, Clear, fixed
    row order with 0-count rows dimmed, reset on scope change, ignored and restored around search;
  - T-2 Partial on user pan, and a restored Collapsed mapping to Partial;
  - the D-13 state machine, including cancel on pop;
  - the Q-7 restore;
  - P-6;
  - screenshots of every sheet, light and dark, phone and tablet.
- **Merge:** after C1, alongside E.

### 6.6 Lane E: map

- **Scope.** The accountable rules in §6.1. That includes:
  - the `cells`/`cell-count` layers cloned from `clusters`/`cluster-count` (paint identical; a tap zooms by +2);
  - `MapCacheInfo` (the MapLibre ambient cache file size);
  - the R-5 mini map as a `MapSnapshotter` image (refreshed when the panel opens and every 10 s).
  - Scope-agnostic; **no multi-select UI.**
- **Owns:** `scanner/map/**` (style assets generated from `map-style.json`) and
  `gallery/…/lanes/EGallery.kt`.
- **Provides:** `CluetoothMap(state, events: MapEvents)`, the `MapRequests` implementation, `RouteMiniMap` and
  `MapCacheInfo`.
- **GeoJSON updates:**
  - devices and density from `MapData` on idle, scope or filter change (a newer query cancels the older through `collectLatest`);
  - live `best` changes at most every 5 s;
  - sightings on selection.
- **Consumes:** `CoreQueries` (`:core-fake`), `LocationStatusSource` (`FakeLocationStatus`) and `:designsystem`.
- **Done when:**
  - unit tests pass for:
    - style layer ids and order equal to `map-style.json` per theme;
    - selection equal to `selectionProps`;
    - M-11;
    - P-4 visibility;
    - M-10 padding;
    - the `byUser` classification;
    - `filter.query` showing only matches;
  - an instrumented test on the `cluetooth-test` AVD passes: hit box 8 dp, cluster + 0.2, empty tap, and Points ↔
    Cells on a 50 k-device fake;
  - JankStats p95 < 32 ms while panning 10 k points.
- **Merge:** after C1, alongside D.

### 6.7 Integration (after A, B, C, D and E)

The integration owner owns `scanner/app/**` and `:ui-contract`.

1. **The new `:app`.**
   - `CluetoothApplication` with the lazy `AppGraph`, where the core opens on IO;
   - `MainActivity` with the splash held until the core is ready;
   - `MainViewModel`: scope selection, the G-2 layout mode, the G-14 back dispatcher, P-1 to P-3, V-2 to V-7, T-9,
     wiring `MapEvents` to D and C and D's `MapRequests` to E, and the R-5 fit action.
2. **Remove v3.** Delete the v3 UI, `local/`, `repository/`, the trigger cache, `CoreGateway` and the v3 bindings
   generation. Delete the `CluetoothCore` v3 object in Rust.
3. **Fakes stay in tests.** `:app` uses `:core-bridge` and `:platform`; the fakes remain only in `:gallery` and
   tests.
4. **Manifest.** Same applicationId, a higher versionCode, and the service and permissions from `:platform`.
5. **Docs.** Update `docs/recording-model.md` (no live mode; pause keeps the service), `docs/local-data.md` (v2 DB,
   plaintext rule), and the `CLAUDE.md`/`AGENTS.md` layout lines.

### 6.8 Verification

1. **Checks.** `mise run check` is green, and the APK is named `cluetooth-0.1.0-debug-<sha8>.apk`, never
   overwriting an existing file.
2. **Rule by rule against the prototype and SPEC** (as synced in this PR), at 412×915 and
   1024×768, light and dark, on the `cluetooth-test` AVD. Results go into
   `scanner/design/ui-v2/VERIFY.md`, and each list is watched for 60 s.
3. **On the test phone** `R58M81J3YJV` and a Moto G-class phone:
   - a 30 min recording with the screen off and the task swiped away;
   - `logcat -b crash` is empty;
   - JankStats p95 < 32 ms with more than 300 devices;
   - PSS < 250 MB;
   - All sessions on a synthetic 3-year DB (the §3.7 budgets).
4. **In-place upgrade from v3**, with pending Parquet and an open recording:
   - the pending files upload as v3;
   - the open recording is sealed as v3;
   - `local-data.db` is deleted afterwards;
   - the server lists the objects as succeeded.
5. **Data parity.** Re-ingest one session's uploaded objects on a test DB. Its `sighting_minutes` must equal the
   phone's, including an interrupted session (commit gating).
6. **Exports.** Export one session and All in all three formats, open them with Python (pyarrow, csv, sqlite3), and
   compare.

### 6.9 Merge order

```
day 1:  A0-api (:core-api + settings.gradle.kts skeleton with every v2 module, each with a placeholder build file)
        → U0 (:ui-contract)
        → B0 (:platform-api, :platform-fake, :gallery shell + registry stubs for A–E, sig-numbers copy)
next:   A0-fake (:core-fake) · C1 (:designsystem)
lanes:  A (core, bridge, bundle exporter) · B (platform) · C2 (:chrome) · D (:sheets) · E (:map)
then:   integration → verification
```

Each lane merges into `feat/v2` through its own PR and never edits another lane's files.

---

## 7. Open questions

None are open. The former questions are now owner decisions, recorded at the top:

- Trackers means `asset-tracker`.
- Category icons use the §3.4 table.
- The CSV always has `session_id` and `source_file`.

Future notes:

- A server-side database dashboard that reuses the HTML prototype is deferred until after a few Android iterations.
- Advertisement decoding (`decoder.js` in rquickjs, the Decoded block, running `decoder.tests.yaml` in the bundle
  step) is newcluetooth issue #39.
- Product icons on wiki pages are Gitea #190. The bundle and API fields exist already, and they stay `null` until it
  lands.
- Multi-select of sessions brings back a union cache (§3.7), with no API change.
