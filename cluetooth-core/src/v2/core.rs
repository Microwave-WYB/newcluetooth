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
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, mpsc};
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_channel::oneshot;
use rusqlite::{Connection, InterruptHandle, OptionalExtension};

use crate::location::RecentLocationFixes;
use crate::payload_identity::generate_uuid_v7;
use crate::{LocationFix, PendingUpload, PreparedUpload, ScanEnvironment, ScanObservationInput};

use super::catalog::Catalog;
use super::db;
use super::error::CoreError;
use super::keys::addr_of;
use super::live::{self, LiveState};
use super::machine::{self, Control, EpochMs, Event, Millis, Reply, SessionUuid};
use super::query::{self, Ctx};
use super::store::{Obs, RoutePoint, Store, haversine_m};
use super::types::*;
use super::{adv, gatt_view, store};

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
            }),
        }
    }
}

type ReadJob = Box<dyn FnOnce(&Connection, &Snapshot) + Send>;

enum ReaderMsg {
    Snapshot(Snapshot),
    Job(ReadJob),
}

fn spawn_reader(conn: Connection, first: Snapshot) -> Result<mpsc::Sender<ReaderMsg>, CoreError> {
    let (tx, rx) = mpsc::channel::<ReaderMsg>();
    spawn("ct-read", move || {
        let mut snapshot = first;
        while let Ok(msg) = rx.recv() {
            match msg {
                ReaderMsg::Snapshot(s) => snapshot = s,
                ReaderMsg::Job(job) => job(&conn, &snapshot),
            }
        }
    })?;
    Ok(tx)
}

