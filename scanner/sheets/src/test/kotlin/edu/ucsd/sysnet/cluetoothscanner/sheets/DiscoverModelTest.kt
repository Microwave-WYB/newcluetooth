package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val m = model(s)
        val initial = m.order.visible(DeviceKeys(longArrayOf(10, 20, 30, 40, 50, 60, 70), 0)).toList()
        var nextKey = 60L
        repeat(200) {
            when (rnd.nextInt(6)) {
                0 -> m.setFilter(m.filter.copy(readable = !m.filter.readable))
                1 -> m.setFilter(m.filter.toggleCategory(listOf("wearable", "asset-tracker", "lighting-control").random(rnd)))
                2 -> m.setFilter(m.filter.copy(identification = Identification.entries.random(rnd)))
                3 -> m.selectSegment(segmentsFor(false).random(rnd))
                4 -> m.onCameraIdle(true, listOf(west, east, Bbox(0.0, 0.0, 2.0, 1.0)).random(rnd), 15.0)
                else -> if (nextKey <= 70) {
                    val d = FakeSession.Dev(nextKey++, "new", p(rnd.nextDouble(0.0, 2.0), 0.5))
                    s.devices += d
                    m.appendLive(longArrayOf(d.key))
                }
            }
            val all = m.order.visible(DeviceKeys(s.devices.map { it.key }.toLongArray(), 0)).toList()
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

        m.enterSearch(" qt ", null, west.camera(15.0))
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
        m.enterSearch("qt", east, east.camera(15.0))
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
        m.enterSearch("qt", null, west.camera(15.0))
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

    @Test
    fun `M-11 moved away - a zoom change of 1, or the centre a quarter of the viewport away`() {
        val vp = Bbox(0.0, 0.0, 1.0, 1.0)
        val ref = CameraSnapshot(GeoPoint(0.5, 0.5), 15.0)
        assertFalse(movedAway(ref, CameraSnapshot(GeoPoint(0.7, 0.7), 15.9), vp))
        assertTrue(movedAway(ref, CameraSnapshot(GeoPoint(0.5, 0.5), 16.0), vp))
        assertTrue(movedAway(ref, CameraSnapshot(GeoPoint(0.76, 0.5), 15.0), vp))
        assertTrue(movedAway(ref, CameraSnapshot(GeoPoint(0.5, 0.24), 15.0), vp))
    }
}
