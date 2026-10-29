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

/// The recording lifecycle. `Pausing` and `Stopping` wait for the commit that must precede them; each state
/// carries exactly the data valid in it.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Rec {
    Idle,
    /// The session row is being created.
    Starting(Session),
    Recording {
        session: Session,
        active_before: Millis,
        running_since: EpochMs,
    },
    Pausing {
        session: Session,
        active_before: Millis,
        running_since: EpochMs,
        at: EpochMs,
    },
    Paused {
        session: Session,
        active_before: Millis,
    },
    /// `running_since` is `None` when the stop came while paused.
    Stopping {
        session: Session,
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

/// The writer's control state: immutable, replaced by every step.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Control {
    pub rec: Rec,
    pub hide_unnamed_apple: bool,
    pub has_listener: bool,
    pub durability: Millis,
}

impl Control {
    pub fn new(durability: Millis) -> Self {
        Self {
            rec: Rec::Idle,
            hide_unnamed_apple: true,
            has_listener: false,
            durability,
        }
    }
}

/// One validated scan row: its device key and the input.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Accepted {
    pub key: i64,
    pub input: ScanObservationInput,
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
    Resume {
        now: EpochMs,
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
        result: Result<(), CoreError>,
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
        now: EpochMs,
    },
    /// Put the batch of the failed commit back in front of the pending buffer.
    RestoreBatch,
    StorePause {
        at: EpochMs,
    },
    StoreResume {
        at: EpochMs,
    },
    /// Mark the session completed.
    Finish {
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

fn due(session: &Session, durability: Millis, now: EpochMs) -> bool {
    now - session.last_commit >= durability
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
                Ok(()) => (
                    with(Rec::Recording {
                        session: s.clone(),
                        active_before: Millis(0),
                        running_since: s.started,
                    }),
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
                Rec::Recording { session, .. } => {
                    let ack = IngestAck {
                        accepted: accepted.len() as u32,
                        rejected,
                        ignored_not_recording: 0,
                    };
                    let mut effects = vec![E::Append(accepted), E::Snapshot];
                    if due(session, c.durability, now) {
                        effects.push(E::Commit { now });
                    }
                    effects.push(E::Reply(Reply::Ack(ack)));
                    same(effects)
                }
                Rec::Idle
                | Rec::Starting(_)
                | Rec::Pausing { .. }
                | Rec::Paused { .. }
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
        Event::Tick { now } => match &c.rec {
            Rec::Recording { session, .. } => match due(session, c.durability, now) {
                true => same(vec![E::Commit { now }]),
                false => same(vec![]),
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
                active_before,
                running_since,
            } => (
                with(Rec::Pausing {
                    session: session.clone(),
                    active_before: *active_before,
                    running_since: *running_since,
                    at: now,
                }),
                vec![E::Commit { now }],
            ),
            Rec::Paused { .. } => same(vec![E::Reply(Reply::Done)]),
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Resume { now } => match &c.rec {
            Rec::Paused {
                session,
                active_before,
            } => (
                with(Rec::Recording {
                    session: session.clone(),
                    active_before: *active_before,
                    running_since: now,
                }),
                vec![
                    E::StoreResume { at: now },
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
                active_before,
                running_since,
            } => (
                with(Rec::Stopping {
                    session: session.clone(),
                    active_before: *active_before,
                    running_since: Some(*running_since),
                    at: now,
                }),
                vec![E::Commit { now }],
            ),
            Rec::Paused {
                session,
                active_before,
            } => (
                with(Rec::Stopping {
                    session: session.clone(),
                    active_before: *active_before,
                    running_since: None,
                    at: now,
                }),
                vec![E::Commit { now }],
            ),
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![reply(CoreError::NotRecording)])
            }
        },
        Event::Configure { env } => match &c.rec {
            Rec::Recording {
                session,
                active_before,
                running_since,
            } => (
                with(Rec::Recording {
                    session: Session {
                        env,
                        ..session.clone()
                    },
                    active_before: *active_before,
                    running_since: *running_since,
                }),
                vec![E::Reply(Reply::Done)],
            ),
            Rec::Paused {
                session,
                active_before,
            } => (
                with(Rec::Paused {
                    session: Session {
                        env,
                        ..session.clone()
                    },
                    active_before: *active_before,
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
            Rec::Recording { .. } | Rec::Paused { .. } => {
                same(vec![E::Commit { now }, E::Reply(Reply::Done)])
            }
            Rec::Idle | Rec::Starting(_) | Rec::Pausing { .. } | Rec::Stopping { .. } => {
                same(vec![E::Reply(Reply::Done)])
            }
        },
    }
}

/// The result of a commit: in a recording, its delta; for a pause or stop, the transition it waited for. A failed
/// commit restores its batch, and a pause or stop that needed it fails while the recording goes on.
fn committed(
    c: &Control,
    rec: Rec,
    result: Result<(), CoreError>,
    now: EpochMs,
) -> (Control, Vec<Effect>) {
    use Effect as E;
    let with = |rec: Rec| Control { rec, ..c.clone() };
    match result {
        Err(e) => {
            let mut effects = vec![E::RestoreBatch, E::EmitStorageWarning(e.to_string())];
            let rec = match rec {
                Rec::Pausing {
                    session,
                    active_before,
                    running_since,
                    ..
                } => {
                    effects.push(reply(e));
                    Rec::Recording {
                        session,
                        active_before,
                        running_since,
                    }
                }
                Rec::Stopping {
                    session,
                    active_before,
                    running_since,
                    ..
                } => {
                    effects.push(reply(e));
                    match running_since {
                        Some(running_since) => Rec::Recording {
                            session,
                            active_before,
                            running_since,
                        },
                        None => Rec::Paused {
                            session,
                            active_before,
                        },
                    }
                }
                Rec::Idle | Rec::Starting(_) | Rec::Recording { .. } | Rec::Paused { .. } => rec,
            };
            (with(rec), effects)
        }
        Ok(()) => match rec {
            Rec::Recording { .. } => (with(rec), vec![E::Delta { now, force: false }, E::Snapshot]),
            Rec::Pausing {
                session,
                active_before,
                running_since,
                at,
            } => (
                with(Rec::Paused {
                    session,
                    active_before: active_before + (at - running_since),
                }),
                vec![
                    E::StorePause { at },
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
                active_before,
                running_since,
                at,
            } => (
                with(Rec::Idle),
                vec![
                    E::Finish {
                        ended: at,
                        active: active_before + running_since.map_or(Millis(0), |s| at - s),
                        was_paused: running_since.is_none(),
                    },
                    E::Snapshot,
                    E::EmitRecordingChanged,
                    E::EmitSessionsChanged,
                    E::Reply(Reply::Session(session.uuid)),
                ],
            ),
            Rec::Idle | Rec::Starting(_) | Rec::Paused { .. } => (with(rec), vec![]),
        },
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
                | Event::Tick { now }
                | Event::Scans { now, .. } => *now,
                _ => EpochMs(0),
            };
            let (next, effects) = step(&c, e);
            c = next;
            for effect in &effects {
                match effect {
                    Effect::CreateSession { .. } => {
                        queue.push_front(Event::SessionCreated { result: Ok(()) })
                    }
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
            now: EpochMs(6_000)
        }));
        // the timer commits without scans
        assert_eq!(
            step(
                &c,
                Event::Tick {
                    now: EpochMs(5_999)
                }
            )
            .1,
            vec![]
        );
        assert_eq!(
            step(
                &c,
                Event::Tick {
                    now: EpochMs(6_000)
                }
            )
            .1,
            vec![Effect::Commit {
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
                    now: EpochMs(99_999)
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
                    now: EpochMs(8_000)
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
                    now: EpochMs(1_000_000)
                }
            )
            .1,
            vec![]
        );
        let (c, _) = run(
            c,
            vec![Event::Resume {
                now: EpochMs(71_000),
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
