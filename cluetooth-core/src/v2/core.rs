//! The v2 `Core` object (`docs/v2-api.md` §2.3 to §2.8).
//!
//! Threading tags, as in §2: [N] non-blocking, [W] applied by the writer, [P] point query, [V] cancellable viewport
//! or filter query (latest wins per kind), [F] file I/O under the file lock.
//!
//! This slice commits observations straight to `local.db` every `durability_interval_ms` and on pause, stop and
//! close; the Parquet staging append that gates each commit, the writer and read threads, the live snapshot and
//! deltas, GATT, uploads, export and delete follow in the next slices and return `Db { "not implemented" }` here.

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard, mpsc};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;

use crate::config::{
    INPUT_FIXED_ESTIMATE_BYTES, MAX_INPUT_BATCH_ESTIMATED_BYTES, MAX_INPUT_BATCH_ROWS,
};
use crate::location::RecentLocationFixes;
use crate::payload::MAX_RAW_BYTES;
use crate::payload_identity::generate_uuid_v7;
use crate::{LocationFix, PendingUpload, PreparedUpload, ScanEnvironment, ScanObservationInput};

use super::catalog::Catalog;
use super::db;
use super::error::CoreError;
use super::keys::{addr_of as key_addr, key_of};
use super::live::{self, LiveState};
use super::query::{self, Ctx};
use super::store::{Obs, RoutePoint, Store, haversine_m};
use super::types::*;
use super::{adv, gatt_view, store};
use rusqlite::OptionalExtension;

pub const API_VERSION: u32 = 8;

/// The route keeps a fix at most 100 m accurate that moved ≥ 5 m or is ≥ 30 s after the last point (§2.4).
const ROUTE_MAX_ACCURACY_M: f64 = 100.0;
const ROUTE_MIN_STEP_M: f64 = 5.0;
const ROUTE_MIN_INTERVAL_MS: i64 = 30_000;

#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    /// Called on `ct-notify`; may block (the core coalesces meanwhile).
    fn on_live(&self, delta: LiveDelta);
    fn on_event(&self, event: CoreEvent);
}

struct Recording {
    session: i64,
    uuid: String,
    started_ms: i64,
    phase: RecordingPhase,
    active_before_ms: i64,
    running_since_ms: Option<i64>,
    env: ScanEnvironment,
    pending: Vec<Obs>,
    route: Vec<RoutePoint>,
    last_route: Option<RoutePoint>,
    last_commit_ms: i64,
}

struct Writer {
    store: Store,
    recording: Option<Recording>,
    fixes: RecentLocationFixes,
    live: LiveState,
}

/// What `ct-notify` delivers to the listener, in order.
enum Note {
    Live(Box<LiveDelta>),
    Event(CoreEvent),
}

type Listener = Arc<Mutex<Option<Arc<dyn CoreListener>>>>;

/// `ct-notify` (§1.3): calls the listener off the ingest path; while it is busy, queued deltas coalesce (§2.6).
fn spawn_notify(listener: Listener) -> Result<mpsc::Sender<Note>, CoreError> {
    let (tx, rx) = mpsc::channel::<Note>();
    std::thread::Builder::new()
        .name("ct-notify".into())
        .spawn(move || {
            while let Ok(first) = rx.recv() {
                let mut notes = vec![first];
                notes.extend(rx.try_iter());
                let mut pending: Option<LiveDelta> = None;
                let mut out = Vec::new();
                for note in notes {
                    match note {
                        Note::Live(d) => {
                            pending = Some(match pending.take() {
                                None => *d,
                                Some(prev) => coalesce(prev, *d),
                            })
                        }
                        Note::Event(e) => {
                            if let Some(d) = pending.take() {
                                out.push(Note::Live(Box::new(d)));
                            }
                            out.push(Note::Event(e));
                        }
                    }
                }
                if let Some(d) = pending {
                    out.push(Note::Live(Box::new(d)));
                }
                let current = lock(&listener).clone();
                let Some(l) = current else { continue };
                for note in out {
                    match note {
                        Note::Live(d) => l.on_live(*d),
                        Note::Event(e) => l.on_event(e),
                    }
                }
            }
        })
        .map_err(CoreError::from_io)?;
    Ok(tx)
}

