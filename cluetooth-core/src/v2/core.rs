//! The v2 `Core` object (`docs/v2-api.md` §2.3 to §2.8).
//!
//! Threading tags, as in §2: [N] non-blocking, [W] applied by the writer, [P] point query, [V] cancellable viewport
//! or filter query (latest wins per kind), [F] file I/O under the file lock.
#![allow(unused_variables)]

use std::sync::Arc;

use crate::{LocationFix, PendingUpload, PreparedUpload, ScanEnvironment, ScanObservationInput};

use super::error::CoreError;
use super::types::*;

pub const API_VERSION: u32 = 8;

#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    /// Called on `ct-notify`; may block (the core coalesces meanwhile).
    fn on_live(&self, delta: LiveDelta);
    fn on_event(&self, event: CoreEvent);
}

#[derive(uniffi::Object)]
pub struct Core {}

#[uniffi::export]
impl Core {
    /// [F] Blocking; Kotlin calls it on `Dispatchers.IO` (§1.2). Opens or migrates `local.db`, marks a session left
    /// open as Interrupted, reconciles uploads, seals a recovered staging WAL (v3 or v2 staging alike), starts the
    /// threads, and schedules a background re-match if the rules key changed (§3.3).
    #[uniffi::constructor]
    pub fn open(config: CoreConfig, assets: CoreAssets) -> Result<Arc<Core>, CoreError> {
        todo!()
    }

    /// [W] Append + commit, seal, stop threads. Kotlin sees it as `closeCore`, because UniFFI objects already
    /// have `AutoCloseable.close()` (which only frees the handle).
    #[uniffi::method(name = "close_core")]
    pub fn close(&self) {
        todo!()
    }

    /// [N] 8.
    pub fn api_version(&self) -> u32 {
        API_VERSION
    }

    /// [N]
    pub fn set_listener(&self, listener: Option<Arc<dyn CoreListener>>) {
        todo!()
    }

    /// [N] Emits `CoreEvent::DataInvalidated`.
    pub fn set_display_filter(&self, filter: DisplayFilter) {
        todo!()
    }

    /// [N] The chart rides on deltas (D-10).
    pub fn set_live_focus(&self, key: Option<DeviceKey>) {
        todo!()
    }

    // ---- §2.3 recording ----

    /// [W] Returns the new UUIDv7 session id.
    pub fn start_recording(&self, env: ScanEnvironment) -> Result<String, CoreError> {
        todo!()
    }

    /// [W] Append + commit; ingest is ignored while paused.
    pub fn pause_recording(&self) -> Result<(), CoreError> {
        todo!()
    }

    /// [W] The same session.
    pub fn resume_recording(&self) -> Result<(), CoreError> {
        todo!()
    }

    /// [W] Append + commit, seal, simplify the route, finalize counts; returns the session id.
    pub fn stop_recording(&self) -> Result<String, CoreError> {
        todo!()
    }

    /// [W] Footer change mid-session.
    pub fn configure_scan(&self, env: ScanEnvironment) -> Result<(), CoreError> {
        todo!()
    }

    /// [N]
    pub fn recording_state(&self) -> RecordingState {
        todo!()
    }

    // ---- §2.4 ingest ----

    /// [N] Validates (v3 rules: ≤ 64 rows, ≤ 1 MiB, raw ≤ 1650 B) and enqueues; blocks ≤ 1 s when the queue (256
    /// batches) is full, then `Err(Backpressure)`: the caller keeps the batch and retries.
    pub fn ingest_scans(&self, batch: Vec<ScanObservationInput>) -> Result<IngestAck, CoreError> {
        todo!()
    }

    /// [N] Fix ring + route (≤ 100 m accuracy, ≥ 5 m or 30 s).
    pub fn ingest_location(&self, fix: LocationFix) {
        todo!()
    }

    /// [N]
    pub fn clear_location(&self) {
        todo!()
    }

    /// [N]
    pub fn new_gatt_discovery_id(&self) -> Result<String, CoreError> {
        todo!()
    }

    /// [P] The trigger advertisement from the device's latest payload.
    pub async fn gatt_target(&self, key: DeviceKey) -> Option<GattTarget> {
        todo!()
    }

    /// [W] Validates gatt/v1 (unchanged), writes `pending/gatt/v1` (plaintext), fills the gatt tables, returns the
    /// view.
    pub fn ingest_gatt(&self, gatt_v1_json: String) -> Result<GattView, CoreError> {
        todo!()
    }

    // ---- §2.5 queries ----

    /// [P] Finished sessions, newest first, no empty ones.
    pub async fn sessions(&self) -> Result<Vec<SessionSummary>, CoreError> {
        todo!()
    }

    /// [N]
    pub fn current_session(&self) -> Option<SessionSummary> {
        todo!()
    }

    /// [P] Counters.
    pub async fn all_sessions_summary(&self) -> Result<AllSessionsSummary, CoreError> {
        todo!()
    }

    /// [P] `getRecording` minus the points.
    pub async fn session_view(&self, scope: Scope) -> Result<SessionView, CoreError> {
        todo!()
    }

