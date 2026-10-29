//! `local.db` (`docs/v2-api.md` §3): the schema, its migrations, and opening a connection.

use std::path::{Path, PathBuf};

use rusqlite::functions::FunctionFlags;
use rusqlite::{Connection, OpenFlags};

use super::error::CoreError;

/// §3.2, schema version 1. Migrations are `N → N+1` steps, kept forever; each runs in one transaction.
const MIGRATIONS: &[&str] = &[SCHEMA_V1];

pub(crate) const SCHEMA_VERSION: u32 = MIGRATIONS.len() as u32;

const SCHEMA_V1: &str = r#"
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;

CREATE TABLE ad_structures (
  id INTEGER PRIMARY KEY, ad_type INTEGER NOT NULL, data BLOB NOT NULL,
  local_name TEXT, company_id INTEGER, uuids TEXT,
  UNIQUE (ad_type, data)
);
CREATE TABLE payloads (
  id INTEGER PRIMARY KEY, sha256 BLOB NOT NULL UNIQUE,
  zero_padding INTEGER NOT NULL DEFAULT 0, tail BLOB,
  local_name TEXT, company_id INTEGER, service_uuid TEXT,
  is_apple INTEGER NOT NULL,
  page_id TEXT,
  match_key TEXT NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL DEFAULT 0,
  CHECK (tail IS NULL OR zero_padding = 0)
);
CREATE TABLE payload_structures (payload_id INTEGER NOT NULL REFERENCES payloads(id), ord INTEGER NOT NULL,
  structure_id INTEGER NOT NULL REFERENCES ad_structures(id), PRIMARY KEY (payload_id, ord)) WITHOUT ROWID;
CREATE INDEX payloads_rematch ON payloads(match_key, id);

CREATE TABLE sessions (
  id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE,
  status INTEGER NOT NULL,
  started_ms INTEGER NOT NULL, ended_ms INTEGER, active_ms INTEGER NOT NULL DEFAULT 0,
  distance_m REAL NOT NULL DEFAULT 0, device_count INTEGER NOT NULL DEFAULT 0, device_count_shown INTEGER NOT NULL DEFAULT 0,
  sightings INTEGER NOT NULL DEFAULT 0, min_lon REAL, min_lat REAL, max_lon REAL, max_lat REAL,
  route_simplified BLOB
);
CREATE INDEX sessions_started ON sessions(started_ms DESC);
CREATE TABLE session_pauses (session_id INTEGER NOT NULL REFERENCES sessions(id), from_ms INTEGER NOT NULL,
  to_ms INTEGER, PRIMARY KEY (session_id, from_ms)) WITHOUT ROWID;
CREATE TABLE route_points (session_id INTEGER NOT NULL REFERENCES sessions(id), t_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, PRIMARY KEY (session_id, t_ms)) WITHOUT ROWID;
CREATE VIRTUAL TABLE sessions_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE sighting_minutes (
  id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES sessions(id),
  minute INTEGER NOT NULL, addr INTEGER NOT NULL, payload_id INTEGER NOT NULL REFERENCES payloads(id),
  addr_type INTEGER NOT NULL DEFAULT -1, is_legacy INTEGER NOT NULL DEFAULT -1,
  primary_phy INTEGER NOT NULL DEFAULT -1, secondary_phy INTEGER NOT NULL DEFAULT -1,
  adv_sid INTEGER NOT NULL DEFAULT -1, data_status INTEGER NOT NULL DEFAULT -1,
  sightings INTEGER NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
  rssi_min INTEGER, rssi_max INTEGER, rssi_sum INTEGER NOT NULL DEFAULT 0, rssi_count INTEGER NOT NULL DEFAULT 0,
  tx_power INTEGER, is_connectable INTEGER, adv_tx_power INTEGER, periodic_interval INTEGER,
  min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL,
  best_lat REAL, best_lon REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER, best_score REAL,
  UNIQUE (session_id, minute, addr, payload_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)
);
CREATE INDEX sighting_minutes_device  ON sighting_minutes(addr, session_id, minute);
CREATE INDEX sighting_minutes_payload ON sighting_minutes(payload_id);

