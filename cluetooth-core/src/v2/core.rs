//! The v2 `Core` object (`docs/v2-api.md` §2.3 to §2.8), as single-owner actors joined by queues (AGENTS.md):
//!
//! - **writer** (`ct-writer`) owns the `Store` (the write connection and its caches), the pending buffers, the fix
//!   ring and the live state, and runs the effects of the pure [`machine::step`]; every mutation is a message;
//! - **reader** (`ct-read`) owns the read connection and the latest immutable [`Snapshot`] from the writer; every
//!   query is a job with a one-shot reply, and dropping the reply future cancels it;
//! - **notify** (`ct-notify`) owns the listener and coalesces live deltas;
//! - **ticker** (`ct-tick`) sends the 5 s commit tick while the core lives.
//!
//! Threading tags, as in §2: [N] non-blocking, [W] applied by the writer, [P] point query, [V] cancellable viewport
//! or filter query, [F] file I/O. Every FFI function returns `Result<_, CoreError>`.

use std::collections::VecDeque;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_channel::oneshot;
use rusqlite::{Connection, OptionalExtension};

use crate::location::RecentLocationFixes;
use crate::payload_identity::generate_uuid_v7;
use crate::{LocationFix, PendingUpload, PreparedUpload, ScanEnvironment, ScanObservationInput};

use super::catalog::Catalog;
use super::db;
use super::error::CoreError;
use super::keys::addr_of;
use super::live::{self, LiveState};
use super::machine::{self, Control, EpochMs, Event, Millis, Reply, SessionRow, SessionUuid};
use super::query::{self, Ctx};
use super::store::{Obs, RoutePoint, Store, haversine_m};
use super::types::*;
use super::{adv, gatt_view, space, store};

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

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

fn not_yet(what: &str) -> CoreError {
    CoreError::Db {
        detail: format!("{what} is not implemented yet in this core slice"),
    }
}

/// A payload core error; out of space (ENOSPC, "os error 28") becomes R-10's `StorageFull`, whose free space the
/// writer fills in.
fn payload_error(e: crate::CoreError) -> CoreError {
    match &e {
        crate::CoreError::Io { detail } | crate::CoreError::Parquet { detail }
            if detail.contains("os error 28") || detail.contains("No space left on device") =>
        {
            CoreError::StorageFull { free_bytes: 0 }
        }
        _ => CoreError::from(e),
    }
}

fn new_uuid(now: i64) -> Result<String, CoreError> {
    let mut random = [0u8; 10];
    getrandom::fill(&mut random).map_err(|e| CoreError::Clock {
        detail: e.to_string(),
    })?;
    Ok(generate_uuid_v7(u64::try_from(now).unwrap_or(0), random)?
        .hyphenated()
        .to_string())
}

fn closed<T>(_: T) -> CoreError {
    CoreError::Closed
}

fn spawn(name: &str, f: impl FnOnce() + Send + 'static) -> Result<(), CoreError> {
    std::thread::Builder::new()
        .name(name.into())
        .spawn(f)
        .map(|_| ())
        .map_err(CoreError::from_io)
}

// ---- notify ----

/// What `ct-notify` receives, in order.
enum Note {
    Live(Box<LiveDelta>),
    Event(CoreEvent),
    Listener(Option<Arc<dyn CoreListener>>),
}

/// `ct-notify` (§1.3): owns the listener and calls it off the ingest path; while it is busy, queued deltas
/// coalesce (§2.6).
fn spawn_notify() -> Result<mpsc::Sender<Note>, CoreError> {
    let (tx, rx) = mpsc::channel::<Note>();
    spawn("ct-notify", move || {
        let mut listener: Option<Arc<dyn CoreListener>> = None;
        while let Ok(first) = rx.recv() {
            let mut pending: Option<LiveDelta> = None;
            let mut out: Vec<Note> = Vec::new();
            for note in std::iter::once(first).chain(rx.try_iter()) {
                match note {
                    Note::Live(d) => {
                        pending = Some(match pending.take() {
                            None => *d,
                            Some(prev) => coalesce(prev, *d),
                        })
                    }
                    other => {
                        if let Some(d) = pending.take() {
                            out.push(Note::Live(Box::new(d)));
                        }
                        out.push(other);
                    }
                }
            }
            if let Some(d) = pending {
                out.push(Note::Live(Box::new(d)));
            }
            for note in out {
                match note {
                    Note::Listener(l) => listener = l,
                    Note::Live(d) => {
                        if let Some(l) = &listener {
                            l.on_live(*d);
                        }
                    }
                    Note::Event(e) => {
                        if let Some(l) = &listener {
                            l.on_event(e);
                        }
                    }
                }
            }
        }
    })?;
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
        discovery: match (a.discovery, b.discovery) {
            (Some(mut a), Some(b)) => {
                // later totals replace earlier ones of the same bin; the runs are the latest
                for bin in b.bins {
                    match a.bins.iter_mut().find(|x| x.start_ms == bin.start_ms) {
                        Some(x) => *x = bin,
                        None => a.bins.push(bin),
                    }
                }
                Some(DiscoveryUpdate {
                    bins: a.bins,
                    runs: b.runs,
                })
            }
            (a, b) => b.or(a),
        },
    }
}

// ---- reader ----

/// What the reader knows of the writer: immutable, replaced by every writer step that changes it.
#[derive(Clone, Debug)]
struct Snapshot {
    hide_unnamed_apple: bool,
    stale_after_ms: i64,
    live: Option<LiveSnapshot>,
}

#[derive(Clone, Debug)]
struct LiveSnapshot {
    session: i64,
    uuid: String,
    seq: u64,
    current: Arc<std::collections::HashMap<i64, i16>>,
    rings: Arc<super::rssi::Rings>,
}

impl Snapshot {
    fn ctx<'a>(&self, catalog: &'a Catalog, now: i64) -> Ctx<'a> {
        Ctx {
            catalog,
            hide_unnamed_apple: self.hide_unnamed_apple,
            live: self.live.as_ref().map(|l| query::Live {
                session: l.session,
                uuid: l.uuid.clone(),
                stale_before_ms: now - self.stale_after_ms,
                seq: l.seq,
                current: l.current.clone(),
                rings: l.rings.clone(),
                stale_after_ms: self.stale_after_ms,
            }),
        }
    }
}

type ReadJob = Box<dyn FnOnce(&Connection, &Snapshot) + Send>;

enum ReaderMsg {
    Snapshot(Snapshot),
    /// A query and its own cancel flag (set when its future is dropped).
    Job(ReadJob, Arc<AtomicBool>),
}

