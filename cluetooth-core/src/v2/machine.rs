//! The writer's control logic (`docs/v2-api.md` §2.3, §2.4, §2.6): a pure `(state, event) -> (state, effects)`
//! step over immutable control state. It never touches SQLite, the buffers, the clock or the listener; every
//! side effect is a named [`Effect`] the writer actor executes, feeding results back as events
//! ([`Event::SessionCreated`], [`Event::Committed`]). Bulk data (pending observations, route, fix ring, caches,
//! connections) stays with the actor.

use crate::config::{
    INPUT_FIXED_ESTIMATE_BYTES, MAX_INPUT_BATCH_ESTIMATED_BYTES, MAX_INPUT_BATCH_ROWS,
};
use crate::payload::{MAX_RAW_BYTES, SchemaV3Row, validate_rows};
use crate::scan::sanitize_local_name;
use crate::{LocationFix, ScanEnvironment, ScanObservationInput};

use super::error::CoreError;
use super::keys::key_of;
use super::types::{IngestAck, RecordingPhase};

/// Milliseconds since the Unix epoch.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct EpochMs(pub i64);

/// A span of milliseconds.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct Millis(pub i64);

impl std::ops::Sub for EpochMs {
    type Output = Millis;
    fn sub(self, rhs: EpochMs) -> Millis {
        Millis(self.0 - rhs.0)
    }
}

impl std::ops::Add for Millis {
    type Output = Millis;
    fn add(self, rhs: Millis) -> Millis {
        Millis(self.0 + rhs.0)
    }
}

/// A session's `sessions.id`; only states after the row exists carry it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SessionRow(pub i64);

/// A session's UUIDv7 (`sessions.uuid`, the Parquet `scan_session_id`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SessionUuid(pub String);

/// What every recording state knows.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Session {
    pub uuid: SessionUuid,
    pub started: EpochMs,
    pub env: ScanEnvironment,
    pub last_commit: EpochMs,
}

/// Why a recording is paused.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum PauseReason {
    User,
    /// SPEC R-10: free space below [`STORAGE_FLOOR_BYTES`] or a write failed for lack of space.
    StorageFull {
        free_bytes: u64,
    },
    /// Staging or committing kept failing for [`GIVE_UP_AFTER`] for another reason; resume tries again.
    StorageFailed {
        message: String,
    },
}

/// How long staging or committing may keep failing before the writer stops writing and pauses.
pub(crate) const GIVE_UP_AFTER: Millis = Millis(60_000);

/// SPEC R-10: writing stops below this much free space on the data volume.
pub(crate) const STORAGE_FLOOR_BYTES: u64 = 200_000_000;

/// The recording lifecycle. `Pausing` and `Stopping` wait for the commit that must precede them; each state
/// carries exactly the data valid in it.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Rec {
    Idle,
    /// The payload session and the session row are being created; the id comes back with them.
    Starting {
        started: EpochMs,
        env: ScanEnvironment,
    },
    Recording {
        session: Session,
        row: SessionRow,
        active_before: Millis,
        running_since: EpochMs,
    },
    Pausing {
        session: Session,
        row: SessionRow,
        active_before: Millis,
        running_since: EpochMs,
        at: EpochMs,
    },
    Paused {
        session: Session,
        row: SessionRow,
        active_before: Millis,
        reason: PauseReason,
    },
    /// `running_since` is `None` when the stop came while paused.
    Stopping {
        session: Session,
        row: SessionRow,
        active_before: Millis,
        running_since: Option<EpochMs>,
        at: EpochMs,
    },
}

impl Rec {
    pub fn session(&self) -> Option<&Session> {
        match self {
            Rec::Idle | Rec::Starting { .. } => None,
            Rec::Recording { session: s, .. }
            | Rec::Pausing { session: s, .. }
            | Rec::Paused { session: s, .. }
            | Rec::Stopping { session: s, .. } => Some(s),
        }
    }

    /// The session row; `None` while idle or while it is being created.
    pub fn row(&self) -> Option<SessionRow> {
        match self {
            Rec::Idle | Rec::Starting { .. } => None,
            Rec::Recording { row, .. }
            | Rec::Pausing { row, .. }
            | Rec::Paused { row, .. }
            | Rec::Stopping { row, .. } => Some(*row),
        }
    }

    /// SPEC R-10: paused because storage is full (`RecordingState.paused_for_storage`).
    pub fn paused_for_storage(&self) -> bool {
        match self {
            Rec::Paused {
                reason: PauseReason::StorageFull { .. },
                ..
            } => true,
            Rec::Idle
            | Rec::Starting { .. }
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused {
                reason: PauseReason::User | PauseReason::StorageFailed { .. },
                ..
            }
            | Rec::Stopping { .. } => false,
        }
    }

    /// Why writing stopped when staging or committing kept failing (`RecordingState.storage_failure`).
    pub fn storage_failure(&self) -> Option<&str> {
        match self {
            Rec::Paused {
                reason: PauseReason::StorageFailed { message },
                ..
            } => Some(message),
            Rec::Idle
            | Rec::Starting { .. }
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused {
                reason: PauseReason::User | PauseReason::StorageFull { .. },
                ..
            }
            | Rec::Stopping { .. } => None,
        }
    }

    /// The phase shown to the UI (`RecordingState.phase`).
    pub fn phase(&self) -> RecordingPhase {
        match self {
            Rec::Idle => RecordingPhase::Idle,
            Rec::Starting { .. } | Rec::Recording { .. } | Rec::Pausing { .. } => {
                RecordingPhase::Recording
            }
            Rec::Paused { .. } => RecordingPhase::Paused,
            Rec::Stopping { running_since, .. } => match running_since {
                Some(_) => RecordingPhase::Recording,
                None => RecordingPhase::Paused,
            },
        }
    }

