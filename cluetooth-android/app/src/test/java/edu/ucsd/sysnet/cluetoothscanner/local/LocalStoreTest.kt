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
    ) = store.flush(FlushBatch(payloads, minutes, recording, route, distance), ids, "v1") { _, raw -> matcher.match(raw) }

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
        store.refreshDeviceCount("r1") // its own statement, not part of a flush
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
                payload("buds-old", now - 50 * day, now - 50 * day, 1), // matched, old: expires too
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
        assertEquals(setOf("noise-ref", "noise-new").map { sha256Hex(adv(it)) }.toSet(), hashes)
        // the expired payload's page is still a previously seen product
        assertEquals(listOf("device--buds"), store.seenProducts(catalog).map { it.page.id })
        assertEquals(0, db.query("SELECT COUNT(*) FROM payload_match") { it.long(0) }.single().toInt())
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
        assertNull(store.pendingRematchReport().also { store.rematchAll(matcher, "v1", t0) })
        assertNull(store.pendingRematchReport())
    }

    @Test
    fun aFirstBundleOnAnEmptyStoreRunsNothing() {
        assertNull(LocalStore(JdbcSqlDatabase()).rematchAll(matcher, "v1", t0))
    }

    @Test
    fun aNewBundleRematchesEveryPayloadAndRecordsTheNewlyIdentified() {
        seed()
        assertNull(store.rematchAll(matcher, "v1", t0)) // same rules: nothing to do
        val v2 = FakeMatcher("v2", mapOf("device--buds" to "buds", "device--bulb" to "bulb", "device--multi" to "lamp"))
        val runId = store.rematchAll(v2, "v2", t0 + 1_000)
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
        assertNull(store.rematchAll(v2, "v2", t0 + 2_000))
    }

    @Test
    fun losingAMatchIsNotNewlyIdentified() {
        seed()
        val v2 = FakeMatcher("v2", mapOf("device--bulb" to "lamp"))
        store.rematchAll(v2, "v2", t0 + 1_000)
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
            store.rematchAll(broken, "v2", t0 + 1_000)
            error("expected the matcher to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("matcher failed", expected.message)
        }
        // matching happens before the write: nothing is half-updated; the run row survives
        val run = db.query("SELECT id FROM rematch_run WHERE to_version = 'v2' AND finished_ms IS NULL") { it.long(0) }
        assertEquals(1, run.size)
        val resumed = store.rematchAll(FakeMatcher("v2", mapOf("device--bulb" to "lamp")), "v2", t0 + 5_000)
        assertEquals(run.single(), resumed)
        assertEquals(1, db.query("SELECT COUNT(*) FROM rematch_run WHERE to_version = 'v2'") { it.long(0) }.single().toInt())
        assertEquals(3, store.pendingRematchReport()!!.payloadsChecked)
    }

    // Expiry, summaries, rollback, scale --------------------------------------------------------------

    @Test
    fun theProductSummaryCountsAndSurvivesPayloadExpiry() {
        val day = 24 * 60 * minute
        val now = t0 + 100 * day
        flush(mapOf(payload("buds-1", now - 50 * day, now - 49 * day, 3), payload("buds-2", now - 50 * day, now - 48 * day, 2)))
        flush(mapOf(payload("buds-1", now - 40 * day, now - 40 * day, 4)))
        val before = store.seenProducts(catalog).single()
        assertEquals("device--buds", before.page.id)
        assertEquals(2, before.payloadCount) // two distinct payloads; hearing one again adds no payload
        assertEquals(9L, before.seenCount)
        assertEquals(now - 50 * day, before.firstSeenMs)
        assertEquals(now - 40 * day, before.lastSeenMs)
        assertEquals(
            db.query("SELECT id FROM seen_payload WHERE hash = ?", listOf(sha256Hex(adv("buds-1")))) { it.long(0) }.single(),
            db.query("SELECT last_payload_id FROM seen_product") { it.long(0) }.single(),
        )

        store.pruneExpired(now)
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(before, store.seenProducts(catalog).single())
    }

    @Test
    fun rotatingPayloadsExpireInBoundedSteps() {
        val day = 24 * 60 * minute
        val now = t0 + 100 * day
        // a beacon that changes its advertisement every few minutes: 3 000 matched payloads in one day
        flush((0 until 3_000).associate { payload("buds-$it", now - 45 * day, now - 45 * day, 1) })
        assertEquals(3_000, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertTrue("one step is bounded, more is left", store.pruneStep(now))
        assertEquals(1_000, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertFalse(store.pruneStep(now))
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(3_000, store.seenProducts(catalog).single().payloadCount)
    }

    @Test
    fun oldSightingsAndRoutesGoInBoundedSteps() {
        val day = 24 * 60 * minute
        val now = t0 + 100 * day
        store.startRecording("r1", now - 45 * day)
        flush(
            mapOf(payload("buds-1", now - 45 * day, now - 45 * day, 1)),
            (0 until 3).associate { d ->
                key("AA:00:00:00:00:01", now - (45 - d) * day, "buds-1") to delta(now - (45 - d) * day, -60, null)
            } + (key("AA:00:00:00:00:01", now - day, "buds-1") to delta(now - day, -60, null)),
            "r1",
            route = listOf(RoutePoint(now - 44 * day, 1.0, 1.0, 1.0), RoutePoint(now - day, 1.0, 1.0, 1.0)),
        )
        var steps = 0
        while (store.pruneStep(now)) steps++
        assertTrue("three old days take more than one step", steps >= 1)
        assertEquals(listOf(now - day), store.sightingsOfDevice("AA:00:00:00:00:01", 10).map { it.minuteMs })
        assertEquals(listOf(now - day), store.route("r1").map { it.timeMs })
    }

    @Test
    fun aRolledBackFlushLeavesNoIdsInTheCache() {
        store.startRecording("r1", t0)
        val a = payload("buds-a", t0, t0, 1)
        val b = payload("bulb-b", t0, t0, 1)
        try {
            store.flush(FlushBatch(mapOf(a, b), emptyMap(), "r1", emptyList(), 0.0), ids, "v1") { hash, raw ->
                if (hash == b.first) error("boom") else matcher.match(raw)
            }
            org.junit.Assert.fail("the flush should fail")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
        assertTrue("nothing was committed, so nothing may be cached", ids.isEmpty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertTrue(store.seenProducts(catalog).isEmpty())

        // AUTOINCREMENT hands the rolled-back id 1 out again, to a different payload
        flush(mapOf(payload("other-c", t0, t0, 1)))
        flush(
            mapOf(a),
            mapOf(key("AA:00:00:00:00:01", t0, "buds-a") to delta(t0, -60, null)),
            "r1",
        )
        val sighting = store.sightingsOfDevice("AA:00:00:00:00:01", 10).single()
        assertEquals(sha256Hex(adv("buds-a")), sighting.payloadHash)
        assertEquals(listOf("device--buds"), sighting.pageIds)
        assertEquals(ids[sha256Hex(adv("buds-a"))], db.query("SELECT id FROM seen_payload WHERE hash = ?", listOf(sha256Hex(adv("buds-a")))) { it.long(0) }.single())
    }

    @Test
    fun aLargeRematchRunsInKeysetBatchesAndUpdatesTheSummary() {
        // 2 500 payloads: v1 knows none of them, v2 matches the even ones
        val payloads = (0 until 2_500).associate { payload("item-$it-${if (it % 2 == 0) "buds" else "zzzz"}", t0, t0 + it, 2) }
        store.flush(FlushBatch(payloads, emptyMap(), null, emptyList(), 0.0), ids, "v1") { _, _ -> emptyList() }
        val v1 = FakeMatcher("v1", emptyMap())
        assertNull(store.rematchAll(v1, "v1", t0)) // everything was inserted at "v1" already
        val v2 = FakeMatcher("v2", mapOf("device--buds" to "buds"))
        val run = store.beginRematch("v2", t0 + 1)!!
        var batches = 0
        while (store.rematchBatch(v2, "v2", run, t0 + 2)) batches++
        assertEquals(5, batches) // 500 per batch, then the empty one that finishes the run
        assertEquals(2_500, db.query("SELECT payloads_checked FROM rematch_run WHERE id = ?", listOf(run)) { it.long(0) }.single().toInt())
        assertEquals(2_500, v2.calls.get())
        val report = store.pendingRematchReport()!!
        assertEquals(1_250, report.newlyIdentified.size)
        val product = store.seenProducts(catalog).single()
        assertEquals(1_250, product.payloadCount)
        assertEquals(2_500L, product.seenCount)
        // a second pass has nothing left to do
        assertNull(store.rematchAll(v2, "v2", t0 + 3))
    }

    @Test
    fun aPayloadThatExpiredComesBackAsANewRow() {
        flush(mapOf(payload("buds-1", t0, t0, 1)))
        val first = ids.getValue(sha256Hex(adv("buds-1")))
        val day = 24 * 60 * minute
        store.pruneExpired(t0 + 40 * day)
        ids.clear() // what the hub does after a prune step
        flush(mapOf(payload("buds-1", t0 + 41 * day, t0 + 41 * day, 1)))
        assertEquals(1, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertTrue(ids.getValue(sha256Hex(adv("buds-1"))) >= first)
    }

    // Review round 2 ----------------------------------------------------------------------------------

    private fun products() = store.seenProducts(catalog).associateBy { it.page.id }

    @Test
    fun aRematchGivesBackWhatAPageNoLongerMatchesAndAToBToANetsToA() {
        flush(mapOf(payload("buds-1", t0, t0 + 10, 3), payload("buds-2", t0 + 5, t0 + 20, 2)))
        assertEquals(2, products().getValue("device--buds").payloadCount)
        assertEquals(5L, products().getValue("device--buds").seenCount)

        // B: buds-1 now identifies a bulb, buds-2 stays a buds
        val b = FakeMatcher("v2", mapOf("device--bulb" to "buds-1", "device--buds" to "buds-2"))
        store.rematchAll(b, "v2", t0 + 100)
        assertEquals(1, products().getValue("device--buds").payloadCount)
        assertEquals(2L, products().getValue("device--buds").seenCount)
        assertEquals(1, products().getValue("device--bulb").payloadCount)
        assertEquals(3L, products().getValue("device--bulb").seenCount)

        // back to A: nothing is counted twice
        val a = FakeMatcher("v3", mapOf("device--buds" to "buds"))
        store.rematchAll(a, "v3", t0 + 200)
        assertEquals(setOf("device--buds"), products().keys) // the bulb page lost its only payload
        assertEquals(2, products().getValue("device--buds").payloadCount)
        assertEquals(5L, products().getValue("device--buds").seenCount)
    }

    @Test
    fun aPayloadHeardAfterItsPageWasDroppedCountsOnTheNewPage() {
        flush(mapOf(payload("buds-1", t0, t0, 3)))
        store.rematchAll(FakeMatcher("v2", mapOf("device--bulb" to "buds")), "v2", t0 + 1)
        flush(mapOf(payload("buds-1", t0 + 5, t0 + 5, 4))) // matched under the old matcher lambda only for new rows
        assertEquals(setOf("device--bulb"), products().keys)
        assertEquals(7L, products().getValue("device--bulb").seenCount)
    }

    @Test
    fun theExpiryQueryUsesTheLastSeenIndexWithoutASort() {
        val plan = db.query("EXPLAIN QUERY PLAN $EXPIRED_PAYLOADS_SQL", listOf(1L, 10)) { it.string(3) }.joinToString("\n")
        assertTrue(plan, "seen_payload_last_seen" in plan)
        assertFalse(plan, "TEMP B-TREE" in plan)
    }

    @Test
    fun anUnacknowledgedReportKeepsItsPayloadsForNinetyDaysAndAcknowledgedOnesDoNot() {
        val day = 24 * 60 * minute
        val now = t0 + 200 * day
        val lamp = payload("lamp-1", now - 60 * day, now - 60 * day, 5) // unmatched by v1, matched by v2
        flush(mapOf(lamp))
        val v2 = FakeMatcher("v2", mapOf("device--bulb" to "lamp"))
        store.rematchAll(v2, "v2", now - 60 * day) // a report made 60 days ago, never acknowledged
        val lampId = db.query("SELECT id FROM seen_payload") { it.long(0) }.single()

        store.pruneExpired(now)
        assertEquals("unacknowledged: kept past 30 days", 1, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(1, db.query("SELECT COUNT(*) FROM newly_identified WHERE payload_id = ?", listOf(lampId)) { it.long(0) }.single().toInt())
        assertNotNull(store.pendingRematchReport())

        store.pruneExpired(now + 40 * day) // the report is now 100 days old
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(0, db.query("SELECT COUNT(*) FROM newly_identified") { it.long(0) }.single().toInt())
        assertEquals(0, db.query("SELECT COUNT(*) FROM rematch_run") { it.long(0) }.single().toInt())
        assertNull(store.pendingRematchReport())
    }

    @Test
    fun anAcknowledgedReportNoLongerHoldsItsPayloads() {
        val day = 24 * 60 * minute
        val now = t0 + 200 * day
        flush(mapOf(payload("lamp-1", now - 60 * day, now - 60 * day, 5)))
        store.rematchAll(FakeMatcher("v2", mapOf("device--bulb" to "lamp")), "v2", now - 60 * day)
        store.acknowledgeRematch(store.pendingRematchReport()!!.id)
        store.pruneExpired(now)
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(0, db.query("SELECT COUNT(*) FROM rematch_run") { it.long(0) }.single().toInt())
    }

    @Test
    fun upgradingAVersion1FileMigratesInPlaceAndKeepsEverything() {
        val v1 = JdbcSqlDatabase(createSchema = false)
        V1_SCHEMA.forEach { v1.execute(it) }
        v1.execute("INSERT INTO seen_payload (id, hash, raw, first_seen_ms, last_seen_ms, seen_count, match_version) VALUES (1, 'h1', x'00', 10, 20, 4, 'oldbundle')")
        v1.execute("INSERT INTO seen_payload (id, hash, raw, first_seen_ms, last_seen_ms, seen_count, match_version) VALUES (2, 'h2', x'01', 5, 30, 6, 'oldbundle')")
        v1.execute("INSERT INTO payload_match VALUES (1, 'device--buds'), (2, 'device--buds')")
        v1.execute("INSERT INTO recording (id, started_ms, ended_ms, distance_m, device_count) VALUES ('rec', 1, 2, 3.5, 1)")
        v1.execute("INSERT INTO recording_route VALUES ('rec', 1, 1.0, 2.0, 3.0)")
        v1.execute("INSERT INTO rematch_run (from_version, to_version, started_ms) VALUES ('a', 'b', 1)")

        LocalSchema.upgrade(1).forEach { v1.execute(it) }

        fun shape(d: SqlDatabase) = d.query("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") { it.string(0) to it.string(1) }.toSet()
        assertEquals(shape(JdbcSqlDatabase()), shape(v1))
        val migrated = LocalStore(v1)
        val product = migrated.seenProducts(catalog).single()
        assertEquals(2, product.payloadCount)
        assertEquals(10L, product.seenCount)
        assertEquals(5L, product.firstSeenMs)
        assertEquals(30L, product.lastSeenMs)
        assertEquals("rec", migrated.recordings(10).single().id)
        assertEquals(1, migrated.route("rec").size)
        assertEquals(0L, v1.query("SELECT cursor_id FROM rematch_run") { it.long(0) }.single())
        // the stored matches belong to the old bundle version: the next start re-matches them
        assertNotNull(migrated.beginRematch("rules+m1", 100))
        try {
            LocalSchema.upgrade(0)
            org.junit.Assert.fail("no migration from 0")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("version 0"))
        }
    }

    private companion object {
        /** The version-1 schema, as it was before seen_product (kept here to test the migration). */
        val V1_SCHEMA = listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE seen_payload (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT NOT NULL UNIQUE, raw BLOB NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, seen_count INTEGER NOT NULL, match_version TEXT)",
            "CREATE INDEX seen_payload_match_version ON seen_payload(match_version)",
            "CREATE TABLE payload_match (payload_id INTEGER NOT NULL, page_id TEXT NOT NULL, PRIMARY KEY (payload_id, page_id)) WITHOUT ROWID",
            "CREATE INDEX payload_match_page ON payload_match(page_id, payload_id)",
            "CREATE TABLE rematch_run (id INTEGER PRIMARY KEY AUTOINCREMENT, from_version TEXT, to_version TEXT NOT NULL, started_ms INTEGER NOT NULL, finished_ms INTEGER, payloads_checked INTEGER NOT NULL DEFAULT 0, acknowledged INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE newly_identified (run_id INTEGER NOT NULL, payload_id INTEGER NOT NULL, PRIMARY KEY (run_id, payload_id)) WITHOUT ROWID",
            "CREATE TABLE recording (id TEXT PRIMARY KEY, started_ms INTEGER NOT NULL, ended_ms INTEGER, distance_m REAL NOT NULL DEFAULT 0, device_count INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX recording_started ON recording(started_ms)",
            "CREATE TABLE sighting_minute (recording_id TEXT NOT NULL, addr TEXT NOT NULL, minute INTEGER NOT NULL, payload_id INTEGER NOT NULL, first_ms INTEGER NOT NULL, last_ms INTEGER NOT NULL, best_rssi INTEGER, lat REAL, lon REAL, accuracy REAL, sample_count INTEGER NOT NULL, PRIMARY KEY (recording_id, addr, minute, payload_id)) WITHOUT ROWID",
            "CREATE INDEX sighting_addr ON sighting_minute(addr, minute)",
            "CREATE INDEX sighting_payload ON sighting_minute(payload_id, minute)",
            "CREATE INDEX sighting_minute_time ON sighting_minute(minute)",
            "CREATE TABLE recording_route (recording_id TEXT NOT NULL, time_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, PRIMARY KEY (recording_id, time_ms)) WITHOUT ROWID",
        )
    }
}