/// The reply of one query. Dropping it before it resolves cancels the query: a query that has not started is
/// skipped, and one in flight is interrupted (`sqlite3_interrupt`, §2 cancellation).
pub(crate) struct ReadFuture<T> {
    rx: oneshot::Receiver<Result<T, CoreError>>,
    id: u64,
    running: Arc<AtomicU64>,
    interrupt: Arc<InterruptHandle>,
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
        if !self.done && self.running.load(Ordering::SeqCst) == self.id {
            self.interrupt.interrupt();
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
    session_row: Option<i64>,
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
            E::CreateSession { uuid, started } => {
                let result = self.store.create_session(&uuid.0, started.0).map(|row| {
                    self.session_row = Some(row);
                });
                queue.push_back(Event::SessionCreated { result });
            }
            E::ResetLive => {
                self.live = LiveState::default();
                self.pending.clear();
                self.route.clear();
                self.last_route = None;
                self.failed = None;
                self.committed_route.clear();
            }
            E::Append(rows) => {
                for machine::Accepted { key, input } in rows {
                    let fix = self
                        .fixes
                        .most_recent_preceding(input.elapsed_realtime_nanos)
                        .map(|f| (f.lat, f.lon, f.accuracy_meters));
                    self.live.hear(key, input.rssi);
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
            E::ClearFixes => self.fixes.clear(),
            E::Commit { now } => {
                let batch = Batch {
                    obs: std::mem::take(&mut self.pending),
                    route: std::mem::take(&mut self.route),
                };
                let result = match self.session_row {
                    Some(row) => self
                        .store
                        .commit(row, &batch.obs, &batch.route, &self.catalog),
                    None => Err(CoreError::NotRecording),
                };
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
            E::StorePause { at } => self.warn_on(|r, row| r.store.pause(row, at.0)),
            E::StoreResume { at } => self.warn_on(|r, row| r.store.resume(row, at.0)),
            E::Finish {
                ended,
                active,
                was_paused,
            } => {
                self.warn_on(|r, row| {
                    if was_paused {
                        r.store.resume(row, ended.0)?;
                    }
                    r.store.finish(row, 2, ended.0, active.0)
                });
                self.session_row = None;
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
            E::Reply(r) => return Some(r),
        }
        None
    }

    /// Runs a store write for the current session; a failure becomes a StorageWarning.
    fn warn_on(&mut self, f: impl FnOnce(&mut Runner, i64) -> Result<(), CoreError>) {
        if let Some(row) = self.session_row
            && let Err(e) = f(self, row)
        {
            self.emit(CoreEvent::StorageWarning {
                message: e.to_string(),
            });
        }
    }

    fn emit(&self, event: CoreEvent) {
        let _ = self.notify.send(Note::Event(event));
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            hide_unnamed_apple: self.control.hide_unnamed_apple,
            stale_after_ms: self.stale_after_ms,
            live: match (self.control.rec.session(), self.session_row) {
                (Some(s), Some(row)) => Some(LiveSnapshot {
                    session: row,
                    uuid: s.uuid.0.clone(),
                    seq: self.live.seq,
                    current: self.live.current.clone(),
                }),
                _ => None,
            },
        }
    }

    fn state(&self) -> RecordingState {
        let rec = &self.control.rec;
        let (active_before, since) = rec.active();
        let (count, distance): (u32, f64) = match self.session_row {
            None => (0, 0.0),
            Some(row) => self
                .store
                .conn
                .query_row(
                    "SELECT CASE WHEN ?2 THEN device_count_shown ELSE device_count END, distance_m
                     FROM sessions WHERE id = ?1",
                    rusqlite::params![row, self.control.hide_unnamed_apple],
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
        }
    }

    /// Builds and queues the live delta after a commit (§2.6); nothing is built without a listener.
    fn delta(&mut self, now: i64, force: bool) {
        let route = std::mem::take(&mut self.committed_route);
        if !self.control.has_listener {
            return;
        }
        let state = self.state();
        let (Some(session), Some(row)) = (self.control.rec.session(), self.session_row) else {
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
        let file = crate::gatt::save(&self.data_dir, json.as_bytes())?;
        let (view, _) = gatt_view::view(&self.catalog, json)?;
        let tx = self.store.conn.transaction()?;
        gatt_view::store(&tx, json)?;
        tx.commit()?;
        if !file.archived {
            self.emit(CoreEvent::UploadNeeded);
        }
        Ok(view)
    }

    fn current_session(&self, now: i64) -> Result<Option<SessionSummary>, CoreError> {
        let Some(row) = self.session_row else {
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
) -> Result<mpsc::Sender<()>, CoreError> {
    let (stop, stopped) = mpsc::channel::<()>();
    spawn("ct-tick", move || {
        while let Err(mpsc::RecvTimeoutError::Timeout) = stopped.recv_timeout(every) {
            let tick = Event::Tick {
                now: EpochMs(now_ms()),
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
    writer: mpsc::Sender<WriterMsg>,
    reader: mpsc::Sender<ReaderMsg>,
    interrupt: Arc<InterruptHandle>,
    /// The id of the query the reader runs now (0 = none).
    running: Arc<AtomicU64>,
    next_query: AtomicU64,
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
        let interrupt = Arc::new(read_conn.get_interrupt_handle());
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
            session_row: None,
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
        })?;
        let _ticker = if ticker {
            Some(spawn_ticker(
                writer.clone(),
                Duration::from_millis(config.durability_interval_ms.max(1)),
            )?)
        } else {
            None
        };
        Ok(Arc::new(Core {
            catalog,
            writer,
            reader,
            interrupt,
            running: Arc::new(AtomicU64::new(0)),
            next_query: AtomicU64::new(1),
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

    /// Queues a query on the reader. `f` may run twice: an interrupt meant for a cancelled query that just ended
    /// can reach the next one, which then runs again.
    pub(crate) fn read<T: Send + 'static>(
        &self,
        f: impl Fn(&Connection, &Ctx<'_>) -> Result<T, CoreError> + Send + 'static,
    ) -> ReadFuture<T> {
        let (tx, rx) = oneshot::channel();
        let id = self.next_query.fetch_add(1, Ordering::SeqCst);
        let running = self.running.clone();
        let catalog = self.catalog.clone();
        let job: ReadJob = Box::new(move |conn, snapshot| {
            if tx.is_canceled() {
                return; // dropped before it started
            }
            running.store(id, Ordering::SeqCst);
            let ctx = snapshot.ctx(&catalog, now_ms());
            let mut result = f(conn, &ctx);
            if matches!(result, Err(CoreError::Cancelled)) && !tx.is_canceled() {
                result = f(conn, &ctx);
            }
            running.store(0, Ordering::SeqCst);
            let _ = tx.send(result);
        });
        let _ = self.reader.send(ReaderMsg::Job(job));
        ReadFuture {
            rx,
            id,
            running: self.running.clone(),
            interrupt: self.interrupt.clone(),
            done: false,
        }
    }

    // ---- crate API with an explicit clock (tests) ----

    pub(crate) fn start_recording_at(
        &self,
        env: ScanEnvironment,
        now: i64,
    ) -> Result<String, CoreError> {
        let uuid = SessionUuid(new_uuid(now)?);
        self.ask_session(Event::Start {
            uuid,
            env,
            now: EpochMs(now),
        })
    }

    pub(crate) fn pause_recording_at(&self, now: i64) -> Result<(), CoreError> {
        self.ask_unit(Event::Pause { now: EpochMs(now) })
    }

    pub(crate) fn resume_recording_at(&self, now: i64) -> Result<(), CoreError> {
        self.ask_unit(Event::Resume { now: EpochMs(now) })
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
        let _ = self.tell(Event::Tick { now: EpochMs(now) });
        let _ = self.job(|_| ());
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

    /// [N] The chart rides on deltas (D-10).
    pub fn set_live_focus(&self, key: Option<DeviceKey>) -> Result<(), CoreError> {
        let _ = key;
        Ok(())
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
        self.read(|c, ctx| query::sessions(c, ctx, &|_| UploadState::Pending))
            .await
    }

    /// [W]
    pub fn current_session(&self) -> Result<Option<SessionSummary>, CoreError> {
        let now = now_ms();
        self.job(move |r| r.current_session(now))?
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

    /// [N] D-10, live only.
    pub fn device_chart(&self, key: DeviceKey) -> Result<Option<LiveChart>, CoreError> {
        let _ = key;
        Ok(None)
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
        Ok(UploadStatus::default())
    }

    /// [N] Unchanged from v3.
    pub fn pending_uploads(&self) -> Result<Vec<PendingUpload>, CoreError> {
        Ok(Vec::new())
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