    /// (active before the current run, the current run's start).
    pub fn active(&self) -> (Millis, Option<EpochMs>) {
        match self {
            Rec::Idle => (Millis(0), None),
            Rec::Starting { started, .. } => (Millis(0), Some(*started)),
            Rec::Recording {
                active_before,
                running_since,
                ..
            }
            | Rec::Pausing {
                active_before,
                running_since,
                ..
            } => (*active_before, Some(*running_since)),
            Rec::Paused { active_before, .. } => (*active_before, None),
            Rec::Stopping {
                active_before,
                running_since,
                ..
            } => (*active_before, *running_since),
        }
    }

    fn with_last_commit(&self, at: EpochMs) -> Rec {
        let mut next = self.clone();
        match &mut next {
            Rec::Idle | Rec::Starting { .. } => {}
            Rec::Recording { session: s, .. }
            | Rec::Pausing { session: s, .. }
            | Rec::Paused { session: s, .. }
            | Rec::Stopping { session: s, .. } => s.last_commit = at,
        }
        next
    }
}

/// The size of the pending buffer: rows, and bytes by the v3 input estimate.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Buffer {
    pub rows: u64,
    pub bytes: u64,
}

impl Buffer {
    fn plus(self, rows: &[Accepted]) -> Buffer {
        Buffer {
            rows: self.rows + rows.len() as u64,
            bytes: self.bytes + rows.iter().map(|a| estimate(&a.input)).sum::<u64>(),
        }
    }

    fn exceeds(self, cap: Buffer) -> bool {
        self.rows > cap.rows || self.bytes > cap.bytes
    }
}

/// The pending buffer's cap. A batch that would pass it commits at once; while commits fail, ingest refuses it
/// with [`CoreError::Backpressure`], so memory stays below the cap plus one batch.
pub(crate) const PENDING_CAP: Buffer = Buffer {
    rows: 20_000,
    bytes: 16 * 1024 * 1024,
};

/// The longest wait between commit attempts while commits fail.
const MAX_BACKOFF: Millis = Millis(60_000);

/// Whether commits reach `local.db`.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Storage {
    Healthy,
    /// `attempts` commits failed in a row, the first at `since`; the next interval commit waits until `retry_at`.
    Failing {
        attempts: u32,
        since: EpochMs,
        retry_at: EpochMs,
    },
}

/// Whether the Parquet payload takes the rows (staging comes before the buffer, §2.4).
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Staging {
    Healthy,
    /// Every batch since `since` was refused.
    Failing {
        since: EpochMs,
    },
}

/// The writer's control state: immutable, replaced by every step.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Control {
    pub rec: Rec,
    pub hide_unnamed_apple: bool,
    pub has_listener: bool,
    pub durability: Millis,
    /// What the pending buffer holds now: appended rows, emptied by a successful commit.
    pub buffer: Buffer,
    pub cap: Buffer,
    pub storage: Storage,
    pub staging: Staging,
}

impl Control {
    pub fn new(durability: Millis) -> Self {
        Self {
            rec: Rec::Idle,
            hide_unnamed_apple: true,
            has_listener: false,
            durability,
            buffer: Buffer::default(),
            cap: PENDING_CAP,
            storage: Storage::Healthy,
            staging: Staging::Healthy,
        }
    }

    /// Whether an interval commit is due: `durability` after the last attempt, or after the backoff while failing.
    fn commit_due(&self, session: &Session, now: EpochMs) -> bool {
        match self.storage {
            Storage::Healthy => now - session.last_commit >= self.durability,
            Storage::Failing { retry_at, .. } => now >= retry_at,
        }
    }

    /// The wait after `attempts` failed commits: the interval, doubled per failure, at most [`MAX_BACKOFF`] (or
    /// the interval when that is longer).
    fn backoff(&self, attempts: u32) -> Millis {
        let doubled = self
            .durability
            .0
            .saturating_mul(1i64 << attempts.saturating_sub(1).min(16));
        Millis(doubled.min(MAX_BACKOFF.0.max(self.durability.0)))
    }
}

/// One validated scan row: its device key and the input.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Accepted {
    pub key: i64,
    pub input: ScanObservationInput,
}

/// The v3 input estimate of one row (`MAX_INPUT_BATCH_ESTIMATED_BYTES` counts the same way).
fn estimate(o: &ScanObservationInput) -> u64 {
    INPUT_FIXED_ESTIMATE_BYTES
        + o.raw.len() as u64
        + o.addr.len() as u64
        + o.local_name.as_ref().map_or(0, |n| n.len() as u64)
}

/// What the writer is asked to do, or the result of an effect it ran.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Event {
    Start {
        env: ScanEnvironment,
        now: EpochMs,
    },
    Pause {
        now: EpochMs,
    },
    /// `free` is the data volume's free space, `None` when it could not be read.
    Resume {
        now: EpochMs,
        free: Option<u64>,
    },
    Stop {
        now: EpochMs,
    },
    Configure {
        env: ScanEnvironment,
    },
    Scans {
        batch: Vec<ScanObservationInput>,
        now: EpochMs,
    },
    /// Result of [`Effect::Stage`]: the batch's rows are in the Parquet payload, or it refused them
    /// ([`CoreError::StorageFull`] when for lack of space).
    Staged {
        rows: Vec<Accepted>,
        rejected: u32,
        now: EpochMs,
        result: Result<(), CoreError>,
    },
    Location {
        fix: LocationFix,
    },
    ClearLocation,
    /// The 5 s timer: commits while recording whether or not scans arrive.
    Tick {
        now: EpochMs,
        free: Option<u64>,
    },
    SetDisplay {
        hide_unnamed_apple: bool,
    },
    SetListener {
        present: bool,
    },
    Close {
        now: EpochMs,
    },
    /// Result of [`Effect::CreateSession`].
    SessionCreated {
        result: Result<(SessionUuid, SessionRow), CoreError>,
    },
    /// Result of [`Effect::Commit`].
    Committed {
        result: Result<(), CoreError>,
        now: EpochMs,
    },
}