/// SQLite VM steps between two looks at a query's cancel flag.
const CANCEL_CHECK_OPS: i32 = 1_000;

fn spawn_reader(
    mut conn: Connection,
    first: Snapshot,
) -> Result<mpsc::Sender<ReaderMsg>, CoreError> {
    let (tx, rx) = mpsc::channel::<ReaderMsg>();
    spawn("ct-read", move || {
        let mut snapshot = first;
        while let Ok(msg) = rx.recv() {
            match msg {
                ReaderMsg::Snapshot(s) => snapshot = s,
                ReaderMsg::Job(job, cancel) => {
                    // the query stops at its next check once its future is dropped (§2 cancellation)
                    let flag = cancel.clone();
                    let _ = conn.progress_handler(
                        CANCEL_CHECK_OPS,
                        Some(move || flag.load(Ordering::SeqCst)),
                    );
                    job(&conn, &snapshot);
                    let _ = conn.progress_handler(0, None::<fn() -> bool>);
                }
            }
        }
    })?;
    Ok(tx)
}

/// The reply of one query. Dropping it before it resolves cancels the query (§2 cancellation): a query that has not
/// started is skipped, and one in flight stops at its next progress check (it ends `Cancelled`). The flag is the
/// query's own, so no other query is ever hit, and a cancellation can't fall between a look and a start.
pub(crate) struct ReadFuture<T> {
    rx: oneshot::Receiver<Result<T, CoreError>>,
    cancel: Arc<AtomicBool>,
    done: bool,
}

impl<T> Future for ReadFuture<T> {
    type Output = Result<T, CoreError>;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        match Pin::new(&mut self.rx).poll(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(result) => {
                self.done = true;
                Poll::Ready(result.unwrap_or(Err(CoreError::Closed)))
            }
        }
    }
}

impl<T> Drop for ReadFuture<T> {
    fn drop(&mut self) {
        if !self.done {
            self.cancel.store(true, Ordering::SeqCst);
        }
    }
}

// ---- writer ----

/// What the writer receives.
enum WriterMsg {
    /// An event for the machine, with the caller's reply channel when it waits.
    Event(Event, Option<mpsc::SyncSender<Reply>>),
    /// The listener for `ct-notify`, then [`Event::SetListener`].
    Listener(Option<Arc<dyn CoreListener>>, mpsc::SyncSender<Reply>),
    /// A runner-side read or write outside the machine (state, GATT, tests).
    Job(Box<dyn FnOnce(&mut Runner) + Send>),
}

/// The batch of the last commit attempt: kept when it failed, until the machine restores it.
#[derive(Default)]
struct Batch {
    obs: Vec<Obs>,
    route: Vec<RoutePoint>,
}

/// The writer's runner: owns the bulk data and the write connection, and executes the machine's effects.
struct Runner {
    control: Control,
    store: Store,
    catalog: Arc<Catalog>,
    data_dir: PathBuf,
    stale_after_ms: i64,
    pending: Vec<Obs>,
    route: Vec<RoutePoint>,
    last_route: Option<RoutePoint>,
    failed: Option<Batch>,
    /// The route points of the last successful commit, for its delta.
    committed_route: Vec<RoutePoint>,
    fixes: RecentLocationFixes,
    live: LiveState,
    listener: Option<Arc<dyn CoreListener>>,
    reader: mpsc::Sender<ReaderMsg>,
    notify: mpsc::Sender<Note>,
    /// The v3 payload core, owned here alone: the staging WAL, Parquet v3 rotation and sealing, the pending and
    /// archived files (v2, v3 and gatt/v1, including those a v3 app left), and the upload queue. Payload v3 and
    /// gatt/v1 bytes are unchanged (§1.5, §1.6).
    payloads: Arc<crate::CluetoothCore>,
    /// The last upload states read, kept when a later read fails (a missing session means local-only).
    upload_cache: Option<std::collections::HashMap<String, UploadState>>,
    /// Test hook: staging fails with a payload I/O error of this detail.
    #[cfg(test)]
    fail_staging: Option<String>,
}

impl Runner {
    /// One event through the machine, then its effects, then the events they produce, until quiet.
    fn handle(&mut self, event: Event) -> Option<Reply> {
        let mut queue = VecDeque::from([event]);
        let mut reply = None;
        while let Some(e) = queue.pop_front() {
            let (next, effects) = machine::step(&self.control, e);
            self.control = next;
            for effect in effects {
                if let Some(r) = self.run(effect, &mut queue) {
                    reply = Some(r);
                }
            }
        }
        reply
    }

