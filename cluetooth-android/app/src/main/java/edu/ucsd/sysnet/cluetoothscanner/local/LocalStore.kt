package edu.ucsd.sysnet.cluetoothscanner.local

import java.security.MessageDigest

private const val MINUTE_MS = 60_000L
const val SIGHTING_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
private const val META_BUNDLE_VERSION = "bundle_version"
private const val REMATCH_BATCH = 500

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
    val refreshDeviceCount: Boolean,
)

fun sha256Hex(raw: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

/**
 * All SQL of the local database. Synchronous; the hub serializes writers and runs
 * queries on a background dispatcher.
 */
class LocalStore(private val db: SqlDatabase) {
    // Startup -----------------------------------------------------------------------------

    /**
     * Drops sighting history, routes and recordings older than 30 days, and
     * unmatched payloads nobody references and nobody heard for 30 days. Matched
     * payloads stay: they are the "previously seen products".
     */
    fun pruneExpired(nowMs: Long) = db.transaction {
        val cutoffMs = nowMs - SIGHTING_RETENTION_MS
        val cutoffMinute = cutoffMs / MINUTE_MS
        db.execute("DELETE FROM sighting_minute WHERE minute < ?", listOf(cutoffMinute))
        db.execute("DELETE FROM recording_route WHERE time_ms < ?", listOf(cutoffMs))
        db.execute(
            "DELETE FROM recording WHERE COALESCE(ended_ms, started_ms) < ? AND " +
                "NOT EXISTS (SELECT 1 FROM sighting_minute s WHERE s.recording_id = recording.id)",
            listOf(cutoffMs),
        )
        db.execute(
            "DELETE FROM seen_payload WHERE last_seen_ms < ? " +
                "AND NOT EXISTS (SELECT 1 FROM payload_match m WHERE m.payload_id = seen_payload.id) " +
                "AND NOT EXISTS (SELECT 1 FROM sighting_minute s WHERE s.payload_id = seen_payload.id) " +
                "AND NOT EXISTS (SELECT 1 FROM newly_identified n WHERE n.payload_id = seen_payload.id)",
            listOf(cutoffMs),
        )
    }

    /** A recording the process died in has no end; it ended at the last thing recorded for it. */
    fun closeOrphanRecordings() = db.transaction {
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

    /**
     * Re-matches every stored payload when [matcher]'s bundle differs from the one the
     * stored matches belong to, recording which payloads went from unmatched to matched.
     * Resumable: payloads already at the new version are skipped, and an unfinished run
     * for the same target version is continued. Returns the run id, or null when nothing ran.
     */
    fun rematchIfNeeded(matcher: AdvertisementMatcher, nowMs: Long): Long? {
        val target = matcher.bundleVersion
        val stored = meta(META_BUNDLE_VERSION)
        if (stored == target) return null
        val hasPayloads = db.query("SELECT 1 FROM seen_payload LIMIT 1") { it.long(0) }.isNotEmpty()
        if (!hasPayloads) {
            setMeta(META_BUNDLE_VERSION, target)
            return null
        }
        val resumed = db.query(
            "SELECT id FROM rematch_run WHERE to_version = ? AND finished_ms IS NULL ORDER BY id DESC LIMIT 1",
            listOf(target),
        ) { it.long(0) }.firstOrNull()
        val runId = resumed ?: db.insert(
            "INSERT INTO rematch_run (from_version, to_version, started_ms) VALUES (?, ?, ?)",
            listOf(stored, target, nowMs),
        )
        while (true) {
            val batch = db.query(
                "SELECT id, raw FROM seen_payload WHERE match_version IS NULL OR match_version != ? ORDER BY id LIMIT ?",
                listOf(target, REMATCH_BATCH),
            ) { it.long(0) to it.blob(1) }
            if (batch.isEmpty()) break
            db.transaction {
                for ((id, raw) in batch) {
                    val before = db.query("SELECT 1 FROM payload_match WHERE payload_id = ? LIMIT 1", listOf(id)) { 1 }
                    val after = matcher.match(raw)
                    db.execute("DELETE FROM payload_match WHERE payload_id = ?", listOf(id))
                    after.forEach { page ->
                        db.execute("INSERT OR IGNORE INTO payload_match (payload_id, page_id) VALUES (?, ?)", listOf(id, page))
                    }
                    db.execute("UPDATE seen_payload SET match_version = ? WHERE id = ?", listOf(target, id))
                    db.execute("UPDATE rematch_run SET payloads_checked = payloads_checked + 1 WHERE id = ?", listOf(runId))
                    if (before.isEmpty() && after.isNotEmpty()) {
                        db.execute("INSERT OR IGNORE INTO newly_identified (run_id, payload_id) VALUES (?, ?)", listOf(runId, id))
                    }
                }
            }
        }
        db.transaction {
            db.execute("UPDATE rematch_run SET finished_ms = ? WHERE id = ?", listOf(nowMs, runId))
            setMeta(META_BUNDLE_VERSION, target)
        }
        return runId
    }

    private fun meta(key: String): String? =
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)) { it.string(0) }.firstOrNull()

    private fun setMeta(key: String, value: String) {
        db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", listOf(key, value))
    }

    // Recording lifecycle -----------------------------------------------------------------

    fun startRecording(id: String, startedMs: Long) {
        db.execute("INSERT OR IGNORE INTO recording (id, started_ms) VALUES (?, ?)", listOf(id, startedMs))
    }

    fun finishRecording(id: String, endedMs: Long, distanceMeters: Double) {
        db.transaction {
            db.execute(
                "UPDATE recording SET ended_ms = ?, distance_m = ? WHERE id = ?",
                listOf(endedMs, distanceMeters, id),
            )
            refreshDeviceCount(id)
        }
    }

    private fun refreshDeviceCount(recordingId: String) {
        db.execute(
            "UPDATE recording SET device_count = " +
                "(SELECT COUNT(*) FROM (SELECT DISTINCT addr FROM sighting_minute WHERE recording_id = ?)) WHERE id = ?",
            listOf(recordingId, recordingId),
        )
    }

    // The hot path -------------------------------------------------------------------------

    /**
     * Writes a batch in one transaction. [payloadIds] caches hash -> row id across
     * flushes; [matchesOf] gives the pages of a new payload and [matchVersion] the bundle
     * they come from.
     */
    fun flush(
        batch: FlushBatch,
        payloadIds: MutableMap<String, Long>,
        matchVersion: String,
        matchesOf: (hash: String, raw: ByteArray) -> List<String>,
    ) = db.transaction {
        for ((hash, delta) in batch.payloads) {
            val known = payloadIds[hash] ?: db.query("SELECT id FROM seen_payload WHERE hash = ?", listOf(hash)) { it.long(0) }.firstOrNull()
            if (known == null) {
                val id = db.insert(
                    "INSERT INTO seen_payload (hash, raw, first_seen_ms, last_seen_ms, seen_count, match_version) VALUES (?, ?, ?, ?, ?, ?)",
                    listOf(hash, delta.raw, delta.firstSeenMs, delta.lastSeenMs, delta.count, matchVersion),
                )
                matchesOf(hash, delta.raw).forEach {
                    db.execute("INSERT OR IGNORE INTO payload_match (payload_id, page_id) VALUES (?, ?)", listOf(id, it))
                }
                payloadIds[hash] = id
            } else {
                db.execute(
                    "UPDATE seen_payload SET seen_count = seen_count + ?, " +
                        "first_seen_ms = CASE WHEN first_seen_ms < ? THEN first_seen_ms ELSE ? END, " +
                        "last_seen_ms = CASE WHEN last_seen_ms > ? THEN last_seen_ms ELSE ? END WHERE id = ?",
                    listOf(delta.count, delta.firstSeenMs, delta.firstSeenMs, delta.lastSeenMs, delta.lastSeenMs, known),
                )
                payloadIds[hash] = known
            }
        }
        val recordingId = batch.recordingId
        if (recordingId != null) {
            for ((key, delta) in batch.minutes) {
                val payloadId = payloadIds[key.payloadHash]
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
            if (batch.refreshDeviceCount) refreshDeviceCount(recordingId)
        }
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

    // Queries ------------------------------------------------------------------------------

    fun seenProducts(catalog: WikiCatalog): List<SeenProduct> = db.query(
        "SELECT m.page_id, MIN(p.first_seen_ms), MAX(p.last_seen_ms), COUNT(*), SUM(p.seen_count) " +
            "FROM payload_match m JOIN seen_payload p ON p.id = m.payload_id GROUP BY m.page_id",
    ) { r ->
        catalog.page(r.string(0))?.let { page -> SeenProduct(page, r.long(1), r.long(2), r.long(3).toInt(), r.long(4)) }
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
        val pages = pagesOf(rows.map { it.payloadId }.toSet())
        return rows.map { it.sighting.copy(pageIds = pages[it.payloadId].orEmpty()) }
    }

    private fun pagesOf(payloadIds: Set<Long>): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        for (chunk in payloadIds.chunked(500)) {
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
        val pages = pagesOf(rows.map { it.payloadId }.toSet())
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
