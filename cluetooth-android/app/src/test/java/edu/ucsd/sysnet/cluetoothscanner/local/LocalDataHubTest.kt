package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    private fun hub(
        version: String = "v1",
        matchRules: Map<String, String> = rules,
        bundle: () -> String = { bundleJson(version, *PAGES) },
        addressRules: Map<String, String> = emptyMap(),
    ) = LocalDataHub(
        openDatabase = { UncloseableDatabase(db) },
        loadBundleJson = bundle,
        createMatcher = { FakeMatcher(version, matchRules, addressRules) },
        scope = scope,
        clock = now::get,
        flushIntervalMs = 3_600_000,
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
    fun aNewBundleVersionRematchesAtStartAndReportsNewlyIdentifiedPayloads() = runBlocking {
        val first = hub(version = "v1", matchRules = mapOf("device--buds" to "buds"))
        first.onAdvertisement("AA:00:00:00:00:01", -60, adv("buds-1"), now.get())
        first.onAdvertisement("AA:00:00:00:00:02", -60, adv("bulb-1"), now.get())
        first.flushNow()
        assertNull(first.pendingRematchReport().firstValue())
        first.close()

        val second = LocalDataHub(
            openDatabase = { UncloseableDatabase(db) },
            loadBundleJson = { bundleJson("v2", *PAGES) },
            createMatcher = { FakeMatcher("v2", rules) },
            scope = scope,
            clock = now::get,
            flushIntervalMs = 3_600_000,
        ).also { it.start() }
        val report = second.pendingRematchReport().firstValue()
        assertNotNull(report)
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
