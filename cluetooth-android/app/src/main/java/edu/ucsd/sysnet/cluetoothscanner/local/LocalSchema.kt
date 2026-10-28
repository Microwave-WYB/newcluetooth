package edu.ucsd.sysnet.cluetoothscanner.local

/**
 * The phone's local database (`local-data.db`). Nothing in it is uploaded, but not all of it can be
 * rebuilt: recordings, routes and sighting history exist nowhere else on the phone once the scan
 * session has been uploaded. So **schema changes are real migrations ([MIGRATIONS]), never a wipe**:
 * add the statements that take version N to N+1 under key N and bump [VERSION]. An older build
 * opening a newer file fails to open (Android's default `onDowngrade` throws), which the data layer
 * reports as unavailable and retries; the file is deleted only when SQLite says it is corrupt.
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
        // newly_identified rows (and the payloads they point to) are kept until the report is
        // acknowledged or 90 days old, whichever comes first; see LocalStore.pruneStep.
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

    /**
     * Statements that take a file from version `key` to `key + 1`, run in one transaction by
     * SQLiteOpenHelper. Keep old entries forever: a phone can skip releases.
     */
    private val MIGRATIONS: Map<Int, List<String>> = mapOf(
        1 to listOf(
            // The page summary did not exist: rebuild it from the payloads that are still stored.
            """CREATE TABLE seen_product (
                page_id TEXT PRIMARY KEY,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL,
                payload_count INTEGER NOT NULL,
                last_payload_id INTEGER
            )""",
            """INSERT INTO seen_product (page_id, first_seen_ms, last_seen_ms, seen_count, payload_count, last_payload_id)
               SELECT m.page_id, MIN(p.first_seen_ms), MAX(p.last_seen_ms), SUM(p.seen_count), COUNT(*), MAX(p.id)
               FROM payload_match m JOIN seen_payload p ON p.id = m.payload_id GROUP BY m.page_id""",
            // v1 matched under the bundle version; v2 under the rules version, so every payload re-matches once.
            """ALTER TABLE rematch_run ADD COLUMN cursor_id INTEGER NOT NULL DEFAULT 0""",
            """DROP INDEX IF EXISTS seen_payload_match_version""",
            """CREATE INDEX seen_payload_last_seen ON seen_payload(last_seen_ms)""",
            """CREATE INDEX newly_identified_payload ON newly_identified(payload_id)""",
            """CREATE INDEX recording_route_time ON recording_route(time_ms)""",
        ),
    )

    /** The statements that take a file from version [from] to [to]. */
    fun upgrade(from: Int, to: Int = VERSION): List<String> =
        (from until to).flatMap { step ->
            MIGRATIONS[step] ?: throw IllegalStateException("No migration from schema version $step")
        }
}
