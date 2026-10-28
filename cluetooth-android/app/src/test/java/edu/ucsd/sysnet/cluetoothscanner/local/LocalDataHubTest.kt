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
        shareLingerMs: Long = 0,
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
        shareLingerMs = shareLingerMs,
        pruneIntervalMs = 3_600_000,
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.onAdvertisement("AA:00:00:00:00:01", -50, adv("buds-1"), t + 1_000)
        hub.onAdvertisement("AA:00:00:00:00:02", -70, adv("unknown"), t + 2_000)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.onAdvertisement("AA:00:00:00:00:02", -45, adv("buds-2"), t + 100)
        hub.onAdvertisement("AA:00:00:00:00:03", -80, adv("multi-1"), t + 200)
        hub.onAdvertisement("AA:00:00:00:00:04", -90, adv("stranger"), t + 300)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("anything"), t)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -70, adv("buds-1"), t + 1_000)
        hub.onAdvertisement("AA:00:00:00:00:01", -55, adv("buds-1"), t + 2_000)
        hub.onLocation(fix(t + 3_000, lat = 32.001)) // 111 m north
        hub.onAdvertisement("AA:00:00:00:00:01", -65, adv("buds-1"), t + 4_000)
        hub.onAdvertisement("AA:00:00:00:00:02", -80, adv("bulb-1"), t + 70_000) // next minute, stale fix
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t - 5_000)
        hub.onRecordingStarted("rec-1", t)
        hub.onRecordingStarted("rec-1", t + 1) // the core returns the same id again: ignored
        hub.onAdvertisement("AA:00:00:00:00:02", -60, adv("buds-1"), t + 1_000)
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
        first.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
        first.onAdvertisement("AA:00:00:00:00:02", -60, adv("bulb-1"), now.get())
        first.flushNow()
        assertNull(first.pendingRematchReport().firstValue())
        first.close()

        val second = hub(version = "v2")
        // startup does not wait for the re-match; the report appears once it ran
        val report = withTimeout(10_000) { second.pendingRematchReport().first { it != null } }
        assertEquals("v1", report!!.fromVersion)
        assertEquals("v2", report.toVersion)
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
        first.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-live"), now.get())
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
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
        repeat(20_000) { broken.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), now.get()) }
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
        repeat(10) { i -> repeat(5) { hub.onAdvertisement("AA:00:00:00:00:0$i", -60, adv("buds-1"), t + it) } }
        hub.flushNow()
        assertEquals(1, matcherOf!!.calls.get())

        val withAddr = hub(addressRules = mapOf("device--bulb" to "AA:00"))
        repeat(10) { i -> withAddr.onAdvertisement("AA:00:00:00:00:0$i", -60, adv("buds-1"), t) }
        withAddr.flushNow()
        assertTrue(matcherOf!!.calls.get() >= 10)
    }

    @Test
    fun aDeviceAlternatingAdvertisementTypesKeepsBothProducts() = runBlocking {
        val hub = hub()
        val t = now.get()
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("bulb-1"), t + 1_000)
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t + 2_000)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, raw, t)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
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
        hub.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), t)
        hub.flushNow()

        val results = List(3) { mutableListOf<List<Sighting>>() }
        val jobs = results.map { sink -> launch { hub.sightingsOfDevice("AA:00:00:00:00:01").collect { sink += it } } }
        withTimeout(10_000) { while (results.any { it.isEmpty() }) delay(5) }
        assertEquals(1, counting.count("FROM sighting_minute s JOIN seen_payload"))

        // more sightings arrive: the heavy query waits out its interval instead of re-running at once
        repeat(5) { i ->
            hub.onAdvertisement("AA:00:00:00:00:01", -50 - i, adv("buds-1"), t + 1_000L * (i + 1))
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
}
