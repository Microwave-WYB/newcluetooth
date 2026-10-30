//! Payload v3 staging in the v2 core (`docs/v2-api.md` §1.5, §1.6, §2.4): the recording's rows reach the Parquet
//! payload, a commit never gets ahead of the staging WAL, a v3 app's open recording and pending files are kept, and
//! uploading keeps the plaintext.

use std::path::Path;
use std::sync::Arc;

use sha2::{Digest, Sha256};

use super::core::{Core, CoreListener};
use super::error::CoreError;
use super::types::*;
use crate::{LocationFix, ScanEnvironment, ScanObservationInput};

fn env() -> ScanEnvironment {
    ScanEnvironment {
        device_model: "Pixel 8".into(),
        android_release: "15".into(),
        android_sdk: 35,
        app_version: "0.2.0".into(),
        cap_ext_adv: true,
        cap_coded_phy: true,
        cap_max_adv_len: 1650,
        scan_phy: "all_supported".into(),
        scan_legacy_only: false,
    }
}

fn config(dir: &Path) -> CoreConfig {
    CoreConfig {
        data_directory: dir.to_string_lossy().into_owned(),
        recipient_public_key: crate::default_core_config().recipient_public_key,
        location_max_age_ms: 5_000,
        payload_max_rows: 100_000,
        payload_max_estimated_bytes: 10 << 20,
        payload_max_age_ms: 300_000,
        durability_interval_ms: 5_000,
        stale_after_ms: 20_000,
        live_interval_ms: 1_000,
    }
}

fn open(dir: &Path, now: i64) -> Arc<Core> {
    Core::open_at(
        config(dir),
        CoreAssets {
            wiki_bundle_json: std::fs::read_to_string(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../scanner/core-fake/src/test/resources/fake-bundle.json"
            ))
            .unwrap(),
            sig_numbers_tsv: String::new(),
        },
        now,
    )
    .unwrap()
}

fn obs(i: u64, at: i64) -> ScanObservationInput {
    let name = format!("dev{}", i % 7);
    let mut raw = vec![2, 1, 6, name.len() as u8 + 1, 9];
    raw.extend(name.as_bytes());
    ScanObservationInput {
        addr: format!("AA:00:00:00:00:{:02X}", i % 7),
        rssi: Some(-50 - (i % 40) as i16),
        scanned_at_ms: at,
        elapsed_realtime_nanos: 1_000_000_000 + i,
        raw,
        local_name: Some(name),
        tx_power: None,
        is_connectable: Some(true),
        is_legacy: Some(true),
        primary_phy: Some(1),
        secondary_phy: Some(0),
        adv_sid: None,
        adv_tx_power: None,
        periodic_interval: None,
        data_status: Some(0),
        addr_type: Some(1),
    }
}

fn db_sightings(core: &Core) -> i64 {
    core.with_writer_conn(|c| {
        c.query_row(
            "SELECT coalesce(sum(sightings), 0) FROM sighting_minutes",
            [],
            |r| r.get(0),
        )
        .unwrap()
    })
}

fn rows_of(path: &str) -> (u64, Option<String>, String) {
    let i = crate::inspect_schema_v2_parquet(path.to_owned()).unwrap();
    (i.row_count, i.scan_session_id, i.schema_version)
}

fn sha(path: &str) -> Vec<u8> {
    Sha256::digest(std::fs::read(path).unwrap()).to_vec()
}

/// §2.4/§1.4: a recording's accepted rows end up in one schema-v3 payload under `pending/`, named for its session.
#[test]
fn a_recording_produces_its_v3_payload() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let core = open(dir.path(), t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    for i in 0..50u64 {
        core.ingest_scans_at(vec![obs(i, t0 + i as i64 * 200)], t0 + i as i64 * 200)
            .unwrap();
    }
    assert_eq!(core.stop_recording_at(t0 + 20_000).unwrap(), id);
    let pending = core.pending_uploads().unwrap();
    assert_eq!(pending.len(), 1);
    let (rows, session, schema) = rows_of(&pending[0].local_path);
    assert_eq!(rows, 50);
    assert_eq!(session.as_deref(), Some(id.as_str()));
    assert_eq!(schema, "v3");
    assert!(pending[0].object_path.starts_with("scans/"));
    assert_eq!(
        db_sightings(&core),
        50,
        "the DB holds exactly what the payload holds"
    );
    assert_eq!(core.upload_status().unwrap().pending_count, 1);
    let s = futures_executor::block_on(core.sessions()).unwrap();
    assert_eq!(s[0].upload, UploadState::Pending);
}

