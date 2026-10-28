package edu.ucsd.sysnet.cluetoothscanner.local

import java.security.MessageDigest

private const val MINUTE_MS = 60_000L
const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
private const val META_RULES_VERSION = "rules_version"
private const val REMATCH_BATCH = 500
private const val PRUNE_BATCH = 2_000
private const val KEY_BACKFILL_BATCH = 500
private const val MAX_SEEN_UNKNOWN_GROUPS = 500
private const val PRUNE_SIGHTING_MINUTES = 6L * 60
private const val PRUNE_ROUTE_MS = 6L * 60 * 60 * 1000

/** An unacknowledged re-match report, and the payloads it points to, are kept this long at most. */
const val UNACKNOWLEDGED_REPORT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

/**
 * Payloads that expired: a range on `last_seen_ms` (its index, oldest first, stops at the limit), not
 * referenced by a sighting or by a report nobody acknowledged yet.
 */
internal const val EXPIRED_PAYLOADS_SQL =
    "SELECT id FROM seen_payload WHERE last_seen_ms < ? " +
        "AND NOT EXISTS (SELECT 1 FROM sighting_minute s WHERE s.payload_id = seen_payload.id) " +
        "AND NOT EXISTS (SELECT 1 FROM newly_identified n JOIN rematch_run r ON r.id = n.run_id " +
        "WHERE n.payload_id = seen_payload.id AND r.acknowledged = 0) " +
        "ORDER BY last_seen_ms LIMIT ?"

/** A payload heard since the last flush. */
class PayloadDelta(val raw: ByteArray, var firstSeenMs: Long, var lastSeenMs: Long, var count: Long)

/** The best sample of one minute for one (address, payload). See [MinuteBest.isBetterThan]. */
class MinuteBest(val rssi: Int?, val location: GeoPoint?) {
    /**
     * Among samples with a fix the strongest wins; a sample with a fix beats any
     * without; without any fix the strongest wins. Ties keep the earlier sample.
     */
    fun isBetterThan(current: MinuteBest): Boolean = when {
        (location != null) != (current.location != null) -> location != null
        else -> rssi != null && (current.rssi == null || rssi > current.rssi)
    }
}

class MinuteKey(val addr: String, val minute: Long, val payloadHash: String) {
    override fun equals(other: Any?) =
        other is MinuteKey && addr == other.addr && minute == other.minute && payloadHash == other.payloadHash
    override fun hashCode() = (addr.hashCode() * 31 + minute.hashCode()) * 31 + payloadHash.hashCode()
}

class MinuteDelta(var firstMs: Long, var lastMs: Long, var samples: Int, var best: MinuteBest) {
    fun add(timeMs: Long, rssi: Int?, location: GeoPoint?) {
        samples++
        firstMs = minOf(firstMs, timeMs)
        lastMs = maxOf(lastMs, timeMs)
        val candidate = MinuteBest(rssi, location)
        if (candidate.isBetterThan(best)) best = candidate
    }
}

/** Everything the hub buffered since the previous flush. */
class FlushBatch(
    val payloads: Map<String, PayloadDelta>,
    val minutes: Map<MinuteKey, MinuteDelta>,
    val recordingId: String?,
    val route: List<RoutePoint>,
    val distanceMeters: Double,
)

private val HEX = "0123456789abcdef".toCharArray()
private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

/** SHA-256 as 64 lowercase hex characters. */
fun sha256Hex(raw: ByteArray): String {
    val digest = SHA256.get()!!.digest(raw)
    val out = CharArray(digest.size * 2)
    for (i in digest.indices) {
        val b = digest[i].toInt()
        out[2 * i] = HEX[(b shr 4) and 0xF]
        out[2 * i + 1] = HEX[b and 0xF]
    }
    return String(out)
}

/**
 * All SQL of the local database. Synchronous; the hub serializes writers and runs
 * queries on a background dispatcher. SQL stays within SQLite 3.9 (API 24).
 */
class LocalStore(private val db: SqlDatabase) {
    /** Rows the latest [pruneStep] deleted; the caller bumps its change signals only when it is above zero. */
    var lastPruneDeleted: Int = 0
        private set