/// §2.6 coalescing: the latest values per key, appends concatenated, one `seq` (the newer).
fn coalesce(mut a: LiveDelta, b: LiveDelta) -> LiveDelta {
    for c in b.changed {
        if let Some(row) = a.appended.iter_mut().find(|r| r.key == c.key) {
            row.rssi = c.rssi;
            row.in_range = c.in_range;
            if c.name.is_some() {
                row.name = c.name;
            }
            if c.best.is_some() {
                row.best = c.best;
            }
            if let Some(page) = c.page {
                row.page = Some(page);
            }
        } else if let Some(prev) = a.changed.iter_mut().find(|p| p.key == c.key) {
            prev.rssi = c.rssi;
            prev.in_range = c.in_range;
            prev.spark = c.spark;
            prev.name = c.name.or(prev.name.take());
            prev.page = c.page.or(prev.page.take());
            prev.best = c.best.or(prev.best.take());
        } else {
            a.changed.push(c);
        }
    }
    a.appended.extend(b.appended);
    for p in b.products {
        match a.products.iter_mut().find(|x| x.id == p.id) {
            Some(x) => *x = p,
            None => a.products.push(p),
        }
    }
    a.route_appended.extend(b.route_appended);
    LiveDelta {
        session_id: b.session_id,
        seq: b.seq,
        at_ms: b.at_ms,
        state: b.state,
        changed: a.changed,
        appended: a.appended,
        products: a.products,
        route_appended: a.route_appended,
        fix: b.fix.or(a.fix),
        chart: b.chart.or(a.chart),
    }
}

#[derive(uniffi::Object)]
pub struct Core {
    config: CoreConfig,
    catalog: Arc<Catalog>,
    /// `filesDir/cluetooth-core` (§1.5).
    data_dir: std::path::PathBuf,
    writer: Mutex<Writer>,
    reader: Mutex<Connection>,
    display: Mutex<DisplayFilter>,
    listener: Listener,
    notify: Mutex<mpsc::Sender<Note>>,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn not_yet(what: &str) -> CoreError {
    CoreError::Db {
        detail: format!("{what} is not implemented yet in this core slice"),
    }
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn new_uuid(now: i64) -> Result<String, CoreError> {
    let mut random = [0u8; 10];
    getrandom::fill(&mut random).map_err(|e| CoreError::Clock {
        detail: e.to_string(),
    })?;
    Ok(generate_uuid_v7(now.max(0) as u64, random)?
        .hyphenated()
        .to_string())
}

impl Core {
    /// Open with an explicit `now`; [`Core::open`] passes the clock.
    pub(crate) fn open_at(
        config: CoreConfig,
        assets: CoreAssets,
        now: i64,
    ) -> Result<Arc<Core>, CoreError> {
        if config.live_interval_ms < 1000 {
            return Err(CoreError::InvalidConfig {
                detail: "live_interval_ms must be at least 1000".into(),
            });
        }
        let catalog = Arc::new(Catalog::load(
            &assets.wiki_bundle_json,
            &assets.sig_numbers_tsv,
        )?);
        let dir = Path::new(&config.data_directory);
        std::fs::create_dir_all(dir).map_err(CoreError::from_io)?;
        let db_path = dir.join("local.db");
        let (conn, _corrupt) = db::open_writer(&db_path, now)?;
        // A session left open by a killed process is interrupted at its last committed data (§1.2).
        conn.execute(
            "UPDATE sessions SET status = 3,
               ended_ms = coalesce((SELECT max(last_seen_ms) FROM session_devices WHERE session_id = sessions.id),
                                   started_ms)
             WHERE status IN (0, 1)",
            [],
        )?;
        for (key, value) in [
            ("rules_key", Some(&catalog.rules_key)),
            ("bundle_version", Some(&catalog.version)),
            ("wiki_commit", catalog.wiki_commit.as_ref()),
        ] {
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?1, coalesce(?2, ''))
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                rusqlite::params![key, value],
            )?;
        }
        let reader = db::open_reader(&db_path)?;
        let listener: Listener = Arc::new(Mutex::new(None));
        let notify = spawn_notify(listener.clone())?;
        Ok(Arc::new(Core {
            writer: Mutex::new(Writer {
                store: Store::new(conn),
                recording: None,
                fixes: RecentLocationFixes::new(32, config.location_max_age_ms),
                live: LiveState::default(),
            }),
            reader: Mutex::new(reader),
            display: Mutex::new(DisplayFilter {
                hide_unnamed_apple: true,
            }),
            listener,
            notify: Mutex::new(notify),
            catalog,
            data_dir: dir.to_path_buf(),
            config,
        }))
    }

    fn live_of(&self, w: &Writer, now: i64) -> Option<query::Live> {
        w.recording.as_ref().map(|r| query::Live {
            session: r.session,
            uuid: r.uuid.clone(),
            stale_before_ms: now - self.config.stale_after_ms as i64,
            seq: w.live.seq,
            current: w.live.current.clone(),
        })
    }