    /// [V Routes] GeoJSON LineStrings for "route", simplified for the zoom; multi-session: the routes meeting the
    /// viewport.
    pub async fn routes(
        &self,
        scope: Scope,
        viewport: Option<Bbox>,
        zoom: f64,
    ) -> Result<String, CoreError> {
        todo!()
    }

    /// [P] Every shown device, sorted ONCE by RSSI desc (live: current; frozen: strongest), ties by key (G-9).
    pub async fn device_order(&self, scope: Scope) -> Result<DeviceKeys, CoreError> {
        todo!()
    }

    /// [V AreaKeys] Keys passing the filter, in `device_order` order; the UI intersects with its own order.
    pub async fn device_keys(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<DeviceKeys, CoreError> {
        todo!()
    }

    /// [P] ≤ 200 keys per call (LazyColumn paging), in the given order.
    pub async fn device_rows(
        &self,
        scope: Scope,
        keys: Vec<DeviceKey>,
    ) -> Result<Vec<DeviceRow>, CoreError> {
        todo!()
    }

    /// [V AreaKeys] Every category present among the filtered devices, `device_count` desc then label; faceted
    /// (D-3a): `filter.categories` is ignored and every other constraint applies, so a count equals the list length
    /// after checking it; a device counts once in each category of its page.
    pub async fn category_counts(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryCount>, CoreError> {
        todo!()
    }

    /// [V AreaProducts] Groups by the page's FIRST category, groups by device count desc, "unidentified" last as
    /// one card (D-2); `filter.page` is ignored.
    pub async fn products(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryGroup>, CoreError> {
        todo!()
    }

    /// [P] A `None` page is the Unidentified devices card.
    pub async fn product(
        &self,
        scope: Scope,
        page_id: Option<String>,
        filter: DeviceFilter,
    ) -> Result<Option<ProductDetail>, CoreError> {
        todo!()
    }

    /// [P]
    pub async fn device(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Option<DeviceDetail>, CoreError> {
        todo!()
    }

    /// [P] ≤ 10 000.
    pub async fn device_sightings(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Vec<Sighting>, CoreError> {
        todo!()
    }

    /// [N] D-10, live only.
    pub fn device_chart(&self, key: DeviceKey) -> Option<LiveChart> {
        todo!()
    }

    /// [P] D-13.7.
    pub async fn device_gatt(&self, key: DeviceKey) -> Result<Option<GattView>, CoreError> {
        todo!()
    }

    /// [V Search] Q-1/Q-2; `None` for a blank query; `device_order` order.
    pub async fn search(
        &self,
        scope: Scope,
        query: String,
        area: Option<Bbox>,
    ) -> Result<Option<DeviceKeys>, CoreError> {
        todo!()
    }

    /// [V MapPoints] "devices"/"density" for the viewport (+50 %: the bbox padded by a quarter of its width and
    /// height on each side), never the whole scope (§3.7); `filter.query` limits it to search matches (Q-5).
    pub async fn map_points(
        &self,
        scope: Scope,
        filter: DeviceFilter,
        viewport: Bbox,
        zoom: f64,
    ) -> Result<MapData, CoreError> {
        todo!()
    }

    // ---- §2.8 storage, export, delete, upload ----

    /// [P]
    pub async fn storage_stats(&self) -> Result<StorageStats, CoreError> {
        todo!()
    }

    /// [F] Cancellable.
    pub async fn prepare_export(
        &self,
        request: ExportRequest,
    ) -> Result<PreparedExport, CoreError> {
        todo!()
    }

    /// [F] Deletes `export-temp/<id>`.
    pub fn acknowledge_export(&self, export_id: String) -> Result<(), CoreError> {
        todo!()
    }

    /// [F]+[W] User-initiated only; refuses while recording if the scope contains the current session. Removes DB
    /// rows and the scope's archived plaintext (All: also pre-v2 files and the `sessions/` JSON metadata).
    /// `include_unuploaded = false` keeps pending files queued and removes them right after upload (tombstones);
    /// `true` removes them now and they never upload (second confirmation only).
    pub async fn delete_local_data(
        &self,
        scope: Scope,
        include_unuploaded: bool,
    ) -> Result<DeleteOutcome, CoreError> {
        todo!()
    }

    /// [N]
    pub fn upload_status(&self) -> UploadStatus {
        todo!()
    }

    /// [N] Unchanged from v3.
    pub fn pending_uploads(&self) -> Vec<PendingUpload> {
        todo!()
    }

    /// [F] Unchanged from v3: encrypts to `upload-cache/`.
    pub fn prepare_upload(&self, id: String) -> Result<PreparedUpload, CoreError> {
        todo!()
    }

    /// [F] Plaintext → archive; the ciphertext is deleted.
    pub fn mark_upload_succeeded(&self, id: String) -> Result<(), CoreError> {
        todo!()
    }

    /// [F]
    pub fn mark_upload_failed(&self, id: String, message: String) -> Result<(), CoreError> {
        todo!()
    }
}

/// [N] Q-1, pure; the same function is registered as SQL `match_device(q, name, addr)`.
#[uniffi::export]
pub fn match_device(query: String, name: Option<String>, addr: String) -> bool {
    super::search::matches(&query, name.as_deref(), &addr)
}