    fn run(&mut self, effect: machine::Effect, queue: &mut VecDeque<Event>) -> Option<Reply> {
        use machine::Effect as E;
        match effect {
            E::CreateSession { env, started } => {
                let result = self.create_session(env, started);
                queue.push_back(Event::SessionCreated { result });
            }
            E::ConfigureScan { env } => {
                let update = self.payloads.configure_scan(env);
                self.payload_update(update);
            }
            E::SealPayload => {
                let sealed = self.payloads.flush_payload().map(|r| r.sealed.is_some());
                self.payload_sealed(sealed);
            }
            E::ResetLive => {
                self.live = LiveState::default();
                self.pending.clear();
                self.route.clear();
                self.last_route = None;
                self.failed = None;
                self.committed_route.clear();
            }
            E::Stage {
                rows,
                rejected,
                now,
            } => {
                // §2.4: the payload records every row the DB will commit, so DB rows ⊆ staged rows
                let result = self.stage(&rows).map_err(|e| self.measured(e));
                queue.push_back(Event::Staged {
                    rows,
                    rejected,
                    now,
                    result,
                });
            }
            E::Append(rows) => {
                for machine::Accepted { key, input } in rows {
                    let fix = self
                        .fixes
                        .most_recent_preceding(input.elapsed_realtime_nanos)
                        .map(|f| (f.lat, f.lon, f.accuracy_meters));
                    self.live.hear(key, input.rssi, input.scanned_at_ms);
                    self.pending.push(Obs {
                        addr: key,
                        at_ms: input.scanned_at_ms,
                        rssi: input.rssi,
                        raw: input.raw,
                        tx_power: input.tx_power,
                        connectable: input.is_connectable,
                        addr_type: input.addr_type,
                        is_legacy: input.is_legacy,
                        primary_phy: input.primary_phy,
                        secondary_phy: input.secondary_phy,
                        adv_sid: input.adv_sid,
                        data_status: input.data_status,
                        adv_tx_power: input.adv_tx_power,
                        periodic_interval: input.periodic_interval,
                        fix,
                    });
                }
            }
            E::AddFix { fix, route } => {
                self.v3_effects(self.payloads.update_location(fix.clone()));
                self.live.fix = Some((fix.lat, fix.lon, fix.accuracy_meters, fix.observed_at_ms));
                self.fixes.push(fix.clone());
                if route && fix.accuracy_meters <= ROUTE_MAX_ACCURACY_M {
                    let keep = match self.last_route {
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
                        self.route.push(p);
                        self.last_route = Some(p);
                    }
                }
            }
            E::ClearFixes => {
                self.v3_effects(self.payloads.clear_location());
                self.fixes.clear();
            }
            E::Commit { row, now } => {
                let batch = Batch {
                    obs: std::mem::take(&mut self.pending),
                    route: std::mem::take(&mut self.route),
                };
                // §2.4 commit gating: append the staging WAL first; the DB commits only what it covered
                let staged = self
                    .payloads
                    .checkpoint_active_payload()
                    .map_err(payload_error);
                let committed = staged.and_then(|staged| {
                    if staged.sealed.is_some() {
                        self.emit(CoreEvent::UploadNeeded);
                    }
                    self.store
                        .commit(row.0, &batch.obs, &batch.route, &self.catalog)
                });
                let result = committed.map_err(|e| self.measured(e));
                match &result {
                    Ok(()) => self.committed_route = batch.route,
                    Err(_) => self.failed = Some(batch),
                }
                queue.push_back(Event::Committed { result, now });
            }
            E::RestoreBatch => {
                if let Some(batch) = self.failed.take() {
                    let later = std::mem::replace(&mut self.pending, batch.obs);
                    self.pending.extend(later);
                    let later = std::mem::replace(&mut self.route, batch.route);
                    self.route.extend(later);
                }
            }
            E::DropPending => {
                self.pending.clear();
                self.route.clear();
                self.failed = None;
            }
            E::StorePause { row, at } => {
                let result = self.store.pause(row.0, at.0);
                self.warn(result);
            }
            E::StoreResume { row, at } => {
                let result = self.store.resume(row.0, at.0);
                self.warn(result);
            }
            E::Finish {
                row,
                ended,
                active,
                was_paused,
            } => {
                let result = match was_paused {
                    true => self.store.resume(row.0, ended.0),
                    false => Ok(()),
                }
                .and_then(|()| self.store.finish(row.0, 2, ended.0, active.0));
                self.warn(result);
                // seals the session's payload into pending/ (§1.4)
                let update = self.payloads.finish_scan_session();
                self.payload_update(update);
            }
            E::Delta { now, force } => self.delta(now.0, force),
            E::Snapshot => {
                let _ = self.reader.send(ReaderMsg::Snapshot(self.snapshot()));
            }
            E::SetListener => {
                let _ = self.notify.send(Note::Listener(self.listener.take()));
            }
            E::EmitRecordingChanged => {
                let state = self.state();
                self.emit(CoreEvent::RecordingChanged { state });
            }
            E::EmitSessionsChanged => self.emit(CoreEvent::SessionsChanged),
            E::EmitDataInvalidated(reason) => self.emit(CoreEvent::DataInvalidated {
                reason: reason.into(),
            }),
            E::EmitStorageWarning(message) => self.emit(CoreEvent::StorageWarning { message }),
            E::EmitStorageFailed {
                message,
                dropped_scans,
            } => self.emit(CoreEvent::StorageFailed {
                message,
                dropped_scans,
            }),
            E::EmitStorageFull {
                free_bytes,
                dropped_scans,
            } => self.emit(CoreEvent::StorageFull {
                free_bytes,
                dropped_scans,
            }),
            E::Reply(r) => return Some(r),
        }
        None
    }

    /// Records the rows in the Parquet payload; a refusal is typed (ENOSPC is `StorageFull`).
    fn stage(&self, rows: &[machine::Accepted]) -> Result<(), CoreError> {
        #[cfg(test)]
        if let Some(detail) = &self.fail_staging {
            return Err(payload_error(crate::CoreError::Io {
                detail: detail.clone(),
            }));
        }
        let inputs: Vec<ScanObservationInput> = rows.iter().map(|a| a.input.clone()).collect();
        let update = self
            .payloads
            .record_observations(inputs)
            .map_err(payload_error)?;
        self.v3_effects(update);
        Ok(())
    }

    /// R-10: a lack of space carries the free space measured now.
    fn measured(&self, e: CoreError) -> CoreError {
        match e {
            CoreError::StorageFull { .. } => CoreError::StorageFull {
                free_bytes: space::free_bytes(&self.data_dir).unwrap_or(0),
            },
            other => other,
        }
    }