/// What a step answers the caller with.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Reply {
    Done,
    Session(SessionUuid),
    Ack(IngestAck),
    Failed(CoreError),
}

/// Every side effect the writer may run.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Effect {
    /// Insert the session row; answer with [`Event::SessionCreated`].
    CreateSession {
        env: ScanEnvironment,
        started: EpochMs,
    },
    /// The scanner settings of the payload footer (a change seals the payload under the old ones).
    ConfigureScan {
        env: ScanEnvironment,
    },
    /// Seal the active payload into `pending/` now (close).
    SealPayload,
    /// Forget the previous recording's live state.
    ResetLive,
    /// Record accepted rows in the Parquet payload; answer with [`Event::Staged`].
    Stage {
        rows: Vec<Accepted>,
        rejected: u32,
        now: EpochMs,
    },
    /// Append staged rows to the pending buffer (their fixes from the ring) and to the live state.
    Append(Vec<Accepted>),
    /// Put a fix in the ring, and in the route when `route` (§2.4 route rule applied by the buffer).
    AddFix {
        fix: LocationFix,
        route: bool,
    },
    ClearFixes,
    /// Commit the pending buffer in one transaction; answer with [`Event::Committed`].
    Commit {
        row: SessionRow,
        now: EpochMs,
    },
    /// Put the batch of the failed commit back in front of the pending buffer.
    RestoreBatch,
    /// Drop the pending buffer, its route points and a failed batch (R-10: they cannot be stored).
    DropPending,
    StorePause {
        row: SessionRow,
        at: EpochMs,
    },
    StoreResume {
        row: SessionRow,
        at: EpochMs,
    },
    /// Mark the session completed.
    Finish {
        row: SessionRow,
        ended: EpochMs,
        active: Millis,
        was_paused: bool,
    },
    /// Build and queue the live delta (§2.6); `force` carries Paused.
    Delta {
        now: EpochMs,
        force: bool,
    },
    /// Send the reader its new snapshot (display filter, live session).
    Snapshot,
    SetListener,
    EmitRecordingChanged,
    EmitSessionsChanged,
    EmitDataInvalidated(&'static str),
    EmitStorageWarning(String),
    EmitStorageFull {
        free_bytes: u64,
        dropped_scans: u64,
    },
    EmitStorageFailed {
        message: String,
        dropped_scans: u64,
    },
    Reply(Reply),
}

/// v3 validation of one ingest batch: the batch bounds fail it whole; a bad address or an oversized advertisement
/// rejects the row.
pub(crate) fn validate(
    batch: Vec<ScanObservationInput>,
) -> Result<(Vec<Accepted>, u32), CoreError> {
    if batch.len() > MAX_INPUT_BATCH_ROWS {
        return Err(CoreError::InputBatchTooLarge {
            detail: format!("{} rows > {MAX_INPUT_BATCH_ROWS}", batch.len()),
        });
    }
    let estimate: u64 = batch.iter().map(estimate).sum();
    if estimate > MAX_INPUT_BATCH_ESTIMATED_BYTES {
        return Err(CoreError::InputBatchTooLarge {
            detail: format!("~{estimate} bytes > {MAX_INPUT_BATCH_ESTIMATED_BYTES}"),
        });
    }
    let mut rejected = 0;
    let mut accepted = Vec::with_capacity(batch.len());
    for input in batch {
        match key_of(&input.addr) {
            Some(key) if input.raw.len() <= MAX_RAW_BYTES && payload_row_valid(&input) => {
                accepted.push(Accepted { key, input })
            }
            _ => rejected += 1,
        }
    }
    Ok((accepted, rejected))
}

/// The payload v3 row rules (`validate_rows`), so a row the local DB keeps is also a row the Parquet payload
/// keeps (§2.4 commit gating). The location is checked when the fix is taken, so it is left out here.
fn payload_row_valid(input: &ScanObservationInput) -> bool {
    let row = SchemaV3Row {
        addr: input.addr.clone(),
        rssi: input.rssi.map(i32::from),
        scanned_at_ms: input.scanned_at_ms,
        raw: input.raw.clone(),
        local_name: sanitize_local_name(input.local_name.clone()),
        tx_power: input.tx_power.map(i32::from),
        is_connectable: input.is_connectable,
        lat: None,
        lon: None,
        accuracy: None,
        is_legacy: input.is_legacy,
        primary_phy: input.primary_phy,
        secondary_phy: input.secondary_phy,
        adv_sid: input.adv_sid,
        adv_tx_power: input.adv_tx_power,
        periodic_interval: input.periodic_interval,
        data_status: input.data_status,
        addr_type: input.addr_type,
    };
    validate_rows(std::slice::from_ref(&row)).is_ok()
}

fn reply(e: CoreError) -> Effect {
    Effect::Reply(Reply::Failed(e))
}