    /// Runs a read on the read connection with the current recording's view.
    fn read<T>(
        &self,
        f: impl FnOnce(&Connection, &Ctx<'_>) -> Result<T, CoreError>,
    ) -> Result<T, CoreError> {
        let now = now_ms();
        let live = self.live_of(&lock(&self.writer), now);
        let ctx = Ctx {
            catalog: &self.catalog,
            hide_unnamed_apple: lock(&self.display).hide_unnamed_apple,
            live,
        };
        let conn = lock(&self.reader);
        f(&conn, &ctx)
    }

    fn emit(&self, event: CoreEvent) {
        let _ = lock(&self.notify).send(Note::Event(event));
    }

    /// Builds and queues the live delta after a commit (§2.6); nothing is built without a listener.
    fn emit_delta(&self, w: &mut Writer, route: &[RoutePoint], now: i64, force: bool) {
        if lock(&self.listener).is_none() {
            return;
        }
        let state = self.state_of(w);
        let Some(r) = w.recording.as_ref() else {
            return;
        };
        let input = live::DeltaInput {
            conn: &w.store.conn,
            catalog: &self.catalog,
            hide_unnamed_apple: lock(&self.display).hide_unnamed_apple,
            session: r.session,
            uuid: &r.uuid,
            now,
            stale_after_ms: self.config.stale_after_ms as i64,
            state,
            route,
            force,
        };
        match live::delta(&mut w.live, input) {
            Ok(Some(d)) => {
                let _ = lock(&self.notify).send(Note::Live(Box::new(d)));
            }
            Ok(None) => {}
            Err(e) => self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            }),
        }
    }

    fn state_of(&self, w: &Writer) -> RecordingState {
        match &w.recording {
            None => RecordingState {
                phase: RecordingPhase::Idle,
                session_id: None,
                started_ms: None,
                active_before_ms: 0,
                running_since_ms: None,
                device_count: 0,
                distance_m: 0.0,
            },
            Some(r) => {
                let hide = lock(&self.display).hide_unnamed_apple;
                let (count, distance): (u32, f64) = w
                    .store
                    .conn
                    .query_row(
                        "SELECT CASE WHEN ?2 THEN device_count_shown ELSE device_count END, distance_m
                         FROM sessions WHERE id = ?1",
                        rusqlite::params![r.session, hide],
                        |row| Ok((row.get(0)?, row.get(1)?)),
                    )
                    .unwrap_or((0, 0.0));
                RecordingState {
                    phase: r.phase,
                    session_id: Some(r.uuid.clone()),
                    started_ms: Some(r.started_ms),
                    active_before_ms: r.active_before_ms,
                    running_since_ms: r.running_since_ms,
                    device_count: count,
                    distance_m: distance,
                }
            }
        }
    }

    /// The commit of §2.4 step 5 for the current recording, then its live delta.
    fn commit(&self, w: &mut Writer, now: i64) -> Result<(), CoreError> {
        let Writer {
            store, recording, ..
        } = w;
        let Some(r) = recording.as_mut() else {
            return Ok(());
        };
        let obs = std::mem::take(&mut r.pending);
        let route = std::mem::take(&mut r.route);
        let result = store.commit(r.session, &obs, &route, &self.catalog);
        r.last_commit_ms = now;
        let recording = r.phase == RecordingPhase::Recording;
        match &result {
            Err(e) => self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            }),
            Ok(()) if recording => self.emit_delta(w, &route, now, false),
            Ok(()) => {}
        }
        result
    }

    /// §2.4 `gatt_target`: the device's latest sighting, pending first, then the DB.
    fn gatt_target_of(&self, key: DeviceKey) -> Result<Option<GattTarget>, CoreError> {
        let w = lock(&self.writer);
        let session_id = w.recording.as_ref().map(|r| r.uuid.clone());
        let addr_type = |t: Option<i64>| match t {
            Some(0) => Some("public".to_owned()),
            Some(1) => Some("random".to_owned()),
            _ => None,
        };
        let pending = w.recording.as_ref().and_then(|r| {
            r.pending
                .iter()
                .filter(|o| o.addr == key)
                .max_by_key(|o| o.at_ms)
        });
        if let Some(o) = pending {
            let named: Option<String> = w
                .store
                .conn
                .query_row("SELECT name FROM devices WHERE addr = ?1", [key], |r| {
                    r.get(0)
                })
                .optional()?
                .flatten();
            return Ok(Some(GattTarget {
                addr: key_addr(key),
                addr_type: addr_type(o.addr_type.map(i64::from)),
                name: adv::payload_fields(&adv::split(&o.raw))
                    .local_name
                    .or(named),
                trigger_raw: o.raw.clone(),
                trigger_rssi: o.rssi,
                trigger_scanned_at_ms: o.at_ms,
                trigger_is_legacy: o.is_legacy,
                trigger_primary_phy: o.primary_phy,
                trigger_secondary_phy: o.secondary_phy,
                trigger_adv_sid: o.adv_sid,
                session_id,
            }));
        }
        let conn = &w.store.conn;
        let latest = conn
            .query_row(
                "SELECT payload_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, rssi_max, last_seen_ms
                 FROM sighting_minutes WHERE addr = ?1 ORDER BY last_seen_ms DESC, id DESC LIMIT 1",
                [key],
                |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, i64>(2)?,
                        r.get::<_, i64>(3)?,
                        r.get::<_, i64>(4)?,
                        r.get::<_, i64>(5)?,
                        r.get::<_, Option<i16>>(6)?,
                        r.get::<_, i64>(7)?,
                    ))
                },
            )
            .optional()?;
        let Some((payload, at, legacy, primary, secondary, sid, rssi, seen)) = latest else {
            return Ok(None);
        };
        let opt = |v: i64| (v >= 0).then_some(v as i32);
        let name: Option<String> = conn
            .query_row("SELECT name FROM devices WHERE addr = ?1", [key], |r| {
                r.get(0)
            })
            .optional()?
            .flatten();
        Ok(Some(GattTarget {
            addr: key_addr(key),
            addr_type: addr_type(Some(at)),
            name,
            trigger_raw: store::payload_raw(conn, payload)?,
            trigger_rssi: rssi,
            trigger_scanned_at_ms: seen,
            trigger_is_legacy: (legacy >= 0).then_some(legacy == 1),
            trigger_primary_phy: opt(primary),
            trigger_secondary_phy: opt(secondary),
            trigger_adv_sid: opt(sid),
            session_id,
        }))
    }

    #[cfg(test)]
    pub(crate) fn with_writer_conn<T>(&self, f: impl FnOnce(&Connection) -> T) -> T {
        f(&lock(&self.writer).store.conn)
    }

    pub(crate) fn ingest_scans_at(
        &self,
        batch: Vec<ScanObservationInput>,
        now: i64,
    ) -> Result<IngestAck, CoreError> {
        if batch.len() > MAX_INPUT_BATCH_ROWS {
            return Err(CoreError::InputBatchTooLarge {
                detail: format!("{} rows > {MAX_INPUT_BATCH_ROWS}", batch.len()),
            });
        }
        let estimate: u64 = batch
            .iter()
            .map(|o| {
                INPUT_FIXED_ESTIMATE_BYTES
                    + o.raw.len() as u64
                    + o.addr.len() as u64
                    + o.local_name.as_ref().map_or(0, |n| n.len() as u64)
            })
            .sum();
        if estimate > MAX_INPUT_BATCH_ESTIMATED_BYTES {
            return Err(CoreError::InputBatchTooLarge {
                detail: format!("~{estimate} bytes > {MAX_INPUT_BATCH_ESTIMATED_BYTES}"),
            });
        }
        let mut w = lock(&self.writer);
        let recording = matches!(&w.recording, Some(r) if r.phase == RecordingPhase::Recording);
        if !recording {
            return Ok(IngestAck {
                accepted: 0,
                rejected: 0,
                ignored_not_recording: batch.len() as u32,
            });
        }
        let mut ack = IngestAck::default();
        let mut rows = Vec::with_capacity(batch.len());
        for o in batch {
            let Some(addr) = key_of(&o.addr) else {
                ack.rejected += 1;
                continue;
            };
            if o.raw.len() > MAX_RAW_BYTES {
                ack.rejected += 1;
                continue;
            }
            let fix = w
                .fixes
                .most_recent_preceding(o.elapsed_realtime_nanos)
                .map(|f| (f.lat, f.lon, f.accuracy_meters));
            rows.push(Obs {
                addr,
                at_ms: o.scanned_at_ms,
                rssi: o.rssi,
                raw: o.raw,
                tx_power: o.tx_power,
                connectable: o.is_connectable,
                addr_type: o.addr_type,
                is_legacy: o.is_legacy,
                primary_phy: o.primary_phy,
                secondary_phy: o.secondary_phy,
                adv_sid: o.adv_sid,
                data_status: o.data_status,
                adv_tx_power: o.adv_tx_power,
                periodic_interval: o.periodic_interval,
                fix,
            });
            ack.accepted += 1;
        }
        let due = {
            for o in &rows {
                w.live.hear(o.addr, o.rssi);
            }
            let r = w.recording.as_mut().expect("recording");
            r.pending.extend(rows);
            now - r.last_commit_ms >= self.config.durability_interval_ms as i64
        };
        if due {
            self.commit(&mut w, now)?;
        }
        Ok(ack)
    }

    pub(crate) fn ingest_location_at(&self, fix: LocationFix) {
        if !fix.is_usable() {
            return;
        }
        let mut w = lock(&self.writer);
        w.fixes.push(fix.clone());
        w.live.fix = Some((fix.lat, fix.lon, fix.accuracy_meters, fix.observed_at_ms));
        let Some(r) = w.recording.as_mut() else {
            return;
        };
        if r.phase != RecordingPhase::Recording || fix.accuracy_meters > ROUTE_MAX_ACCURACY_M {
            return;
        }
        let keep = match r.last_route {
            None => true,
            Some(p) => {
                haversine_m(p.lat, p.lon, fix.lat, fix.lon) >= ROUTE_MIN_STEP_M
                    || fix.observed_at_ms - p.t_ms >= ROUTE_MIN_INTERVAL_MS
            }
        };
        if keep {
            let p = RoutePoint {
                t_ms: fix.observed_at_ms,
                lat: fix.lat,
                lon: fix.lon,
                accuracy: Some(fix.accuracy_meters),
            };
            r.route.push(p);
            r.last_route = Some(p);
        }
    }

    pub(crate) fn start_recording_at(
        &self,
        env: ScanEnvironment,
        now: i64,
    ) -> Result<String, CoreError> {
        let state = {
            let mut w = lock(&self.writer);
            if w.recording.is_some() {
                return Err(CoreError::AlreadyRecording);
            }
            let uuid = new_uuid(now)?;
            let session = w.store.create_session(&uuid, now)?;
            w.live = LiveState::default();
            w.recording = Some(Recording {
                session,
                uuid,
                started_ms: now,
                phase: RecordingPhase::Recording,
                active_before_ms: 0,
                running_since_ms: Some(now),
                env,
                pending: Vec::new(),
                route: Vec::new(),
                last_route: None,
                last_commit_ms: now,
            });
            self.state_of(&w)
        };
        let id = state.session_id.clone().expect("recording");
        self.emit(CoreEvent::RecordingChanged { state });
        Ok(id)
    }

    pub(crate) fn pause_recording_at(&self, now: i64) -> Result<(), CoreError> {
        let state = {
            let mut w = lock(&self.writer);
            match &w.recording {
                None => return Err(CoreError::NotRecording),
                Some(r) if r.phase == RecordingPhase::Paused => return Ok(()),
                _ => {}
            }
            self.commit(&mut w, now)?;
            let Writer {
                store, recording, ..
            } = &mut *w;
            let r = recording.as_mut().expect("recording");
            r.active_before_ms += now - r.running_since_ms.unwrap_or(now);
            r.running_since_ms = None;
            r.phase = RecordingPhase::Paused;
            store.pause(r.session, now)?;
            // deltas stop after the one that carries Paused (§2.6)
            self.emit_delta(&mut w, &[], now, true);
            self.state_of(&w)
        };
        self.emit(CoreEvent::RecordingChanged { state });
        Ok(())
    }

    pub(crate) fn resume_recording_at(&self, now: i64) -> Result<(), CoreError> {
        let state = {
            let mut w = lock(&self.writer);
            let Writer {
                store, recording, ..
            } = &mut *w;
            let r = recording.as_mut().ok_or(CoreError::NotRecording)?;
            if r.phase == RecordingPhase::Recording {
                return Ok(());
            }
            r.phase = RecordingPhase::Recording;
            r.running_since_ms = Some(now);
            store.resume(r.session, now)?;
            self.state_of(&w)
        };
        self.emit(CoreEvent::RecordingChanged { state });
        Ok(())
    }

    pub(crate) fn stop_recording_at(&self, now: i64) -> Result<String, CoreError> {
        let (uuid, state) = {
            let mut w = lock(&self.writer);
            if w.recording.is_none() {
                return Err(CoreError::NotRecording);
            }
            self.commit(&mut w, now)?;
            let r = w.recording.take().expect("recording");
            let active = r.active_before_ms + r.running_since_ms.map_or(0, |s| now - s);
            if r.phase == RecordingPhase::Paused {
                w.store.resume(r.session, now)?;
            }
            w.store.finish(r.session, 2, now, active)?;
            (r.uuid, self.state_of(&w))
        };
        self.emit(CoreEvent::RecordingChanged { state });
        self.emit(CoreEvent::SessionsChanged);
        Ok(uuid)
    }
}

