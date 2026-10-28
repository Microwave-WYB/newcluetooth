package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataHubTest {
    private val db = JdbcSqlDatabase()
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val now = AtomicLong(1_800_000_000_000L)
    private val rules = mapOf("device--buds" to "buds", "device--bulb" to "bulb", "device--multi" to "multi")

    private var matcherOf: FakeMatcher? = null

    private fun hub(
        version: String = "v1",
        matchRules: Map<String, String> = rules,
        bundle: () -> String = { bundleJson(version, *PAGES) },
        addressRules: Map<String, String> = emptyMap(),
        database: SqlDatabase = UncloseableDatabase(db),
        heavyIntervalMs: Long = 0,
        activeSightingsIntervalMs: Long = heavyIntervalMs,
        shareLingerMs: Long = 0,
        retryMs: Long = 10,
        createMatcher: (String) -> AdvertisementMatcher = { FakeMatcher(version, matchRules, addressRules).also { m -> matcherOf = m } },
    ) = LocalDataHub(
        openDatabase = { database },
        loadBundleJson = bundle,
        createMatcher = createMatcher,
        scope = scope,
        clock = now::get,
        flushIntervalMs = 3_600_000,
        lightQueryIntervalMs = 0,
        heavyQueryIntervalMs = heavyIntervalMs,
        activeRecordingSightingsIntervalMs = activeSightingsIntervalMs,
        shareLingerMs = shareLingerMs,
        pruneIntervalMs = 3_600_000,
        retryInitialMs = retryMs,
        rematchRetryMs = retryMs,
    ).also { it.start() }

    private fun fix(atMs: Long, lat: Double = 32.0, lon: Double = -117.0, accuracy: Double = 5.0) =
        CoreLocationInput(lat, lon, accuracy, atMs, atMs * 1_000_000)

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = withTimeout(10_000) { first() }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun scanningWithoutARecordingKeepsPayloadsButNoHistory() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.hear("AA:00:00:00:00:01", -50, adv("buds-1"), t + 1_000)
        hub.hear("AA:00:00:00:00:02", -70, adv("unknown"), t + 2_000)
        hub.flushNow()

        assertEquals(2, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())
        assertEquals(0, db.query("SELECT COUNT(*) FROM sighting_minute") { it.long(0) }.single().toInt())
        assertTrue(hub.recordings().firstValue().isEmpty())

        val groups = hub.previouslySeenProducts().firstValue()
        assertEquals(listOf("audio"), groups.map { it.category?.id })
        val product = groups.single().products.single()
        assertEquals("device--buds", product.page.id)
        assertEquals(2L, product.seenCount)
        assertEquals(1, product.payloadCount)
        assertEquals(t, product.firstSeenMs)
        assertEquals(t + 1_000, product.lastSeenMs)
        assertEquals(listOf("device--buds"), hub.previouslySeenProducts("audio").firstValue().map { it.page.id })
        assertTrue(hub.previouslySeenProducts("lighting").firstValue().isEmpty())
        assertEquals("v1", hub.catalog.value?.version)
    }

    @Test
    fun productsInRangeCountDevicesAndAgeOut() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.hear("AA:00:00:00:00:02", -45, adv("buds-2"), t + 100)
        hub.hear("AA:00:00:00:00:03", -80, adv("multi-1"), t + 200)
        hub.hear("AA:00:00:00:00:04", -90, adv("stranger"), t + 300)
        hub.flushNow()

        now.set(t + 1_000)
        val snapshot = hub.productsInRange().firstValue()
        assertEquals(4, snapshot.totalDeviceCount)
        assertEquals(1, snapshot.unidentifiedDeviceCount)
        assertEquals(listOf("device--buds", "device--multi"), snapshot.products.map { it.page.id }) // strongest first
        assertEquals(2, snapshot.products[0].deviceCount)
        assertEquals(-45, snapshot.products[0].strongestRssi)
        assertEquals(t + 100, snapshot.products[0].lastSeenMs)

        now.set(t + 31_000)
        val later = hub.productsInRange().firstValue()
        assertEquals(0, later.totalDeviceCount)
        assertTrue(later.products.isEmpty())
        assertEquals(2, hub.productsInRange(windowMs = 60_000).firstValue().products.size)
    }

    @Test
    fun addressRulesSpeakInTheLiveViewOnly() = runBlocking {
        val hub = hub(addressRules = mapOf("device--bulb" to "AA:00:00"))
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("anything"), t)
        hub.flushNow()
        now.set(t + 10)
        assertEquals(listOf("device--bulb"), hub.productsInRange().firstValue().products.map { it.page.id })
        // the store keeps payload-level results: an address rule cannot be known there
        assertTrue(hub.previouslySeenProducts().firstValue().isEmpty())
    }

    @Test
    fun aRecordingKeepsMinuteHistoryRouteAndDevices() = runBlocking {
        val hub = hub()
        val t = now.get() - now.get() % 60_000
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t))
        hub.hear("AA:00:00:00:00:01", -70, adv("buds-1"), t + 1_000)
        hub.hear("AA:00:00:00:00:01", -55, adv("buds-1"), t + 2_000)
        hub.onLocation(fix(t + 3_000, lat = 32.001)) // 111 m north
        hub.hear("AA:00:00:00:00:01", -65, adv("buds-1"), t + 4_000)
        hub.hear("AA:00:00:00:00:02", -80, adv("bulb-1"), t + 70_000) // next minute, stale fix
        hub.flushNow()
        hub.onRecordingFinished("rec-1", t + 120_000)
        hub.flushNow()
        now.set(t + 200_000)

        val recording = hub.recordings().firstValue().single()
        assertEquals("rec-1", recording.id)
        assertFalse(recording.active)
        assertEquals(120_000L, recording.durationMs)
        assertEquals(2, recording.deviceCount)
        assertEquals(111.2, recording.distanceMeters, 1.0)

        val devices = hub.devicesInRecording("rec-1").firstValue()
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), devices.map { it.addr })
        assertEquals(listOf("device--buds"), devices[0].pageIds)

        val sightings = hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue()
        assertEquals(1, sightings.size)
        assertEquals(-55, sightings[0].bestRssi)
        assertEquals(32.0, sightings[0].location?.lat)
        assertEquals(3, sightings[0].sampleCount)
        // the second device was heard 70 s after the last fix: no location
        assertNull(hub.sightingsOfDevice("AA:00:00:00:00:02").firstValue().single().location)
        assertEquals(1, hub.sightingsOfProduct("device--bulb").firstValue().size)

        assertEquals(listOf(t, t + 3_000), hub.route("rec-1").firstValue().map { it.timeMs })
        assertEquals("rec-1", hub.recording("rec-1").firstValue()?.id)
    }

    @Test
    fun theRouteSkipsJitterAndPoorFixes() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t))
        hub.onLocation(fix(t + 1_000, lat = 32.00001)) // 1 m: jitter
        hub.onLocation(fix(t + 2_000, lat = 32.01, accuracy = 500.0)) // useless accuracy
        hub.onLocation(fix(t + 40_000)) // not moved, but a long gap: kept as a point without distance
        hub.onLocation(fix(t + 41_000, lon = -117.0003)) // ~28 m
        hub.flushNow()
        assertEquals(listOf(t, t + 40_000, t + 41_000), hub.route("rec-1").firstValue().map { it.timeMs })
        hub.onRecordingFinished("rec-1", t + 50_000)
        hub.flushNow()
        now.set(t + 60_000)
        assertEquals(28.0, hub.recordings().firstValue().single().distanceMeters, 2.0)
    }

    @Test
    fun anActiveRecordingIsActiveAndAdvertisementsBeforeItAreNotHistory() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t - 5_000)
        hub.onRecordingStarted("rec-1", t)
        hub.onRecordingStarted("rec-1", t + 1) // the core returns the same id again: ignored
        hub.hear("AA:00:00:00:00:02", -60, adv("buds-1"), t + 1_000)
        hub.flushNow()
        now.set(t + 10_000)
        val recording = hub.recordings().firstValue().single()
        assertTrue(recording.active)
        assertEquals(10_000L, recording.durationMs)
        assertEquals(listOf("AA:00:00:00:00:02"), hub.devicesInRecording("rec-1").firstValue().map { it.addr })
        assertEquals(t, recording.startedAtMs)
    }

    @Test
    fun aNewRulesVersionRematchesInTheBackgroundAndReportsNewlyIdentifiedPayloads() = runBlocking {
        val first = hub(version = "v1", matchRules = mapOf("device--buds" to "buds"))
        first.hear("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
        first.hear("AA:00:00:00:00:02", -60, adv("bulb-1"), now.get())
        first.flushNow()
        assertNull(first.pendingRematchReport().firstValue())
        first.close()

        val second = hub(version = "v2")
        // startup does not wait for the re-match; the report appears once it ran
        val report = withTimeout(10_000) { second.pendingRematchReport().first { it != null } }
        assertEquals("v1+m1", report!!.fromVersion) // rules version + matcher engine
        assertEquals("v2+m1", report.toVersion)
        assertEquals(2, report.payloadsChecked)
        assertEquals(listOf(listOf("device--bulb")), report.newlyIdentified.map { it.pageIds })
        second.acknowledgeRematch(report.id)
        assertNull(second.pendingRematchReport().firstValue())
        // both products are now "previously seen"
        assertEquals(
            setOf("device--buds", "device--bulb"),
            second.previouslySeenProducts().firstValue().flatMap { it.products }.map { it.page.id }.toSet(),
        )
    }

    @Test
    fun relabellingTheBundleWithoutTouchingRulesDoesNotRematch() = runBlocking {
        val first = hub(version = "v1", bundle = { bundleJson("v1", "r1", PAGES.toList()) })
        first.hear("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
        first.flushNow()
        first.close()
        val second = hub(version = "v2", bundle = { bundleJson("v2", "r1", PAGES.toList()) })
        second.flushNow()
        assertEquals("v2", second.catalog.value?.version)
        assertEquals(0, db.query("SELECT COUNT(*) FROM rematch_run") { it.long(0) }.single().toInt())
    }

    @Test
    fun aBigRematchDoesNotBlockStartupOrIngest() = runBlocking {
        val store = LocalStore(db)
        val t = now.get()
        val payloads = (0 until 3_000).associate { sha256Hex(adv("item-$it-buds")) to PayloadDelta(adv("item-$it-buds"), t, t, 1L) }
        store.flush(FlushBatch(payloads, emptyMap(), null, emptyList(), 0.0), HashMap(), "old") { _, _ -> emptyList() }
        val hub = hub(version = "new")
        // ingest is served while the re-match is still going (it runs a batch per turn of the queue)
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-live"), now.get())
        hub.flushNow()
        val report = withTimeout(30_000) { hub.pendingRematchReport().first { it != null } }
        assertEquals(3_000, report!!.payloadsChecked) // the live payload was matched on arrival
        assertEquals(3_000, report.newlyIdentified.size)
        assertEquals(
            3_001,
            hub.previouslySeenProducts().firstValue().single().products.single().payloadCount,
        )
    }

    @Test
    fun withoutABundleEverythingStillRecordsButNothingIsIdentified() = runBlocking {
        val hub = hub(bundle = { throw java.io.FileNotFoundException("no asset") })
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.flushNow()
        assertNull(hub.catalog.value)
        assertEquals(1, hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().size)
        assertTrue(hub.previouslySeenProducts().firstValue().isEmpty())
        now.set(t + 1)
        val snapshot = hub.productsInRange().firstValue()
        assertEquals(1, snapshot.unidentifiedDeviceCount)
    }

    @Test
    fun aMatcherThatCannotLoadOnlyDisablesIdentification() = runBlocking {
        val hub = hub(createMatcher = { throw UnsatisfiedLinkError("libcluetooth_core.so") })
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.flushNow()
        assertEquals(LocalDataStatus.State.IDENTIFICATION_UNAVAILABLE, hub.status.value.state)
        assertNull(hub.catalog.value)
        assertEquals(1, hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().size)
        assertTrue(hub.previouslySeenProducts().firstValue().isEmpty())
    }

    @Test
    fun anUnopenableDatabaseDegradesToEmptyDataInsteadOfCrashing() = runBlocking {
        val broken = LocalDataHub(
            openDatabase = { throw java.io.IOException("disk I/O error") },
            loadBundleJson = { bundleJson("v1", *PAGES) },
            createMatcher = { FakeMatcher("v1", rules) },
            scope = scope,
            clock = now::get,
        ).also { it.start() }
        withTimeout(10_000) { broken.status.first { it.state == LocalDataStatus.State.UNAVAILABLE } }
        assertEquals("disk I/O error", broken.status.value.message)
        // every query completes with empty data; none throws
        assertTrue(broken.recordings().firstValue().isEmpty())
        assertNull(broken.recording("x").firstValue())
        assertTrue(broken.previouslySeenProducts().firstValue().isEmpty())
        assertTrue(broken.previouslySeenProducts("audio").firstValue().isEmpty())
        assertTrue(broken.devicesInRecording("x").firstValue().isEmpty())
        assertTrue(broken.route("x").firstValue().isEmpty())
        assertTrue(broken.sightingsOfDevice("AA").firstValue().isEmpty())
        assertTrue(broken.sightingsOfProduct("x").firstValue().isEmpty())
        assertNull(broken.pendingRematchReport().firstValue())
        assertEquals(0, broken.productsInRange().firstValue().totalDeviceCount)
        // the scan callback keeps working and nothing piles up
        repeat(20_000) { broken.hear("AA:00:00:00:00:01", -60, adv("buds-1"), now.get()) }
        broken.onRecordingStarted("rec", now.get())
        broken.flushNow()
        broken.acknowledgeRematch(1)
    }

    @Test
    fun aFailingQueryEmitsTheFallbackInsteadOfThrowing() = runBlocking {
        val hub = hub()
        hub.flushNow()
        db.execute("DROP TABLE recording")
        assertTrue(hub.recordings().firstValue().isEmpty())
    }

    @Test
    fun theMatcherRunsOncePerDistinctPayloadUnlessRulesLookAtTheAddress() = runBlocking {
        val hub = hub()
        val t = now.get()
        repeat(10) { i -> repeat(5) { hub.hear("AA:00:00:00:00:0$i", -60, adv("buds-1"), t + it) } }
        hub.flushNow()
        assertEquals(1, matcherOf!!.calls.get())

        val withAddr = hub(addressRules = mapOf("device--bulb" to "AA:00"))
        repeat(10) { i -> withAddr.hear("AA:00:00:00:00:0$i", -60, adv("buds-1"), t) }
        withAddr.flushNow()
        assertTrue(matcherOf!!.calls.get() >= 10)
    }

    @Test
    fun aDeviceAlternatingAdvertisementTypesKeepsBothProducts() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.hear("AA:00:00:00:00:01", -60, adv("bulb-1"), t + 1_000)
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t + 2_000)
        hub.flushNow()
        now.set(t + 3_000)
        val snapshot = hub.productsInRange().firstValue()
        assertEquals(1, snapshot.totalDeviceCount)
        assertEquals(setOf("device--buds", "device--bulb"), snapshot.products.map { it.page.id }.toSet())
        // once the older payload is out of the window only the newer one counts
        now.set(t + 31_500)
        assertEquals(listOf("device--buds"), hub.productsInRange().firstValue().products.map { it.page.id })
    }

    @Test
    fun anExtendedAdvertisementOver255BytesIsStoredWhole() = runBlocking {
        val hub = hub()
        val t = now.get()
        // flags + three 201-byte manufacturer structures + a name: 6xx bytes, as extended advertising allows
        val raw = byteArrayOf(2, 1, 6) +
            (0 until 3).flatMap { i -> listOf<Byte>(-55, -1, 0x4C, 0) + List(198) { i.toByte() } } +
            byteArrayOf(5, 9) + "buds".toByteArray()
        assertTrue(raw.size > 600)
        hub.onRecordingStarted("rec-1", t)
        hub.hear("AA:00:00:00:00:01", -60, raw, t)
        hub.flushNow()
        val stored = db.query("SELECT raw, hash FROM seen_payload") { it.blob(0) to it.string(1) }.single()
        assertTrue(raw.contentEquals(stored.first))
        assertEquals(sha256Hex(raw), stored.second)
        assertEquals(listOf("device--buds"), hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().single().pageIds)
    }

    @Test
    fun aWriteToAnotherTableGroupDoesNotRerunAQuery() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting)
        val t = now.get()
        val job = launch { hub.previouslySeenProducts().collect { } }
        withTimeout(10_000) { while (counting.count("FROM seen_product") < 1) delay(5) }
        assertEquals(1, counting.count("FROM seen_product"))

        // recordings, routes and locations are other groups
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(CoreLocationInput(32.0, -117.0, 5.0, t, t))
        hub.flushNow()
        delay(100)
        assertEquals(1, counting.count("FROM seen_product"))

        // a new payload is the same group: it runs again
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.flushNow()
        withTimeout(10_000) { while (counting.count("FROM seen_product") < 2) delay(5) }
        job.cancel()
    }

    @Test
    fun collectorsOfTheSameQuerySharedOneExecutionAndHeavyOnesAreThrottled() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting, heavyIntervalMs = 10_000, shareLingerMs = 60_000)
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.flushNow()

        val results = List(3) { mutableListOf<List<Sighting>>() }
        val jobs = results.map { sink -> launch { hub.sightingsOfDevice("AA:00:00:00:00:01").collect { sink += it } } }
        withTimeout(10_000) { while (results.any { it.isEmpty() }) delay(5) }
        assertEquals(1, counting.count("FROM sighting_minute s JOIN seen_payload"))

        // more sightings arrive: the heavy query waits out its interval instead of re-running at once
        repeat(5) { i ->
            hub.hear("AA:00:00:00:00:01", -50 - i, adv("buds-1"), t + 1_000L * (i + 1))
            hub.flushNow()
        }
        delay(100)
        assertEquals(1, counting.count("FROM sighting_minute s JOIN seen_payload"))
        jobs.forEach { it.cancel() }
    }

    @Test
    fun flowsReEmitWhenDataChanges() = runBlocking {
        val hub = hub()
        val t = now.get()
        assertTrue(hub.recordings().firstValue().isEmpty())
        val updates = hub.recordings()
        hub.onRecordingStarted("rec-1", t)
        hub.flushNow()
        val afterStart = withTimeout(10_000) { updates.first { it.isNotEmpty() } }
        assertEquals("rec-1", afterStart.single().id)
    }

    // Review round 2 ----------------------------------------------------------------------------------

    @Test
    fun aDatabaseThatOpensLaterIsRetriedAndTheQueuedEventsAreKept() = runBlocking {
        var attempts = 0
        val hub = LocalDataHub(
            openDatabase = { if (++attempts <= 2) throw java.io.IOException("No space left on device") else UncloseableDatabase(db) },
            loadBundleJson = { bundleJson("v1", *PAGES) },
            createMatcher = { FakeMatcher("v1", rules) },
            scope = scope,
            clock = now::get,
            flushIntervalMs = 3_600_000,
            lightQueryIntervalMs = 0,
            retryInitialMs = 20,
        )
        val t = now.get()
        hub.onRecordingStarted("rec-1", t) // before the database exists
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        val seen = java.util.concurrent.CopyOnWriteArrayList<List<Recording>>()
        val job = launch { hub.recordings().collect { seen += it } }
        hub.start()

        withTimeout(10_000) { hub.status.first { it.state == LocalDataStatus.State.UNAVAILABLE } }
        assertEquals("No space left on device", hub.status.value.message)
        withTimeout(10_000) { hub.status.first { it.state == LocalDataStatus.State.READY } }
        // the collector that started while it was unavailable now gets the real data
        withTimeout(10_000) { while (seen.none { it.isNotEmpty() }) delay(10) }
        assertEquals("rec-1", seen.last().single().id)
        hub.flushNow()
        assertEquals(1, hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().size)
        assertTrue(attempts >= 3)
        // nothing was deleted along the way
        assertEquals(1, db.query("SELECT COUNT(*) FROM recording") { it.long(0) }.single().toInt())
        job.cancel()
    }

    @Test
    fun aFailedRematchBatchIsRetriedWithBackoffInsteadOfStallingUntilRestart() = runBlocking {
        val t = now.get()
        val payloads = (0 until 5).associate { sha256Hex(adv("item-$it-buds")) to PayloadDelta(adv("item-$it-buds"), t, t, 1L) }
        LocalStore(db).flush(FlushBatch(payloads, emptyMap(), null, emptyList(), 0.0), HashMap(), "old") { _, _ -> emptyList() }
        val flaky = FakeMatcher("v2", rules).also { it.failFirst = 2 }
        val hub = hub(version = "v2", createMatcher = { flaky })
        val report = withTimeout(10_000) { hub.pendingRematchReport().first { it != null } }
        assertEquals(5, report!!.newlyIdentified.size)
        assertEquals(5, report.payloadsChecked)
    }

    @Test
    fun aNewMatcherEngineRematchesEvenWhenTheRulesAreTheSame() = runBlocking {
        val first = hub(version = "v1", bundle = { bundleJson("v1", "r1", PAGES.toList()) })
        first.hear("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
        first.flushNow()
        first.close()
        assertEquals(0, db.query("SELECT COUNT(*) FROM rematch_run") { it.long(0) }.single().toInt())

        val second = hub(version = "v1", bundle = { bundleJson("v1", "r1", PAGES.toList()) }, createMatcher = { FakeMatcher("v1", rules, engineVersion = 2) })
        second.flushNow()
        withTimeout(10_000) { while (db.query("SELECT COUNT(*) FROM rematch_run WHERE finished_ms IS NOT NULL") { it.long(0) }.single() == 0L) delay(10) }
        assertEquals(setOf("r1+m2"), db.query("SELECT DISTINCT match_version FROM seen_payload") { it.string(0) }.toSet())
    }

    @Test
    fun aThrottledQueryRunsAgainAfterItsIntervalAndShowsTheLaterWrite() = runBlocking {
        val hub = hub(heavyIntervalMs = 300)
        val t = now.get() - now.get() % 60_000
        hub.onRecordingStarted("rec-1", t)
        hub.hear("AA:00:00:00:00:01", -70, adv("buds-1"), t + 1_000)
        hub.flushNow()
        val seen = java.util.concurrent.CopyOnWriteArrayList<List<Sighting>>()
        val job = launch { hub.sightingsOfDevice("AA:00:00:00:00:01").collect { seen += it } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
        assertEquals(-70, seen.first().single().bestRssi)

        // a stronger sample arrives while the query is waiting out its interval
        hub.hear("AA:00:00:00:00:01", -50, adv("buds-1"), t + 2_000)
        hub.flushNow()
        withTimeout(10_000) { while (seen.last().single().bestRssi != -50) delay(5) }
        assertEquals(2, seen.last().single().sampleCount)
        job.cancel()
    }

    // Live scanning versus recording (PR #28) ----------------------------------------------------------

    @Test
    fun liveScanningFeedsPayloadsAndTheInRangeViewButNeverSightings() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        // what BleScanService.liveScans delivers for every advertisement, recorded or not
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.flushNow()
        now.set(t + 2_000)
        assertEquals(listOf("device--buds"), hub.productsInRange().firstValue().products.map { it.page.id })
        assertEquals(1, hub.previouslySeenProducts().firstValue().single().products.single().payloadCount)
        // no row reached the core, so no minute history either
        assertTrue(hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().isEmpty())
        assertTrue(hub.devicesInRecording("rec-1").firstValue().isEmpty())
    }

    @Test
    fun aCoreAdmittedRowWritesTheMinuteHistoryOnlyWhileARecordingIsActive() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.onRecordedAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t) // no session yet: ignored
        hub.flushNow()
        assertEquals(0, db.query("SELECT COUNT(*) FROM sighting_minute") { it.long(0) }.single().toInt())
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_payload") { it.long(0) }.single().toInt())

        hub.onRecordingStarted("rec-1", t)
        // the live event for this row was dropped by the live stream's bounded buffer: the row still lands
        hub.onRecordedAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.flushNow()
        val s = hub.sightingsOfDevice("AA:00:00:00:00:01").firstValue().single()
        assertEquals(listOf("device--buds"), s.pageIds)
        assertEquals(1, s.sampleCount)

        hub.onRecordingFinished("rec-1", t + 5_000)
        hub.onRecordedAdvertisement("AA:00:00:00:00:01", -50, adv("buds-1"), t + 6_000) // after the session: ignored
        hub.flushNow()
        assertEquals(1, db.query("SELECT SUM(sample_count) FROM sighting_minute") { it.long(0) }.single().toInt())
    }

    // Unknown structures, recording notes, recording-wide sightings --------------------------------------------------

    private fun company(id: Int) = byteArrayOf(3, 0xFF.toByte(), (id and 0xFF).toByte(), (id shr 8).toByte())

    @Test
    fun unknownStructuresInRangeGroupUnidentifiedDevicesByCoarseKey() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t) // identified
        hub.hear("AA:00:00:00:00:02", -70, company(76), t + 10)
        hub.hear("AA:00:00:00:00:03", -55, company(76), t + 20)
        hub.hear("AA:00:00:00:00:04", null, company(6), t + 30)
        hub.hear("AA:00:00:00:00:05", -80, byteArrayOf(2, 1, 6), t + 40)
        // a device that also sends an identified payload is identified, whatever else it sends
        hub.hear("AA:00:00:00:00:06", -40, company(76), t + 50)
        hub.hear("AA:00:00:00:00:06", -40, adv("bulb-1"), t + 60)
        hub.flushNow()

        now.set(t + 1_000)
        val groups = hub.unknownStructuresInRange().firstValue()
        assertEquals(listOf("company:76", "shape:01/1", "company:6"), groups.map { it.structure.key }) // strongest first, no rssi last
        assertEquals(2, groups[0].deviceCount)
        assertEquals(-55, groups[0].strongestRssi)
        assertEquals(t + 20, groups[0].lastSeenMs)
        assertNull(groups[2].strongestRssi)
        assertEquals(4, hub.productsInRange().firstValue().unidentifiedDeviceCount) // devices 2-5; groups: 2 + 1 + 1

        now.set(t + 31_000) // everything aged out of the 30 s window
        assertTrue(hub.unknownStructuresInRange().firstValue().isEmpty())
        assertEquals(3, hub.unknownStructuresInRange(windowMs = 60_000).firstValue().size)
    }

    @Test
    fun aDeviceAlternatingUnidentifiedStructuresCountsInEach() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, company(76), t)
        hub.hear("AA:00:00:00:00:01", -50, byteArrayOf(2, 1, 6), t + 10)
        now.set(t + 100)
        val groups = hub.unknownStructuresInRange().firstValue()
        assertEquals(setOf("company:76", "shape:01/1"), groups.map { it.structure.key }.toSet())
        assertTrue(groups.all { it.deviceCount == 1 && it.strongestRssi == -50 }) // the device's latest sample
    }

    @Test
    fun previouslySeenUnknownStructuresCountStoredPayloadsAndFollowTheBundle() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.hear("AA:00:00:00:00:01", -60, company(76), t)
        hub.hear("AA:00:00:00:00:02", -60, company(76) + byteArrayOf(2, 0x0A, 1), t + 5_000)
        hub.hear("AA:00:00:00:00:03", -60, company(6), t + 9_000)
        hub.hear("AA:00:00:00:00:04", -60, adv("buds-1"), t + 9_500) // identified: not listed
        hub.flushNow()

        val seen = hub.previouslySeenUnknownStructures().firstValue()
        assertEquals(listOf("company:6", "company:76"), seen.map { it.structure.key }) // most recently heard first
        assertEquals(2, seen[1].payloadCount)
        assertEquals(2L, seen[1].seenCount)
        assertEquals(t, seen[1].firstSeenMs)
        assertEquals(t + 5_000, seen[1].lastSeenMs)

        // the group follows its own flow: a new payload shows up without re-collecting
        val updates = hub.previouslySeenUnknownStructures()
        hub.hear("AA:00:00:00:00:07", -60, byteArrayOf(2, 1, 6), t + 20_000)
        hub.flushNow()
        val withShape = withTimeout(10_000) { updates.first { l -> l.any { it.structure.key == "shape:01/1" } } }
        assertEquals("shape:01/1", withShape.first().structure.key)
    }

    @Test
    fun aBundleUpdateMovesNewlyIdentifiedPayloadsOutOfTheUnknownGroups() = runBlocking {
        val first = hub()
        val t = now.get()
        first.hear("AA:00:00:00:00:01", -60, adv("gadget-1"), t) // local name "gadget"
        first.flushNow()
        assertEquals(listOf("name:gadget"), first.previouslySeenUnknownStructures().firstValue().map { it.structure.key })
        first.close()

        val second = hub(
            version = "v2",
            matchRules = rules + ("device--bulb" to "gadget"),
            database = UncloseableDatabase(db),
        )
        withTimeout(10_000) { while (second.pendingRematchReport().firstValue() == null) delay(10) }
        assertTrue(second.previouslySeenUnknownStructures().firstValue().isEmpty())
    }

    @Test
    fun aMigratedFilesPayloadsGetTheirKeysInTheBackground() = runBlocking {
        val key = sha256Hex(company(76))
        db.execute(
            "INSERT INTO seen_payload (hash, raw, first_seen_ms, last_seen_ms, seen_count, match_version, structure_key) VALUES (?, ?, ?, ?, 3, 'v1+m1', NULL)",
            listOf(key, company(76), now.get() - 10_000, now.get() - 5_000), // recent: expiry would take an old one
        )
        val hub = hub()
        val groups = withTimeout(10_000) { hub.previouslySeenUnknownStructures().first { it.isNotEmpty() } }
        assertEquals("company:76", groups.single().structure.key)
        assertEquals(3L, groups.single().seenCount)
        hub.close()
    }

    @Test
    fun recordingNotesPersistAndObserversSeeEveryChange() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        assertEquals("", hub.getRecordingNote("rec-1"))
        assertEquals("", hub.observeRecordingNote("rec-1").firstValue())

        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = launch { hub.observeRecordingNote("rec-1").collect { seen += it } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
        hub.setRecordingNote("rec-1", "Parking lot sweep")
        assertEquals("Parking lot sweep", hub.getRecordingNote("rec-1")) // read-your-write
        withTimeout(10_000) { while (seen.lastOrNull() != "Parking lot sweep") delay(5) }
        hub.setRecordingNote("rec-1", "")
        withTimeout(10_000) { while (seen.lastOrNull() != "") delay(5) }
        assertEquals(listOf("", "Parking lot sweep", ""), seen.toList())
        job.cancel()

        hub.setRecordingNote("rec-1", "kept")
        hub.close()
        // a new process on the same file
        val again = hub(database = UncloseableDatabase(db))
        assertEquals("kept", again.getRecordingNote("rec-1"))
        assertEquals("kept", again.observeRecordingNote("rec-1").firstValue())
        assertEquals("", again.getRecordingNote("other"))
    }

    @Test
    fun aNoteOnlyInvalidatesTheNoteQueries() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting)
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.flushNow()
        val recordings = hub.recordings().firstValue()
        assertEquals(1, recordings.size)
        val before = counting.count("FROM recording ORDER BY started_ms")
        val noted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = launch { hub.observeRecordingNote("rec-1").collect { noted += it } }
        hub.setRecordingNote("rec-1", "hello")
        withTimeout(10_000) { while (noted.lastOrNull() != "hello") delay(5) }
        assertEquals(before, counting.count("FROM recording ORDER BY started_ms"))
        job.cancel()
    }

    @Test
    fun aNoteIsNotLostSilentlyWhenTheDatabaseIsUnavailable() = runBlocking {
        val hub = LocalDataHub(
            openDatabase = { throw java.io.IOException("No space left on device") },
            loadBundleJson = { bundleJson("v1", *PAGES) },
            createMatcher = { FakeMatcher("v1", rules) },
            scope = scope,
            clock = now::get,
            flushIntervalMs = 3_600_000,
            lightQueryIntervalMs = 0,
            retryInitialMs = 3_600_000,
        )
        hub.start()
        withTimeout(10_000) { hub.status.first { it.state == LocalDataStatus.State.UNAVAILABLE } }
        assertNull(hub.getRecordingNote("rec-1")) // unknown, not "no note": an editor must not save over it
        assertEquals("", hub.observeRecordingNote("rec-1").firstValue())
        try {
            hub.setRecordingNote("rec-1", "important")
            org.junit.Assert.fail("expected the failure to be visible")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not saved"))
        }
    }

    @Test
    fun sightingsInARecordingFollowTheBoundsAndTheRecording() = runBlocking {
        val hub = hub()
        val t = now.get() - now.get() % 60_000
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t, 32.80, -117.20))
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.onLocation(fix(t + 60_000, 33.50, -116.00))
        hub.hear("AA:00:00:00:00:02", -70, adv("bulb-1"), t + 61_000)
        hub.hear("AA:00:00:00:00:03", -75, adv("stranger"), t + 181_000) // no fresh fix
        hub.flushNow()

        val all = hub.sightingsInRecording("rec-1").firstValue()
        assertEquals(3, all.size)
        assertNull(all.first().location)
        val box = GeoBounds(32.7, -117.3, 33.0, -117.0)
        val inside = hub.sightingsInRecording("rec-1", box).firstValue()
        assertEquals(listOf("AA:00:00:00:00:01"), inside.map { it.addr })
        assertEquals(listOf("device--buds"), inside.single().pageIds)
        assertEquals(GeoPoint(32.80, -117.20, 5.0), inside.single().location)
        assertTrue(hub.sightingsInRecording("rec-other").firstValue().isEmpty())

        // it re-emits when the recording grows
        val updates = hub.sightingsInRecording("rec-1", box)
        hub.onLocation(fix(t + 240_000, 32.81, -117.21))
        hub.hear("AA:00:00:00:00:04", -50, adv("buds-9"), t + 241_000)
        hub.flushNow()
        val grown = withTimeout(10_000) { updates.first { it.size == 2 } }
        assertEquals(setOf("AA:00:00:00:00:01", "AA:00:00:00:00:04"), grown.map { it.addr }.toSet())
    }

    @Test
    fun recordingSightingsAreSharedPerBoundsAndThrottledLikeOtherHeavyQueries() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting, heavyIntervalMs = 10_000, shareLingerMs = 60_000)
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t))
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.flushNow()

        val box = GeoBounds(31.0, -118.0, 33.0, -116.0)
        val results = List(3) { mutableListOf<List<Sighting>>() }
        val jobs = results.map { sink -> launch { hub.sightingsInRecording("rec-1", box).collect { sink += it } } }
        withTimeout(10_000) { while (results.any { it.isEmpty() }) delay(5) }
        assertEquals(1, counting.count("WHERE s.recording_id = ? AND s.lat >="))

        repeat(3) { i ->
            hub.hear("AA:00:00:00:00:0${i + 2}", -50, adv("buds-1"), t + 2_000L + i)
            hub.flushNow()
        }
        delay(100)
        assertEquals(1, counting.count("WHERE s.recording_id = ? AND s.lat >="))
        // another rectangle is another key: it runs at once
        val other = hub.sightingsInRecording("rec-1", GeoBounds(0.0, 0.0, 1.0, 1.0)).firstValue()
        assertTrue(other.isEmpty())
        jobs.forEach { it.cancel() }
    }

    @Test
    fun aFinishedRecordingsSightingsDoNotReRunOnNewSightingsElsewhere() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting)
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t))
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.onRecordingFinished("rec-1", t + 2_000)
        hub.flushNow()

        val seen = java.util.concurrent.CopyOnWriteArrayList<List<Sighting>>()
        val job = launch { hub.sightingsInRecording("rec-1").collect { seen += it } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
        val runs = counting.count("WHERE s.recording_id = ?")

        hub.onRecordingStarted("rec-2", t + 10_000)
        hub.onLocation(fix(t + 10_000))
        repeat(3) { i ->
            hub.hear("AA:00:00:00:00:0${i + 2}", -50, adv("buds-1"), t + 11_000L + i)
            hub.flushNow()
        }
        delay(200)
        assertEquals(runs, counting.count("WHERE s.recording_id = ?"))
        assertEquals(1, seen.last().size)
        job.cancel()
    }

    @Test
    fun anActiveRecordingsSightingsUseTheirOwnIntervalAndRunOnceMoreWhenItFinishes() = runBlocking {
        val counting = CountingDatabase(db)
        val hub = hub(database = counting, activeSightingsIntervalMs = 60_000)
        val t = now.get()
        hub.onRecordingStarted("rec-1", t)
        hub.onLocation(fix(t))
        hub.hear("AA:00:00:00:00:01", -60, adv("buds-1"), t + 1_000)
        hub.flushNow()

        val seen = java.util.concurrent.CopyOnWriteArrayList<List<Sighting>>()
        val job = launch { hub.sightingsInRecording("rec-1").collect { seen += it } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
        hub.hear("AA:00:00:00:00:02", -60, adv("buds-1"), t + 2_000)
        hub.flushNow()
        delay(200)
        assertEquals(1, seen.last().size) // throttled by the 60 s interval

        // finishing switches to the finished-recording flavour, which reads the final rows at once
        hub.onRecordingFinished("rec-1", t + 3_000)
        hub.flushNow()
        withTimeout(10_000) { while (seen.last().size != 2) delay(5) }
        job.cancel()
    }

    @Test
    fun theInterfaceDefaultOfSetRecordingNoteFailsLoudly() = runBlocking {
        val fake = object : LocalData {
            override val catalog = kotlinx.coroutines.flow.MutableStateFlow<WikiCatalog?>(null)
            override fun productsInRange(windowMs: Long) = kotlinx.coroutines.flow.emptyFlow<InRangeSnapshot>()
            override fun previouslySeenProducts() = kotlinx.coroutines.flow.emptyFlow<List<CategoryProducts>>()
            override fun previouslySeenProducts(categoryId: String) = kotlinx.coroutines.flow.emptyFlow<List<SeenProduct>>()
            override fun recordings() = kotlinx.coroutines.flow.emptyFlow<List<Recording>>()
            override fun recording(recordingId: String) = kotlinx.coroutines.flow.emptyFlow<Recording?>()
            override fun devicesInRecording(recordingId: String) = kotlinx.coroutines.flow.emptyFlow<List<RecordedDevice>>()
            override fun route(recordingId: String) = kotlinx.coroutines.flow.emptyFlow<List<RoutePoint>>()
            override fun sightingsOfDevice(addr: String, limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<Sighting>>()
            override fun sightingsOfProduct(pageId: String, limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<Sighting>>()
            override fun pendingRematchReport() = kotlinx.coroutines.flow.emptyFlow<RematchReport?>()
            override suspend fun acknowledgeRematch(reportId: Long) = Unit
        }
        assertNull(fake.getRecordingNote("rec"))
        try {
            fake.setRecordingNote("rec", "lost?")
            org.junit.Assert.fail("a fake that cannot save must say so")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("setRecordingNote"))
        }
    }
}