/// The step. Pure: the same state and event always give the same state and effects.
pub(crate) fn step(c: &Control, event: Event) -> (Control, Vec<Effect>) {
    use Effect as E;
    let with = |rec: Rec| Control { rec, ..c.clone() };
    let same = |effects: Vec<Effect>| (c.clone(), effects);
    match event {
        Event::Start { env, now } => match &c.rec {
            Rec::Idle => (
                with(Rec::Starting {
                    started: now,
                    env: env.clone(),
                }),
                vec![E::CreateSession { env, started: now }],
            ),
            Rec::Starting { .. }
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused { .. }
            | Rec::Stopping { .. } => same(vec![reply(CoreError::AlreadyRecording)]),
        },
        Event::SessionCreated { result } => match &c.rec {
            Rec::Starting { started, env } => match result {
                Ok((uuid, row)) => (
                    Control {
                        rec: Rec::Recording {
                            session: Session {
                                uuid: uuid.clone(),
                                started: *started,
                                env: env.clone(),
                                last_commit: *started,
                            },
                            row,
                            active_before: Millis(0),
                            running_since: *started,
                        },
                        buffer: Buffer::default(),
                        ..c.clone()
                    },
                    vec![
                        E::ResetLive,
                        E::Snapshot,
                        E::EmitRecordingChanged,
                        E::Reply(Reply::Session(uuid)),
                    ],
                ),
                Err(e) => (with(Rec::Idle), vec![reply(e)]),
            },
            // a result nobody waits for
            Rec::Idle
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused { .. }
            | Rec::Stopping { .. } => same(vec![]),
        },
        Event::Scans { batch, now } => match validate(batch) {
            Err(e) => same(vec![reply(e)]),
            Ok((accepted, rejected)) => match &c.rec {
                Rec::Recording { session, row, .. } => {
                    let after = c.buffer.plus(&accepted);
                    let over = after.exceeds(c.cap);
                    match (&c.storage, over) {
                        // commits fail and the buffer is full: refuse rather than grow
                        (Storage::Failing { .. }, true) => {
                            same(vec![reply(CoreError::Backpressure)])
                        }
                        // the payload first: the reply waits for Staged
                        (Storage::Healthy, _) | (Storage::Failing { .. }, false) => {
                            let _ = (session, row);
                            same(vec![E::Stage {
                                rows: accepted,
                                rejected,
                                now,
                            }])
                        }
                    }
                }
                Rec::Paused {
                    reason: PauseReason::StorageFull { free_bytes },
                    ..
                } => same(vec![reply(CoreError::StorageFull {
                    free_bytes: *free_bytes,
                })]),
                Rec::Idle
                | Rec::Starting { .. }
                | Rec::Pausing { .. }
                | Rec::Paused {
                    reason: PauseReason::User | PauseReason::StorageFailed { .. },
                    ..
                }
                | Rec::Stopping { .. } => {
                    let ack = IngestAck {
                        accepted: 0,
                        rejected: 0,
                        ignored_not_recording: accepted.len() as u32 + rejected,
                    };
                    same(vec![E::Reply(Reply::Ack(ack))])
                }
            },
        },
        Event::Staged {
            rows,
            rejected,
            now,
            result,
        } => staged(c, rows, rejected, now, result),
        Event::Tick { now, free } => match &c.rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => match free {
                Some(free_bytes) if free_bytes < STORAGE_FLOOR_BYTES => paused_for(
                    c,
                    session.clone(),
                    *row,
                    *active_before + (now - *running_since),
                    now,
                    PauseReason::StorageFull { free_bytes },
                ),
                Some(_) | None => match c.commit_due(session, now) {
                    true => same(vec![E::Commit { row: *row, now }]),
                    false => same(vec![]),
                },
            },
            Rec::Idle
            | Rec::Starting { .. }
            | Rec::Pausing { .. }
            | Rec::Paused { .. }
            | Rec::Stopping { .. } => same(vec![]),
        },
        Event::Committed { result, now } => committed(c, c.rec.with_last_commit(now), result, now),
        Event::Pause { now } => match &c.rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => (
                with(Rec::Pausing {
                    session: session.clone(),
                    row: *row,
                    active_before: *active_before,
                    running_since: *running_since,
                    at: now,
                }),
                vec![E::Commit { row: *row, now }],
            ),
            Rec::Paused { .. } => same(vec![E::Reply(Reply::Done)]),
            Rec::Idle | Rec::Starting { .. } | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Resume { now, free } => match &c.rec {
            Rec::Paused {
                reason: PauseReason::StorageFull { .. },
                ..
            } if free.is_some_and(|f| f < STORAGE_FLOOR_BYTES) => {
                same(vec![reply(CoreError::StorageFull {
                    free_bytes: free.unwrap_or(0),
                })])
            }
            Rec::Paused {
                session,
                row,
                active_before,
                ..
            } => (
                Control {
                    rec: Rec::Recording {
                        session: session.clone(),
                        row: *row,
                        active_before: *active_before,
                        running_since: now,
                    },
                    storage: Storage::Healthy,
                    staging: Staging::Healthy,
                    ..c.clone()
                },
                vec![
                    E::StoreResume { row: *row, at: now },
                    E::EmitRecordingChanged,
                    E::Reply(Reply::Done),
                ],
            ),
            Rec::Recording { .. } => same(vec![E::Reply(Reply::Done)]),
            Rec::Idle | Rec::Starting { .. } | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Stop { now } => match &c.rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => (
                with(Rec::Stopping {
                    session: session.clone(),
                    row: *row,
                    active_before: *active_before,
                    running_since: Some(*running_since),
                    at: now,
                }),
                vec![E::Commit { row: *row, now }],
            ),
            Rec::Paused {
                session,
                row,
                active_before,
                ..
            } => (
                with(Rec::Stopping {
                    session: session.clone(),
                    row: *row,
                    active_before: *active_before,
                    running_since: None,
                    at: now,
                }),
                vec![E::Commit { row: *row, now }],
            ),
            Rec::Idle | Rec::Starting { .. } | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Configure { env } => match &c.rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => (
                with(Rec::Recording {
                    session: Session {
                        env: env.clone(),
                        ..session.clone()
                    },
                    row: *row,
                    active_before: *active_before,
                    running_since: *running_since,
                }),
                vec![E::ConfigureScan { env }, E::Reply(Reply::Done)],
            ),
            Rec::Paused {
                session,
                row,
                active_before,
                reason,
            } => (
                with(Rec::Paused {
                    session: Session {
                        env: env.clone(),
                        ..session.clone()
                    },
                    row: *row,
                    active_before: *active_before,
                    reason: reason.clone(),
                }),
                vec![E::ConfigureScan { env }, E::Reply(Reply::Done)],
            ),
            Rec::Idle | Rec::Starting { .. } | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Location { fix } => match fix.is_usable() {
            false => same(vec![]),
            true => {
                let route = match &c.rec {
                    Rec::Recording { .. } => true,
                    Rec::Idle
                    | Rec::Starting { .. }
                    | Rec::Pausing { .. }
                    | Rec::Paused { .. }
                    | Rec::Stopping { .. } => false,
                };
                same(vec![E::AddFix { fix, route }])
            }
        },
        Event::ClearLocation => same(vec![E::ClearFixes]),
        Event::SetDisplay { hide_unnamed_apple } => (
            Control {
                hide_unnamed_apple,
                ..c.clone()
            },
            vec![
                E::Snapshot,
                E::EmitDataInvalidated("display filter"),
                E::Reply(Reply::Done),
            ],
        ),
        Event::SetListener { present } => (
            Control {
                has_listener: present,
                ..c.clone()
            },
            vec![E::SetListener, E::Reply(Reply::Done)],
        ),
        Event::Close { now } => match &c.rec {
            Rec::Recording { row, .. } | Rec::Paused { row, .. } => same(vec![
                E::Commit { row: *row, now },
                E::SealPayload,
                E::Reply(Reply::Done),
            ]),
            Rec::Idle | Rec::Starting { .. } | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![E::Reply(Reply::Done)])
            }
        },
    }
}