#[uniffi::export]
impl Core {
    /// [F] Blocking; Kotlin calls it on `Dispatchers.IO` (§1.2). Opens or migrates `local.db` and marks a session
    /// left open as Interrupted.
    #[uniffi::constructor]
    pub fn open(config: CoreConfig, assets: CoreAssets) -> Result<Arc<Core>, CoreError> {
        Core::open_at(config, assets, now_ms())
    }

    /// [W] Commit, then stop. Kotlin sees it as `closeCore`, because UniFFI objects already have
    /// `AutoCloseable.close()` (which only frees the handle).
    #[uniffi::method(name = "close_core")]
    pub fn close(&self) {
        let mut w = lock(&self.writer);
        let _ = self.commit(&mut w, now_ms());
    }

    /// [N] 8.
    pub fn api_version(&self) -> u32 {
        API_VERSION
    }

    /// [N]
    pub fn set_listener(&self, listener: Option<Arc<dyn CoreListener>>) {
        *lock(&*self.listener) = listener;
    }

    /// [N] Emits `CoreEvent::DataInvalidated`.
    pub fn set_display_filter(&self, filter: DisplayFilter) {
        *lock(&self.display) = filter;
        self.emit(CoreEvent::DataInvalidated {
            reason: "display filter".into(),
        });
    }