    /// A failed bookkeeping write becomes a StorageWarning.
    fn warn(&self, result: Result<(), CoreError>) {
        if let Err(e) = result {
            self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            });
        }
    }

    fn db_path(&self) -> PathBuf {
        self.data_dir.join("local.db")
    }

    fn emit(&self, event: CoreEvent) {
        let _ = self.notify.send(Note::Event(event));
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            hide_unnamed_apple: self.control.hide_unnamed_apple,
            stale_after_ms: self.stale_after_ms,
            live: match (self.control.rec.session(), self.control.rec.row()) {
                (Some(s), Some(row)) => Some(LiveSnapshot {
                    session: row.0,
                    uuid: s.uuid.0.clone(),
                    seq: self.live.seq,
                    current: self.live.current.clone(),
                    rings: self.live.rings.clone(),
                }),
                _ => None,
            },
        }
    }

    fn state(&self) -> RecordingState {
        let rec = &self.control.rec;
        let (active_before, since) = rec.active();
        let (count, distance): (u32, f64) = match self.control.rec.row() {
            None => (0, 0.0),
            Some(row) => self
                .store
                .conn
                .query_row(
                    "SELECT CASE WHEN ?2 THEN device_count_shown ELSE device_count END, distance_m
                     FROM sessions WHERE id = ?1",
                    rusqlite::params![row.0, self.control.hide_unnamed_apple],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .unwrap_or((0, 0.0)),
        };
        let session = rec.session();
        RecordingState {
            phase: rec.phase(),
            session_id: session.map(|s| s.uuid.0.clone()),
            started_ms: session.map(|s| s.started.0),
            active_before_ms: active_before.0,
            running_since_ms: since.map(|s| s.0),
            device_count: if session.is_some() { count } else { 0 },
            distance_m: if session.is_some() { distance } else { 0.0 },
            paused_for_storage: rec.paused_for_storage(),
            storage_failure: rec.storage_failure().map(str::to_owned),
        }
    }

    /// Builds and queues the live delta after a commit (§2.6); nothing is built without a listener.
    fn delta(&mut self, now: i64, force: bool) {
        let route = std::mem::take(&mut self.committed_route);
        if !self.control.has_listener {
            return;
        }
        let state = self.state();
        let (Some(session), Some(SessionRow(row))) =
            (self.control.rec.session(), self.control.rec.row())
        else {
            return;
        };
        let input = live::DeltaInput {
            conn: &self.store.conn,
            catalog: &self.catalog,
            hide_unnamed_apple: self.control.hide_unnamed_apple,
            session: row,
            uuid: &session.uuid.0,
            now,
            stale_after_ms: self.stale_after_ms,
            state,
            route: &route,
            force,
        };
        match live::delta(&mut self.live, input) {
            Ok(Some(d)) => {
                let _ = self.notify.send(Note::Live(Box::new(d)));
            }
            Ok(None) => {}
            Err(e) => self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            }),
        }
    }

    /// §2.4 `gatt_target`: the device's latest sighting, the uncommitted ones first, then the DB.
    fn gatt_target(&self, key: DeviceKey) -> Result<Option<GattTarget>, CoreError> {
        let session_id = self.control.rec.session().map(|s| s.uuid.0.clone());
        let addr_type = |t: Option<i64>| match t {
            Some(0) => Some("public".to_owned()),
            Some(1) => Some("random".to_owned()),
            _ => None,
        };
        let conn = &self.store.conn;
        let named = || -> Result<Option<String>, CoreError> {
            Ok(conn
                .query_row("SELECT name FROM devices WHERE addr = ?1", [key], |r| {
                    r.get(0)
                })
                .optional()?
                .flatten())
        };
        let pending = self
            .pending
            .iter()
            .filter(|o| o.addr == key)
            .max_by_key(|o| o.at_ms);
        if let Some(o) = pending {
            return Ok(Some(GattTarget {
                addr: addr_of(key),
                addr_type: addr_type(o.addr_type.map(i64::from)),
                name: adv::payload_fields(&adv::split(&o.raw))
                    .local_name
                    .or(named()?),
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
        let opt = |v: i64| i32::try_from(v).ok().filter(|v| *v >= 0);
        Ok(Some(GattTarget {
            addr: addr_of(key),
            addr_type: addr_type(Some(at)),
            name: named()?,
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

    /// §2.4 `ingest_gatt`: the plaintext under `pending/gatt/v1`, then the rows.
    fn ingest_gatt(&mut self, json: &str) -> Result<GattView, CoreError> {
        // validated and kept as plaintext under pending/gatt/v1 by the payload core, which also queues its upload
        let update = self
            .payloads
            .save_gatt_discovery(json.to_owned())
            .map_err(payload_error)?;
        let (view, _) = gatt_view::view(&self.catalog, json)?;
        let tx = self.store.conn.transaction()?;
        gatt_view::store(&tx, json)?;
        tx.commit()?;
        self.v3_effects(update);
        Ok(view)
    }

    /// The payload session (its UUIDv7 is the v2 session id and the Parquet `scan_session_id`), then the row.
    fn create_session(
        &mut self,
        env: ScanEnvironment,
        started: EpochMs,
    ) -> Result<(SessionUuid, SessionRow), CoreError> {
        let update = self.payloads.configure_scan(env).map_err(payload_error)?;
        self.v3_effects(update);
        let uuid = self.payloads.start_scan_session().map_err(payload_error)?;
        match self.store.create_session(&uuid, started.0) {
            Ok(row) => Ok((SessionUuid(uuid), SessionRow(row))),
            Err(e) => {
                let update = self.payloads.finish_scan_session();
                self.payload_update(update);
                Err(e)
            }
        }
    }

    /// The payload core's effects: sealed payloads need an upload; its warnings are ours.
    fn v3_effects(&self, update: crate::CoreUpdate) {
        for effect in update.effects {
            match effect {
                crate::CoreEffect::ScheduleUpload => self.emit(CoreEvent::UploadNeeded),
                crate::CoreEffect::CancelUpload => {}
                crate::CoreEffect::StorageWarning { message }
                | crate::CoreEffect::PersistenceDegraded { message } => {
                    self.emit(CoreEvent::StorageWarning { message })
                }
            }
        }
    }

    fn payload_update(&self, update: Result<crate::CoreUpdate, crate::CoreError>) {
        match update {
            Ok(u) => self.v3_effects(u),
            Err(e) => self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            }),
        }
    }

    fn payload_sealed(&self, sealed: Result<bool, crate::CoreError>) {
        match sealed {
            Ok(true) => self.emit(CoreEvent::UploadNeeded),
            Ok(false) => {}
            Err(e) => self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            }),
        }
    }

    fn upload_status(&self) -> UploadStatus {
        let s = self.payloads.state();
        UploadStatus {
            pending_count: u32::try_from(s.pending_upload_count).unwrap_or(u32::MAX),
            pending_bytes: self
                .payloads
                .pending_uploads()
                .iter()
                .map(|u| u.size_bytes)
                .sum(),
            failed_count: u32::try_from(s.failed_upload_count).unwrap_or(u32::MAX),
            last_error: s.last_upload_error,
        }
    }

    /// Each payload session's upload state, for the session list (L-2), from the known files (none is read). When
    /// the read fails, the last states read stand (with a StorageWarning); before any read it is an error. A session
    /// missing from the map has no payload: local-only, recorded before the Parquet staging.
    fn upload_states(
        &mut self,
    ) -> Result<std::collections::HashMap<String, UploadState>, CoreError> {
        match self.payloads.session_upload_states() {
            Ok(states) => {
                let states: std::collections::HashMap<String, UploadState> = states
                    .into_iter()
                    .map(|(session, state)| {
                        let state = match state {
                            crate::SessionUploadState::Pending => UploadState::Pending,
                            crate::SessionUploadState::Uploaded => UploadState::Uploaded,
                            crate::SessionUploadState::Failed => UploadState::Failed,
                            crate::SessionUploadState::Empty => UploadState::Empty,
                        };
                        (session, state)
                    })
                    .collect();
                self.upload_cache = Some(states.clone());
                Ok(states)
            }
            Err(e) => match &self.upload_cache {
                Some(last) => {
                    self.emit(CoreEvent::StorageWarning {
                        message: format!("upload states kept from the last read: {e}"),
                    });
                    Ok(last.clone())
                }
                None => Err(payload_error(e)),
            },
        }
    }

    fn current_session(&self, now: i64) -> Result<Option<SessionSummary>, CoreError> {
        let Some(SessionRow(row)) = self.control.rec.row() else {
            return Ok(None);
        };
        let ctx = Ctx {
            catalog: &self.catalog,
            hide_unnamed_apple: self.control.hide_unnamed_apple,
            live: None,
        };
        let (before, since) = self.control.rec.active();
        Ok(query::session_rows(&self.store.conn)?
            .into_iter()
            .find(|s| s.id == row)
            .map(|mut s| {
                s.active_ms = before.0 + since.map_or(0, |since| now - since.0);
                query::summary(&ctx, &s, UploadState::Pending)
            }))
    }
}

fn spawn_writer(runner: Runner) -> Result<mpsc::Sender<WriterMsg>, CoreError> {
    let (tx, rx) = mpsc::channel::<WriterMsg>();
    let mut runner = runner;
    spawn("ct-writer", move || {
        while let Ok(msg) = rx.recv() {
            match msg {
                WriterMsg::Event(event, reply) => {
                    let r = runner.handle(event);
                    if let (Some(reply), Some(r)) = (reply, r) {
                        let _ = reply.send(r);
                    }
                }
                WriterMsg::Listener(listener, reply) => {
                    let present = listener.is_some();
                    runner.listener = listener;
                    if let Some(r) = runner.handle(Event::SetListener { present }) {
                        let _ = reply.send(r);
                    }
                }
                WriterMsg::Job(job) => job(&mut runner),
            }
        }
    })?;
    Ok(tx)
}

/// `ct-tick`: the commit tick every `durability_interval_ms` (the machine commits only while recording); it ends
/// when the core is dropped.
fn spawn_ticker(
    writer: mpsc::Sender<WriterMsg>,
    every: Duration,
    data_dir: PathBuf,
) -> Result<mpsc::Sender<()>, CoreError> {
    let (stop, stopped) = mpsc::channel::<()>();
    spawn("ct-tick", move || {
        while let Err(mpsc::RecvTimeoutError::Timeout) = stopped.recv_timeout(every) {
            let tick = Event::Tick {
                now: EpochMs(now_ms()),
                free: space::free_bytes(&data_dir),
            };
            if writer.send(WriterMsg::Event(tick, None)).is_err() {
                break;
            }
        }
    })?;
    Ok(stop)
}

// ---- the object ----

#[derive(uniffi::Object)]
pub struct Core {
    catalog: Arc<Catalog>,
    data_dir: PathBuf,
    writer: mpsc::Sender<WriterMsg>,
    reader: mpsc::Sender<ReaderMsg>,
    /// Dropping it stops `ct-tick`.
    _ticker: Option<mpsc::Sender<()>>,
}

impl Core {
    #[cfg(test)]
    /// Opens with an explicit `now` and no ticker (tests drive the ticks); [`Core::open`] passes the clock and
    /// starts `ct-tick`.
    pub(crate) fn open_at(
        config: CoreConfig,
        assets: CoreAssets,
        now: i64,
    ) -> Result<Arc<Core>, CoreError> {
        Self::open_with(config, assets, now, false)
    }

    fn open_with(
        config: CoreConfig,
        assets: CoreAssets,
        now: i64,
        ticker: bool,
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
        // The payload core first: it seals a staging WAL a killed recording left (v3 or v2 staging alike), marks
        // its session interrupted, and discovers every pending and archived file in the same directory (§1.5).
        let payloads = crate::CluetoothCore::open(
            config.data_directory.clone(),
            crate::CoreConfig {
                location_max_age_ms: config.location_max_age_ms,
                recent_location_capacity: crate::config::DEFAULT_RECENT_LOCATION_CAPACITY,
                payload_max_rows: config.payload_max_rows,
                payload_max_estimated_bytes: config.payload_max_estimated_bytes,
                payload_max_age_ms: config.payload_max_age_ms,
                recipient_public_key: config.recipient_public_key.clone(),
            },
        )?;
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
        let read_conn = db::open_reader(&db_path)?;
        let stale_after_ms = i64::try_from(config.stale_after_ms).unwrap_or(i64::MAX);
        let control = Control::new(Millis(
            i64::try_from(config.durability_interval_ms).unwrap_or(i64::MAX),
        ));
        let reader = spawn_reader(
            read_conn,
            Snapshot {
                hide_unnamed_apple: control.hide_unnamed_apple,
                stale_after_ms,
                live: None,
            },
        )?;
        let notify = spawn_notify()?;
        let writer = spawn_writer(Runner {
            control,
            store: Store::new(conn),
            catalog: catalog.clone(),
            data_dir: dir.to_path_buf(),
            stale_after_ms,
            pending: Vec::new(),
            route: Vec::new(),
            last_route: None,
            failed: None,
            committed_route: Vec::new(),
            fixes: RecentLocationFixes::new(32, config.location_max_age_ms),
            live: LiveState::default(),
            listener: None,
            reader: reader.clone(),
            notify,
            payloads,
            upload_cache: None,
            #[cfg(test)]
            fail_staging: None,
        })?;
        let _ticker = if ticker {
            Some(spawn_ticker(
                writer.clone(),
                Duration::from_millis(config.durability_interval_ms.max(1)),
                dir.to_path_buf(),
            )?)
        } else {
            None
        };
        Ok(Arc::new(Core {
            catalog,
            data_dir: dir.to_path_buf(),
            writer,
            reader,
            _ticker,
        }))
    }

    /// Sends an event and waits for the machine's reply.
    fn ask(&self, event: Event) -> Result<Reply, CoreError> {
        let (tx, rx) = mpsc::sync_channel(1);
        self.writer
            .send(WriterMsg::Event(event, Some(tx)))
            .map_err(closed)?;
        rx.recv().map_err(closed)
    }

    fn tell(&self, event: Event) -> Result<(), CoreError> {
        self.writer
            .send(WriterMsg::Event(event, None))
            .map_err(closed)
    }

    fn ask_unit(&self, event: Event) -> Result<(), CoreError> {
        match self.ask(event)? {
            Reply::Failed(e) => Err(e),
            Reply::Done | Reply::Session(_) | Reply::Ack(_) => Ok(()),
        }
    }

    fn ask_session(&self, event: Event) -> Result<String, CoreError> {
        match self.ask(event)? {
            Reply::Session(s) => Ok(s.0),
            Reply::Failed(e) => Err(e),
            Reply::Done | Reply::Ack(_) => Err(CoreError::Db {
                detail: "the writer answered without a session id".into(),
            }),
        }
    }

    /// Runs `f` on the writer and waits for it.
    fn job<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Runner) -> T + Send + 'static,
    ) -> Result<T, CoreError> {
        let (tx, rx) = mpsc::sync_channel(1);
        self.writer
            .send(WriterMsg::Job(Box::new(move |r| {
                let _ = tx.send(f(r));
            })))
            .map_err(closed)?;
        rx.recv().map_err(closed)
    }

    /// Runs `f` on the writer without blocking the caller's thread.
    async fn job_async<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Runner) -> T + Send + 'static,
    ) -> Result<T, CoreError> {
        let (tx, rx) = oneshot::channel();
        self.writer
            .send(WriterMsg::Job(Box::new(move |r| {
                let _ = tx.send(f(r));
            })))
            .map_err(closed)?;
        rx.await.map_err(closed)
    }

    /// Queues a query on the reader; dropping the future cancels it.
    pub(crate) fn read<T: Send + 'static>(
        &self,
        f: impl FnOnce(&Connection, &Ctx<'_>) -> Result<T, CoreError> + Send + 'static,
    ) -> ReadFuture<T> {
        let (tx, rx) = oneshot::channel();
        let cancel = Arc::new(AtomicBool::new(false));
        let catalog = self.catalog.clone();
        let job: ReadJob = Box::new(move |conn, snapshot| {
            if tx.is_canceled() {
                return; // dropped before it started
            }
            let ctx = snapshot.ctx(&catalog, now_ms());
            let _ = tx.send(f(conn, &ctx));
        });
        let _ = self.reader.send(ReaderMsg::Job(job, cancel.clone()));
        ReadFuture {
            rx,
            cancel,
            done: false,
        }
    }

    // ---- crate API with an explicit clock (tests) ----

    pub(crate) fn start_recording_at(
        &self,
        env: ScanEnvironment,
        now: i64,
    ) -> Result<String, CoreError> {
        self.ask_session(Event::Start {
            env,
            now: EpochMs(now),
        })
    }

    /// `device_chart` with an explicit clock.
    pub(crate) async fn device_chart_at(
        &self,
        key: DeviceKey,
        now: i64,
    ) -> Result<Option<LiveChart>, CoreError> {
        self.read(move |c, ctx| query::device_chart(c, ctx, key, now))
            .await
    }

    pub(crate) fn pause_recording_at(&self, now: i64) -> Result<(), CoreError> {
        self.ask_unit(Event::Pause { now: EpochMs(now) })
    }

    #[cfg(test)]
    pub(crate) fn resume_recording_at(&self, now: i64) -> Result<(), CoreError> {
        self.resume_recording_free_at(now, None)
    }

    /// Resume with the free space measured by the caller (R-10).
    pub(crate) fn resume_recording_free_at(
        &self,
        now: i64,
        free: Option<u64>,
    ) -> Result<(), CoreError> {
        self.ask_unit(Event::Resume {
            now: EpochMs(now),
            free,
        })
    }

    pub(crate) fn stop_recording_at(&self, now: i64) -> Result<String, CoreError> {
        self.ask_session(Event::Stop { now: EpochMs(now) })
    }

    pub(crate) fn ingest_scans_at(
        &self,
        batch: Vec<ScanObservationInput>,
        now: i64,
    ) -> Result<IngestAck, CoreError> {
        match self.ask(Event::Scans {
            batch,
            now: EpochMs(now),
        })? {
            Reply::Ack(ack) => Ok(ack),
            Reply::Failed(e) => Err(e),
            Reply::Done | Reply::Session(_) => Err(CoreError::Db {
                detail: "the writer answered ingest without an ack".into(),
            }),
        }
    }

    #[cfg(test)]
    pub(crate) fn ingest_location_at(&self, fix: LocationFix) {
        let _ = self.tell(Event::Location { fix });
    }

    /// The commit tick with an explicit clock; waits until it was handled.
    #[cfg(test)]
    pub(crate) fn tick_at(&self, now: i64) {
        self.tick_free_at(now, None);
    }

    /// The commit tick with an explicit clock and free space; waits until it was handled.
    #[cfg(test)]
    pub(crate) fn tick_free_at(&self, now: i64, free: Option<u64>) {
        let _ = self.tell(Event::Tick {
            now: EpochMs(now),
            free,
        });
        let _ = self.job(|_| ());
    }

    /// Staging fails with a payload I/O error of `detail` until called with `None`.
    #[cfg(test)]
    pub(crate) fn fail_staging(&self, detail: Option<&str>) {
        let detail = detail.map(str::to_owned);
        let _ = self.job(move |r| r.fail_staging = detail);
    }

    /// Every commit fails with `error` until called with `None`.
    #[cfg(test)]
    pub(crate) fn fail_commits(&self, error: Option<CoreError>) {
        let _ = self.job(move |r| r.store.fail_commits = error);
    }

    #[cfg(test)]
    pub(crate) fn fail_next_commit(&self) {
        let _ = self.job(|r| r.store.fail_next_commit = true);
    }

    #[cfg(test)]
    pub(crate) fn with_writer_conn<T: Send + 'static>(
        &self,
        f: impl FnOnce(&Connection) -> T + Send + 'static,
    ) -> T {
        match self.job(move |r| f(&r.store.conn)) {
            Ok(v) => v,
            Err(e) => panic!("writer gone: {e}"),
        }
    }

    /// The writer's queue length after everything sent so far was handled (tests).
    #[cfg(test)]
    pub(crate) fn pending_len(&self) -> usize {
        self.job(|r| r.pending.len()).unwrap_or(usize::MAX)
    }
}