    // Expiry ---------------------------------------------------------------------------------

    /**
     * One bounded step of expiry (each step is one short transaction): sighting history, routes,
     * recordings and payloads older than 30 days. Returns true while more is left to delete, so the
     * caller can interleave other work between steps.
     *
     * A payload expires once nobody heard it for 30 days, matched or not, unless a sighting or a
     * re-match report nobody acknowledged yet (90 days at most) still refers to it. [seen_product] keeps what "previously seen
     * products" needs, so rotating payloads (beacons that change their advertisement every few
     * minutes) cost 30 days of rows, not forever.
     */
    fun pruneStep(nowMs: Long): Boolean {
        var deleted = 0
        val more = db.transaction { pruneStepIn(nowMs) { deleted += it } }
        lastPruneDeleted = deleted
        return more
    }

    private fun pruneStepIn(nowMs: Long, deleted: (Int) -> Unit): Boolean {
        val cutoffMs = nowMs - RETENTION_MS
        val cutoffMinute = cutoffMs / MINUTE_MS
        var more = false

        val oldestMinute = db.query("SELECT MIN(minute) FROM sighting_minute") { it.longOrNull(0) }.firstOrNull()
        if (oldestMinute != null && oldestMinute < cutoffMinute) {
            val upTo = minOf(cutoffMinute, oldestMinute + PRUNE_SIGHTING_MINUTES)
            deleted(db.execute("DELETE FROM sighting_minute WHERE minute < ?", listOf(upTo)))
            if (upTo < cutoffMinute) more = true
        }
        val oldestRoute = db.query("SELECT MIN(time_ms) FROM recording_route") { it.longOrNull(0) }.firstOrNull()
        if (oldestRoute != null && oldestRoute < cutoffMs) {
            val upTo = minOf(cutoffMs, oldestRoute + PRUNE_ROUTE_MS)
            deleted(db.execute("DELETE FROM recording_route WHERE time_ms < ?", listOf(upTo)))
            if (upTo < cutoffMs) more = true
        }
        deleted(
            db.execute(
                "DELETE FROM recording WHERE COALESCE(ended_ms, started_ms) < ? AND " +
                    "NOT EXISTS (SELECT 1 FROM sighting_minute s WHERE s.recording_id = recording.id)",
                listOf(cutoffMs),
            ),
        )
        // A note outlives nothing: it goes with its recording (an orphan only once it is itself old).
        deleted(
            db.execute(
                "DELETE FROM recording_note WHERE updated_ms < ? AND " +
                    "NOT EXISTS (SELECT 1 FROM recording r WHERE r.id = recording_note.recording_id)",
                listOf(cutoffMs),
            ),
        )
        // Reports: acknowledged ones are only history (30 days); unacknowledged ones stay until
        // acknowledged, but at most 90 days.
        val staleReports = "SELECT id FROM rematch_run WHERE finished_ms IS NOT NULL AND " +
            "((acknowledged = 1 AND started_ms < ?) OR started_ms < ?)"
        val staleArgs = listOf(cutoffMs, nowMs - UNACKNOWLEDGED_REPORT_RETENTION_MS)
        deleted(db.execute("DELETE FROM newly_identified WHERE run_id IN ($staleReports)", staleArgs))
        deleted(db.execute("DELETE FROM rematch_run WHERE id IN ($staleReports)", staleArgs))

        val expired = db.query(EXPIRED_PAYLOADS_SQL, listOf(cutoffMs, PRUNE_BATCH)) { it.long(0) }
        if (expired.isNotEmpty()) {
            // ids come from our own query: safe to inline
            val ids = expired.joinToString(",")
            db.execute("DELETE FROM payload_match WHERE payload_id IN ($ids)")
            deleted(db.execute("DELETE FROM seen_payload WHERE id IN ($ids)"))
            if (expired.size == PRUNE_BATCH) more = true
        }
        return more
    }

    /** Runs [pruneStep] until nothing is left; for tests and startup-style callers. */
    fun pruneExpired(nowMs: Long) {
        while (pruneStep(nowMs)) Unit
    }

