package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeCoreTest {
    private fun TestScope.core(demo: FakeDemo = FakeDemo.AllSessions, bulk: Int = 0, scope: CoroutineScope = backgroundScope) =
        FakeCore(scope, { FakeCore.DEMO_NOW_MS + testScheduler.currentTime }, demo, bulk)

    private val apple = 76

    private val presetFilters = listOf(
        DeviceFilter(), DeviceFilter(readable = true), DeviceFilter(trackers = true), DeviceFilter(vulnerable = true),
        DeviceFilter(identification = Identification.Identified), DeviceFilter(identification = Identification.Unidentified),
        DeviceFilter(area = Bbox(-117.236, 32.878, -117.231, 32.883)),
    )

    /** L-2: finished sessions newest first, without the empty one (r4). */
    @Test
    fun sessionsAreNewestFirstWithoutEmpty() = runTest {
        val c = core()
        val ids = c.sessions().map { it.id }
        assertEquals(listOf("r1", "r2", "r3", "r5", "r6"), ids)
        assertTrue(c.sessions().all { it.status == SessionStatus.Completed })
        assertNull(c.currentSession())
        assertEquals(5, c.allSessionsSummary().sessionCount)
    }

    /** G-9: device_order is RSSI desc then key; device_keys only hides, in the same order. */
    @Test
    fun orderIsRssiDescAndFiltersOnlyHide() = runTest {
        val c = core()
        for (scope in listOf(Scope.All, Scope.session("r1"), Scope.session("r3"))) {
            val order = c.deviceOrder(scope).keys.toList()
            val rows = order.chunked(200).flatMap { c.deviceRows(scope, it.toLongArray()) }
            assertEquals(order, rows.map { it.key })
            rows.zipWithNext().forEach { (a, b) ->
                assertTrue(a.rssi!! > b.rssi!! || (a.rssi == b.rssi && a.key < b.key))
            }
            for (f in presetFilters) {
                val keys = c.deviceKeys(scope, f).keys.toList()
                assertEquals("$f keeps the order", order.filter { it in keys.toSet() }, keys)
            }
        }
    }

    /** G-12: unnamed Apple devices are hidden from every list, count and map unless the filter is off. */
    @Test
    fun displayFilterHidesUnnamedApple() = runTest {
        val c = core()
        fun hidden(rows: List<edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow>) = rows.count { it.name == null && it.addr in appleAddrs(c) }
        val shown = c.deviceOrder(Scope.All).keys
        val rows = shown.toList().chunked(200).flatMap { c.deviceRows(Scope.All, it.toLongArray()) }
        assertEquals(0, hidden(rows))
        c.setDisplayFilter(DisplayFilter(hideUnnamedApple = false))
        val all = c.deviceOrder(Scope.All).keys
        assertTrue(all.size > shown.size)
        assertEquals(all.size, c.allSessionsSummary().deviceCount)
        val view = Bbox(-118.0, 32.0, -116.0, 34.0)
        assertEquals(all.size, c.mapPoints(Scope.All, DeviceFilter(), view, 14.0).count)
        c.setDisplayFilter(DisplayFilter(hideUnnamedApple = true))
        assertEquals(shown.size, c.mapPoints(Scope.All, DeviceFilter(), view, 14.0).count)
    }

    private fun appleAddrs(c: FakeCore): Set<String> =
        FakeData.build().byAddr.values.filter { it.company == apple }.map { it.addr }.toSet()

    /** D-3a: a category count equals the list length after checking that category, under every other constraint. */
    @Test
    fun categoryCountsAreFaceted() = runTest {
        val c = core(FakeDemo.Recording)
        val live = c.initialScope!!
        for (scope in listOf(Scope.All, Scope.session("r1"), live)) {
            for (base in presetFilters + DeviceFilter(inRangeOnly = true) + DeviceFilter(categories = setOf("audio-device"))) {
                val counts = c.categoryCounts(scope, base)
                assertEquals(counts.sortedWith(compareByDescending<edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount> { it.deviceCount }.thenBy { it.label }), counts)
                for (cc in counts) {
                    val listed = c.deviceKeys(scope, base.copy(categories = setOf(cc.category))).keys.size
                    assertEquals("$scope $base ${cc.category}", listed, cc.deviceCount)
                    assertEquals(FakeCatalog.CATEGORY.getValue(cc.category).title, cc.label)
                }
            }
        }
        // any-of: two categories list the union
        val two = setOf("audio-device", "lighting-control")
        val union = c.deviceKeys(Scope.All, DeviceFilter(categories = two)).keys.size
        assertEquals(two.sumOf { cat -> c.deviceKeys(Scope.All, DeviceFilter(categories = setOf(cat))).keys.size }, union)
    }

    /** §2.2: Unidentified with any page constraint is empty; presets AND. */
    @Test
    fun presetsAndIdentificationCombine() = runTest {
        val c = core()
        assertEquals(0, c.deviceKeys(Scope.All, DeviceFilter(identification = Identification.Unidentified, readable = true)).keys.size)
        val readable = c.deviceKeys(Scope.All, DeviceFilter(readable = true)).keys.toSet()
        val trackers = c.deviceKeys(Scope.All, DeviceFilter(trackers = true)).keys.toSet()
        assertTrue(readable.isNotEmpty() && trackers.isNotEmpty())
        assertEquals((readable intersect trackers).size,
            c.deviceKeys(Scope.All, DeviceFilter(readable = true, trackers = true)).keys.size)
        val vulnerable = c.deviceKeys(Scope.All, DeviceFilter(vulnerable = true)).keys.toList()
        assertTrue(vulnerable.isNotEmpty())
        val rows = c.deviceRows(Scope.All, vulnerable.take(200).toLongArray())
        assertTrue(rows.all { it.vulnerable && it.page!!.id == "device--tesla-phone-key" })
    }

    /** D-2: groups by the page's first category, by device count, cards in bundle order, Unidentified last. */
    @Test
    fun productsGroupAndCount() = runTest {
        val c = core()
        for (scope in listOf(Scope.All, Scope.session("r1"))) {
            val groups = c.products(scope, DeviceFilter())
            assertEquals("unidentified", groups.last().category)
            val identified = groups.dropLast(1)
            identified.zipWithNext().forEach { (a, b) -> assertTrue(a.products.sumOf { it.count } >= b.products.sumOf { it.count }) }
            identified.forEach { g ->
                assertEquals(g.products.sortedBy { FakeCatalog.PAGE_ORDER.getValue(it.id) }, g.products)
                g.products.forEach { assertEquals(g.category, FakeCatalog.PAGE.getValue(it.id).categories.first()) }
            }
            assertEquals(c.deviceOrder(scope).keys.size, groups.sumOf { g -> g.products.sumOf { it.count } })
            val tile = c.product(scope, "device--tile-feed", DeviceFilter())!!
            assertEquals(c.deviceKeys(scope, DeviceFilter(page = "device--tile-feed")).keys.size, tile.deviceCount)
            assertEquals("https://wirelesswiki.org/p/device--tile-feed", tile.wikiUrl)
            val unidentified = c.product(scope, null, DeviceFilter())!!
            assertEquals(c.deviceKeys(scope, DeviceFilter(identification = Identification.Unidentified)).keys.size, unidentified.deviceCount)
            assertNull(unidentified.page)
        }
        assertNull(c.product(Scope.All, "device--nope", DeviceFilter()))
    }

    /** §2.1: the union keeps each device once, at its strongest point across sessions. */
    @Test
    fun unionTakesStrongest() = runTest {
        val c = core()
        val all = c.deviceOrder(Scope.All).keys.toList()
        val r1 = c.deviceOrder(Scope.session("r1")).keys.toList()
        val r3 = c.deviceOrder(Scope.session("r3")).keys.toList()
        val two = Scope(SessionScope.Sessions(listOf("r1", "r3")))
        val union = c.deviceOrder(two).keys.toList()
        assertEquals((r1 + r3).toSet(), union.toSet())
        assertTrue(all.toSet().containsAll(union))
        val k = r1.first { it in r3 }
        val rssi = listOf("r1", "r3").mapNotNull { c.deviceRows(Scope.session(it), longArrayOf(k)).single().rssi }.max()
        assertEquals(rssi, c.deviceRows(two, longArrayOf(k)).single().rssi)
        val view = c.sessionView(two)
        assertTrue(view.routesFaint)
        assertFalse(view.live)
        assertEquals(listOf("r1", "r3"), view.sessionIds)
        // a session filter selects whole sessions
        val filtered = Scope(SessionScope.All, SessionFilter(startedFromMs = FakeData.epochMs("2026-10-28", "12:00 AM")))
        assertEquals(listOf("r1", "r2"), c.sessionView(filtered).sessionIds)
        assertEquals(0, c.deviceOrder(Scope(SessionScope.Sessions(listOf("nope")))).keys.size)
    }

    /** Q-1/Q-2: blank is no search; results keep device_order; the area limits them. */
    @Test
    fun searchKeepsOrder() = runTest {
        val c = core()
        assertNull(c.search(Scope.All, "   ", null))
        val hits = c.search(Scope.All, "govee", null)!!.keys.toList()
        assertTrue(hits.isNotEmpty())
        val order = c.deviceOrder(Scope.All).keys.toList()
        assertEquals(order.filter { it in hits.toSet() }, hits)
        val area = Bbox(-117.236, 32.878, -117.231, 32.883)
        val inArea = c.search(Scope.All, "govee", area)!!.keys.toSet()
        assertTrue(hits.toSet().containsAll(inArea))
        val mac = DeviceKeyCodec.addr(hits.first()).substring(6, 11)
        assertTrue(c.search(Scope.All, mac, null)!!.keys.contains(hits.first()))
    }

    /** §3.7: Points up to the cap, Cells beyond it, for any scope (lane E's 50 k fake). */
    @Test
    fun mapPointsAndCells() = runTest {
        val c = core(bulk = 50_000)
        val near = Bbox(-117.2345, 32.8795, -117.2335, 32.8805)
        val points = c.mapPoints(Scope.All, DeviceFilter(), near, 18.0)
        assertEquals(MapMode.Points, points.mode)
        assertTrue(points.geojson.contains("\"addr\""))
        val wide = Bbox(-117.6, 32.6, -116.9, 33.1)
        val cells = c.mapPoints(Scope.All, DeviceFilter(), wide, 9.0)
        assertEquals(MapMode.Cells, cells.mode)
        assertTrue(cells.count > 10_000)
        assertTrue(cells.geojson.contains("point_count_abbreviated"))
        val filtered = c.mapPoints(Scope.session("bulk"), DeviceFilter(trackers = true), wide, 9.0)
        assertEquals(c.deviceKeys(Scope.session("bulk"), DeviceFilter(trackers = true)).keys.size, filtered.count)
    }

    /** §2.5: `addr` is the uppercase colon MAC; the viewport is padded by a quarter of its size on each side. */
    @Test
    fun mapPointsAddrAndPadding() = runTest {
        val c = core()
        val view = Bbox(-117.236, 32.878, -117.232, 32.882)
        val data = c.mapPoints(Scope.All, DeviceFilter(), view, 16.0)
        val addrs = Regex("\"addr\":\"([^\"]*)\"").findAll(data.geojson).map { it.groupValues[1] }.toList()
        assertEquals(data.count, addrs.size)
        assertTrue(addrs.all { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) })
        val padded = Bbox(-117.237, 32.877, -117.231, 32.883)
        val rows = c.deviceKeys(Scope.All, DeviceFilter(area = padded)).keys
        assertEquals(rows.size, data.count)
    }

    /** §2.6: deltas at 1 Hz with increasing seq, an append every 5 s, staleness flips; pause ends them. */
    @Test
    fun liveSimulator() = runTest {
        val c = core(FakeDemo.Recording)
        val scope = c.initialScope!!
        val deltas = mutableListOf<LiveDelta>()
        val job = launch { c.live.collect { deltas += it } }
        runCurrent()
        val snapshot = c.deviceOrder(scope)
        assertEquals(0L, snapshot.seq)
        assertTrue(c.sessionView(scope).live)
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals(10, deltas.size)
        assertEquals((1L..10L).toList(), deltas.map { it.seq })
        assertEquals(2, deltas.sumOf { it.appended.size })
        assertTrue(deltas.all { it.state.phase == RecordingPhase.Recording })
        assertTrue(deltas.flatMap { it.routeAppended }.isNotEmpty())
        val appended = deltas.flatMap { it.appended }.map { it.key }
        val order = c.deviceOrder(scope).keys.toList()
        assertTrue(order.containsAll(appended))
        // data.js stale rows come back after 40 s; a staleness flip is a change
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(deltas.flatMap { it.changed }.any { it.inRange })
        c.setLiveFocus(order.first())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(60, deltas.last().chart!!.samples.size)
        c.pauseRecording()
        runCurrent()
        val paused = deltas.size
        assertEquals(RecordingPhase.Paused, deltas.last().state.phase)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(paused, deltas.size)
        assertEquals(RecordingPhase.Paused, c.recordingState().phase)
        c.resumeRecording()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(deltas.size > paused)
        val id = c.stopRecording()
        assertEquals(id, (scope.sessions as SessionScope.Sessions).ids.single())
        assertEquals(id, c.sessions().first().id)
        assertFalse(c.sessionView(scope).live)
        assertNull(c.deviceChart(order.first()))
        job.cancel()
    }

    /** Elapsed excludes paused time (§2.3). */
    @Test
    fun pauseExcludesElapsed() = runTest {
        val c = core(FakeDemo.Recording)
        val start = c.recordingState()
        advanceTimeBy(10_000)
        c.pauseRecording()
        advanceTimeBy(60_000)
        c.resumeRecording()
        val s = c.recordingState()
        assertEquals(start.sessionId, s.sessionId)
        assertEquals(12 * 60_000L + 10_000L, s.activeBeforeMs)
        assertEquals(FakeCore.DEMO_NOW_MS + 70_000, s.runningSinceMs)
    }

    /** CoreIngest: scans outside a recording are ignored; real ingest feeds the live list; bounds and backpressure. */
    @Test
    fun ingest() = runTest {
        val c = FakeCore(backgroundScope, { FakeCore.DEMO_NOW_MS + testScheduler.currentTime }, simulate = false)
        val obs = ScanObservationInput("C0:FF:EE:00:00:01", -60, FakeCore.DEMO_NOW_MS, 0, byteArrayOf(2, 1, 6, 5, 9, 'a'.code.toByte(),
            'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte()), "abcd", null, true, true, 1, 0, null, null, null, 0, 0)
        assertEquals(1, c.ingestScans(listOf(obs)).ignoredNotRecording)
        val id = c.startRecording(FakeCore.DEMO_ENVIRONMENT)
        assertEquals(1, c.ingestScans(listOf(obs)).accepted)
        val keys = c.deviceOrder(Scope.session(id)).keys
        assertArrayEquals(longArrayOf(0xC0FFEE000001L), keys)
        assertEquals("abcd", c.device(Scope.session(id), keys[0])!!.name)
        try { c.ingestScans(List(65) { obs }); fail() } catch (_: CoreException.InputBatchTooLarge) {}
        c.backpressure = true
        try { c.ingestScans(listOf(obs)); fail() } catch (_: CoreException.Backpressure) {}
        try { c.startRecording(FakeCore.DEMO_ENVIRONMENT); fail() } catch (_: CoreException.AlreadyRecording) {}
    }

    /** O-1 item 11: delete refuses while recording, keeps un-uploaded data by default. */
    @Test
    fun deleteKeepsPendingByDefault() = runTest {
        val c = core(FakeDemo.Recording)
        assertTrue(c.deleteLocalData(Scope.All, false) is DeleteOutcome.Refused)
        assertEquals(DeleteOutcome.DeletedPendingKept(3), c.deleteLocalData(Scope.session("r1"), false))
        assertEquals(3, c.uploadStatus().pendingCount)
        assertEquals(DeleteOutcome.Deleted(1), c.deleteLocalData(Scope.session("r2"), true))
        assertEquals(listOf("r3", "r5", "r6"), c.sessions().map { it.id })
    }

    /** O-1 items 9–10 and the upload queue. */
    @Test
    fun storageExportAndUploads() = runTest {
        val c = core()
        val stats = c.storageStats()
        assertEquals(3, stats.pendingCount)
        assertEquals(5, stats.sessionCount)
        for (format in ExportFormat.entries) {
            val export = c.prepareExport(Scope.session("r1"), format)
            assertTrue(java.io.File(export.localPath).isFile)
            c.acknowledgeExport(export.exportId)
            assertFalse(java.io.File(export.localPath).exists())
        }
        val first = c.pendingUploads().first()
        val prepared = c.prepareUpload(first.payloadId)
        assertTrue(java.io.File(prepared.ciphertextPath).isFile)
        c.markUploadSucceeded(first.payloadId)
        assertFalse(java.io.File(prepared.ciphertextPath).exists())
        assertEquals(2, c.uploadStatus().pendingCount)
        c.markUploadFailed(c.pendingUploads().first().payloadId, "offline")
        assertEquals(1, c.uploadStatus().failedCount)
        assertEquals("offline", c.uploadStatus().lastError)
    }

    /** §2.7: gatt/v1 → steps; failures stop at the failed step; the JBL demo result is stored. */
    @Test
    fun gattViews() = runTest {
        val c = core()
        val jbl = FakeData.build().devices.first { it.name == "JBL Flip 6" }
        val stored = c.deviceGatt(DeviceKeyCodec.key(jbl.addr))!!
        assertTrue(stored.ok)
        assertEquals(4, stored.steps.size)
        val id = c.newGattDiscoveryId()
        val timeout = """{"schema":"gatt/v1","id":"$id","target":{"addr":"AA:BB:CC:DD:EE:FF"},
            "started_at":"2026-10-28T19:00:00.000Z","outcome":"failed","stage_reached":"connect",
            "error":{"stage":"connect","status":-1},"disconnect":{"initiator":"timeout","status":null},
            "timings_ms":{"connect":null,"mtu":null,"discover":null,"read":null},"services":[]}"""
        val v = c.ingestGatt(timeout)
        assertFalse(v.ok)
        assertEquals(1, v.steps.size)
        assertEquals(GattStepKind.Connecting, v.steps[0].kind)
        assertEquals(GattStepState.Failed, v.steps[0].state)
        assertEquals(GattFailure.TimedOut, v.steps[0].failure)
        val remote = timeout.replace("\"failed\"", "\"partial\"").replace("\"stage\":\"connect\",\"status\":-1", "\"stage\":\"read\",\"status\":19")
            .replace("\"initiator\":\"timeout\"", "\"initiator\":\"remote\"").replace("\"connect\":null", "\"connect\":800")
        val r = FakeGatt.view(remote)
        assertEquals(3, r.steps.size)
        assertEquals(GattFailure.DisconnectedUnexpectedly, r.steps[2].failure)
        val cancelled = c.ingestGatt(timeout.replace("\"failed\"", "\"cancelled\"").replace(id, c.newGattDiscoveryId()))
        assertNotNull(cancelled)
        assertEquals(v.discoveryId, c.deviceGatt(0xAABBCCDDEEFFL)!!.discoveryId)
        assertNotNull(c.gattTarget(DeviceKeyCodec.key(jbl.addr)))
    }

    /** V-6 demo states. */
    @Test
    fun demoStates() = runTest {
        assertNull(core(FakeDemo.FirstRun).initialScope)
        assertEquals(0, core(FakeDemo.FirstRun).sessions().size)
        assertEquals(Scope.session("r1"), core(FakeDemo.History).initialScope)
        val after = core(FakeDemo.AfterStop)
        assertEquals(RecordingPhase.Idle, after.recordingState().phase)
        assertFalse(after.sessionView(after.initialScope!!).live)
        assertEquals(26, after.sessions().first().deviceCount + 1) // one unnamed Apple device is hidden
        val events = mutableListOf<edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent>()
        val c = core()
        val job = launch { c.events.toList(events) }
        runCurrent()
        c.setDisplayFilter(DisplayFilter(false))
        runCurrent()
        assertTrue(events.any { it is edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent.DataInvalidated })
        job.cancel()
    }
}