#[uniffi::export]
impl Core {
    /// [F] Blocking; Kotlin calls it on `Dispatchers.IO` (§1.2). Opens or migrates `local.db`, marks a session left
    /// open as Interrupted, and starts the actors.
    #[uniffi::constructor]
    pub fn open(config: CoreConfig, assets: CoreAssets) -> Result<Arc<Core>, CoreError> {
        Core::open_with(config, assets, now_ms(), true)
    }

    /// [W] Commit. Kotlin sees it as `closeCore`, because UniFFI objects already have `AutoCloseable.close()`
    /// (which frees the handle; the actors end with it).
    #[uniffi::method(name = "close_core")]
    pub fn close(&self) -> Result<(), CoreError> {
        self.ask_unit(Event::Close {
            now: EpochMs(now_ms()),
        })
    }

    /// [N] 8.
    pub fn api_version(&self) -> Result<u32, CoreError> {
        Ok(API_VERSION)
    }

    /// [W] The listener moves to `ct-notify`; deltas are built only while one is set.
    pub fn set_listener(&self, listener: Option<Arc<dyn CoreListener>>) -> Result<(), CoreError> {
        let (tx, rx) = mpsc::sync_channel(1);
        self.writer
            .send(WriterMsg::Listener(listener, tx))
            .map_err(closed)?;
        match rx.recv().map_err(closed)? {
            Reply::Failed(e) => Err(e),
            Reply::Done | Reply::Session(_) | Reply::Ack(_) => Ok(()),
        }
    }