    /** A recording the process died in has no end; it ended at the last thing recorded for it. */
    fun closeOrphanRecordings() {
        val orphans = db.query("SELECT id, started_ms FROM recording WHERE ended_ms IS NULL") {
            it.string(0) to it.long(1)
        }
        for ((id, startedMs) in orphans) {
            val lastSighting = db.query("SELECT MAX(last_ms) FROM sighting_minute WHERE recording_id = ?", listOf(id)) {
                it.longOrNull(0)
            }.firstOrNull()
            val lastRoute = db.query("SELECT MAX(time_ms) FROM recording_route WHERE recording_id = ?", listOf(id)) {
                it.longOrNull(0)
            }.firstOrNull()
            val endedMs = listOfNotNull(lastSighting, lastRoute, startedMs).max()
            db.execute("UPDATE recording SET ended_ms = ? WHERE id = ?", listOf(endedMs, id))
            refreshDeviceCount(id)
        }
    }

    // Re-match on a rules change -----------------------------------------------------------------

    /**
     * Starts (or resumes) the re-match of the stored payloads whose matches were made under other
     * rules than [rulesVersion]. Returns the run id, or null when there is nothing to do.
     * Then call [rematchBatch] until it returns false.
     */
    fun beginRematch(rulesVersion: String, nowMs: Long): Long? {
        // Stale = stored under other rules (or none, e.g. heard while the bundle failed to load).
        val stale = db.query(
            "SELECT 1 FROM seen_payload WHERE match_version IS NULL OR match_version != ? LIMIT 1",
            listOf(rulesVersion),
        ) { it.long(0) }.isNotEmpty()
        val stored = meta(META_RULES_VERSION)
        if (!stale) {
            if (stored != rulesVersion) setMeta(META_RULES_VERSION, rulesVersion)
            return null
        }
        val resumed = db.query(
            "SELECT id FROM rematch_run WHERE to_version = ? AND finished_ms IS NULL ORDER BY id DESC LIMIT 1",
            listOf(rulesVersion),
        ) { it.long(0) }.firstOrNull()
        return resumed ?: db.insert(
            "INSERT INTO rematch_run (from_version, to_version, started_ms) VALUES (?, ?, ?)",
            listOf(stored, rulesVersion, nowMs),
        )
    }

    /**
     * Re-matches the next batch of payloads (keyset pagination on the run's cursor) and records the
     * ones that went from unmatched to matched. Matching happens before the write, so a matcher
     * failure leaves the batch untouched and the run resumable. Returns false when the run is done.
     */
    fun rematchBatch(matcher: AdvertisementMatcher, rulesVersion: String, runId: Long, nowMs: Long): Boolean {
        class Row(val id: Long, val raw: ByteArray, val firstMs: Long, val lastMs: Long, val seen: Long)
        val cursor = db.query("SELECT cursor_id FROM rematch_run WHERE id = ?", listOf(runId)) { it.long(0) }.first()
        val batch = db.query(
            "SELECT id, raw, first_seen_ms, last_seen_ms, seen_count FROM seen_payload " +
                "WHERE id > ? AND (match_version IS NULL OR match_version != ?) ORDER BY id LIMIT ?",
            listOf(cursor, rulesVersion, REMATCH_BATCH),
        ) { Row(it.long(0), it.blob(1), it.long(2), it.long(3), it.long(4)) }
        if (batch.isEmpty()) {
            db.transaction {
                db.execute("UPDATE rematch_run SET finished_ms = ? WHERE id = ?", listOf(nowMs, runId))
                setMeta(META_RULES_VERSION, rulesVersion)
            }
            return false
        }
        val after = batch.map { matcher.match(it.raw) }
        db.transaction {
            val before = pagesOfPayloads(batch.map { it.id })
            batch.forEachIndexed { i, row ->
                val now = after[i]
                val was = before[row.id].orEmpty()
                db.execute("DELETE FROM payload_match WHERE payload_id = ?", listOf(row.id))
                now.forEach { page ->
                    db.execute("INSERT OR IGNORE INTO payload_match (payload_id, page_id) VALUES (?, ?)", listOf(row.id, page))
                }
                db.execute("UPDATE seen_payload SET match_version = ? WHERE id = ?", listOf(rulesVersion, row.id))
                if (was.isEmpty() && now.isNotEmpty()) {
                    db.execute("INSERT OR IGNORE INTO newly_identified (run_id, payload_id) VALUES (?, ?)", listOf(runId, row.id))
                }
                // the page summary follows the payload: it counts a page the payload now matches and
                // gives back what a page it no longer matches had counted (so A -> B -> A nets to A)
                (now - was.toSet()).forEach { page -> addToProduct(page, row.firstMs, row.lastMs, row.seen, 1, row.id) }
                (was - now.toSet()).forEach { page -> removeFromProduct(page, row.seen, row.id) }
            }
            db.execute(
                "UPDATE rematch_run SET payloads_checked = payloads_checked + ?, cursor_id = ? WHERE id = ?",
                listOf(batch.size, batch.last().id, runId),
            )
        }
        return true
    }