    /// [N] The chart rides on deltas (D-10).
    pub fn set_live_focus(&self, _key: Option<DeviceKey>) {}

    // ---- §2.3 recording ----

    /// [W] Returns the new UUIDv7 session id.
    pub fn start_recording(&self, env: ScanEnvironment) -> Result<String, CoreError> {
        self.start_recording_at(env, now_ms())
    }

    /// [W] Commit; ingest is ignored while paused.
    pub fn pause_recording(&self) -> Result<(), CoreError> {
        self.pause_recording_at(now_ms())
    }

    /// [W] The same session.
    pub fn resume_recording(&self) -> Result<(), CoreError> {
        self.resume_recording_at(now_ms())
    }

    /// [W] Commit and finish; returns the session id.
    pub fn stop_recording(&self) -> Result<String, CoreError> {
        self.stop_recording_at(now_ms())
    }

    /// [W] Footer change mid-session.
    pub fn configure_scan(&self, env: ScanEnvironment) -> Result<(), CoreError> {
        let mut w = lock(&self.writer);
        let r = w.recording.as_mut().ok_or(CoreError::NotRecording)?;
        r.env = env;
        Ok(())
    }

    /// [N]
    pub fn recording_state(&self) -> RecordingState {
        let w = lock(&self.writer);
        self.state_of(&w)
    }

