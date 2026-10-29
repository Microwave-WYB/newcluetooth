package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-17, D-8, D-14 and S-8 through MapRequests, on :core-fake's history session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetsStateMapTest {
    private class RecordingMap : MapRequests {
        val calls = mutableListOf<String>()
        var shown: DeviceFilter? = null
        var selected: DeviceKey? = null
        var framed: Bbox? = null
        val focused = mutableListOf<DeviceKey>()
        var cam = CameraSnapshot(GeoPoint(-117.23, 32.88), 15.0)
        override fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean) { shown = filter; calls += "show" }
        override fun frame(bbox: Bbox) { framed = bbox; calls += "frame" }
        override fun focusDevice(key: DeviceKey, minZoom: Double) { focused += key; calls += "focus" }
        override fun setSelection(key: DeviceKey?) { selected = key; calls += "select" }
        override fun camera() = cam
        override fun restore(camera: CameraSnapshot) { cam = camera; calls += "restore" }
        override fun setCover(insets: CoverInsets) = Unit
    }

    private suspend fun TestScope.state(map: RecordingMap): SheetsState {
        val core = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
        val s = SheetsState(
            core.initialScope!!, live = false, routesFaint = false, core = core,
            gattRunner = FakeGattRunner({ FakeGattDevice(null, true) }), map = map, coroutines = this,
            nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
        )
        s.stack.setScreen(915f, 1f, 0f)
        s.open()
        return s
    }

    @Test
    fun `S-17 the Discover sheet shows the session's point set with its filters`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        assertEquals(DeviceFilter(), map.shown)
        assertNull(map.selected)
        s.discover.setFilter(DeviceFilter(trackers = true))
        s.stack.push(SheetKind.Filter) // the Filter sheet leaves the map as it is
        s.stack.pop(PopReason.Close)
        assertEquals(DeviceFilter(trackers = true), map.shown)
    }

    @Test
    fun `S-17 D-8 a product shows only its devices and frames them on open, the pop restores the camera and set`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        val before = map.cam
        val page = (s.load(SheetQuery.Products(DeviceFilter())) as Outcome.Ok).value.flatMap { it.products }.first { it.id != UNIDENTIFIED }.id
        s.openProduct(page)
        advanceUntilIdle()
        assertEquals(page, map.shown?.page)
        assertNotNull(map.framed)
        map.cam = CameraSnapshot(GeoPoint(-117.0, 33.0), 17.0)
        s.stack.pop(PopReason.Back)
        advanceUntilIdle()
        assertEquals(before, map.cam)
        assertEquals(DeviceFilter(), map.shown)
    }

    @Test
    fun `S-17 the unidentified card shows the unidentified devices`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        s.openProduct(null)
        assertEquals(Identification.Unidentified, map.shown?.identification)
    }

    @Test
    fun `D-14 a device from a list is selected and focused, from a map tap only selected`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        val keys = s.discover.rows
        s.openDevice(keys[0], fromList = true)
        assertEquals(keys[0], map.selected)
        assertEquals(listOf(keys[0]), map.focused)
        s.mapEvents.onDeviceTap(keys[1]) // replaces the device sheet; the camera stays
        assertEquals(keys[1], map.selected)
        assertEquals(listOf(keys[0]), map.focused)
        s.mapEvents.onEmptyTap() // M-7: pops it
        assertNull(map.selected)
        assertEquals(1, s.stack.entries.size)
    }

    @Test
    fun `G-14 handleBack pops pushed sheets, then search state, then nothing`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        s.enterSearch("a", null, map.cam, picked = s.discover.rows.firstOrNull())
        advanceUntilIdle()
        assertTrue(s.searching)
        assertTrue(s.handleBack()) // the picked device
        assertTrue(s.handleBack()) // search state
        advanceUntilIdle()
        assertTrue(!s.searching)
        assertTrue(!s.handleBack())
    }

    @Test
    fun `S-17 a segment switch shows the new point set, and This area follows the user's pan`() = runTest {
        val map = RecordingMap()
        val s = state(map)
        val view = Bbox(-117.25, 32.86, -117.22, 32.89)
        s.mapEvents.onCameraIdle(false, view, 15.0)
        advanceUntilIdle()
        s.selectSegment(Segment.ThisArea)
        advanceUntilIdle()
        assertEquals(view, map.shown?.area)
        val panned = Bbox(-117.24, 32.87, -117.21, 32.90)
        s.mapEvents.onCameraIdle(true, panned, 15.0)
        advanceUntilIdle()
        assertEquals(panned, map.shown?.area)
        s.selectSegment(Segment.All)
        advanceUntilIdle()
        assertNull(map.shown?.area)
    }

    @Test
    fun `S-2_6 the live deltas are subscribed before the first sort, and a delta during Loading is kept`() = runTest {
        val calls = mutableListOf<String>()
        val core = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
        val gate = CompletableDeferred<Unit>()
        val deltas = MutableSharedFlow<LiveDelta>()
        val newRow = core.deviceRows(core.initialScope!!, core.deviceOrder(core.initialScope!!).keys.copyOf(1)).single()
            .copy(key = 0x0000AABBCCDDEEFFL, addr = "AA:BB:CC:DD:EE:FF")
        val spy = object : CoreQueries by core {
            override val live: Flow<LiveDelta> = deltas.onSubscription { calls += "live" }
            override suspend fun deviceOrder(scope: Scope): DeviceKeys {
                calls += "order"
                return core.deviceOrder(scope).also { gate.await() }
            }
        }
        val s = SheetsState(
            core.initialScope!!, live = true, routesFaint = false, core = spy,
            gattRunner = FakeGattRunner({ FakeGattDevice(null, true) }), map = null, coroutines = this,
            nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
        )
        val job = launch { s.start() }
        runCurrent()
        assertEquals(listOf("live", "order"), calls)
        deltas.emit(delta(seq = 1, appended = listOf(newRow))) // while Loading: kept by the state
        assertTrue(s.discover.phase is DiscoverPhase.Loading)
        gate.complete(Unit)
        runCurrent()
        assertTrue(s.discover.phase is DiscoverPhase.Ready)
        assertEquals(newRow.key, s.discover.ordered(DeviceKeys(longArrayOf(newRow.key), 0)).single())
        job.cancel()
    }

    private fun delta(seq: Long, appended: List<edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow>) = LiveDelta(
        sessionId = FakeCore.HISTORY_SESSION, seq = seq, atMs = FakeCore.DEMO_NOW_MS,
        state = edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState(
            edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase.Recording, FakeCore.HISTORY_SESSION, 0, 0, null, 0, 0.0,
        ),
        changed = emptyList(), appended = appended, products = emptyList(), routeAppended = emptyList(), fix = null, chart = null,
    )
}
