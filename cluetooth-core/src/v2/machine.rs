//! The writer's control logic (`docs/v2-api.md` §2.3, §2.4, §2.6): a pure `(state, event) -> (state, effects)`
//! step over immutable control state. It never touches SQLite, the buffers, the clock or the listener; every
//! side effect is a named [`Effect`] the writer actor executes, feeding results back as events
//! ([`Event::SessionCreated`], [`Event::Committed`]). Bulk data (pending observations, route, fix ring, caches,
//! connections) stays with the actor.

use crate::config::{
    INPUT_FIXED_ESTIMATE_BYTES, MAX_INPUT_BATCH_ESTIMATED_BYTES, MAX_INPUT_BATCH_ROWS,
};
use crate::payload::MAX_RAW_BYTES;
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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PauseReason {
    User,
    /// SPEC R-10: free space below [`STORAGE_FLOOR_BYTES`] or a write failed for lack of space.
    StorageFull {
        free_bytes: u64,
    },
}

/// SPEC R-10: writing stops below this much free space on the data volume.
pub(crate) const STORAGE_FLOOR_BYTES: u64 = 200_000_000;

/// The recording lifecycle. `Pausing` and `Stopping` wait for the commit that must precede them; each state
/// carries exactly the data valid in it.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Rec {
    Idle,
    /// The session row is being created.
    Starting(Session),
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
            Rec::Idle => None,
            Rec::Starting(s)
            | Rec::Recording { session: s, .. }
            | Rec::Pausing { session: s, .. }
            | Rec::Paused { session: s, .. }
            | Rec::Stopping { session: s, .. } => Some(s),
        }
    }

    /// The session row; `None` while idle or while it is being created.
    pub fn row(&self) -> Option<SessionRow> {
        match self {
            Rec::Idle | Rec::Starting(_) => None,
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
            | Rec::Starting(_)
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused {
                reason: PauseReason::User,
                ..
            }
            | Rec::Stopping { .. } => false,
        }
    }

    /// The phase shown to the UI (`RecordingState.phase`).
    pub fn phase(&self) -> RecordingPhase {
        match self {
            Rec::Idle => RecordingPhase::Idle,
            Rec::Starting(_) | Rec::Recording { .. } | Rec::Pausing { .. } => {
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
            Rec::Starting(s) => (Millis(0), Some(s.started)),
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
            Rec::Idle => {}
            Rec::Starting(s)
            | Rec::Recording { session: s, .. }
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
/// with [`CoreError::StorageFailing`], so memory stays below the cap plus one batch.
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
    /// `attempts` commits failed in a row; the next interval commit waits until `retry_at`.
    Failing {
        attempts: u32,
        retry_at: EpochMs,
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
        uuid: SessionUuid,
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
        result: Result<SessionRow, CoreError>,
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
        uuid: SessionUuid,
        started: EpochMs,
    },
    /// Forget the previous recording's live state.
    ResetLive,
    /// Append accepted rows to the pending buffer (their fixes from the ring) and to the live state.
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
            Some(key) if input.raw.len() <= MAX_RAW_BYTES => accepted.push(Accepted { key, input }),
            _ => rejected += 1,
        }
    }
    Ok((accepted, rejected))
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
        Event::Start { uuid, env, now } => match &c.rec {
            Rec::Idle => {
                let session = Session {
                    uuid: uuid.clone(),
                    started: now,
                    env,
                    last_commit: now,
                };
                (
                    with(Rec::Starting(session)),
                    vec![E::CreateSession { uuid, started: now }],
                )
            }
            Rec::Starting(_)
            | Rec::Recording { .. }
            | Rec::Pausing { .. }
            | Rec::Paused { .. }
            | Rec::Stopping { .. } => same(vec![reply(CoreError::AlreadyRecording)]),
        },
        Event::SessionCreated { result } => match &c.rec {
            Rec::Starting(s) => match result {
                Ok(row) => (
                    Control {
                        rec: Rec::Recording {
                            session: s.clone(),
                            row,
                            active_before: Millis(0),
                            running_since: s.started,
                        },
                        buffer: Buffer::default(),
                        ..c.clone()
                    },
                    vec![
                        E::ResetLive,
                        E::Snapshot,
                        E::EmitRecordingChanged,
                        E::Reply(Reply::Session(s.uuid.clone())),
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
                        (Storage::Healthy, _) | (Storage::Failing { .. }, false) => {
                            let ack = IngestAck {
                                accepted: accepted.len() as u32,
                                rejected,
                                ignored_not_recording: 0,
                            };
                            let mut effects = vec![E::Append(accepted), E::Snapshot];
                            if over || c.commit_due(session, now) {
                                effects.push(E::Commit { row: *row, now });
                            }
                            effects.push(E::Reply(Reply::Ack(ack)));
                            (
                                Control {
                                    buffer: after,
                                    ..c.clone()
                                },
                                effects,
                            )
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
                | Rec::Starting(_)
                | Rec::Pausing { .. }
                | Rec::Paused {
                    reason: PauseReason::User,
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
        Event::Tick { now, free } => match &c.rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => match free {
                Some(free_bytes) if free_bytes < STORAGE_FLOOR_BYTES => paused_full(
                    c,
                    session.clone(),
                    *row,
                    *active_before + (now - *running_since),
                    now,
                    free_bytes,
                ),
                Some(_) | None => match c.commit_due(session, now) {
                    true => same(vec![E::Commit { row: *row, now }]),
                    false => same(vec![]),
                },
            },
            Rec::Idle
            | Rec::Starting(_)
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
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
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
                    ..c.clone()
                },
                vec![
                    E::StoreResume { row: *row, at: now },
                    E::EmitRecordingChanged,
                    E::Reply(Reply::Done),
                ],
            ),
            Rec::Recording { .. } => same(vec![E::Reply(Reply::Done)]),
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
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
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
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
                        env,
                        ..session.clone()
                    },
                    row: *row,
                    active_before: *active_before,
                    running_since: *running_since,
                }),
                vec![E::Reply(Reply::Done)],
            ),
            Rec::Paused {
                session,
                row,
                active_before,
                reason,
            } => (
                with(Rec::Paused {
                    session: Session {
                        env,
                        ..session.clone()
                    },
                    row: *row,
                    active_before: *active_before,
                    reason: *reason,
                }),
                vec![E::Reply(Reply::Done)],
            ),
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Location { fix } => match fix.is_usable() {
            false => same(vec![]),
            true => {
                let route = match &c.rec {
                    Rec::Recording { .. } => true,
                    Rec::Idle
                    | Rec::Starting(_)
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
            Rec::Recording { row, .. } | Rec::Paused { row, .. } => {
                same(vec![E::Commit { row: *row, now }, E::Reply(Reply::Done)])
            }
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![E::Reply(Reply::Done)])
            }
        },
    }
}

