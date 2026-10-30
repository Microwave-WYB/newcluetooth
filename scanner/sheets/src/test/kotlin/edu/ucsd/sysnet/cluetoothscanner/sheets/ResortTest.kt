package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** D-1c: the re-sort button builds a new order from the current values; everything else stays. */
class ResortTest {
    private val area = AreaState(Bbox(0.0, 0.0, 1.0, 1.0), KeyOrder.of(longArrayOf(1, 2)))
    private val ready = DiscoverState(
        Liveness.Frozen,
        phase = DiscoverPhase.Ready(KeyOrder.of(longArrayOf(3, 1, 2))),
        segment = SegmentState.ThisArea(area, asked = 4),
        filter = DeviceFilter(readable = true),
        view = DiscoverView.List,
        rows = longArrayOf(1, 2), rowsVersion = 7,
    )

    private fun order(s: DiscoverState) = (s.phase as DiscoverPhase.Ready).order.toLongArray()

    @Test
    fun `D-1c the tap asks the core for the current order, and its answer replaces the order`() {
        val asked = reduceDiscover(ready, DiscoverEvent.ResortRequested)
        assertEquals(listOf(DiscoverEffect.LoadResort(1)), asked.effects)
        assertArrayEquals(order(ready), order(asked.state)) // nothing moves until the answer
        // the core's order now: 2, 3 (and 1 not in it, e.g. read before it was stored)
        val done = reduceDiscover(asked.state, DiscoverEvent.Resorted(1, longArrayOf(2, 3)))
        assertArrayEquals(longArrayOf(2, 3, 1), order(done.state)) // the answer, then what it lacks: nothing lost
        assertEquals(listOf(DiscoverEffect.ScrollToTop, DiscoverEffect.Refresh), done.effects)
        assertEquals(1, done.state.resorts)
        // the area keeps its box and keys, in the new order; filters, view and the rows' own state stay
        val seg = done.state.segment as SegmentState.ThisArea
        assertEquals(area.bbox, seg.area?.bbox)
        assertArrayEquals(longArrayOf(2, 1), seg.area?.order?.toLongArray())
        assertEquals(4, seg.asked)
        assertEquals(ready.filter, done.state.filter)
        assertEquals(ready.view, done.state.view)
        assertSame(ready.rows, done.state.rows) // the re-read (Refresh) brings them in the new order
    }

    @Test
    fun `D-1c a stale answer, an answer in search, or a tap in search changes nothing`() {
        val asked = reduceDiscover(reduceDiscover(ready, DiscoverEvent.ResortRequested).state, DiscoverEvent.ResortRequested).state
        assertSame(asked, reduceDiscover(asked, DiscoverEvent.Resorted(1, longArrayOf(2, 3))).state) // a newer one is asked
        val back = SearchReturn(DiscoverView.List, SheetHeight.Half, PanelHeight.Open, ScrollSnapshot())
        val searching = ready.copy(searching = Searching("qt", null, CameraSnapshot(GeoPoint(1.0, 1.0), 15.0), false, KeyOrder.of(longArrayOf(1)), DeviceFilter(), back))
        assertEquals(DiscoverStep(searching), reduceDiscover(searching, DiscoverEvent.ResortRequested))
        val searchingAsked = searching.copy(resortVersion = 1) // asked before search was entered
        assertSame(searchingAsked, reduceDiscover(searchingAsked, DiscoverEvent.Resorted(1, longArrayOf(2, 3))).state)
        // before the first sort there is nothing to re-sort
        val loading = DiscoverState(Liveness.Frozen)
        assertEquals(DiscoverStep(loading), reduceDiscover(loading, DiscoverEvent.ResortRequested))
    }
}