    // ---- §2.4 ingest ----

    /// [N] Validates (v3 rules: ≤ 64 rows, ≤ 1 MiB, raw ≤ 1650 B) and buffers until the next commit.
    pub fn ingest_scans(&self, batch: Vec<ScanObservationInput>) -> Result<IngestAck, CoreError> {
        self.ingest_scans_at(batch, now_ms())
    }

    /// [N] Fix ring + route (≤ 100 m accuracy, ≥ 5 m or 30 s).
    pub fn ingest_location(&self, fix: LocationFix) {
        self.ingest_location_at(fix);
    }

    /// [N]
    pub fn clear_location(&self) {
        lock(&self.writer).fixes.clear();
    }

    /// [N]
    pub fn new_gatt_discovery_id(&self) -> Result<String, CoreError> {
        new_uuid(now_ms())
    }

    /// [P] The trigger advertisement from the device's latest payload.
    /// The address and type come from the latest sighting, the current recording's uncommitted ones included.
    /// `None` for a device the core has never seen.
    pub async fn gatt_target(&self, key: DeviceKey) -> Option<GattTarget> {
        self.gatt_target_of(key).ok().flatten()
    }

    /// [W] Validates gatt/v1 (unchanged), writes `pending/gatt/v1` (plaintext), fills the gatt tables, and returns
    /// the view. Saving the same object again is idempotent.
    pub fn ingest_gatt(&self, gatt_v1_json: String) -> Result<GattView, CoreError> {
        let file = crate::gatt::save(&self.data_dir, gatt_v1_json.as_bytes())?;
        let (view, _) = gatt_view::view(&self.catalog, &gatt_v1_json)?;
        {
            let mut w = lock(&self.writer);
            let tx = w.store.conn.transaction()?;
            gatt_view::store(&tx, &gatt_v1_json)?;
            tx.commit()?;
        }
        if !file.archived {
            self.emit(CoreEvent::UploadNeeded);
        }
        Ok(view)
    }

    // ---- §2.5 queries ----