    /// [W] Emits `CoreEvent::DataInvalidated`; queries sent after it returns see the new filter.
    pub fn set_display_filter(&self, filter: DisplayFilter) -> Result<(), CoreError> {
        self.ask_unit(Event::SetDisplay {
            hide_unnamed_apple: filter.hide_unnamed_apple,
        })
    }

    /// [N] The focused device's chart rides on the deltas (D-10).
    pub fn set_live_focus(&self, key: Option<DeviceKey>) -> Result<(), CoreError> {
        self.writer
            .send(WriterMsg::Job(Box::new(move |r| r.live.focus = key)))
            .map_err(closed)
    }

    // ---- §2.3 recording ----

    /// [W] Returns the new UUIDv7 session id.
    pub fn start_recording(&self, env: ScanEnvironment) -> Result<String, CoreError> {
        self.start_recording_at(env, now_ms())
    }

    /// [W] Commit; ingest is ignored while paused.
    pub fn pause_recording(&self) -> Result<(), CoreError> {
        self.pause_recording_at(now_ms())
    }

    /// [W] The same session. After a storage-full pause (R-10) it needs free space above the floor, else
    /// `StorageFull`.
    pub fn resume_recording(&self) -> Result<(), CoreError> {
        self.resume_recording_free_at(now_ms(), space::free_bytes(&self.data_dir))
    }

