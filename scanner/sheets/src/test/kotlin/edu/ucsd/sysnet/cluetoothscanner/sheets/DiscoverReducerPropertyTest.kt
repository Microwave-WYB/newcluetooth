package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fixed-seed random event sequences through the pure [reduceDiscover]. A synchronous in-test runner answers the query
 * effects from a fixed device set, and delivers the answers later, in random order, so stale answers happen.
 */
class DiscoverReducerPropertyTest {
    private class Dev(val key: Long, val at: GeoPoint, val name: String, val inRange: Boolean)

    private class World(rnd: Random) {
        val devices = (1L..120L).map { k ->
            Dev(k, GeoPoint(rnd.nextDouble(0.0, 2.0), rnd.nextDouble(0.0, 2.0)), listOf("qt", "lamp", "band", "tag").random(rnd) + k, rnd.nextBoolean())
        }.toMutableList()

        fun keys(filter: DeviceFilter): DeviceKeys = DeviceKeys(
            devices.filter { d ->
                (filter.area?.let { d.at.lon in it.west..it.east && d.at.lat in it.south..it.north } ?: true) &&
                    (!filter.inRangeOnly || d.inRange) && (!filter.readable || d.key % 3 == 0L)
            }.map { it.key }.toLongArray(),
            0,
        )

        fun search(query: String, area: Bbox?): DeviceKeys =
            DeviceKeys(keys(DeviceFilter(area = area)).keys.filter { k -> devices.first { it.key == k }.name.contains(query) }.toLongArray(), 0)
    }

    private fun sessionOrder(s: DiscoverState): LongArray =
        (s.phase as? DiscoverPhase.Ready)?.order?.toLongArray() ?: LongArray(0)

    private fun bbox(rnd: Random): Bbox {
        val w = rnd.nextDouble(0.0, 1.5); val s = rnd.nextDouble(0.0, 1.5)
        return Bbox(w, s, w + rnd.nextDouble(0.2, 1.0), s + rnd.nextDouble(0.2, 1.0))
    }

    private val back = SearchReturn(DiscoverView.Categories, SheetHeight.Half, PanelHeight.Open, ScrollSnapshot())

    /** The order's keys in [keys], in the order's order. */
    private fun LongArray.within(keys: DeviceKeys): LongArray = keys.keys.toHashSet().let { set -> filter { it in set }.toLongArray() }

