package edu.ucsd.sysnet.cluetoothscanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoreTest {
    private val db = JdbcSqlDatabase()
    private val store = LocalStore(db)
    private val catalog = WikiCatalog.parse(bundleJson("v1", *PAGES))
    private val matcher = FakeMatcher("v1", mapOf("device--buds" to "buds", "device--bulb" to "bulb"))
    private val ids = HashMap<String, Long>()
    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % minute

    private fun flush(
        payloads: Map<String, PayloadDelta> = emptyMap(),
        minutes: Map<MinuteKey, MinuteDelta> = emptyMap(),
        recording: String? = null,
        route: List<RoutePoint> = emptyList(),
        distance: Double = 0.0,
    ) = store.flush(FlushBatch(payloads, minutes, recording, route, distance, true), ids, "v1") { _, raw -> matcher.match(raw) }

    private fun payload(name: String, first: Long, last: Long, count: Long) =
        sha256Hex(adv(name)) to PayloadDelta(adv(name), first, last, count)

    private fun key(addr: String, atMs: Long, name: String) = MinuteKey(addr, atMs / minute, sha256Hex(adv(name)))

    private fun delta(atMs: Long, rssi: Int?, loc: GeoPoint?, samples: Int = 1) =
        MinuteDelta(atMs, atMs, 0, MinuteBest(null, null)).also { d -> repeat(samples) { d.add(atMs, rssi, loc) } }

    @Test
    fun payloadsAreStoredOnceWithCountsAndMatches() {
        flush(mapOf(payload("buds-1", t0, t0 + 5, 3)))
        flush(mapOf(payload("buds-1", t0 - 100, t0 + 500, 2), payload("other", t0, t0, 1)))
        val rows = db.query("SELECT hash, first_seen_ms, last_seen_ms, seen_count, match_version FROM seen_payload ORDER BY hash") {
            listOf(it.string(0), it.long(1), it.long(2), it.long(3), it.string(4))
        }
        assertEquals(2, rows.size)
        val buds = rows.first { it[0] == sha256Hex(adv("buds-1")) }
        assertEquals(listOf(sha256Hex(adv("buds-1")), t0 - 100, t0 + 500, 5L, "v1"), buds)
        assertEquals(
            listOf("device--buds"),
            db.query("SELECT page_id FROM payload_match") { it.string(0) },
        )
    }

    @Test
    fun theBestSampleOfAMinuteIsTheStrongestLocatedOne() {
        store.startRecording("r1", t0)
        val here = GeoPoint(32.0, -117.0, 5.0)
        val there = GeoPoint(32.1, -117.1, 8.0)
        val k = key("AA:00:00:00:00:01", t0, "buds-1")
        flush(
            mapOf(payload("buds-1", t0, t0, 1)),
            mapOf(k to MinuteDelta(t0 + 1_000, t0 + 3_000, 0, MinuteBest(null, null)).also {
                it.add(t0 + 1_000, -80, here)
                it.add(t0 + 2_000, -50, null) // stronger but unlocated
                it.add(t0 + 3_000, -70, there) // located and stronger than -80
            }),
            "r1",
        )
        flush(
            minutes = mapOf(k to delta(t0 + 20_000, -60, null, samples = 2)), // unlocated never replaces a located one
            recording = "r1",
        )
        val s = store.sightingsOfDevice("aa:00:00:00:00:01", 10).single()
        assertEquals(t0, s.minuteMs)
        assertEquals(-70, s.bestRssi)
        assertEquals(there, s.location)
        assertEquals(5, s.sampleCount)
        assertEquals(sha256Hex(adv("buds-1")), s.payloadHash)
        assertEquals("r1", s.recordingId)

        // a minute with no fix at all keeps the strongest sample, with no location
        val k2 = key("AA:00:00:00:00:02", t0, "bulb-1")
        flush(mapOf(payload("bulb-1", t0, t0, 1)), mapOf(k2 to MinuteDelta(t0, t0, 0, MinuteBest(null, null)).also {
            it.add(t0, -90, null); it.add(t0, -60, null); it.add(t0, -75, null)
        }), "r1")
        val s2 = store.sightingsOfDevice("AA:00:00:00:00:02", 10).single()
        assertEquals(-60, s2.bestRssi)
        assertNull(s2.location)
        assertEquals(listOf("device--bulb"), s2.pageIds)
    }

    @Test
    fun tiesKeepTheEarlierSample() {
        store.startRecording("r1", t0)
        val a = GeoPoint(1.0, 1.0, 1.0)
        val b = GeoPoint(2.0, 2.0, 1.0)
        val k = key("AA:00:00:00:00:01", t0, "buds-1")
        flush(mapOf(payload("buds-1", t0, t0, 1)), mapOf(k to delta(t0, -60, a)), "r1")
        flush(minutes = mapOf(k to delta(t0 + 1, -60, b)), recording = "r1")
        assertEquals(a, store.sightingsOfDevice("AA:00:00:00:00:01", 10).single().location)
    }

    @Test
    fun recordingsListDevicesRouteAndDistance() {
        store.startRecording("r1", t0)
        val buds = payload("buds-1", t0, t0, 1)
        val bulb = payload("bulb-1", t0, t0, 1)
        val loc = GeoPoint(32.0, -117.0, 4.0)
        flush(
            mapOf(buds, bulb),
            mapOf(
                key("AA:00:00:00:00:01", t0, "buds-1") to delta(t0 + 10, -55, loc),
                key("AA:00:00:00:00:01", t0 + minute, "buds-1") to delta(t0 + minute + 10, -65, loc),
                key("AA:00:00:00:00:02", t0, "bulb-1") to delta(t0 + 20, -80, null),
            ),
            "r1",
            route = listOf(RoutePoint(t0 + 2, 32.0, -117.0, 4.0), RoutePoint(t0 + 1, 32.001, -117.0, 4.0)),
            distance = 111.2,
        )
        // active recording
        val active = store.recordings(t0 + 90_000).single()
        assertTrue(active.active)
        assertEquals(90_000L, active.durationMs)
        assertEquals(2, active.deviceCount)
        assertEquals(111.2, active.distanceMeters, 1e-9)

        store.finishRecording("r1", t0 + 120_000, 111.2)
        val done = store.recording("r1", t0 + 999_999)!!
        assertFalse(done.active)
        assertEquals(120_000L, done.durationMs)
        assertEquals(t0 + 120_000, done.endedAtMs)

        val devices = store.devicesInRecording("r1")
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), devices.map { it.addr }) // strongest first
        val first = devices[0]
        assertEquals(-55, first.strongestRssi)
        assertEquals(2, first.minuteCount)
        assertEquals(t0 + 10, first.firstSeenMs)
        assertEquals(t0 + minute + 10, first.lastSeenMs)
        assertEquals(listOf("device--buds"), first.pageIds)
        assertEquals(loc, first.bestLocation)
        assertNull(devices[1].bestLocation)

        assertEquals(listOf(t0 + 1, t0 + 2), store.route("r1").map { it.timeMs })
        assertNull(store.recording("nope", t0))
    }

    @Test
    fun sightingsOfAProductJoinPayloadMatches() {
        store.startRecording("r1", t0)
        val loc = GeoPoint(32.0, -117.0, 4.0)
        flush(
            mapOf(payload("buds-1", t0, t0, 1), payload("buds-2", t0, t0, 1), payload("bulb-1", t0, t0, 1)),
            mapOf(
                key("AA:00:00:00:00:01", t0, "buds-1") to delta(t0, -60, loc),
                key("AA:00:00:00:00:03", t0 + 2 * minute, "buds-2") to delta(t0 + 2 * minute, -70, loc),
                key("AA:00:00:00:00:02", t0, "bulb-1") to delta(t0, -50, loc),
            ),
            "r1",
        )
        val sightings = store.sightingsOfProduct("device--buds", 10)
        assertEquals(listOf("AA:00:00:00:00:03", "AA:00:00:00:00:01"), sightings.map { it.addr }) // newest first
        assertEquals(1, store.sightingsOfProduct("device--buds", 1).size)
        assertTrue(store.sightingsOfProduct("device--unknown", 10).isEmpty())
        val seen = store.seenProducts(catalog).associateBy { it.page.id }
        assertEquals(2, seen.getValue("device--buds").payloadCount)
        assertEquals(setOf("device--buds", "device--bulb"), seen.keys)
    }

    @Test
    fun pruningKeepsThirtyDaysAndMatchedPayloads() {
        val now = t0 + 100L * 24 * 60 * minute
        val day = 24 * 60 * minute
        store.startRecording("old", now - 40 * day)
        store.finishRecording("old", now - 40 * day + 1_000, 0.0)
        store.startRecording("recent", now - 10 * day)
        val loc = GeoPoint(1.0, 1.0, 1.0)
        flush(
            mapOf(
                payload("buds-old", now - 50 * day, now - 50 * day, 1), // matched, old: stays
                payload("noise-old", now - 50 * day, now - 50 * day, 1), // unmatched, old, unreferenced: goes
                payload("noise-ref", now - 50 * day, now - 50 * day, 1), // unmatched, old, but a recent sighting uses it: stays
                payload("noise-new", now - day, now - day, 1), // unmatched, recent: stays
            ),
            mapOf(key("AA:00:00:00:00:01", now - 40 * day, "noise-ref") to delta(now - 40 * day, -60, loc)),
            "old",
            route = listOf(RoutePoint(now - 40 * day, 1.0, 1.0, 1.0)),
        )
        flush(minutes = mapOf(key("AA:00:00:00:00:01", now - 10 * day, "noise-ref") to delta(now - 10 * day, -60, loc)), recording = "recent")
        store.pruneExpired(now)
        assertEquals(listOf("recent"), store.recordings(now).map { it.id })
        assertEquals(listOf(now - 10 * day), store.sightingsOfDevice("AA:00:00:00:00:01", 10).map { it.minuteMs })
        assertTrue(store.route("old").isEmpty())
        val hashes = db.query("SELECT hash FROM seen_payload") { it.string(0) }.toSet()
        assertEquals(setOf("buds-old", "noise-ref", "noise-new").map { sha256Hex(adv(it)) }.toSet(), hashes)
    }

    @Test
    fun anOrphanedRecordingEndsAtItsLastRecordedThing() {
        store.startRecording("crashed", t0)
        flush(
            mapOf(payload("buds-1", t0, t0, 1)),
            mapOf(key("AA:00:00:00:00:01", t0, "buds-1") to delta(t0 + 42_000, -60, null)),
            "crashed",
            route = listOf(RoutePoint(t0 + 50_000, 1.0, 1.0, 1.0)),
        )
        store.startRecording("empty", t0 + 5)
        store.closeOrphanRecordings()
        val byId = store.recordings(t0 + 10 * minute).associateBy { it.id }
        assertEquals(t0 + 50_000, byId.getValue("crashed").endedAtMs)
        assertEquals(1, byId.getValue("crashed").deviceCount)
        assertEquals(t0 + 5, byId.getValue("empty").endedAtMs)
    }

    // Bundle updates -------------------------------------------------------------------------

    private fun seed() {
        flush(
            mapOf(
                payload("buds-1", t0, t0, 4), // matched by v1
                payload("lamp-1", t0, t0, 7), // unmatched by v1
                payload("noise", t0, t0, 1), // unmatched by v1 and v2
            ),
        )
        // the first run on existing data only records the version: v1 already matched everything inserted
        assertNull(store.pendingRematchReport().also { store.rematchIfNeeded(matcher, t0) })
        assertNull(store.pendingRematchReport())
    }

    @Test
    fun aFirstBundleOnAnEmptyStoreRunsNothing() {
        assertNull(LocalStore(JdbcSqlDatabase()).rematchIfNeeded(matcher, t0))
    }

    @Test
    fun aNewBundleRematchesEveryPayloadAndRecordsTheNewlyIdentified() {
        seed()
        assertNull(store.rematchIfNeeded(matcher, t0)) // same version: nothing to do
        val v2 = FakeMatcher("v2", mapOf("device--buds" to "buds", "device--bulb" to "bulb", "device--multi" to "lamp"))
        val runId = store.rematchIfNeeded(v2, t0 + 1_000)
        assertNotNull(runId)
        val report = store.pendingRematchReport()!!
        assertEquals(runId, report.id)
        assertEquals("v1", report.fromVersion)
        assertEquals("v2", report.toVersion)
        assertEquals(3, report.payloadsChecked)
        assertEquals(t0 + 1_000, report.ranAtMs)
        assertEquals(listOf(NewlyIdentified(sha256Hex(adv("lamp-1")), listOf("device--multi"), 7)), report.newlyIdentified)
        assertEquals(
            setOf("v2"),
            db.query("SELECT DISTINCT match_version FROM seen_payload") { it.string(0) }.toSet(),
        )
        store.acknowledgeRematch(report.id)
        assertNull(store.pendingRematchReport())
        assertNull(store.rematchIfNeeded(v2, t0 + 2_000))
    }

    @Test
    fun losingAMatchIsNotNewlyIdentified() {
        seed()
        val v2 = FakeMatcher("v2", mapOf("device--bulb" to "lamp"))
        store.rematchIfNeeded(v2, t0 + 1_000)
        // buds-1 lost its match; lamp-1 gained one (it had none before)
        val report = store.pendingRematchReport()!!
        assertEquals(listOf(sha256Hex(adv("lamp-1"))), report.newlyIdentified.map { it.payloadHash })
        assertEquals(emptyList<String>(), db.query("SELECT page_id FROM payload_match pm JOIN seen_payload p ON p.id = pm.payload_id WHERE p.hash = ?", listOf(sha256Hex(adv("buds-1")))) { it.string(0) })
    }

    @Test
    fun anInterruptedRematchResumesTheSameRun() {
        seed()
        val broken = FakeMatcher("v2", mapOf("device--bulb" to "lamp")).also { it.failAfter = 1 }
        try {
            store.rematchIfNeeded(broken, t0 + 1_000)
            error("expected the matcher to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("matcher failed", expected.message)
        }
        // the batch rolled back as a whole, nothing is half-updated; the run row survives
        val run = db.query("SELECT id FROM rematch_run WHERE to_version = 'v2' AND finished_ms IS NULL") { it.long(0) }
        assertEquals(1, run.size)
        val resumed = store.rematchIfNeeded(FakeMatcher("v2", mapOf("device--bulb" to "lamp")), t0 + 5_000)
        assertEquals(run.single(), resumed)
        assertEquals(1, db.query("SELECT COUNT(*) FROM rematch_run WHERE to_version = 'v2'") { it.long(0) }.single().toInt())
        assertEquals(3, store.pendingRematchReport()!!.payloadsChecked)
    }
}