    /// [W] Commit and finish; returns the session id.
    pub fn stop_recording(&self) -> Result<String, CoreError> {
        self.stop_recording_at(now_ms())
    }

    /// [W] Footer change mid-session.
    pub fn configure_scan(&self, env: ScanEnvironment) -> Result<(), CoreError> {
        self.ask_unit(Event::Configure { env })
    }

    /// [W]
    pub fn recording_state(&self) -> Result<RecordingState, CoreError> {
        self.job(|r| r.state())
    }

    // ---- §2.4 ingest ----

    /// [W] Validates (v3 rules: ≤ 64 rows, ≤ 1 MiB, raw ≤ 1650 B) and buffers until the next commit. A failed
    /// interval commit keeps the batch pending (StorageWarning) and still acknowledges it.
    pub fn ingest_scans(&self, batch: Vec<ScanObservationInput>) -> Result<IngestAck, CoreError> {
        self.ingest_scans_at(batch, now_ms())
    }

    /// [N] Fix ring + route (≤ 100 m accuracy, ≥ 5 m or 30 s).
    pub fn ingest_location(&self, fix: LocationFix) -> Result<(), CoreError> {
        self.tell(Event::Location { fix })
    }

    /// [N]
    pub fn clear_location(&self) -> Result<(), CoreError> {
        self.tell(Event::ClearLocation)
    }

    /// [N]
    pub fn new_gatt_discovery_id(&self) -> Result<String, CoreError> {
        new_uuid(now_ms())
    }

    /// [P] The trigger advertisement from the device's latest sighting, the current recording's uncommitted ones
    /// included. `None` for a device the core has never seen.
    pub async fn gatt_target(&self, key: DeviceKey) -> Result<Option<GattTarget>, CoreError> {
        self.job_async(move |r| r.gatt_target(key)).await?
    }

    /// [W] Validates gatt/v1 (unchanged), writes `pending/gatt/v1` (plaintext), fills the gatt tables, and returns
    /// the view. Saving the same object again is idempotent.
    pub fn ingest_gatt(&self, gatt_v1_json: String) -> Result<GattView, CoreError> {
        self.job(move |r| r.ingest_gatt(&gatt_v1_json))?
    }

    // ---- §2.5 queries ----

    /// [P] Finished sessions, newest first, no empty ones.
    pub async fn sessions(&self) -> Result<Vec<SessionSummary>, CoreError> {
        let states = Arc::new(self.job_async(|r| r.upload_states()).await??);
        self.read(move |c, ctx| {
            query::sessions(c, ctx, &|id| {
                states.get(id).copied().unwrap_or(UploadState::Empty)
            })
        })
        .await
    }

    /// [W]
    pub fn current_session(&self) -> Result<Option<SessionSummary>, CoreError> {
        let now = now_ms();
        self.job(move |r| r.current_session(now))?
    }

    /// [P] SPEC R-4a: the session's discovery rate at the live `seq` (0 when not live), from `local.db`.
    pub async fn discovery_rate(&self, session_id: String) -> Result<DiscoveryRate, CoreError> {
        self.read(move |c, ctx| {
            let seq = ctx
                .live
                .as_ref()
                .filter(|l| l.uuid == session_id)
                .map_or(0, |l| l.seq);
            super::discovery::rate(c, &session_id, seq, ctx.hide_unnamed_apple)
        })
        .await
    }

    /// [P] Counters.
    pub async fn all_sessions_summary(&self) -> Result<AllSessionsSummary, CoreError> {
        self.read(query::all_sessions_summary).await
    }

    /// [P] `getRecording` minus the points.
    pub async fn session_view(&self, scope: Scope) -> Result<SessionView, CoreError> {
        self.read(move |c, ctx| query::session_view(c, ctx, &scope))
            .await
    }

    /// [V Routes] GeoJSON LineStrings for "route"; multi-session: the routes meeting the viewport.
    pub async fn routes(
        &self,
        scope: Scope,
        viewport: Option<Bbox>,
        zoom: f64,
    ) -> Result<String, CoreError> {
        let _ = zoom;
        self.read(move |c, ctx| query::routes(c, ctx, &scope, viewport))
            .await
    }

    /// [P] Every shown device, sorted ONCE by RSSI desc, ties by key (G-9).
    pub async fn device_order(&self, scope: Scope) -> Result<DeviceKeys, CoreError> {
        self.read(move |c, ctx| query::device_order(c, ctx, &scope))
            .await
    }

