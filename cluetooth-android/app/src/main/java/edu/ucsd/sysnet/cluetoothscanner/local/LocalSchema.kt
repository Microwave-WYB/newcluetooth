package edu.ucsd.sysnet.cluetoothscanner.local

/**
 * The phone's local database (`local-data.db`). Nothing in it is uploaded.
 *
 * Plain SQLite with `PRAGMA user_version` migrations; add a statement list per
 * version in [upgrade] and bump [VERSION].
 */
object LocalSchema {
    const val VERSION = 1

    val CREATE: List<String> = listOf(
        // Key/value bookkeeping: bundle_version = the bundle the stored matches belong to.
        """CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",

        // One row per distinct advertisement payload, kept across sessions.
        // match_version = the bundle version whose result payload_match holds.
        """CREATE TABLE seen_payload (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            hash TEXT NOT NULL UNIQUE,
            raw BLOB NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            seen_count INTEGER NOT NULL,
            match_version TEXT
        )""",
        """CREATE INDEX seen_payload_match_version ON seen_payload(match_version)""",

        // The pages a payload matched (payload-level: address rules cannot match here).
        """CREATE TABLE payload_match (
            payload_id INTEGER NOT NULL,
            page_id TEXT NOT NULL,
            PRIMARY KEY (payload_id, page_id)
        ) WITHOUT ROWID""",
        """CREATE INDEX payload_match_page ON payload_match(page_id, payload_id)""",

        // A bundle update's re-match of every stored payload, and what it newly identified.
        """CREATE TABLE rematch_run (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            from_version TEXT,
            to_version TEXT NOT NULL,
            started_ms INTEGER NOT NULL,
            finished_ms INTEGER,
            payloads_checked INTEGER NOT NULL DEFAULT 0,
            acknowledged INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE newly_identified (
            run_id INTEGER NOT NULL,
            payload_id INTEGER NOT NULL,
            PRIMARY KEY (run_id, payload_id)
        ) WITHOUT ROWID""",

        // A recording is a core scan session.
        """CREATE TABLE recording (
            id TEXT PRIMARY KEY,
            started_ms INTEGER NOT NULL,
            ended_ms INTEGER,
            distance_m REAL NOT NULL DEFAULT 0,
            device_count INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE INDEX recording_started ON recording(started_ms)""",

        // Per address, minute and payload: the strongest sample, with where it was heard.
        // Among samples with a fix the strongest wins; a minute without any fix keeps the strongest.
        """CREATE TABLE sighting_minute (
            recording_id TEXT NOT NULL,
            addr TEXT NOT NULL,
            minute INTEGER NOT NULL,
            payload_id INTEGER NOT NULL,
            first_ms INTEGER NOT NULL,
            last_ms INTEGER NOT NULL,
            best_rssi INTEGER,
            lat REAL,
            lon REAL,
            accuracy REAL,
            sample_count INTEGER NOT NULL,
            PRIMARY KEY (recording_id, addr, minute, payload_id)
        ) WITHOUT ROWID""",
        """CREATE INDEX sighting_addr ON sighting_minute(addr, minute)""",
        """CREATE INDEX sighting_payload ON sighting_minute(payload_id, minute)""",
        """CREATE INDEX sighting_minute_time ON sighting_minute(minute)""",

        """CREATE TABLE recording_route (
            recording_id TEXT NOT NULL,
            time_ms INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            accuracy REAL NOT NULL,
            PRIMARY KEY (recording_id, time_ms)
        ) WITHOUT ROWID""",
    )

    fun upgrade(from: Int): List<String> {
        require(from in 1 until VERSION) { "No migration from schema version $from" }
        return emptyList()
    }
}
