package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.viewportCamera
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A session in memory: devices in their session (RSSI) order, with what the filters look at. */
internal class FakeSession(val devices: MutableList<Dev>) : DiscoverQueries {
    data class Dev(
        val key: Long, val name: String?, val at: GeoPoint, val inRange: Boolean = true,
        val category: String? = null, val readable: Boolean = false, val vulnerable: Boolean = false,
    )

    private fun Dev.passes(f: DeviceFilter): Boolean =
        (f.area == null || f.area!!.let { at.lon in it.west..it.east && at.lat in it.south..it.north }) &&
            (!f.inRangeOnly || inRange) &&
            (!f.readable || readable) && (!f.vulnerable || vulnerable) && (!f.trackers || category == "asset-tracker") &&
            (f.categories.isEmpty() || category in f.categories) &&
            when (f.identification) {
                Identification.Any -> true
                Identification.Identified -> category != null
                Identification.Unidentified -> category == null
            }

    private fun keys(list: List<Dev>) = DeviceKeys(list.map { it.key }.toLongArray(), 0)
    override suspend fun deviceOrder() = keys(devices)
    override suspend fun deviceKeys(filter: DeviceFilter) = keys(devices.filter { it.passes(filter) })
    override suspend fun search(query: String, area: Bbox?): DeviceKeys? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return keys(devices.filter { it.name?.lowercase()?.contains(q) == true && it.passes(DeviceFilter(area = area)) })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverModelTest {
    private fun p(lon: Double, lat: Double) = GeoPoint(lon, lat)
    private val west = Bbox(0.0, 0.0, 1.0, 1.0)
    private val east = Bbox(1.0, 0.0, 2.0, 1.0)

    // Session order (RSSI): 10, 20, 30, 40, 50.
    private fun session() = FakeSession(
        mutableListOf(
            FakeSession.Dev(10, "QT-Sensor", p(0.5, 0.5), inRange = true, category = "wearable"),
            FakeSession.Dev(20, null, p(1.5, 0.5), inRange = false, category = "asset-tracker"),
            FakeSession.Dev(30, "SmartQt", p(1.5, 0.5), inRange = true, category = null, readable = true),
            FakeSession.Dev(40, "Lamp", p(0.5, 0.5), inRange = false, category = "lighting-control"),
            FakeSession.Dev(50, "aqtb", p(1.6, 0.6), inRange = true, category = "wearable", vulnerable = true),
        ),
    )

    private fun TestScope.stack() = SheetStackController(this, { _, to, _, onValue -> onValue(to) })
        .also { it.setScreen(915f, 1f, 0f) }

    private suspend fun TestScope.model(s: FakeSession = session(), live: Boolean = false) =
        DiscoverModel(s, live, stack()).also { it.open() }

    @Test
    fun `D-1 segments and defaults per view kind`() {
        assertEquals(listOf(Segment.Nearby, Segment.All), segmentsFor(live = true))
        assertEquals(listOf(Segment.ThisArea, Segment.All), segmentsFor(live = false))
        assertEquals(Segment.Nearby, defaultSegment(live = true))
        assertEquals(Segment.All, defaultSegment(live = false))
    }

    @Test
    fun `D-3 Nearby hides stale rows in place, All shows them again`() = runTest {
        val m = model(live = true)
        assertArrayEquals(longArrayOf(10, 30, 50), m.rows)
        m.selectSegment(Segment.All)
        assertArrayEquals(longArrayOf(10, 20, 30, 40, 50), m.rows)
    }

    @Test
    fun `G-9 filters, categories and the area only hide rows, and the order only grows by appends`() = runTest {
        val rnd = Random(7)
        val s = session()
        val m = model(s, live = true) // live appends exist only while recording (R-7: a frozen view ignores them)
        val initial = m.ordered(DeviceKeys(longArrayOf(10, 20, 30, 40, 50, 60, 70), 0)).toList()
        var nextKey = 60L
        repeat(200) {
            when (rnd.nextInt(6)) {
                0 -> m.setFilter(m.filter.copy(readable = !m.filter.readable))
                1 -> m.setFilter(m.filter.toggleCategory(listOf("wearable", "asset-tracker", "lighting-control").random(rnd)))
                2 -> m.setFilter(m.filter.copy(identification = Identification.entries.random(rnd)))
                3 -> m.selectSegment(segmentsFor(true).random(rnd))
                4 -> m.onCameraIdle(true, listOf(west, east, Bbox(0.0, 0.0, 2.0, 1.0)).random(rnd), 15.0)
                else -> if (nextKey <= 70) {
                    val d = FakeSession.Dev(nextKey++, "new", p(rnd.nextDouble(0.0, 2.0), 0.5))
                    s.devices += d
                    m.appendLive(longArrayOf(d.key))
                }
            }
            val all = m.ordered(DeviceKeys(s.devices.map { it.key }.toLongArray(), 0)).toList()
            assertEquals("the order is the first sort plus appends", initial + (60L until nextKey).toList(), all)
            val shown = m.rows.toList()
            val reference = if (m.segment == Segment.ThisArea) m.area?.order?.let { o -> o.visible(DeviceKeys(all.toLongArray(), 0)).toList() } ?: all else all
            assertEquals("rows are a subsequence of the order", shown, reference.filter { it in shown })
        }
    }

    @Test
    fun `D-4 This area re-filters only after the user's own move, kept rows first, new ones appended`() = runTest {
        val m = model()
        m.onCameraIdle(false, west, 15.0) // the app framed the session
        m.selectSegment(Segment.ThisArea)
        assertArrayEquals(longArrayOf(10, 40), m.rows)
        m.onCameraIdle(false, east, 15.0) // an app camera move: nothing changes
        assertArrayEquals(longArrayOf(10, 40), m.rows)
        m.onCameraIdle(true, Bbox(0.4, 0.0, 1.55, 1.0), 15.0) // the user's pan: 10, 40 stay; 20, 30 come in
        assertArrayEquals(longArrayOf(10, 40, 20, 30), m.rows)
        m.onCameraIdle(true, east, 15.0) // 10 and 40 leave, 50 is new
        assertArrayEquals(longArrayOf(20, 30, 50), m.rows)
        m.onCameraIdle(true, Bbox(0.0, 0.0, 2.0, 1.0), 15.0) // 10 and 40 come back: appended, not reinserted
        assertArrayEquals(longArrayOf(20, 30, 50, 10, 40), m.rows)
        m.selectSegment(Segment.All)
        assertArrayEquals(longArrayOf(10, 20, 30, 40, 50), m.rows)
        assertNull(m.area)
    }

    @Test
    fun `D-3a filters apply live and combine with This area`() = runTest {
        val m = model()
        m.setFilter(m.filter.toggleCategory("wearable"))
        assertArrayEquals(longArrayOf(10, 50), m.rows)
        m.onCameraIdle(false, east, 15.0)
        m.selectSegment(Segment.ThisArea)
        assertArrayEquals(longArrayOf(50), m.rows)
    }

    @Test
    fun `Q-5 search shows only the matches in session order and ignores the filters, Q-7 restores everything`() = runTest {
        val st = stack()
        val m = DiscoverModel(session(), false, st).also { it.open() }
        st.setHeight(st.root, SheetHeight.Half)
        st.root.scroll = ScrollSnapshot(3, 8)
        m.toggleView() // list view
        m.toggleView() // back to categories
        val filtered = DeviceFilter(readable = true)
        m.setFilter(filtered)
        st.push(SheetKind.Product("p"))

        m.enterSearch(" qt ", null, viewportCamera(west, 15.0))
        assertEquals(1, st.entries.size) // the stack is reset to the root
        assertEquals(DiscoverView.List, m.view)
        assertArrayEquals(longArrayOf(10, 30, 50), m.rows) // "qt" matches QT-Sensor, SmartQt, aqtb; readable ignored
        assertEquals(filtered, m.search!!.savedFilter)

        st.setHeight(st.root, SheetHeight.Full)
        m.exitSearch()
        assertNull(m.search)
        assertEquals(filtered, m.filter)
        assertEquals(DiscoverView.Categories, m.view)
        assertEquals(SheetHeight.Half, st.root.height)
        assertEquals(ScrollSnapshot(3, 8), st.root.scroll)
        assertArrayEquals(longArrayOf(30), m.rows)
    }

    @Test
    fun `Q-6 moving away offers search this area, which keeps present matches and appends new ones`() = runTest {
        val m = model()
        m.enterSearch("qt", east, viewportCamera(east, 15.0))
        assertArrayEquals(longArrayOf(30, 50), m.rows)
        m.onCameraIdle(false, Bbox(1.1, 0.0, 2.1, 1.0), 15.0) // app move: the reference follows, no chip
        assertFalse(m.search!!.movedAway)
        m.onCameraIdle(true, Bbox(1.12, 0.0, 2.12, 1.0), 15.0) // a small user pan: not moved away
        assertFalse(m.search!!.movedAway)
        m.onCameraIdle(true, Bbox(0.0, 0.0, 1.6, 1.0), 15.0)  // a big one: the chip shows
        assertTrue(m.search!!.movedAway)
        m.searchThisArea(Bbox(0.0, 0.0, 1.55, 1.0), 15.0)
        assertArrayEquals(longArrayOf(30, 10), m.rows) // 30 kept first, 10 new (appended), 50 hidden
        assertFalse(m.search!!.movedAway)
        m.searchEverywhere()
        assertArrayEquals(longArrayOf(10, 30, 50), m.rows)
    }

    @Test
    fun `G-14 Back pops pushed sheets first, then leaves search state`() = runTest {
        val st = stack()
        val m = DiscoverModel(session(), false, st).also { it.open() }
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        st.openDevice(10)
        assertTrue(m.handleBack())
        assertEquals(1, st.entries.size)
        assertTrue(m.search != null)
        assertTrue(m.handleBack())
        assertNull(m.search)
        assertFalse(m.handleBack())
        advanceUntilIdle()
    }

    @Test
    fun `S-12 the view switch and the segments raise a Collapsed root to Half`() = runTest {
        val st = stack()
        val m = DiscoverModel(session(), false, st).also { it.open() }
        m.toggleView()
        assertEquals(SheetHeight.Half, st.root.height)
        st.setHeight(st.root, SheetHeight.Collapsed)
        m.selectSegment(Segment.ThisArea)
        assertEquals(SheetHeight.Half, st.root.height)
    }

    // ---- #52 review fixes ----

    @Test
    fun `Q-7 on tablet leaving search restores the panel state, not one derived from the phone height`() = runTest {
        val st = stack()
        st.setHeight(st.root, SheetHeight.Half) // on phone first: a stale height.toPanel() would say Open
        st.setLayout(tablet = true)
        val m = DiscoverModel(session(), false, st).also { it.open() }
        st.onMapUserGesture() // the user panned: Partial
        assertEquals(PanelHeight.Partial, st.root.panel)
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        st.expandPanel() // opened during search
        m.exitSearch()
        assertEquals(PanelHeight.Partial, st.root.panel)
        assertEquals(SheetHeight.Half, st.root.height) // the phone height is kept for T-9, untouched by the panel
    }

    @Test
    fun `Q-7 the outgoing search list does not overwrite the restored main scroll`() = runTest {
        val st = stack()
        val m = DiscoverModel(session(), false, st).also { it.open() }
        val root = st.root
        val main = FakeContent()
        var mainAt = ScrollSnapshot(12, 4)
        root.attachList(SheetEntry.MAIN_LIST, main, { mainAt }) { mainAt = it }
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        root.detachList(SheetEntry.MAIN_LIST, main) // the main list leaves; the results list comes in
        val results = FakeContent()
        root.attachList(SEARCH, results, { ScrollSnapshot(2, 0) }) {}
        mainAt = ScrollSnapshot(0, 0)
        m.exitSearch()
        root.detachList(SEARCH, results) // after the restore, as composition disposes it
        assertEquals(ScrollSnapshot(12, 4), root.scroll)
        assertEquals(ScrollSnapshot(2, 0), root.scrollOf(SEARCH))
    }

    @Test
    fun `Q-7 a main list still composed moves to the restored scroll`() = runTest {
        val st = stack()
        val m = DiscoverModel(session(), false, st).also { it.open() }
        var mainAt = ScrollSnapshot(9, 1)
        st.root.attachList(SheetEntry.MAIN_LIST, FakeContent(), { mainAt }) { mainAt = it }
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        mainAt = ScrollSnapshot(0, 0) // the same list showed the results and was scrolled
        m.exitSearch()
        assertEquals(ScrollSnapshot(9, 1), mainAt)
    }

    @Test
    fun `D-4 This area follows the camera that moved during search`() = runTest {
        val m = model()
        m.onCameraIdle(false, west, 15.0)
        m.selectSegment(Segment.ThisArea)
        assertArrayEquals(longArrayOf(10, 40), m.rows)
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        m.onCameraIdle(true, east, 15.0) // the user panned during search
        m.exitSearch()
        assertArrayEquals(longArrayOf(20, 30, 50), m.rows)
    }

    @Test
    fun `G-9 Q-5 a live device that matches during search is appended to the results`() = runTest {
        val s = session()
        val m = model(s, live = true)
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        assertArrayEquals(longArrayOf(10, 30, 50), m.rows)
        s.devices += FakeSession.Dev(60, "qt-live", p(0.5, 0.5))
        s.devices += FakeSession.Dev(70, "other", p(0.5, 0.5))
        m.appendLive(longArrayOf(60, 70))
        assertArrayEquals(longArrayOf(10, 30, 50, 60), m.rows)
        m.exitSearch()
        assertEquals(listOf(10L, 20, 30, 40, 50, 60, 70), m.ordered(DeviceKeys(longArrayOf(10, 20, 30, 40, 50, 60, 70), 0)).toList())
    }

    // ---- #58 review fixes ----

    /** Queries whose first sort waits for [gate], to act while the model is Loading. */
    private class Gated(val inner: FakeSession) : DiscoverQueries by inner {
        val gate = CompletableDeferred<Unit>()
        override suspend fun deviceOrder(): DeviceKeys {
            val sorted = inner.deviceOrder() // the snapshot is taken first, as the core does (§2.6)
            gate.await()
            return sorted
        }
    }

    @Test
    fun `lifecycle - a live append while Loading is kept and applied when Ready, never a crash`() = runTest {
        val s = session()
        val q = Gated(s)
        val m = DiscoverModel(q, true, stack())
        assertTrue(m.phase is DiscoverPhase.Loading)
        val opening = launch { m.open() }
        runCurrent() // the sort is in flight
        s.devices += FakeSession.Dev(60, "new", p(0.5, 0.5), inRange = true)
        m.appendLive(longArrayOf(60)) // was UninitializedPropertyAccessException on the lateinit order
        assertEquals(listOf(60L), (m.phase as DiscoverPhase.Loading).pending)
        q.gate.complete(Unit)
        opening.join()
        assertTrue(m.phase is DiscoverPhase.Ready)
        assertArrayEquals(longArrayOf(10, 30, 50, 60), m.rows) // 60 after the sorted ones (G-9)
    }

    @Test
    fun `lifecycle - a failed first sort is a state, every entry point is safe`() = runTest {
        val failing = object : DiscoverQueries by session() {
            override suspend fun deviceOrder(): DeviceKeys = throw IllegalStateException("core closed")
        }
        val st = stack()
        val m = DiscoverModel(failing, true, st)
        m.open()
        assertEquals(DiscoverPhase.Failed("core closed"), m.phase)
        m.appendLive(longArrayOf(10))
        m.setFilter(DeviceFilter(readable = true)) // the view's own choices still apply
        m.selectSegment(Segment.All)
        m.onCameraIdle(true, west, 15.0)
        m.enterSearch("qt", null, viewportCamera(west, 15.0))
        assertNull(m.search)
        assertArrayEquals(LongArray(0), m.keysFor(DeviceFilter()))
        assertArrayEquals(LongArray(0), m.rows)
        assertEquals(DeviceFilter(readable = true), m.filter)
    }

    @Test
    fun `Q-5 a result picked while Loading enters search once the order is ready`() = runTest {
        val q = Gated(session())
        val m = DiscoverModel(q, false, stack())
        val opening = launch { m.open() }
        val searching = launch { m.enterSearch("qt", null, viewportCamera(west, 15.0)) }
        runCurrent()
        assertNull(m.search) // waiting on the phase, not racing it
        q.gate.complete(Unit)
        opening.join(); searching.join()
        assertArrayEquals(longArrayOf(10, 30, 50), m.rows)
        assertEquals("qt", m.search?.query)
    }

    @Test
    fun `G-9 a product's device list gets live devices of that product appended`() = runTest {
        val s = session()
        val m = model(s, live = true)
        val wearables = DeviceFilter(categories = setOf("wearable"))
        assertArrayEquals(longArrayOf(10, 50), m.keysFor(wearables))
        val before = m.liveAppends
        s.devices.add(0, FakeSession.Dev(60, "band", p(0.5, 0.5), category = "wearable")) // strongest, but new
        m.appendLive(longArrayOf(60))
        assertEquals(before + 1, m.liveAppends) // the product sheet re-queries on this
        assertArrayEquals(longArrayOf(10, 50, 60), m.keysFor(wearables)) // appended, never re-sorted
    }

    private companion object {
        const val SEARCH = "search"
    }
}