    /// [P] Finished sessions, newest first, no empty ones.
    pub async fn sessions(&self) -> Result<Vec<SessionSummary>, CoreError> {
        self.read(|c, ctx| query::sessions(c, ctx, &|_| UploadState::Pending))
    }

    /// [N]
    pub fn current_session(&self) -> Option<SessionSummary> {
        let now = now_ms();
        let w = lock(&self.writer);
        let r = w.recording.as_ref()?;
        let ctx = Ctx {
            catalog: &self.catalog,
            hide_unnamed_apple: lock(&self.display).hide_unnamed_apple,
            live: self.live_of(&w, now),
        };
        query::session_rows(&w.store.conn)
            .ok()?
            .into_iter()
            .find(|s| s.id == r.session)
            .map(|mut s| {
                s.active_ms =
                    r.active_before_ms + r.running_since_ms.map_or(0, |since| now - since);
                query::summary(&ctx, &s, UploadState::Pending)
            })
    }

    /// [P] Counters.
    pub async fn all_sessions_summary(&self) -> Result<AllSessionsSummary, CoreError> {
        self.read(query::all_sessions_summary)
    }

    /// [P] `getRecording` minus the points.
    pub async fn session_view(&self, scope: Scope) -> Result<SessionView, CoreError> {
        self.read(|c, ctx| query::session_view(c, ctx, &scope))
    }

    /// [V Routes] GeoJSON LineStrings for "route"; multi-session: the routes meeting the viewport.
    pub async fn routes(
        &self,
        scope: Scope,
        viewport: Option<Bbox>,
        zoom: f64,
    ) -> Result<String, CoreError> {
        let _ = zoom;
        self.read(|c, ctx| query::routes(c, ctx, &scope, viewport))
    }

    /// [P] Every shown device, sorted ONCE by RSSI desc, ties by key (G-9).
    pub async fn device_order(&self, scope: Scope) -> Result<DeviceKeys, CoreError> {
        self.read(|c, ctx| query::device_order(c, ctx, &scope))
    }