/// §1.2/§2.4 commit gating: after a kill between commits, the DB rows are a subset of the staged rows, and the
/// staging WAL the killed process left is sealed as a v3 payload at the next open.
#[test]
fn a_kill_between_commits_never_leaves_db_rows_ahead_of_the_payload() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let id = {
        let core = open(dir.path(), t0);
        let id = core.start_recording_at(env(), t0).unwrap();
        for i in 0..10u64 {
            core.ingest_scans_at(vec![obs(i, t0 + 100 + i as i64)], t0 + 100 + i as i64)
                .unwrap();
        }
        core.tick_at(t0 + 6_000); // staged, then committed
        assert_eq!(db_sightings(&core), 10);
        for i in 10..15u64 {
            core.ingest_scans_at(vec![obs(i, t0 + 7_000 + i as i64)], t0 + 7_000 + i as i64)
                .unwrap();
        }
        // killed here: 5 rows accepted, neither staged nor committed
        id
    };
    std::thread::sleep(std::time::Duration::from_millis(100));
    let core = open(dir.path(), t0 + 60_000);
    assert_eq!(db_sightings(&core), 10);
    let pending = core.pending_uploads().unwrap();
    assert_eq!(pending.len(), 1, "the recovered staging WAL was sealed");
    let (rows, session, _) = rows_of(&pending[0].local_path);
    assert!(
        rows >= 10,
        "the DB's 10 rows are all in the payload ({rows} staged)"
    );
    assert_eq!(session.as_deref(), Some(id.as_str()));
    let sessions = futures_executor::block_on(core.sessions()).unwrap();
    assert_eq!(sessions[0].status, SessionStatus::Interrupted);
}

/// §1.5/§6.8.4: opening over a v3 app's data keeps its pending payloads and seals its open recording as v3; all of
/// them are listed for upload.
#[test]
fn an_upgrade_keeps_v3_pending_files_and_seals_the_open_v3_recording() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().to_string_lossy().into_owned();
    {
        let v3 = crate::CluetoothCore::open(path.clone(), crate::default_core_config()).unwrap();
        v3.configure_scan(env()).unwrap();
        // a finished v3 session with its sealed payload
        v3.start_scan_session().unwrap();
        v3.record_observations(vec![obs(1, 1_761_000_000_000), obs(2, 1_761_000_000_100)])
            .unwrap();
        v3.finish_scan_session().unwrap();
        // a v3 recording still open when the upgrade lands: staged, never sealed
        v3.start_scan_session().unwrap();
        v3.record_observations(vec![obs(3, 1_761_000_100_000)])
            .unwrap();
        v3.checkpoint_active_payload().unwrap();
        assert_eq!(v3.pending_uploads().len(), 1);
    }
    let core = open(dir.path(), 1_761_000_200_000);
    let pending = core.pending_uploads().unwrap();
    assert_eq!(
        pending.len(),
        2,
        "the old payload and the sealed open recording"
    );
    let mut counts: Vec<u64> = pending.iter().map(|p| rows_of(&p.local_path).0).collect();
    counts.sort();
    assert_eq!(counts, vec![1, 2]);
    assert!(pending.iter().all(|p| rows_of(&p.local_path).2 == "v3"));
    // pre-v2 sessions are not imported into the DB
    assert!(
        futures_executor::block_on(core.sessions())
            .unwrap()
            .is_empty()
    );
}