    /// [V AreaKeys] Keys passing the filter, in `device_order` order.
    pub async fn device_keys(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<DeviceKeys, CoreError> {
        self.read(move |c, ctx| query::device_keys(c, ctx, &scope, &filter))
            .await
    }

    /// [P] ≤ 200 keys per call, in the given order.
    pub async fn device_rows(
        &self,
        scope: Scope,
        keys: Vec<DeviceKey>,
    ) -> Result<Vec<DeviceRow>, CoreError> {
        self.read(move |c, ctx| query::device_rows(c, ctx, &scope, &keys))
            .await
    }

    /// [V AreaKeys] Faceted (D-3a): `filter.categories` ignored, every other constraint applied.
    pub async fn category_counts(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryCount>, CoreError> {
        self.read(move |c, ctx| query::category_counts(c, ctx, &scope, &filter))
            .await
    }

    /// [V AreaProducts] Groups by the page's first category, "unidentified" last (D-2); `filter.page` ignored.
    pub async fn products(
        &self,
        scope: Scope,
        filter: DeviceFilter,
    ) -> Result<Vec<CategoryGroup>, CoreError> {
        self.read(move |c, ctx| query::products(c, ctx, &scope, &filter))
            .await
    }

    /// [P] A `None` page is the Unidentified devices card.
    pub async fn product(
        &self,
        scope: Scope,
        page_id: Option<String>,
        filter: DeviceFilter,
    ) -> Result<Option<ProductDetail>, CoreError> {
        self.read(move |c, ctx| query::product(c, ctx, &scope, page_id.as_deref(), &filter))
            .await
    }

    /// [P]
    pub async fn device(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Option<DeviceDetail>, CoreError> {
        self.read(move |c, ctx| query::device(c, ctx, &scope, key))
            .await
    }

    /// [P] ≤ 10 000.
    pub async fn device_sightings(
        &self,
        scope: Scope,
        key: DeviceKey,
    ) -> Result<Vec<Sighting>, CoreError> {
        self.read(move |c, ctx| query::device_sightings(c, ctx, &scope, key))
            .await
    }

    /// [P] D-10, live only: the device's last 60 s at 1 s resolution, `None` when no session is live.
    pub async fn device_chart(&self, key: DeviceKey) -> Result<Option<LiveChart>, CoreError> {
        self.device_chart_at(key, now_ms()).await
    }

    /// [P] D-13.7.
    pub async fn device_gatt(&self, key: DeviceKey) -> Result<Option<GattView>, CoreError> {
        self.read(move |c, ctx| gatt_view::latest(c, ctx.catalog, key))
            .await
    }

    /// [V Search] Q-1/Q-2; `None` for a blank query; `device_order` order.
    pub async fn search(
        &self,
        scope: Scope,
        query: String,
        area: Option<Bbox>,
    ) -> Result<Option<DeviceKeys>, CoreError> {
        self.read(move |c, ctx| query::search(c, ctx, &scope, &query, area))
            .await
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
        self.read(move |c, ctx| query::map_points(c, ctx, &scope, &filter, viewport))
            .await
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

    /// [W] SPEC R-10: the free space now and what each storage-full action would free.
    pub async fn reclaimable(&self) -> Result<Reclaimable, CoreError> {
        self.job_async(|r| {
            let keep = r.control.rec.row().map(|row| row.0);
            Ok(Reclaimable {
                free_bytes: space::free_bytes(&r.data_dir).unwrap_or(0),
                uploaded_raw_bytes: space::uploaded_raw_bytes(&r.data_dir)?,
                local_database_bytes: r.store.clearable_bytes(keep, &r.db_path())?,
            })
        })
        .await?
    }

    /// [W] SPEC R-10 "Delete uploaded raw files": only files that already uploaded (`archive/`); returns the bytes
    /// freed. Files not yet uploaded are never touched.
    pub async fn delete_uploaded_raw(&self) -> Result<u64, CoreError> {
        self.job_async(|r| {
            // the payload core forgets the files it knew; anything else left under archive/ goes too
            let known = r.payloads.delete_archived().map_err(payload_error)?;
            Ok(known + space::delete_uploaded_raw(&r.data_dir)?)
        })
        .await?
    }

    /// [W] SPEC R-10 "Clear local database": the stored history of every session except the current one; raw files
    /// (uploaded or not) are kept. Returns the bytes freed. Call it after `delete_uploaded_raw`, which frees the
    /// room the vacuum needs.
    pub async fn clear_local_database(&self) -> Result<u64, CoreError> {
        self.job_async(|r| {
            let keep = r.control.rec.row().map(|row| row.0);
            let db_path = r.db_path();
            let freed = r.store.clear_except(keep, &db_path)?;
            r.emit(CoreEvent::SessionsChanged);
            r.emit(CoreEvent::DataInvalidated {
                reason: "local database cleared".into(),
            });
            Ok(freed)
        })
        .await?
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
    pub fn upload_status(&self) -> Result<UploadStatus, CoreError> {
        self.job(|r| r.upload_status())
    }

    /// [W] Unchanged from v3: every pending Parquet payload (v3, and v2 or pre-v2 files a v3 app left) and gatt/v1
    /// object, oldest first (§1.5, §6.8.4).
    pub fn pending_uploads(&self) -> Result<Vec<PendingUpload>, CoreError> {
        self.job(|r| r.payloads.pending_uploads())
    }

    /// [F] Unchanged from v3: encrypts to `upload-cache/`.
    pub fn prepare_upload(&self, id: String) -> Result<PreparedUpload, CoreError> {
        self.job(move |r| r.payloads.prepare_upload(id))?
            .map_err(payload_error)
    }

    /// [F] Plaintext → archive; the ciphertext is deleted.
    pub fn mark_upload_succeeded(&self, id: String) -> Result<(), CoreError> {
        self.job(move |r| {
            let update = r
                .payloads
                .mark_upload_succeeded(id)
                .map_err(payload_error)?;
            r.v3_effects(update);
            r.emit(CoreEvent::SessionsChanged);
            Ok(())
        })?
    }

    /// [F]
    pub fn mark_upload_failed(&self, id: String, message: String) -> Result<(), CoreError> {
        self.job(move |r| {
            let update = r
                .payloads
                .mark_upload_failed(id, message)
                .map_err(payload_error)?;
            r.v3_effects(update);
            Ok(())
        })?
    }
}

/// [N] Q-1, pure; the same function is registered as SQL `match_device(q, name, addr)`.
#[uniffi::export]
pub fn match_device(query: String, name: Option<String>, addr: String) -> Result<bool, CoreError> {
    Ok(super::search::matches(&query, name.as_deref(), &addr))
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
            paused_for_storage: false,
            storage_failure: None,
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
            discovery: None,
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