    /** The whole re-match in one call; returns the run id, or null when the rules did not change. */
    fun rematchAll(matcher: AdvertisementMatcher, rulesVersion: String, nowMs: Long): Long? {
        val run = beginRematch(rulesVersion, nowMs) ?: return null
        while (rematchBatch(matcher, rulesVersion, run, nowMs)) Unit
        return run
    }

    private fun meta(key: String): String? =
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)) { it.string(0) }.firstOrNull()

    private fun setMeta(key: String, value: String) {
        db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", listOf(key, value))
    }

    // Structure keys ---------------------------------------------------------------------------------

    /**
     * Gives the payloads that predate schema version 3 their [StructureKey] (the migration can only add
     * the column). Visits at most [limit] rows with an id above [afterId] and returns the highest id seen,
     * or null when there is nothing left; call again with that id until null. New rows get their key on insert.
     */
    fun backfillStructureKeys(afterId: Long, limit: Int = KEY_BACKFILL_BATCH): Long? {
        class Row(val id: Long, val raw: ByteArray)
        val rows = db.query(
            "SELECT id, raw FROM seen_payload WHERE id > ? AND structure_key IS NULL ORDER BY id LIMIT ?",
            listOf(afterId, limit),
        ) { Row(it.long(0), it.blob(1)) }
        if (rows.isEmpty()) return null
        db.transaction {
            rows.forEach { db.execute("UPDATE seen_payload SET structure_key = ? WHERE id = ?", listOf(StructureKey.of(it.raw).key, it.id)) }
        }
        return rows.last().id
    }

    // Recording notes ------------------------------------------------------------------------------------

    /** The note of a recording, or "" when there is none. */
    fun recordingNote(recordingId: String): String =
        db.query("SELECT note FROM recording_note WHERE recording_id = ?", listOf(recordingId)) { it.string(0) }.firstOrNull().orEmpty()

    /** Replaces the note; a blank one deletes it. */
    fun setRecordingNote(recordingId: String, note: String, nowMs: Long) {
        if (note.isBlank()) {
            db.execute("DELETE FROM recording_note WHERE recording_id = ?", listOf(recordingId))
        } else {
            db.execute(
                "INSERT OR REPLACE INTO recording_note (recording_id, note, updated_ms) VALUES (?, ?, ?)",
                listOf(recordingId, note, nowMs),
            )
        }
    }

    // Recording lifecycle -----------------------------------------------------------------------

    fun startRecording(id: String, startedMs: Long) {
        db.execute("INSERT OR IGNORE INTO recording (id, started_ms) VALUES (?, ?)", listOf(id, startedMs))
    }

    fun finishRecording(id: String, endedMs: Long, distanceMeters: Double) {
        db.execute("UPDATE recording SET ended_ms = ?, distance_m = ? WHERE id = ?", listOf(endedMs, distanceMeters, id))
        refreshDeviceCount(id)
    }

    /** Counts the recording's distinct addresses. Its own statement: never inside a write batch. */
    fun refreshDeviceCount(recordingId: String) {
        db.execute(
            "UPDATE recording SET device_count = " +
                "(SELECT COUNT(*) FROM (SELECT DISTINCT addr FROM sighting_minute WHERE recording_id = ?)) WHERE id = ?",
            listOf(recordingId, recordingId),
        )
    }

    // The hot path --------------------------------------------------------------------------------

    /**
     * Writes a batch in one transaction. [payloadIds] caches hash -> row id across flushes; it is
     * updated only after the commit, because a rolled-back insert hands its AUTOINCREMENT id out
     * again. [matchesOf] gives the pages of a new payload and [matchVersion] the rules they come from.
     */
    fun flush(
        batch: FlushBatch,
        payloadIds: MutableMap<String, Long>,
        matchVersion: String,
        matchesOf: (hash: String, raw: ByteArray) -> List<String>,
    ) {
        val resolved = HashMap<String, Long>()
        db.transaction {
            val existing = HashMap<String, Long>()
            for (hash in batch.payloads.keys) {
                val id = payloadIds[hash]
                    ?: db.query("SELECT id FROM seen_payload WHERE hash = ?", listOf(hash)) { it.long(0) }.firstOrNull()
                if (id != null) existing[hash] = id
            }
            val knownPages = pagesOfPayloads(existing.values.toList())
            for ((hash, delta) in batch.payloads) {
                val known = existing[hash]
                if (known == null) {
                    val id = db.insert(
                        "INSERT INTO seen_payload (hash, raw, first_seen_ms, last_seen_ms, seen_count, match_version, structure_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        listOf(hash, delta.raw, delta.firstSeenMs, delta.lastSeenMs, delta.count, matchVersion, StructureKey.of(delta.raw).key),
                    )
                    val pages = matchesOf(hash, delta.raw)
                    pages.forEach {
                        db.execute("INSERT OR IGNORE INTO payload_match (payload_id, page_id) VALUES (?, ?)", listOf(id, it))
                        addToProduct(it, delta.firstSeenMs, delta.lastSeenMs, delta.count, 1, id)
                    }
                    resolved[hash] = id
                } else {
                    db.execute(
                        "UPDATE seen_payload SET seen_count = seen_count + ?, " +
                            "first_seen_ms = CASE WHEN first_seen_ms < ? THEN first_seen_ms ELSE ? END, " +
                            "last_seen_ms = CASE WHEN last_seen_ms > ? THEN last_seen_ms ELSE ? END WHERE id = ?",
                        listOf(delta.count, delta.firstSeenMs, delta.firstSeenMs, delta.lastSeenMs, delta.lastSeenMs, known),
                    )
                    knownPages[known].orEmpty().forEach {
                        addToProduct(it, delta.firstSeenMs, delta.lastSeenMs, delta.count, 0, known)
                    }
                    resolved[hash] = known
                }
            }
            val recordingId = batch.recordingId
            if (recordingId != null) {
                for ((key, delta) in batch.minutes) {
                    val payloadId = resolved[key.payloadHash] ?: payloadIds[key.payloadHash]
                        ?: db.query("SELECT id FROM seen_payload WHERE hash = ?", listOf(key.payloadHash)) { it.long(0) }.first()
                    writeMinute(recordingId, key, payloadId, delta)
                }
                for (p in batch.route) {
                    db.execute(
                        "INSERT OR IGNORE INTO recording_route (recording_id, time_ms, lat, lon, accuracy) VALUES (?, ?, ?, ?, ?)",
                        listOf(recordingId, p.timeMs, p.lat, p.lon, p.accuracyMeters),
                    )
                }
                db.execute("UPDATE recording SET distance_m = ? WHERE id = ?", listOf(batch.distanceMeters, recordingId))
            }
        }
        payloadIds.putAll(resolved) // committed
    }

    /** Adds heard payload stats to a page's summary (no UPSERT before SQLite 3.24: insert, else update). */
    private fun addToProduct(page: String, firstMs: Long, lastMs: Long, count: Long, payloads: Int, payloadId: Long) {
        val inserted = db.execute(
            "INSERT OR IGNORE INTO seen_product (page_id, first_seen_ms, last_seen_ms, seen_count, payload_count, last_payload_id) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            listOf(page, firstMs, lastMs, count, payloads, payloadId),
        )
        if (inserted > 0) return
        db.execute(
            "UPDATE seen_product SET seen_count = seen_count + ?, payload_count = payload_count + ?, " +
                "last_payload_id = CASE WHEN ? >= last_seen_ms THEN ? ELSE last_payload_id END, " +
                "first_seen_ms = CASE WHEN first_seen_ms < ? THEN first_seen_ms ELSE ? END, " +
                "last_seen_ms = CASE WHEN last_seen_ms > ? THEN last_seen_ms ELSE ? END WHERE page_id = ?",
            listOf(count, payloads, lastMs, payloadId, firstMs, firstMs, lastMs, lastMs, page),
        )
    }

    /**
     * Gives back one payload's counts to a page it stopped matching. First/last seen stay (they cannot be
     * un-merged); a page left with no payloads loses its summary row.
     */
    private fun removeFromProduct(page: String, count: Long, payloadId: Long) {
        db.execute(
            "UPDATE seen_product SET seen_count = CASE WHEN seen_count > ? THEN seen_count - ? ELSE 0 END, " +
                "payload_count = payload_count - 1, " +
                "last_payload_id = CASE WHEN last_payload_id = ? THEN NULL ELSE last_payload_id END WHERE page_id = ?",
            listOf(count, count, payloadId, page),
        )
        db.execute("DELETE FROM seen_product WHERE page_id = ? AND payload_count <= 0", listOf(page))
    }

    private fun writeMinute(recordingId: String, key: MinuteKey, payloadId: Long, d: MinuteDelta) {
        val pk = listOf(recordingId, key.addr, key.minute, payloadId)
        class Existing(val rssi: Int?, val location: GeoPoint?)
        val existing = db.query(
            "SELECT best_rssi, lat, lon, accuracy FROM sighting_minute WHERE recording_id = ? AND addr = ? AND minute = ? AND payload_id = ?",
            pk,
        ) { r ->
            Existing(
                r.longOrNull(0)?.toInt(),
                r.doubleOrNull(1)?.let { GeoPoint(it, r.double(2), r.doubleOrNull(3)) },
            )
        }.firstOrNull()
        if (existing == null) {
            val loc = d.best.location
            db.execute(
                "INSERT INTO sighting_minute (recording_id, addr, minute, payload_id, first_ms, last_ms, best_rssi, lat, lon, accuracy, sample_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pk + listOf(d.firstMs, d.lastMs, d.best.rssi, loc?.lat, loc?.lon, loc?.accuracyMeters, d.samples),
            )
            return
        }
        val takeNew = d.best.isBetterThan(MinuteBest(existing.rssi, existing.location))
        val best = if (takeNew) d.best else MinuteBest(existing.rssi, existing.location)
        val loc = best.location
        db.execute(
            "UPDATE sighting_minute SET sample_count = sample_count + ?, " +
                "first_ms = CASE WHEN first_ms < ? THEN first_ms ELSE ? END, " +
                "last_ms = CASE WHEN last_ms > ? THEN last_ms ELSE ? END, " +
                "best_rssi = ?, lat = ?, lon = ?, accuracy = ? " +
                "WHERE recording_id = ? AND addr = ? AND minute = ? AND payload_id = ?",
            listOf(d.samples, d.firstMs, d.firstMs, d.lastMs, d.lastMs, best.rssi, loc?.lat, loc?.lon, loc?.accuracyMeters) + pk,
        )
    }

    // Queries -------------------------------------------------------------------------------------

    /** Every product ever seen, from the per-page summary (survives payload expiry). */
    fun seenProducts(catalog: WikiCatalog): List<SeenProduct> = db.query(
        "SELECT page_id, first_seen_ms, last_seen_ms, payload_count, seen_count FROM seen_product",
    ) { r ->
        catalog.page(r.string(0))?.let { page -> SeenProduct(page, r.long(1), r.long(2), r.long(3).toInt(), r.long(4)) }
    }.filterNotNull()

    /**
     * Stored payloads no page matches, grouped by structure key, most recently heard first. Payloads without
     * a key yet (pre-version-3 rows before the backfill reached them) are not listed.
     */
    fun seenUnknownStructures(): List<SeenUnknown> = db.query(
        "SELECT structure_key, COUNT(*), SUM(seen_count), MIN(first_seen_ms), MAX(last_seen_ms) FROM seen_payload p " +
            "WHERE structure_key IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payload_match m WHERE m.payload_id = p.id) " +
            "GROUP BY structure_key ORDER BY MAX(last_seen_ms) DESC, structure_key LIMIT ?",
        listOf(MAX_SEEN_UNKNOWN_GROUPS),
    ) { r ->
        StructureKey.parse(r.string(0))?.let { SeenUnknown(it, r.long(1).toInt(), r.long(2), r.long(3), r.long(4)) }
    }.filterNotNull()

    fun recordings(nowMs: Long): List<Recording> = db.query(
        "SELECT id, started_ms, ended_ms, distance_m, device_count FROM recording ORDER BY started_ms DESC",
    ) { it.toRecording(nowMs) }

    fun recording(id: String, nowMs: Long): Recording? = db.query(
        "SELECT id, started_ms, ended_ms, distance_m, device_count FROM recording WHERE id = ?", listOf(id),
    ) { it.toRecording(nowMs) }.firstOrNull()

    private fun SqlRow.toRecording(nowMs: Long): Recording {
        val start = long(1)
        val end = longOrNull(2)
        return Recording(string(0), start, end, ((end ?: nowMs) - start).coerceAtLeast(0), double(3), long(4).toInt(), end == null)
    }

    fun devicesInRecording(recordingId: String): List<RecordedDevice> {
        val pages = db.query(
            "SELECT DISTINCT s.addr, m.page_id FROM sighting_minute s JOIN payload_match m ON m.payload_id = s.payload_id " +
                "WHERE s.recording_id = ? ORDER BY m.page_id",
            listOf(recordingId),
        ) { it.string(0) to it.string(1) }.groupBy({ it.first }, { it.second })
        // SQLite returns the other columns of the row holding MAX(best_rssi)
        val located = db.query(
            "SELECT addr, lat, lon, accuracy, MAX(best_rssi) FROM sighting_minute " +
                "WHERE recording_id = ? AND lat IS NOT NULL GROUP BY addr",
            listOf(recordingId),
        ) { r -> r.string(0) to GeoPoint(r.double(1), r.double(2), r.doubleOrNull(3)) }.toMap()
        return db.query(
            "SELECT addr, MIN(first_ms), MAX(last_ms), MAX(best_rssi), COUNT(DISTINCT minute) FROM sighting_minute " +
                "WHERE recording_id = ? GROUP BY addr ORDER BY MAX(best_rssi) DESC, addr",
            listOf(recordingId),
        ) { r ->
            val addr = r.string(0)
            RecordedDevice(addr, r.long(1), r.long(2), r.longOrNull(3)?.toInt(), r.long(4).toInt(), pages[addr].orEmpty(), located[addr])
        }
    }

    fun route(recordingId: String): List<RoutePoint> = db.query(
        "SELECT time_ms, lat, lon, accuracy FROM recording_route WHERE recording_id = ? ORDER BY time_ms",
        listOf(recordingId),
    ) { RoutePoint(it.long(0), it.double(1), it.double(2), it.double(3)) }

    fun sightingsInRecording(recordingId: String, bounds: GeoBounds?, limit: Int): List<Sighting> {
        if (bounds == null) return sightings("WHERE s.recording_id = ?", listOf(recordingId), limit)
        // lat/lon compare as numbers: a text argument takes the column's REAL affinity
        val lon = if (bounds.crossesAntimeridian) "(s.lon >= ? OR s.lon <= ?)" else "(s.lon >= ? AND s.lon <= ?)"
        return sightings(
            "WHERE s.recording_id = ? AND s.lat >= ? AND s.lat <= ? AND $lon",
            listOf(recordingId, bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon),
            limit,
        )
    }

    fun sightingsOfDevice(addr: String, limit: Int): List<Sighting> = sightings(
        "WHERE s.addr = ?", listOf(addr.uppercase()), limit,
    )

    fun sightingsOfProduct(pageId: String, limit: Int): List<Sighting> = sightings(
        "WHERE s.payload_id IN (SELECT payload_id FROM payload_match WHERE page_id = ?)", listOf(pageId), limit,
    )

    private fun sightings(where: String, args: List<Any?>, limit: Int): List<Sighting> {
        class Row(val payloadId: Long, val sighting: Sighting)
        val rows = db.query(
            "SELECT s.minute, s.addr, p.hash, s.recording_id, s.best_rssi, s.lat, s.lon, s.accuracy, s.sample_count, s.payload_id " +
                "FROM sighting_minute s JOIN seen_payload p ON p.id = s.payload_id $where ORDER BY s.minute DESC, s.addr LIMIT ?",
            args + limit,
        ) { r ->
            Row(
                r.long(9),
                Sighting(
                    minuteMs = r.long(0) * MINUTE_MS,
                    addr = r.string(1),
                    payloadHash = r.string(2),
                    recordingId = r.string(3),
                    bestRssi = r.longOrNull(4)?.toInt(),
                    location = r.doubleOrNull(5)?.let { GeoPoint(it, r.double(6), r.doubleOrNull(7)) },
                    sampleCount = r.long(8).toInt(),
                    pageIds = emptyList(),
                ),
            )
        }
        val pages = pagesOfPayloads(rows.map { it.payloadId })
        return rows.map { it.sighting.copy(pageIds = pages[it.payloadId].orEmpty()) }
    }

    /** payload id -> matched page ids (sorted), for the given payloads. */
    private fun pagesOfPayloads(payloadIds: Collection<Long>): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        for (chunk in payloadIds.toSet().chunked(500)) {
            // ids are numbers from our own query: safe to inline
            db.query(
                "SELECT payload_id, page_id FROM payload_match WHERE payload_id IN (${chunk.joinToString(",")}) ORDER BY page_id",
            ) { it.long(0) to it.string(1) }.forEach { (id, page) -> out.getOrPut(id) { mutableListOf() } += page }
        }
        return out
    }

    fun pendingRematchReport(): RematchReport? {
        data class Run(val id: Long, val from: String?, val to: String, val at: Long, val checked: Int, val ack: Boolean)
        val run = db.query(
            "SELECT r.id, r.from_version, r.to_version, r.finished_ms, r.payloads_checked, r.acknowledged FROM rematch_run r " +
                "WHERE r.finished_ms IS NOT NULL AND r.acknowledged = 0 " +
                "AND EXISTS (SELECT 1 FROM newly_identified n WHERE n.run_id = r.id) ORDER BY r.id DESC LIMIT 1",
        ) { Run(it.long(0), it.stringOrNull(1), it.string(2), it.long(3), it.long(4).toInt(), it.long(5) != 0L) }.firstOrNull()
            ?: return null
        class Row(val payloadId: Long, val hash: String, val seenCount: Long)
        val rows = db.query(
            "SELECT p.id, p.hash, p.seen_count FROM newly_identified n JOIN seen_payload p ON p.id = n.payload_id " +
                "WHERE n.run_id = ? ORDER BY p.seen_count DESC, p.id",
            listOf(run.id),
        ) { Row(it.long(0), it.string(1), it.long(2)) }
        val pages = pagesOfPayloads(rows.map { it.payloadId })
        return RematchReport(
            run.id, run.from, run.to, run.at, run.checked,
            rows.map { NewlyIdentified(it.hash, pages[it.payloadId].orEmpty(), it.seenCount) },
            run.ack,
        )
    }

    fun acknowledgeRematch(reportId: Long) {
        db.execute("UPDATE rematch_run SET acknowledged = 1 WHERE id = ?", listOf(reportId))
    }
}