/// §1.6: an upload encrypts into `upload-cache/` only; success moves the plaintext to `archive/` unchanged (same
/// SHA-256) and removes the ciphertext; the session reads Uploaded.
#[test]
fn uploading_keeps_the_plaintext_and_removes_the_ciphertext() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let core = open(dir.path(), t0);
    core.start_recording_at(env(), t0).unwrap();
    core.ingest_location_at(LocationFix {
        lat: 32.88,
        lon: -117.234,
        accuracy_meters: 5.0,
        observed_at_ms: t0,
        elapsed_realtime_nanos: 1_000_000_000,
    });
    core.ingest_scans_at(vec![obs(1, t0 + 10)], t0 + 10)
        .unwrap();
    core.stop_recording_at(t0 + 1_000).unwrap();
    let pending = core.pending_uploads().unwrap();
    let before = sha(&pending[0].local_path);
    let prepared = core.prepare_upload(pending[0].payload_id.clone()).unwrap();
    assert!(Path::new(&prepared.ciphertext_path).is_file());
    assert!(prepared.ciphertext_path.contains("upload-cache"));
    core.mark_upload_succeeded(pending[0].payload_id.clone())
        .unwrap();
    assert!(
        !Path::new(&prepared.ciphertext_path).exists(),
        "the ciphertext is transient"
    );
    assert!(core.pending_uploads().unwrap().is_empty());
    let archived: Vec<_> = walk(&dir.path().join("archive"))
        .into_iter()
        .filter(|p| p.extension().is_some_and(|e| e == "parquet"))
        .collect();
    assert_eq!(archived.len(), 1);
    assert_eq!(
        sha(&archived[0].to_string_lossy()),
        before,
        "the plaintext is kept byte-identical"
    );
    assert_eq!(core.upload_status().unwrap().pending_count, 0);
    let s = futures_executor::block_on(core.sessions()).unwrap();
    assert_eq!(s[0].upload, UploadState::Uploaded);
}

/// SPEC R-10 with real payloads: "Delete uploaded raw files" frees the uploaded Parquet only, the session stays
/// Uploaded (also after a restart), the un-uploaded payload still uploads, and "Clear local database" keeps it too.
#[test]
fn storage_cleanup_keeps_unuploaded_payloads() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let core = open(dir.path(), t0);
    let uploaded = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![obs(1, t0 + 10)], t0 + 10)
        .unwrap();
    core.stop_recording_at(t0 + 1_000).unwrap();
    let first = core.pending_uploads().unwrap().remove(0);
    core.prepare_upload(first.payload_id.clone()).unwrap();
    core.mark_upload_succeeded(first.payload_id.clone())
        .unwrap();
    let waiting = core.start_recording_at(env(), t0 + 2_000).unwrap();
    core.ingest_scans_at(vec![obs(2, t0 + 2_010)], t0 + 2_010)
        .unwrap();
    core.stop_recording_at(t0 + 3_000).unwrap();
    let pending = core.pending_uploads().unwrap();
    assert_eq!(pending.len(), 1);

    let archived_bytes: u64 = walk(&dir.path().join("archive"))
        .iter()
        .map(|p| std::fs::metadata(p).unwrap().len())
        .sum();
    assert!(archived_bytes > 0);
    let r = futures_executor::block_on(core.reclaimable()).unwrap();
    assert_eq!(r.uploaded_raw_bytes, archived_bytes);
    assert_eq!(
        futures_executor::block_on(core.delete_uploaded_raw()).unwrap(),
        archived_bytes
    );
    assert!(walk(&dir.path().join("archive")).is_empty());
    assert!(
        Path::new(&pending[0].local_path).is_file(),
        "the un-uploaded payload stays"
    );
    let states = |core: &Core| -> Vec<(String, UploadState)> {
        let mut v: Vec<_> = futures_executor::block_on(core.sessions())
            .unwrap()
            .into_iter()
            .map(|s| (s.id, s.upload))
            .collect();
        v.sort_by(|a, b| a.0.cmp(&b.0));
        v
    };
    let expected = vec![
        (uploaded.clone(), UploadState::Uploaded),
        (waiting.clone(), UploadState::Pending),
    ];
    assert_eq!(states(&core), expected);
    drop(core);
    let core = open(dir.path(), t0 + 4_000);
    assert_eq!(states(&core), expected, "after a restart");

    assert!(futures_executor::block_on(core.clear_local_database()).unwrap() > 0);
    assert!(
        futures_executor::block_on(core.sessions())
            .unwrap()
            .is_empty()
    );
    let still = core.pending_uploads().unwrap();
    assert_eq!(still.len(), 1);
    assert_eq!(still[0].payload_id, pending[0].payload_id);
    core.prepare_upload(still[0].payload_id.clone()).unwrap();
}