    @Test
    fun `random event sequences keep the Discover invariants, and at rest the rows are the order and the newest answers`() {
        for (seed in 1..30) {
            val rnd = Random(seed)
            val world = World(rnd)
            val live = seed % 2 == 0
            var orderFailures = if (seed % 5 == 0) 2 else 0 // some runs see the first sorts fail, then Try again
            var state = DiscoverState(live = live)
            val pending = mutableListOf<DiscoverEvent>()
            val areaAsked = HashMap<Long, Bbox>()
            var nextKey = 1000L
            var appendedWhileReady = 0

            fun apply(event: DiscoverEvent) {
                val before = state
                val step = reduceDiscover(state, event)
                val after = step.state
                val ctx = "seed $seed, $event"
                // the phase: Loading → Ready | Failed; Ready → Failed only by a fault; Failed → Loading only by Try again
                when (before.phase) {
                    is DiscoverPhase.Loading -> Unit
                    is DiscoverPhase.Ready -> assertTrue(ctx, after.phase is DiscoverPhase.Ready || event is DiscoverEvent.Faulted)
                    is DiscoverPhase.Failed -> assertTrue(ctx, after.phase is DiscoverPhase.Failed || event == DiscoverEvent.RetryRequested)
                }
                // G-9: the order only grows by appends
                val was = sessionOrder(before)
                val now = sessionOrder(after)
                if (before.phase is DiscoverPhase.Ready && after.phase is DiscoverPhase.Ready) {
                    assertTrue(ctx, now.size >= was.size && now.copyOf(was.size).contentEquals(was))
                }
                // keys appended while loading are all in the first order
                val loading = before.phase as? DiscoverPhase.Loading
                if (loading != null && after.phase is DiscoverPhase.Ready) assertTrue(ctx, loading.pending.all { it in now })
                // rows, the search order and the area order hold only session keys, each once (they only hide, G-9)
                val inSession = now.toHashSet()
                for (keys in listOfNotNull(after.rows, after.searching?.order?.toLongArray(), after.area?.order?.toLongArray())) {
                    assertTrue(ctx, keys.all { it in inSession })
                    assertEquals(ctx, keys.size, keys.toSet().size)
                }
                // the area exists only in This area (the type), and a stale answer never changes anything
                if (event is DiscoverEvent.AreaLoaded && event.version != (before.segment as? SegmentState.ThisArea)?.asked) assertSame(ctx, before, after)
                if (event is DiscoverEvent.SearchLoaded && event.version != before.searchVersion) assertSame(ctx, before, after)
                if (event is DiscoverEvent.RowsLoaded && event.version != before.rowsVersion) assertSame(ctx, before, after)
                assertTrue(ctx, after.rowsVersion >= before.rowsVersion && after.searchVersion >= before.searchVersion && after.areaVersion >= before.areaVersion)
                if (after.phase is DiscoverPhase.Failed) assertTrue(ctx, after.rows.isEmpty() && after.searching == null && after.area == null)
                if (event is DiscoverEvent.LiveAppended && before.phase is DiscoverPhase.Ready) appendedWhileReady++
                assertEquals(ctx, appendedWhileReady, after.liveAppends)
                state = after
                // the runner: answers are computed now and delivered later, in any order
                for (effect in step.effects) when (effect) {
                    DiscoverEffect.LoadOrder -> pending +=
                        if (orderFailures-- > 0) DiscoverEvent.OrderFailed("closed") else DiscoverEvent.OrderLoaded(world.devices.map { it.key }.toLongArray())
                    is DiscoverEffect.LoadArea -> {
                        areaAsked[effect.version] = effect.bbox
                        pending += DiscoverEvent.AreaLoaded(effect.version, effect.bbox, world.keys(DeviceFilter(area = effect.bbox)))
                    }
                    is DiscoverEffect.LoadSearch -> pending +=
                        DiscoverEvent.SearchLoaded(effect.version, effect.query, effect.area, effect.purpose, world.search(effect.query, effect.area), effect.appendsAsked)
                    DiscoverEffect.Refresh -> apply(DiscoverEvent.RefreshRequested)
                    is DiscoverEffect.LoadRows -> pending += DiscoverEvent.RowsLoaded(
                        effect.version,
                        when (val q = effect.query) {
                            is RowsQuery.Search -> world.search(q.query, q.area)
                            is RowsQuery.Devices -> world.keys(q.filter)
                        },
                    )
                    is DiscoverEffect.RestoreRoot, DiscoverEffect.RaiseRoot, DiscoverEffect.ResetStack -> Unit // the stack's
                }
            }

            fun drain() {
                while (pending.isNotEmpty()) apply(pending.removeAt(rnd.nextInt(pending.size)))
            }

            repeat(400) {
                val event: DiscoverEvent = when (rnd.nextInt(14)) {
                    0 -> DiscoverEvent.OpenRequested
                    1 -> if (!live) DiscoverEvent.ViewToggled else {
                        val k = nextKey++
                        world.devices += Dev(k, GeoPoint(rnd.nextDouble(0.0, 2.0), rnd.nextDouble(0.0, 2.0)), listOf("qt", "lamp").random(rnd) + k, true)
                        DiscoverEvent.LiveAppended(longArrayOf(k))
                    }
                    2 -> DiscoverEvent.ViewToggled
                    3 -> DiscoverEvent.SegmentSelected(Segment.entries.random(rnd))
                    4 -> DiscoverEvent.FilterSet(DeviceFilter(readable = rnd.nextBoolean()))
                    5 -> DiscoverEvent.CameraIdle(rnd.nextBoolean(), bbox(rnd), rnd.nextDouble(10.0, 18.0))
                    6 -> DiscoverEvent.SearchRequested(listOf("qt", "lamp").random(rnd), bbox(rnd).takeIf { rnd.nextBoolean() }, CameraSnapshot(GeoPoint(1.0, 1.0), 15.0), back)
                    7 -> DiscoverEvent.SearchAreaRequested(bbox(rnd), 15.0)
                    8 -> DiscoverEvent.SearchEverywhereRequested
                    9 -> DiscoverEvent.SearchExited
                    10 -> if (rnd.nextInt(8) == 0) DiscoverEvent.Faulted("bug") else DiscoverEvent.RetryRequested
                    else -> if (pending.isEmpty()) DiscoverEvent.RefreshRequested else pending.removeAt(rnd.nextInt(pending.size))
                }
                apply(event)
            }
            drain()
            if (state.phase !is DiscoverPhase.Ready) {
                apply(DiscoverEvent.OpenRequested)
                apply(DiscoverEvent.RetryRequested)
                drain()
            }

            // at rest: the session holds every device, the newest answers won, and the rows are the order ∩ the answer
            val ctx = "seed $seed at rest"
            val ready = state.phase as? DiscoverPhase.Ready
            assertNotNull(ctx, ready)
            val session = sessionOrder(state)
            assertEquals(ctx, world.devices.map { it.key }.toSet(), session.toSet())
            val s = state.searching
            val seg = state.segment
            when {
                s != null -> {
                    val hits = world.search(s.query, s.area)
                    assertEquals(ctx, session.within(hits).toSet(), s.order.toLongArray().toSet())
                    assertArrayEquals(ctx, s.order.toLongArray().within(hits), state.rows)
                }
                seg is SegmentState.ThisArea && seg.asked != 0L -> {
                    val area = seg.area
                    assertNotNull(ctx, area)
                    // a stale area answer lost: the area is the newest one asked
                    assertEquals(ctx, areaAsked[seg.asked], area?.bbox)
                    val order = area?.order?.toLongArray() ?: LongArray(0)
                    assertEquals(ctx, session.within(world.keys(DeviceFilter(area = area?.bbox))).toSet(), order.toSet())
                    assertArrayEquals(ctx, order.within(world.keys(state.queryFilter())), state.rows)
                }
                else -> assertArrayEquals(ctx, session.within(world.keys(state.queryFilter())), state.rows)
            }
        }
    }

