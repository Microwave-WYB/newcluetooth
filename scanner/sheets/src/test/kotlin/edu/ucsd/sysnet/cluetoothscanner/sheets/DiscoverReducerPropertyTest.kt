package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import org.junit.Assert.assertEquals
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

    private fun isSubsequence(sub: LongArray, of: LongArray): Boolean {
        var i = 0
        for (k in of) if (i < sub.size && sub[i] == k) i++
        return i == sub.size
    }

    private fun sessionOrder(s: DiscoverState): LongArray =
        (s.phase as? DiscoverPhase.Ready)?.order?.toLongArray() ?: LongArray(0)

    private fun bbox(rnd: Random): Bbox {
        val w = rnd.nextDouble(0.0, 1.5); val s = rnd.nextDouble(0.0, 1.5)
        return Bbox(w, s, w + rnd.nextDouble(0.2, 1.0), s + rnd.nextDouble(0.2, 1.0))
    }

    private val back = SearchReturn(DiscoverView.Categories, SheetHeight.Half, PanelHeight.Open, ScrollSnapshot())

    @Test
    fun `random event sequences keep the Discover invariants`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            val world = World(rnd)
            val fail = seed % 7 == 0 // some runs see the first sort fail
            var state = DiscoverState(live = rnd.nextBoolean())
            val pending = mutableListOf<DiscoverEvent>()
            var nextKey = 1000L
            var appendedWhileReady = 0

            fun apply(event: DiscoverEvent) {
                val before = state
                val step = reduceDiscover(state, event)
                val after = step.state
                val ctx = "seed $seed, $event"
                // the phase only moves forward
                when (before.phase) {
                    is DiscoverPhase.Loading -> Unit
                    is DiscoverPhase.Ready -> assertTrue(ctx, after.phase is DiscoverPhase.Ready)
                    is DiscoverPhase.Failed -> assertEquals(ctx, before.phase, after.phase)
                }
                // G-9: the order only grows by appends
                val was = sessionOrder(before)
                val now = sessionOrder(after)
                if (before.phase is DiscoverPhase.Ready) assertTrue(ctx, now.size >= was.size && now.copyOf(was.size).contentEquals(was))
                // keys appended while loading are all in the first order
                val loading = before.phase as? DiscoverPhase.Loading
                if (loading != null && after.phase is DiscoverPhase.Ready) assertTrue(ctx, loading.pending.all { it in now })
                // rows, the search order and the area order hold only session keys, each once (they only hide, G-9);
                // the search and area orders keep present keys first and append new ones (Q-6, D-4), so they are
                // subsets, not subsequences, of the session order
                val inSession = now.toHashSet()
                for (keys in listOfNotNull(after.rows, after.searching?.order?.toLongArray(), after.area?.order?.toLongArray())) {
                    assertTrue(ctx, keys.all { it in inSession })
                    assertEquals(ctx, keys.size, keys.toSet().size)
                }
                // rows taken from the session order itself keep its order
                if (event is DiscoverEvent.RowsLoaded && after.searching == null && after.segment != Segment.ThisArea) {
                    assertTrue(ctx, isSubsequence(after.rows, now))
                }
                // a stale rows answer never changes the rows
                if (event is DiscoverEvent.RowsLoaded && event.version != before.rowsVersion) assertTrue(ctx, after.rows === before.rows)
                assertTrue(ctx, after.rowsVersion >= before.rowsVersion && after.liveAppends >= before.liveAppends)
                if (after.phase is DiscoverPhase.Failed) assertTrue(ctx, after.rows.isEmpty() && after.searching == null)
                if (event is DiscoverEvent.LiveAppended && before.phase is DiscoverPhase.Ready) appendedWhileReady++
                assertEquals(ctx, appendedWhileReady, after.liveAppends)
                state = after
                // the runner: answers are computed now and delivered later
                for (effect in step.effects) when (effect) {
                    DiscoverEffect.LoadOrder -> pending += if (fail) DiscoverEvent.OrderFailed("closed") else DiscoverEvent.OrderLoaded(world.devices.map { it.key }.toLongArray())
                    is DiscoverEffect.LoadArea -> pending += DiscoverEvent.AreaLoaded(effect.bbox, world.keys(DeviceFilter(area = effect.bbox)))
                    is DiscoverEffect.LoadSearchAppend -> pending += DiscoverEvent.SearchAppendLoaded(world.search(effect.query, effect.area))
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

            repeat(400) {
                val event: DiscoverEvent = when (rnd.nextInt(12)) {
                    0 -> DiscoverEvent.OpenRequested
                    1 -> {
                        val k = nextKey++
                        world.devices += Dev(k, GeoPoint(rnd.nextDouble(0.0, 2.0), rnd.nextDouble(0.0, 2.0)), "qt$k", true)
                        DiscoverEvent.LiveAppended(longArrayOf(k))
                    }
                    2 -> DiscoverEvent.ViewToggled
                    3 -> DiscoverEvent.SegmentSelected(Segment.entries.random(rnd))
                    4 -> DiscoverEvent.FilterSet(DeviceFilter(readable = rnd.nextBoolean()))
                    5 -> DiscoverEvent.CameraIdle(rnd.nextBoolean(), bbox(rnd), rnd.nextDouble(10.0, 18.0))
                    6 -> {
                        val area = bbox(rnd).takeIf { rnd.nextBoolean() }
                        DiscoverEvent.SearchLoaded("qt", area, CameraSnapshot(GeoPoint(1.0, 1.0), 15.0), world.search("qt", area), back)
                    }
                    7 -> bbox(rnd).let { DiscoverEvent.SearchAreaLoaded(it, 15.0, world.search(state.searching?.query ?: "qt", it)) }
                    8 -> DiscoverEvent.SearchEverywhereLoaded(world.search(state.searching?.query ?: "qt", null))
                    9 -> DiscoverEvent.SearchExited
                    else -> if (pending.isEmpty()) DiscoverEvent.RefreshRequested else pending.removeAt(rnd.nextInt(pending.size))
                }
                apply(event)
            }
            while (pending.isNotEmpty()) apply(pending.removeAt(0))
            // not vacuous: the runs reach their final phase with rows
            if (fail) assertTrue(state.phase is DiscoverPhase.Failed)
            else assertTrue("seed $seed", state.phase is DiscoverPhase.Ready && sessionOrder(state).size > 120)
        }
    }

    @Test
    fun `a segment not offered in the view is ignored, never a crash`() {
        val frozen = DiscoverState(live = false)
        assertEquals(frozen, reduceDiscover(frozen, DiscoverEvent.SegmentSelected(Segment.Nearby)).state)
        val live = DiscoverState(live = true)
        assertEquals(live, reduceDiscover(live, DiscoverEvent.SegmentSelected(Segment.ThisArea)).state)
    }
}