CREATE TABLE session_devices (
  id INTEGER PRIMARY KEY,
  session_id INTEGER NOT NULL REFERENCES sessions(id), addr INTEGER NOT NULL,
  name TEXT, company_id INTEGER, is_apple INTEGER NOT NULL,
  page_id TEXT,
  latest_payload_id INTEGER NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max INTEGER,
  best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,
  tile17 INTEGER,
  shown INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED,
  UNIQUE (session_id, addr)
);
CREATE INDEX session_devices_order ON session_devices(session_id, rssi_max DESC, addr, shown, page_id);
CREATE INDEX session_devices_addr  ON session_devices(addr);
CREATE VIRTUAL TABLE session_devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE session_device_payloads (
  session_id INTEGER NOT NULL, addr INTEGER NOT NULL, payload_id INTEGER NOT NULL,
  sightings INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
  PRIMARY KEY (session_id, addr, payload_id)
) WITHOUT ROWID;
CREATE INDEX sdp_addr    ON session_device_payloads(addr, payload_id);
CREATE INDEX sdp_payload ON session_device_payloads(payload_id);

CREATE TABLE devices (
  addr INTEGER PRIMARY KEY,
  name TEXT, company_id INTEGER, is_apple INTEGER NOT NULL, page_id TEXT, latest_payload_id INTEGER NOT NULL,
  first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, session_count INTEGER NOT NULL, sightings INTEGER NOT NULL,
  rssi_max INTEGER, best_lon REAL, best_lat REAL, best_accuracy REAL, best_rssi INTEGER, best_seen_ms INTEGER,
  best_session_id INTEGER, tile17 INTEGER,
  shown INTEGER GENERATED ALWAYS AS (NOT (is_apple AND name IS NULL)) STORED
);
CREATE INDEX devices_order ON devices(rssi_max DESC, addr, shown, page_id);
CREATE INDEX devices_page  ON devices(page_id, tile17, shown);
CREATE INDEX devices_named ON devices(name, addr, shown) WHERE name IS NOT NULL;
CREATE VIRTUAL TABLE devices_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat);