    /// [V AreaKeys] Keys passing the filter, in `device_order` order.
    pub async fn device_keys(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<DeviceKeys, CoreError> {
        self.read(|c, ctx| query::device_keys(c, ctx, &scope, &filter))
    }

    /// [P] ≤ 200 keys per call, in the given order.
    pub async fn device_rows(
        &self,
        scope: Scope,
        keys: Vec<DeviceKey>,
    ) -> Result<Vec<DeviceRow>, CoreError> {
        self.read(|c, ctx| query::device_rows(c, ctx, &scope, &keys))
    }

    /// [V AreaKeys] Faceted (D-3a): `filter.categories` ignored, every other constraint applied.
    pub async fn category_counts(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryCount>, CoreError> {
        self.read(|c, ctx| query::category_counts(c, ctx, &scope, &filter))
    }

    /// [V AreaProducts] Groups by the page's first category, "unidentified" last (D-2); `filter.page` ignored.
    pub async fn products(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryGroup>, CoreError> {
        self.read(|c, ctx| query::products(c, ctx, &scope, &filter))
    }

    /// [P] A `None` page is the Unidentified devices card.
    pub async fn product(
        &self,
        scope: Scope,
        page_id: Option<String>,
        filter: DeviceFilter,
    ) -> Result<Option<ProductDetail>, CoreError> {
        self.read(|c, ctx| query::product(c, ctx, &scope, page_id.as_deref(), &filter))
    }

    /// [P]
    pub async fn device(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Option<DeviceDetail>, CoreError> {
        self.read(|c, ctx| query::device(c, ctx, &scope, key))
    }

    /// [P] ≤ 10 000.
    pub async fn device_sightings(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Vec<Sighting>, CoreError> {
        self.read(|c, ctx| query::device_sightings(c, ctx, &scope, key))
    }

    /// [N] D-10, live only.
    pub fn device_chart(&self, key: DeviceKey) -> Option<LiveChart> {
        let _ = key;
        None
    }

    /// [P] D-13.7.
    pub async fn device_gatt(&self, key: DeviceKey) -> Result<Option<GattView>, CoreError> {
        self.read(|c, _| gatt_view::latest(c, &self.catalog, key))
    }

    /// [V Search] Q-1/Q-2; `None` for a blank query; `device_order` order.
    pub async fn search(
        &self,
        scope: Scope,
        query: String,
        area: Option<Bbox>,
    ) -> Result<Option<DeviceKeys>, CoreError> {
        self.read(|c, ctx| query::search(c, ctx, &scope, &query, area))
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
        let _ = zoom;
        self.read(|c, ctx| query::map_points(c, ctx, &scope, &filter, viewport))
    }

    // ---- §2.8 storage, export, delete, upload ----

    /// [P]
    pub async fn storage_stats(&self) -> Result<StorageStats, CoreError> {
        Err(not_yet("storage_stats"))
    }

    /// [F] Cancellable.
    pub async fn prepare_export(
        &self,
        request: ExportRequest,
    ) -> Result<PreparedExport, CoreError> {
        let _ = request;
        Err(not_yet("prepare_export"))
    }

    /// [F] Deletes `export-temp/<id>`.
    pub fn acknowledge_export(&self, export_id: String) -> Result<(), CoreError> {
        let _ = export_id;
        Err(not_yet("acknowledge_export"))
    }

    /// [F]+[W] User-initiated only.
    pub async fn delete_local_data(
        &self,
        scope: Scope,
        include_unuploaded: bool,
    ) -> Result<DeleteOutcome, CoreError> {
        let _ = (scope, include_unuploaded);
        Err(not_yet("delete_local_data"))
    }

    /// [N]
    pub fn upload_status(&self) -> UploadStatus {
        UploadStatus::default()
    }

    /// [N] Unchanged from v3.
    pub fn pending_uploads(&self) -> Vec<PendingUpload> {
        Vec::new()
    }

    /// [F] Unchanged from v3: encrypts to `upload-cache/`.
    pub fn prepare_upload(&self, id: String) -> Result<PreparedUpload, CoreError> {
        let _ = id;
        Err(not_yet("prepare_upload"))
    }

    /// [F] Plaintext → archive; the ciphertext is deleted.
    pub fn mark_upload_succeeded(&self, id: String) -> Result<(), CoreError> {
        let _ = id;
        Err(not_yet("mark_upload_succeeded"))
    }

    /// [F]
    pub fn mark_upload_failed(&self, id: String, message: String) -> Result<(), CoreError> {
        let _ = (id, message);
        Err(not_yet("mark_upload_failed"))
    }
}

/// [N] Q-1, pure; the same function is registered as SQL `match_device(q, name, addr)`.
#[uniffi::export]
pub fn match_device(query: String, name: Option<String>, addr: String) -> bool {
    super::search::matches(&query, name.as_deref(), &addr)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> RecordingState {
        RecordingState {
            phase: RecordingPhase::Recording,
            session_id: Some("s".into()),
            started_ms: Some(0),
            active_before_ms: 0,
            running_since_ms: Some(0),
            device_count: 0,
            distance_m: 0.0,
        }
    }

    fn delta(seq: u64, changed: Vec<DeviceLive>, appended: Vec<DeviceRow>) -> LiveDelta {
        LiveDelta {
            session_id: "s".into(),
            seq,
            at_ms: seq as i64,
            state: state(),
            changed,
            appended,
            products: vec![],
            route_appended: vec![GeoPoint {
                lon: seq as f64,
                lat: 0.0,
            }],
            fix: None,
            chart: None,
        }
    }

    fn live(key: i64, rssi: i16, name: Option<&str>) -> DeviceLive {
        DeviceLive {
            key,
            rssi: Some(rssi),
            in_range: true,
            spark: vec![],
            name: name.map(Into::into),
            page: None,
            best: None,
        }
    }

    /// §2.6 coalescing: latest values per key (a change never drops an earlier name), appends concatenated with
    /// later changes folded in, routes concatenated, one seq (the newer).
    #[test]
    fn coalescing_keeps_latest_values_and_concatenates_appends() {
        let row = DeviceRow {
            key: 3,
            addr: "00:00:00:00:00:03".into(),
            name: None,
            page: None,
            categories: vec![],
            traits: vec![],
            readable: false,
            vulnerable: false,
            icon: None,
            rssi: Some(-80),
            in_range: true,
            spark: None,
            best: None,
            last_seen_ms: 0,
        };
        let a = delta(1, vec![live(1, -70, Some("one"))], vec![row]);
        let b = delta(
            2,
            vec![
                live(1, -60, None),
                live(2, -50, None),
                live(3, -40, Some("three")),
            ],
            vec![],
        );
        let m = coalesce(a, b);
        assert_eq!(m.seq, 2);
        assert_eq!(m.changed.len(), 2);
        let one = m.changed.iter().find(|c| c.key == 1).unwrap();
        assert_eq!((one.rssi, one.name.as_deref()), (Some(-60), Some("one")));
        assert_eq!(m.appended.len(), 1);
        assert_eq!(
            (m.appended[0].rssi, m.appended[0].name.as_deref()),
            (Some(-40), Some("three"))
        );
        assert_eq!(m.route_appended.len(), 2);
    }
}