/// Stop writing and pause (SPEC R-10, or [`GIVE_UP_AFTER`] of failures). The pending rows cannot be stored and are
/// dropped (and counted); the session stays open and everything committed stays.
fn paused_for(
    c: &Control,
    session: Session,
    row: SessionRow,
    active_before: Millis,
    at: EpochMs,
    reason: PauseReason,
) -> (Control, Vec<Effect>) {
    use Effect as E;
    let mut effects = vec![
        E::DropPending,
        E::StorePause { row, at },
        E::Delta {
            now: at,
            force: true,
        },
        E::EmitRecordingChanged,
    ];
    effects.extend(stopped_writing(c, &reason));
    (
        Control {
            rec: Rec::Paused {
                session,
                row,
                active_before,
                reason,
            },
            buffer: Buffer::default(),
            storage: Storage::Healthy,
            staging: Staging::Healthy,
            ..c.clone()
        },
        effects,
    )
}

/// The event that says why writing stopped.
fn stopped_writing(c: &Control, reason: &PauseReason) -> Vec<Effect> {
    let dropped_scans = c.buffer.rows;
    match reason {
        PauseReason::User => vec![],
        PauseReason::StorageFull { free_bytes } => vec![Effect::EmitStorageFull {
            free_bytes: *free_bytes,
            dropped_scans,
        }],
        PauseReason::StorageFailed { message } => vec![Effect::EmitStorageFailed {
            message: message.clone(),
            dropped_scans,
        }],
    }
}

/// The result of staging a batch: appended and acknowledged, or refused with a typed reply (`StorageFull`, else
/// `Backpressure`: keep the batch and retry). Refusals that last [`GIVE_UP_AFTER`] stop writing and pause.
fn staged(
    c: &Control,
    rows: Vec<Accepted>,
    rejected: u32,
    now: EpochMs,
    result: Result<(), CoreError>,
) -> (Control, Vec<Effect>) {
    use Effect as E;
    let Rec::Recording {
        session,
        row,
        active_before,
        running_since,
    } = &c.rec
    else {
        // staging runs inside the step that asked for it, so the state cannot have moved
        let ack = IngestAck {
            accepted: 0,
            rejected: 0,
            ignored_not_recording: rows.len() as u32 + rejected,
        };
        return (c.clone(), vec![E::Reply(Reply::Ack(ack))]);
    };
    let active = *active_before + (now - *running_since);
    match result {
        Ok(()) => {
            let after = c.buffer.plus(&rows);
            let over = after.exceeds(c.cap);
            let ack = IngestAck {
                accepted: rows.len() as u32,
                rejected,
                ignored_not_recording: 0,
            };
            let mut effects = vec![E::Append(rows), E::Snapshot];
            if over || c.commit_due(session, now) {
                effects.push(E::Commit { row: *row, now });
            }
            effects.push(E::Reply(Reply::Ack(ack)));
            (
                Control {
                    buffer: after,
                    staging: Staging::Healthy,
                    ..c.clone()
                },
                effects,
            )
        }
        Err(CoreError::StorageFull { free_bytes }) => {
            let (next, mut effects) = paused_for(
                c,
                session.clone(),
                *row,
                active,
                now,
                PauseReason::StorageFull { free_bytes },
            );
            effects.push(reply(CoreError::StorageFull { free_bytes }));
            (next, effects)
        }
        Err(e) => {
            let since = match c.staging {
                Staging::Healthy => now,
                Staging::Failing { since } => since,
            };
            let message = format!("the payload refused a batch: {e}");
            match now - since >= GIVE_UP_AFTER {
                true => {
                    let (next, mut effects) = paused_for(
                        c,
                        session.clone(),
                        *row,
                        active,
                        now,
                        PauseReason::StorageFailed { message },
                    );
                    effects.push(reply(CoreError::Backpressure));
                    (next, effects)
                }
                false => (
                    Control {
                        staging: Staging::Failing { since },
                        ..c.clone()
                    },
                    vec![
                        E::EmitStorageWarning(message),
                        reply(CoreError::Backpressure),
                    ],
                ),
            }
        }
    }
}

/// The effects of a finished stop.
fn finished(
    session: &Session,
    row: SessionRow,
    active_before: Millis,
    running_since: Option<EpochMs>,
    at: EpochMs,
) -> Vec<Effect> {
    use Effect as E;
    vec![
        E::Finish {
            row,
            ended: at,
            active: active_before + running_since.map_or(Millis(0), |s| at - s),
            was_paused: running_since.is_none(),
        },
        E::Snapshot,
        E::EmitRecordingChanged,
        E::EmitSessionsChanged,
        E::Reply(Reply::Session(session.uuid.clone())),
    ]
}