CREATE TABLE device_cells (
  z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL,
  n_all INTEGER NOT NULL, n_shown INTEGER NOT NULL, sum_lon REAL NOT NULL, sum_lat REAL NOT NULL,
  PRIMARY KEY (z, x, y)
) WITHOUT ROWID;
CREATE TABLE page_totals (
  page_id TEXT PRIMARY KEY,
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
"#;

pub(crate) fn db_error(error: impl std::fmt::Display) -> CoreError {
    CoreError::Db {
        detail: error.to_string(),
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(error: rusqlite::Error) -> Self {
        if let rusqlite::Error::SqliteFailure(e, _) = &error
            && e.code == rusqlite::ErrorCode::OperationInterrupted
        {
            return CoreError::Cancelled;
        }
        db_error(error)
    }
}

/// The writer: the pragmas of §3 plus [`register`].
fn configure(conn: &Connection) -> Result<(), CoreError> {
    conn.execute_batch(
        "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON; PRAGMA temp_store=MEMORY;",
    )?;
    register(conn)
}

/// `rarray` and the Q-1 `match_device(q, name, addr)` SQL function (`addr` is the integer key).
fn register(conn: &Connection) -> Result<(), CoreError> {
    rusqlite::vtab::array::load_module(conn)?;
    conn.create_scalar_function(
        "match_device",
        3,
        FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC,
        |ctx| {
            let query: String = ctx.get(0)?;
            let name: Option<String> = ctx.get(1)?;
            let key: i64 = ctx.get(2)?;
            Ok(super::search::matches(
                &query,
                name.as_deref(),
                &super::keys::addr_of(key),
            ))
        },
    )?;
    Ok(())
}

fn user_version(conn: &Connection) -> Result<u32, CoreError> {
    Ok(conn.query_row("PRAGMA user_version", [], |r| r.get::<_, u32>(0))?)
}

/// Opens `path` read-write and brings it to [`SCHEMA_VERSION`]. A newer file returns `SchemaTooNew` untouched;
/// a corrupt one is moved aside to `local.db.corrupt-<ms>` (never deleted) and the returned flag is true.
pub(crate) fn open_writer(path: &Path, now_ms: i64) -> Result<(Connection, bool), CoreError> {
    match try_open_writer(path) {
        Ok(conn) => Ok((conn, false)),
        Err(OpenError::Corrupt) => {
            let aside = PathBuf::from(format!("{}.corrupt-{now_ms}", path.display()));
            std::fs::rename(path, &aside).map_err(CoreError::from_io)?;
            for suffix in ["-wal", "-shm"] {
                let side = PathBuf::from(format!("{}{suffix}", path.display()));
                if side.exists() {
                    let to = PathBuf::from(format!("{}{suffix}", aside.display()));
                    std::fs::rename(&side, to).map_err(CoreError::from_io)?;
                }
            }
            let conn = try_open_writer(path).map_err(OpenError::into_core)?;
            Ok((conn, true))
        }
        Err(e) => Err(e.into_core()),
    }
}

enum OpenError {
    Corrupt,
    Core(CoreError),
}

impl OpenError {
    fn into_core(self) -> CoreError {
        match self {
            OpenError::Corrupt => db_error("local.db is corrupt"),
            OpenError::Core(e) => e,
        }
    }
}

impl From<CoreError> for OpenError {
    fn from(e: CoreError) -> Self {
        OpenError::Core(e)
    }
}

fn is_corruption(e: &rusqlite::Error) -> bool {
    matches!(e, rusqlite::Error::SqliteFailure(f, _)
        if matches!(f.code, rusqlite::ErrorCode::DatabaseCorrupt | rusqlite::ErrorCode::NotADatabase))
}

fn try_open_writer(path: &Path) -> Result<Connection, OpenError> {
    let conn = Connection::open(path).map_err(|e| OpenError::Core(e.into()))?;
    let check = conn.query_row("PRAGMA quick_check", [], |r| r.get::<_, String>(0));
    match check {
        Ok(ok) if ok == "ok" => {}
        Ok(_) => return Err(OpenError::Corrupt),
        Err(e) if is_corruption(&e) => return Err(OpenError::Corrupt),
        Err(e) => return Err(OpenError::Core(e.into())),
    }
    let found = user_version(&conn)?;
    if found > SCHEMA_VERSION {
        return Err(OpenError::Core(CoreError::SchemaTooNew {
            found,
            supported: SCHEMA_VERSION,
        }));
    }
    configure(&conn)?;
    migrate(&conn, found)?;
    Ok(conn)
}

fn migrate(conn: &Connection, from: u32) -> Result<(), CoreError> {
    for (i, step) in MIGRATIONS.iter().enumerate().skip(from as usize) {
        let tx = conn.unchecked_transaction()?;
        tx.execute_batch(step)?;
        tx.pragma_update(None, "user_version", i as u32 + 1)?;
        tx.commit()?;
    }
    Ok(())
}

/// A read connection (the writer has already migrated the file).
pub(crate) fn open_reader(path: &Path) -> Result<Connection, CoreError> {
    let conn = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY
            | OpenFlags::SQLITE_OPEN_NO_MUTEX
            | OpenFlags::SQLITE_OPEN_URI,
    )?;
    conn.execute_batch("PRAGMA temp_store=MEMORY;")?;
    register(&conn)?;
    Ok(conn)
}

impl CoreError {
    pub(crate) fn from_io(error: std::io::Error) -> Self {
        CoreError::Io {
            detail: error.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrates_and_has_rtree_and_rarray() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("local.db");
        let (conn, fresh_after_corruption) = open_writer(&path, 1).unwrap();
        assert!(!fresh_after_corruption);
        assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
        conn.execute(
            "INSERT INTO devices_rtree VALUES (1, -117.3, -117.2, 32.8, 32.9)",
            [],
        )
        .unwrap();
        let ids: std::rc::Rc<Vec<rusqlite::types::Value>> =
            std::rc::Rc::new(vec![1i64.into(), 2i64.into()]);
        let n: i64 = conn
            .query_row("SELECT count(*) FROM rarray(?1)", [ids], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 2);
        let hit: bool = conn
            .query_row(
                "SELECT match_device('4b4b', NULL, ?1)",
                [0x72CD4C4B4B6Fi64],
                |r| r.get(0),
            )
            .unwrap();
        assert!(hit);
        drop(conn);
        // reopening is idempotent
        let (conn, _) = open_writer(&path, 2).unwrap();
        assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
        open_reader(&path).unwrap();
    }

    #[test]
    fn newer_schema_is_refused_untouched() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("local.db");
        {
            let conn = Connection::open(&path).unwrap();
            conn.pragma_update(None, "user_version", SCHEMA_VERSION + 1)
                .unwrap();
        }
        let before = std::fs::read(&path).unwrap();
        match open_writer(&path, 1) {
            Err(CoreError::SchemaTooNew { found, supported }) => {
                assert_eq!((found, supported), (SCHEMA_VERSION + 1, SCHEMA_VERSION));
            }
            other => panic!("expected SchemaTooNew, got {:?}", other.map(|_| ())),
        }
        assert_eq!(std::fs::read(&path).unwrap(), before);
    }

    #[test]
    fn corrupt_file_is_moved_aside() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("local.db");
        std::fs::write(&path, vec![0x42; 8192]).unwrap();
        let (conn, corrupt) = open_writer(&path, 77).unwrap();
        assert!(corrupt);
        assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
        let aside = dir.path().join("local.db.corrupt-77");
        assert_eq!(std::fs::read(aside).unwrap(), vec![0x42; 8192]);
    }
}