/// Owner decision: no backfill. A session recorded before the Parquet staging (local.db only) stays local-only:
/// it shows the Empty upload state, and neither open nor a recording creates a payload for it.
#[test]
fn a_db_only_session_is_not_backfilled() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = 1_761_000_000_000i64;
    let old = "019a03c7-d820-7973-8013-63f452e5a126";
    {
        let core = open(dir.path(), t0);
        core.with_writer_conn(move |c| {
            c.execute(
                "INSERT INTO sessions (uuid, status, started_ms, ended_ms, active_ms, device_count, sightings)
                 VALUES (?1, 2, ?2, ?2 + 60000, 60000, 3, 40)",
                rusqlite::params![old, t0],
            )
            .unwrap();
        });
    }
    let core = open(dir.path(), t0 + 100_000);
    core.start_recording_at(env(), t0 + 100_000).unwrap();
    core.ingest_scans_at(vec![obs(1, t0 + 100_010)], t0 + 100_010)
        .unwrap();
    let new = core.stop_recording_at(t0 + 101_000).unwrap();
    let sessions = futures_executor::block_on(core.sessions()).unwrap();
    let state = |id: &str| sessions.iter().find(|s| s.id == id).map(|s| s.upload);
    assert_eq!(state(old), Some(UploadState::Empty));
    assert_eq!(state(&new), Some(UploadState::Pending));
    let pending = core.pending_uploads().unwrap();
    assert_eq!(pending.len(), 1, "only the new recording has a payload");
    assert!(!pending[0].local_path.contains(old));
}

/// §2.4: a GATT discovery joins the upload queue as gatt/v1 plaintext.
#[test]
fn a_gatt_discovery_is_queued_for_upload() {
    let dir = tempfile::tempdir().unwrap();
    let core = open(dir.path(), 1_761_000_000_000);
    let id = core.new_gatt_discovery_id().unwrap();
    let json = serde_json::json!({
        "schema": "gatt/v1", "id": id, "session_id": null, "app_version": "0.2.0",
        "device": {"model": "Pixel 8", "android": "15"},
        "target": {"addr": "AA:BB:CC:DD:EE:FF", "addr_type": "random", "name": null, "trigger_adv": null},
        "started_at": "2026-10-28T19:00:01.000Z", "ended_at": "2026-10-28T19:00:02.000Z", "location": null,
        "outcome": "failed", "stage_reached": "connect",
        "error": {"stage": "connect", "status": -1, "name": "TIMEOUT"},
        "disconnect": {"initiator": "timeout", "status": null}, "mtu": null, "phy": null,
        "timings_ms": {"connect": null, "mtu": null, "discover": null, "read": null}, "services": []
    })
    .to_string();
    core.ingest_gatt(json).unwrap();
    let pending = core.pending_uploads().unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].payload_id, id);
    assert!(pending[0].object_path.starts_with("gatt/v1/"));
}

fn walk(dir: &Path) -> Vec<std::path::PathBuf> {
    let mut out = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.is_dir() {
                out.extend(walk(&p));
            } else {
                out.push(p);
            }
        }
    }
    out
}

#[derive(Default)]
struct Events(std::sync::Mutex<Vec<CoreEvent>>);

impl CoreListener for Events {
    fn on_live(&self, _: LiveDelta) {}
    fn on_event(&self, event: CoreEvent) {
        self.0.lock().unwrap().push(event);
    }
}