/// The result of a commit: in a recording, its delta; for a pause or stop, the transition it waited for.
/// - Out of space (R-10): the batch is dropped and the recording pauses; a stop still finishes.
/// - Any other failure restores the batch and backs off; a pause or stop that needed it fails while the recording
///   goes on.
fn committed(
    c: &Control,
    rec: Rec,
    result: Result<(), CoreError>,
    now: EpochMs,
) -> (Control, Vec<Effect>) {
    use Effect as E;
    let give_up = |e: &CoreError| match (e, &c.storage) {
        (CoreError::StorageFull { free_bytes }, _) => Some(PauseReason::StorageFull {
            free_bytes: *free_bytes,
        }),
        (e, Storage::Failing { since, .. }) if now - *since >= GIVE_UP_AFTER => {
            Some(PauseReason::StorageFailed {
                message: format!("commits keep failing: {e}"),
            })
        }
        (_, Storage::Healthy | Storage::Failing { .. }) => None,
    };
    match result {
        Err(e) => match give_up(&e) {
            Some(reason) => match rec {
                Rec::Recording {
                    session,
                    row,
                    active_before,
                    running_since,
                } => paused_for(
                    c,
                    session,
                    row,
                    active_before + (now - running_since),
                    now,
                    reason,
                ),
                Rec::Pausing {
                    session,
                    row,
                    active_before,
                    running_since,
                    at,
                } => {
                    let (next, mut effects) = paused_for(
                        c,
                        session,
                        row,
                        active_before + (at - running_since),
                        at,
                        reason,
                    );
                    effects.push(E::Reply(Reply::Done));
                    (next, effects)
                }
                Rec::Stopping {
                    session,
                    row,
                    active_before,
                    running_since,
                    at,
                } => {
                    let mut effects = vec![E::DropPending];
                    effects.extend(stopped_writing(c, &reason));
                    effects.extend(finished(&session, row, active_before, running_since, at));
                    (
                        Control {
                            rec: Rec::Idle,
                            buffer: Buffer::default(),
                            storage: Storage::Healthy,
                            staging: Staging::Healthy,
                            ..c.clone()
                        },
                        effects,
                    )
                }
                Rec::Idle | Rec::Starting { .. } | Rec::Paused { .. } => {
                    let mut effects = vec![E::DropPending];
                    effects.extend(stopped_writing(c, &reason));
                    (
                        Control {
                            rec,
                            buffer: Buffer::default(),
                            ..c.clone()
                        },
                        effects,
                    )
                }
            },
            None => {
                let (attempts, since) = match c.storage {
                    Storage::Healthy => (1, now),
                    Storage::Failing {
                        attempts, since, ..
                    } => (attempts.saturating_add(1), since),
                };
                let storage = Storage::Failing {
                    attempts,
                    since,
                    retry_at: EpochMs(now.0.saturating_add(c.backoff(attempts).0)),
                };
                let mut effects = vec![E::RestoreBatch, E::EmitStorageWarning(e.to_string())];
                let rec = match rec {
                    Rec::Pausing {
                        session,
                        row,
                        active_before,
                        running_since,
                        ..
                    } => {
                        effects.push(reply(e));
                        Rec::Recording {
                            session,
                            row,
                            active_before,
                            running_since,
                        }
                    }
                    Rec::Stopping {
                        session,
                        row,
                        active_before,
                        running_since,
                        ..
                    } => {
                        effects.push(reply(e));
                        match running_since {
                            Some(running_since) => Rec::Recording {
                                session,
                                row,
                                active_before,
                                running_since,
                            },
                            None => Rec::Paused {
                                session,
                                row,
                                active_before,
                                reason: PauseReason::User,
                            },
                        }
                    }
                    Rec::Idle
                    | Rec::Starting { .. }
                    | Rec::Recording { .. }
                    | Rec::Paused { .. } => rec,
                };
                (
                    Control {
                        rec,
                        storage,
                        ..c.clone()
                    },
                    effects,
                )
            }
        },
        Ok(()) => {
            let ok = |rec: Rec| Control {
                rec,
                buffer: Buffer::default(),
                storage: Storage::Healthy,
                ..c.clone()
            };
            match rec {
                Rec::Recording { .. } => {
                    (ok(rec), vec![E::Delta { now, force: false }, E::Snapshot])
                }
                Rec::Pausing {
                    session,
                    row,
                    active_before,
                    running_since,
                    at,
                } => (
                    ok(Rec::Paused {
                        session,
                        row,
                        active_before: active_before + (at - running_since),
                        reason: PauseReason::User,
                    }),
                    vec![
                        E::StorePause { row, at },
                        // deltas stop after the one that carries Paused (§2.6)
                        E::Delta {
                            now: at,
                            force: true,
                        },
                        E::EmitRecordingChanged,
                        E::Reply(Reply::Done),
                    ],
                ),
                Rec::Stopping {
                    session,
                    row,
                    active_before,
                    running_since,
                    at,
                } => (
                    ok(Rec::Idle),
                    finished(&session, row, active_before, running_since, at),
                ),
                Rec::Idle | Rec::Starting { .. } | Rec::Paused { .. } => (ok(rec), vec![]),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env() -> ScanEnvironment {
        ScanEnvironment {
            device_model: "m".into(),
            android_release: "15".into(),
            android_sdk: 35,
            app_version: "0".into(),
            cap_ext_adv: true,
            cap_coded_phy: true,
            cap_max_adv_len: 1650,
            scan_phy: "1m".into(),
            scan_legacy_only: false,
        }
    }

    fn scan(addr: &str) -> ScanObservationInput {
        ScanObservationInput {
            addr: addr.into(),
            rssi: Some(-60),
            scanned_at_ms: 1,
            elapsed_realtime_nanos: 1,
            raw: vec![2, 1, 6],
            local_name: None,
            tx_power: None,
            is_connectable: None,
            is_legacy: None,
            primary_phy: None,
            secondary_phy: None,
            adv_sid: None,
            adv_tx_power: None,
            periodic_interval: None,
            data_status: None,
            addr_type: None,
        }
    }

    fn uuid() -> SessionUuid {
        SessionUuid("s".into())
    }

    /// Runs events, answering CreateSession and Commit with `ok`; returns the final state and all effects.
    fn run(c: Control, events: Vec<Event>, commit_ok: bool) -> (Control, Vec<Effect>) {
        let mut c = c;
        let mut all = Vec::new();
        let mut queue: std::collections::VecDeque<Event> = events.into();
        while let Some(e) = queue.pop_front() {
            let now = match &e {
                Event::Pause { now }
                | Event::Stop { now }
                | Event::Tick { now, .. }
                | Event::Scans { now, .. } => *now,
                _ => EpochMs(0),
            };
            let (next, effects) = step(&c, e);
            c = next;
            for effect in &effects {
                match effect {
                    Effect::CreateSession { .. } => queue.push_front(Event::SessionCreated {
                        result: Ok((uuid(), SessionRow(1))),
                    }),
                    Effect::Stage {
                        rows,
                        rejected,
                        now,
                    } => queue.push_front(Event::Staged {
                        rows: rows.clone(),
                        rejected: *rejected,
                        now: *now,
                        result: Ok(()),
                    }),
                    Effect::Commit { .. } => queue.push_front(Event::Committed {
                        result: if commit_ok {
                            Ok(())
                        } else {
                            Err(CoreError::Db { detail: "x".into() })
                        },
                        now,
                    }),
                    _ => {}
                }
            }
            all.extend(effects);
        }
        (c, all)
    }

    fn start() -> Event {
        Event::Start {
            env: env(),
            now: EpochMs(1_000),
        }
    }

    #[test]
    fn start_ingest_tick_and_illegal_events() {
        let c = Control::new(Millis(5_000));
        let (c, effects) = run(c, vec![start()], true);
        assert!(matches!(c.rec, Rec::Recording { .. }));
        assert_eq!(effects.last(), Some(&Effect::Reply(Reply::Session(uuid()))));
        // a second start fails without changing anything
        let (same, effects) = step(&c, start());
        assert_eq!(same, c);
        assert_eq!(
            effects,
            vec![Effect::Reply(Reply::Failed(CoreError::AlreadyRecording))]
        );
        // ingest stages first and answers only after; before the interval: append; at the interval: also commit
        let (_, effects) = step(
            &c,
            Event::Scans {
                batch: vec![scan("AA:00:00:00:00:01"), scan("bad")],
                now: EpochMs(2_000),
            },
        );
        assert!(
            matches!(&effects[..], [Effect::Stage { rows, rejected: 1, .. }] if rows.len() == 1),
            "{effects:?}"
        );
        let (_, effects) = run(
            c.clone(),
            vec![Event::Scans {
                batch: vec![scan("AA:00:00:00:00:01"), scan("bad")],
                now: EpochMs(2_000),
            }],
            true,
        );
        assert!(
            effects
                .iter()
                .any(|e| matches!(e, Effect::Append(rows) if rows.len() == 1))
        );
        assert!(!effects.iter().any(|e| matches!(e, Effect::Commit { .. })));
        assert!(
            matches!(effects.last(), Some(Effect::Reply(Reply::Ack(a))) if a.accepted == 1 && a.rejected == 1)
        );
        let (_, effects) = run(
            c.clone(),
            vec![Event::Scans {
                batch: vec![scan("AA:00:00:00:00:01")],
                now: EpochMs(6_000),
            }],
            true,
        );
        assert!(effects.contains(&Effect::Commit {
            row: SessionRow(1),
            now: EpochMs(6_000)
        }));
        // the timer commits without scans
        assert_eq!(
            step(
                &c,
                Event::Tick {
                    now: EpochMs(5_999),
                    free: None
                }
            )
            .1,
            vec![]
        );
        assert_eq!(
            step(
                &c,
                Event::Tick {
                    now: EpochMs(6_000),
                    free: None
                }
            )
            .1,
            vec![Effect::Commit {
                row: SessionRow(1),
                now: EpochMs(6_000)
            }]
        );
        // idle: pause, resume and stop are refused; scans are ignored; ticks do nothing
        let idle = Control::new(Millis(5_000));
        assert_eq!(
            step(&idle, Event::Stop { now: EpochMs(1) }).1,
            vec![reply(CoreError::NotRecording)]
        );
        assert_eq!(
            step(
                &idle,
                Event::Tick {
                    now: EpochMs(99_999),
                    free: None
                }
            )
            .1,
            vec![]
        );
        assert!(matches!(
            step(
                &idle,
                Event::Scans {
                    batch: vec![scan("AA:00:00:00:00:01")],
                    now: EpochMs(1)
                }
            )
            .1[..],
            [Effect::Reply(Reply::Ack(IngestAck {
                ignored_not_recording: 1,
                ..
            }))]
        ));
    }

    #[test]
    fn a_failed_commit_restores_and_keeps_recording() {
        let (c, _) = run(Control::new(Millis(5_000)), vec![start()], true);
        let (after, effects) = run(
            c.clone(),
            vec![Event::Tick {
                now: EpochMs(7_000),
                free: None,
            }],
            false,
        );
        assert!(effects.contains(&Effect::RestoreBatch));
        assert!(matches!(after.rec, Rec::Recording { .. }));
        // the interval restarts after an attempt, failed or not
        assert_eq!(
            step(
                &after,
                Event::Tick {
                    now: EpochMs(8_000),
                    free: None
                }
            )
            .1,
            vec![]
        );
        // a pause or stop whose commit fails is refused and the recording goes on
        let (paused, effects) = run(
            c.clone(),
            vec![Event::Pause {
                now: EpochMs(9_000),
            }],
            false,
        );
        assert!(matches!(paused.rec, Rec::Recording { .. }));
        assert!(matches!(
            effects.last(),
            Some(Effect::Reply(Reply::Failed(_)))
        ));
        let (stopped, effects) = run(
            c,
            vec![Event::Stop {
                now: EpochMs(9_000),
            }],
            false,
        );
        assert!(matches!(stopped.rec, Rec::Recording { .. }));
        assert!(effects.contains(&Effect::RestoreBatch));
    }

    #[test]
    fn pause_resume_stop_exclude_paused_time() {
        let (c, _) = run(
            Control::new(Millis(5_000)),
            vec![
                start(),
                Event::Pause {
                    now: EpochMs(11_000),
                },
            ],
            true,
        );
        assert_eq!(c.rec.phase(), RecordingPhase::Paused);
        assert_eq!(c.rec.active(), (Millis(10_000), None));
        // paused: no commit is due, scans are ignored
        assert_eq!(
            step(
                &c,
                Event::Tick {
                    now: EpochMs(1_000_000),
                    free: None
                }
            )
            .1,
            vec![]
        );
        let (c, _) = run(
            c,
            vec![Event::Resume {
                now: EpochMs(71_000),
                free: None,
            }],
            true,
        );
        let (c, effects) = run(
            c,
            vec![Event::Stop {
                now: EpochMs(76_000),
            }],
            true,
        );
        assert_eq!(c.rec, Rec::Idle);
        assert!(effects.contains(&Effect::Finish {
            row: SessionRow(1),
            ended: EpochMs(76_000),
            active: Millis(15_000),
            was_paused: false
        }));
        assert_eq!(effects.last(), Some(&Effect::Reply(Reply::Session(uuid()))));
    }

    #[test]
    fn validation_bounds() {
        assert!(matches!(
            validate(vec![scan("AA:00:00:00:00:01"); 65]),
            Err(CoreError::InputBatchTooLarge { .. })
        ));
        let mut big = scan("AA:00:00:00:00:01");
        big.raw = vec![0; MAX_RAW_BYTES + 1];
        let (ok, rejected) = validate(vec![big, scan("AA:00:00:00:00:02")]).unwrap();
        assert_eq!((ok.len(), rejected), (1, 1));
    }

    fn staged(c: &Control, at: i64, result: Result<(), CoreError>) -> (Control, Vec<Effect>) {
        step(
            c,
            Event::Staged {
                rows: vec![Accepted {
                    key: 1,
                    input: scan("AA:00:00:00:00:01"),
                }],
                rejected: 0,
                now: EpochMs(at),
                result,
            },
        )
    }

    #[test]
    fn a_refused_stage_is_never_acked() {
        let (c, _) = run(Control::new(Millis(5_000)), vec![start()], true);
        let io = || CoreError::Io {
            detail: "EIO".into(),
        };
        // not for space: Backpressure (keep the batch, retry), a warning, nothing appended
        let (c1, effects) = staged(&c, 2_000, Err(io()));
        assert!(matches!(c1.rec, Rec::Recording { .. }));
        assert_eq!(c1.buffer, Buffer::default());
        assert!(!effects.iter().any(|e| matches!(e, Effect::Append(_))));
        assert!(
            !effects
                .iter()
                .any(|e| matches!(e, Effect::Reply(Reply::Ack(_))))
        );
        assert_eq!(effects.last(), Some(&reply(CoreError::Backpressure)));
        assert!(matches!(&effects[0], Effect::EmitStorageWarning(_)));
        // it keeps failing: within the limit still Backpressure, at the limit writing stops and the recording pauses
        let (c2, _) = staged(&c1, 2_000 + GIVE_UP_AFTER.0 - 1, Err(io()));
        assert!(matches!(c2.rec, Rec::Recording { .. }));
        let (c3, effects) = staged(&c2, 2_000 + GIVE_UP_AFTER.0, Err(io()));
        assert!(c3.rec.storage_failure().is_some());
        assert_eq!(c3.rec.phase(), RecordingPhase::Paused);
        assert!(
            effects
                .iter()
                .any(|e| matches!(e, Effect::EmitStorageFailed { .. }))
        );
        assert_eq!(effects.last(), Some(&reply(CoreError::Backpressure)));
        // a success in between restarts the clock
        let (ok, _) = staged(&c2, 3_000, Ok(()));
        assert_eq!(ok.staging, Staging::Healthy);
        // out of space: the R-10 pause and a StorageFull reply
        let (full, effects) = staged(&c, 2_000, Err(CoreError::StorageFull { free_bytes: 5 }));
        assert!(full.rec.paused_for_storage());
        assert_eq!(
            effects.last(),
            Some(&reply(CoreError::StorageFull { free_bytes: 5 }))
        );
        // resume after a storage failure needs no free space and starts afresh
        let (resumed, _) = step(
            &c3,
            Event::Resume {
                now: EpochMs(100_000),
                free: Some(1),
            },
        );
        assert!(matches!(resumed.rec, Rec::Recording { .. }));
        assert_eq!(resumed.staging, Staging::Healthy);
    }

    #[test]
    fn commits_failing_past_the_limit_stop_writing() {
        let (c, _) = run(Control::new(Millis(5_000)), vec![start()], true);
        let fail = |c: &Control, at: i64| {
            step(
                c,
                Event::Committed {
                    result: Err(CoreError::Db {
                        detail: "disk I/O error".into(),
                    }),
                    now: EpochMs(at),
                },
            )
        };
        let (c1, _) = fail(&c, 10_000);
        assert!(matches!(c1.rec, Rec::Recording { .. }));
        let (c2, effects) = fail(&c1, 10_000 + GIVE_UP_AFTER.0);
        assert!(c2.rec.storage_failure().is_some());
        assert!(effects.contains(&Effect::DropPending));
        assert!(
            effects
                .iter()
                .any(|e| matches!(e, Effect::EmitStorageFailed { .. }))
        );
    }
}