    @Test
    fun `D-13_1 Try again leaves Failed for Loading and reads the order again`() {
        var s = reduceDiscover(DiscoverState(live = false), DiscoverEvent.OpenRequested).state
        s = reduceDiscover(s, DiscoverEvent.OrderFailed("closed")).state
        assertEquals(DiscoverPhase.Failed("closed"), s.phase)
        val retry = reduceDiscover(s, DiscoverEvent.RetryRequested)
        assertEquals(DiscoverPhase.Loading(requested = true), retry.state.phase)
        assertEquals(listOf(DiscoverEffect.LoadOrder), retry.effects)
        val ready = reduceDiscover(retry.state, DiscoverEvent.OrderLoaded(longArrayOf(3, 1)))
        assertTrue(ready.state.phase is DiscoverPhase.Ready)
        // a fault fails a ready sheet, and Try again recovers it too
        val faulted = reduceDiscover(ready.state, DiscoverEvent.Faulted("bug")).state
        assertEquals(DiscoverPhase.Failed("bug"), faulted.phase)
        assertEquals(listOf(DiscoverEffect.LoadOrder), reduceDiscover(faulted, DiscoverEvent.RetryRequested).effects)
    }

    @Test
    fun `a segment not offered in the view is ignored, never a crash`() {
        val frozen = DiscoverState(live = false)
        assertEquals(frozen, reduceDiscover(frozen, DiscoverEvent.SegmentSelected(Segment.Nearby)).state)
        val live = DiscoverState(live = true)
        assertEquals(live, reduceDiscover(live, DiscoverEvent.SegmentSelected(Segment.ThisArea)).state)
    }
}
