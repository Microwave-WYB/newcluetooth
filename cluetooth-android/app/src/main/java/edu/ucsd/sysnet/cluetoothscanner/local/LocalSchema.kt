package edu.ucsd.sysnet.cluetoothscanner.local

/**
 * The phone's local database (`local-data.db`). Nothing in it is uploaded, and
 * all of it can be rebuilt from scanning again: it is a cache. So a schema
 * change that is not trivial wipes and recreates it ([WIPE] then [CREATE]) on
 * upgrade, and so does a downgrade (an older build opening a newer file).
 */
object LocalSchema {
    /** 2: seen_product, rules-version re-match cursor, prune indexes (1 never shipped). */
    const val VERSION = 2

    val CREATE: List<String> = listOf(
        // Key/value bookkeeping: rules_version = the rules the stored matches belong to.
        """CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",

        // One row per distinct advertisement payload; expires 30 days after it was last heard
        // unless a sighting or an unacknowledged re-match still refers to it.
        // match_version = the rules version whose result payload_match holds.
        """CREATE TABLE seen_payload (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            hash TEXT NOT NULL UNIQUE,
            raw BLOB NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            seen_count INTEGER NOT NULL,
            match_version TEXT
        )""",
        """CREATE INDEX seen_payload_last_seen ON seen_payload(last_seen_ms)""",

        // The pages a payload matched (payload-level: address rules cannot match here).
        """CREATE TABLE payload_match (
            payload_id INTEGER NOT NULL,
            page_id TEXT NOT NULL,
            PRIMARY KEY (payload_id, page_id)
        ) WITHOUT ROWID""",
        """CREATE INDEX payload_match_page ON payload_match(page_id, payload_id)""",

        // Per page, forever: "previously seen products" survives payload expiry.
        // payload_count = payload rows ever created for the page (an expired payload that is
        // heard again counts again); last_payload_id may refer to a payload that expired.
        """CREATE TABLE seen_product (
            page_id TEXT PRIMARY KEY,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            seen_count INTEGER NOT NULL,
            payload_count INTEGER NOT NULL,
            last_payload_id INTEGER
        )""",

        // A bundle update's re-match of every stored payload, and what it newly identified.
        // cursor_id: the highest payload id processed (keyset pagination, resumable).
        """CREATE TABLE rematch_run (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            from_version TEXT,
            to_version TEXT NOT NULL,
            started_ms INTEGER NOT NULL,
            finished_ms INTEGER,
            payloads_checked INTEGER NOT NULL DEFAULT 0,
            cursor_id INTEGER NOT NULL DEFAULT 0,
            acknowledged INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE newly_identified (
            run_id INTEGER NOT NULL,
            payload_id INTEGER NOT NULL,
            PRIMARY KEY (run_id, payload_id)
        ) WITHOUT ROWID""",
        """CREATE INDEX newly_identified_payload ON newly_identified(payload_id)""",

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
        """CREATE INDEX recording_route_time ON recording_route(time_ms)""",
    )

    val WIPE: List<String> = listOf(
        "meta", "seen_payload", "payload_match", "seen_product", "rematch_run", "newly_identified",
        "recording", "sighting_minute", "recording_route",
    ).map { "DROP TABLE IF EXISTS $it" }

    /** Statements for opening a file of schema [from] (older or newer): start over. */
    fun reset(): List<String> = WIPE + CREATE
}