/// SPEC R-10: stop writing and pause. The pending rows cannot be stored and are dropped (and counted); the
/// session stays open and everything committed stays.
fn paused_full(
    c: &Control,
    session: Session,
    row: SessionRow,
    active_before: Millis,
    at: EpochMs,
    free_bytes: u64,
) -> (Control, Vec<Effect>) {
    use Effect as E;
    (
        Control {
            rec: Rec::Paused {
                session,
                row,
                active_before,
                reason: PauseReason::StorageFull { free_bytes },
            },
            buffer: Buffer::default(),
            storage: Storage::Healthy,
            ..c.clone()
        },
        vec![
            E::DropPending,
            E::StorePause { row, at },
            E::Delta {
                now: at,
                force: true,
            },
            E::EmitRecordingChanged,
            E::EmitStorageFull {
                free_bytes,
                dropped_scans: c.buffer.rows,
            },
        ],
    )
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
    match result {
        Err(CoreError::StorageFull { free_bytes }) => match rec {
            Rec::Recording {
                session,
                row,
                active_before,
                running_since,
            } => paused_full(
                c,
                session,
                row,
                active_before + (now - running_since),
                now,
                free_bytes,
            ),
            Rec::Pausing {
                session,
                row,
                active_before,
                running_since,
                at,
            } => {
                let (next, mut effects) = paused_full(
                    c,
                    session,
                    row,
                    active_before + (at - running_since),
                    at,
                    free_bytes,
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
                let mut effects = vec![
                    E::DropPending,
                    E::EmitStorageFull {
                        free_bytes,
                        dropped_scans: c.buffer.rows,
                    },
                ];
                effects.extend(finished(&session, row, active_before, running_since, at));
                (
                    Control {
                        rec: Rec::Idle,
                        buffer: Buffer::default(),
                        storage: Storage::Healthy,
                        ..c.clone()
                    },
                    effects,
                )
            }
            Rec::Idle | Rec::Starting(_) | Rec::Paused { .. } => (
                Control {
                    rec,
                    buffer: Buffer::default(),
                    ..c.clone()
                },
                vec![
                    E::DropPending,
                    E::EmitStorageFull {
                        free_bytes,
                        dropped_scans: c.buffer.rows,
                    },
                ],
            ),
        },
        Err(e) => {
            let attempts = match c.storage {
                Storage::Healthy => 1,
                Storage::Failing { attempts, .. } => attempts.saturating_add(1),
            };
            let storage = Storage::Failing {
                attempts,
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
                Rec::Idle | Rec::Starting(_) | Rec::Recording { .. } | Rec::Paused { .. } => rec,
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
                Rec::Idle | Rec::Starting(_) | Rec::Paused { .. } => (ok(rec), vec![]),
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
                        result: Ok(SessionRow(1)),
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
            uuid: uuid(),
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
        // ingest before the interval: append only; at the interval: append and commit
        let (_, effects) = step(
            &c,
            Event::Scans {
                batch: vec![scan("AA:00:00:00:00:01"), scan("bad")],
                now: EpochMs(2_000),
            },
        );
        assert!(matches!(&effects[0], Effect::Append(rows) if rows.len() == 1));
        assert!(!effects.iter().any(|e| matches!(e, Effect::Commit { .. })));
        assert!(
            matches!(effects.last(), Some(Effect::Reply(Reply::Ack(a))) if a.accepted == 1 && a.rejected == 1)
        );
        let (_, effects) = step(
            &c,
            Event::Scans {
                batch: vec![scan("AA:00:00:00:00:01")],
                now: EpochMs(6_000),
            },
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
}