impl Events {
    fn wait(&self, f: impl Fn(&CoreEvent) -> bool) -> bool {
        (0..200).any(|_| {
            let hit = self.0.lock().unwrap().iter().any(&f);
            if !hit {
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            hit
        })
    }
}

/// #79 review: a batch the payload refuses for lack of space (ENOSPC through `payload_error`) is not acknowledged:
/// ingest returns `StorageFull`, the recording pauses for storage (R-10), and none of its rows is stored.
#[test]
fn a_full_payload_refuses_the_batch_with_storage_full() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = 1_761_000_000_000i64;
    let core = open(dir.path(), t0);
    let events = Arc::new(Events::default());
    core.set_listener(Some(events.clone())).unwrap();
    core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![obs(1, t0 + 10)], t0 + 10)
        .unwrap();
    core.fail_staging(Some("write failed: No space left on device (os error 28)"));
    assert!(matches!(
        core.ingest_scans_at(vec![obs(2, t0 + 20)], t0 + 20),
        Err(CoreError::StorageFull { .. })
    ));
    let state = core.recording_state().unwrap();
    assert!(state.paused_for_storage);
    assert!(events.wait(|e| matches!(
        e,
        CoreEvent::StorageFull {
            dropped_scans: 1,
            ..
        }
    )));
    assert_eq!(
        db_sightings(&core),
        0,
        "neither the refused batch nor the dropped one is stored"
    );
    assert!(matches!(
        core.ingest_scans_at(vec![obs(3, t0 + 30)], t0 + 30),
        Err(CoreError::StorageFull { .. })
    ));
}

/// #79 review: a refusal that is not for space gets `Backpressure` (keep the batch, retry) and a warning, never an
/// ack; one that lasts 60 s stops writing and pauses with `storage_failure` and `StorageFailed`, so the app does
/// not show a silent "recording". Resume records again.
#[test]
fn a_failing_payload_backpressures_then_pauses() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = 1_761_000_000_000i64;
    let core = open(dir.path(), t0);
    let events = Arc::new(Events::default());
    core.set_listener(Some(events.clone())).unwrap();
    core.start_recording_at(env(), t0).unwrap();
    core.fail_staging(Some("Input/output error (os error 5)"));
    assert!(matches!(
        core.ingest_scans_at(vec![obs(1, t0 + 10)], t0 + 10),
        Err(CoreError::Backpressure)
    ));
    let state = core.recording_state().unwrap();
    assert_eq!(state.phase, RecordingPhase::Recording);
    assert_eq!(state.storage_failure, None);
    assert!(events.wait(|e| matches!(e, CoreEvent::StorageWarning { .. })));
    assert_eq!(core.pending_len(), 0, "a refused batch is not buffered");
    assert!(matches!(
        core.ingest_scans_at(vec![obs(1, t0 + 60_010)], t0 + 60_010),
        Err(CoreError::Backpressure)
    ));
    let state = core.recording_state().unwrap();
    assert_eq!(state.phase, RecordingPhase::Paused);
    assert!(state.storage_failure.is_some());
    assert!(!state.paused_for_storage, "not a storage-full pause");
    assert!(events.wait(|e| matches!(e, CoreEvent::StorageFailed { .. })));
    core.fail_staging(None);
    core.resume_recording_at(t0 + 70_000).unwrap();
    core.ingest_scans_at(vec![obs(2, t0 + 70_010)], t0 + 70_010)
        .unwrap();
    core.stop_recording_at(t0 + 71_000).unwrap();
    assert_eq!(
        db_sightings(&core),
        1,
        "only the batch staged after the resume"
    );
}

/// #79 review: a failed upload-state read never turns sessions Empty (Empty means local-only, before Parquet):
/// the last states read stand, and before any read `sessions()` returns a typed error.
#[test]
fn an_upload_state_read_error_keeps_the_last_states() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let core = open(dir.path(), t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![obs(1, t0 + 10)], t0 + 10)
        .unwrap();
    core.stop_recording_at(t0 + 1_000).unwrap();
    let state = |core: &Core| {
        futures_executor::block_on(core.sessions())
            .map(|s| s.into_iter().find(|s| s.id == id).map(|s| s.upload))
    };
    // the failure markers become unreadable: a file where their directory goes
    let failed = dir.path().join("upload-state/failed");
    let break_markers = || {
        let _ = std::fs::remove_dir_all(&failed);
        std::fs::create_dir_all(failed.parent().unwrap()).unwrap();
        std::fs::write(&failed, b"not a directory").unwrap();
    };
    break_markers();
    assert!(
        state(&core).is_err(),
        "no states read yet: a typed error, not Empty"
    );
    std::fs::remove_file(&failed).unwrap();
    assert_eq!(state(&core).unwrap(), Some(UploadState::Pending));
    break_markers();
    assert_eq!(
        state(&core).unwrap(),
        Some(UploadState::Pending),
        "the last states stand"
    );
}
