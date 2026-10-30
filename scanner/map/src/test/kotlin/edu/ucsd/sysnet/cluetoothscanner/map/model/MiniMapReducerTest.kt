package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** R-5: the mini map's snapshot decisions, without MapLibre. */
class MiniMapReducerTest {
    private val frame = MiniFrame(840, 352, 2.625f, MapTheme.Light, null)
    private val a = GeoPoint(-117.24, 32.87)
    private val b = GeoPoint(-117.23, 32.88)

    private class Run(var state: MiniMapState = MiniMapState.Initial) {
        fun send(e: MiniMapEvent) = MiniMapReducer.reduce(state, e).also { state = it.state }.effects
    }

    @Test fun `the first tick builds one snapshotter and starts a snapshot, later ticks reuse it`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val first = r.send(MiniMapEvent.Tick)
        val create = first.filterIsInstance<MiniMapEffect.Create>().single()
        val start = first.filterIsInstance<MiniMapEffect.Start>().single()
        assertEquals(create.id, start.id)
        // a tick while it runs does nothing
        assertTrue(r.send(MiniMapEvent.Tick).isEmpty())
        r.send(MiniMapEvent.Done(start.id, start.target))
        // the same route and camera: no new snapshot
        assertTrue(r.send(MiniMapEvent.Tick).isEmpty())
        // a longer route: update the same snapshotter, then snapshot
        r.send(MiniMapEvent.Inputs(listOf(a, b, GeoPoint(-117.22, 32.89)), null))
        val next = r.send(MiniMapEvent.Tick)
        assertEquals(listOf(MiniMapEffect.Update::class, MiniMapEffect.Start::class), next.map { it::class })
        assertTrue(next.none { it is MiniMapEffect.Create })
    }

    @Test fun `M-8 a stationary route snapshots without Start and End, a moving one with them`() {
        val r = Run()
        val still = listOf(a, GeoPoint(a.lon + 0.0001, a.lat + 0.0001), GeoPoint(a.lon - 0.0002, a.lat))
        r.send(MiniMapEvent.Inputs(still, null))
        r.send(MiniMapEvent.Configure(frame))
        val first = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        assertEquals(false, first.target.showEnds)
        r.send(MiniMapEvent.Done(first.id, first.target))
        r.send(MiniMapEvent.Inputs(still + b, null))
        val moved = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        assertEquals(true, moved.target.showEnds)
    }

    @Test fun `no route and no fallback, nothing to show`() {
        val r = Run()
        r.send(MiniMapEvent.Configure(frame))
        assertTrue(r.send(MiniMapEvent.Tick).isEmpty())
        r.send(MiniMapEvent.Inputs(emptyList(), a))
        assertTrue(r.send(MiniMapEvent.Tick).any { it is MiniMapEffect.Create })
    }

    @Test fun `a new frame retires a busy snapshotter until its callback, then releases it, and builds the next`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val old = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        // busy: no release, no cancel; it retires
        assertTrue(r.send(MiniMapEvent.Configure(frame.copy(theme = MapTheme.Dark))).isEmpty())
        assertEquals(setOf(old.id), r.state.retiring)
        // one native snapshotter at a time: nothing new while the old one is still loading
        assertTrue(r.send(MiniMapEvent.Tick).isEmpty())
        // its callback releases it, and the next tick builds one for the new frame
        assertEquals(listOf(MiniMapEffect.Release(old.id)), r.send(MiniMapEvent.Done(old.id, old.target)))
        val created = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Create>().single()
        assertTrue(created.id != old.id)
        assertEquals(MapTheme.Dark, created.frame.theme)
    }

    @Test fun `an idle snapshotter is released at once on a new frame or dispose`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val s = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        r.send(MiniMapEvent.Done(s.id, s.target))
        assertEquals(listOf(MiniMapEffect.Release(s.id)), r.send(MiniMapEvent.Disposed))
        assertEquals(MiniSnapshotter.None, r.state.snapshotter)
    }

    @Test fun `disposing while the style loads keeps the snapshotter until it calls back`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val s = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        assertTrue(r.send(MiniMapEvent.Disposed).isEmpty())
        assertEquals(listOf(MiniMapEffect.Release(s.id)), r.send(MiniMapEvent.Failed(s.id)))
    }

    @Test fun `a failed snapshot keeps the previous image and tries again on the next tick`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val s = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        r.send(MiniMapEvent.Failed(s.id))
        assertEquals(null, r.state.rendered)
        assertEquals(MiniSnapshotter.Idle(s.id, frame), r.state.snapshotter)
        assertTrue(r.send(MiniMapEvent.Tick).any { it is MiniMapEffect.Start })
    }

    /**
     * Random sequences against a model of the native side: callbacks come only for running snapshots (sometimes
     * late, sometimes duplicated). After every step: a running snapshotter is never released (the Note10 SIGSEGV),
     * at most one native snapshotter exists, starts run one at a time on a live idle snapshotter, and every live
     * snapshotter is the state's own or retiring (none is dropped while it could still call back). At the end every
     * snapshotter is released.
     */
    @Test fun `invariants hold after every step of random event sequences`() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            var state = MiniMapState.Initial
            val alive = HashSet<SnapshotterId>()
            val running = HashMap<SnapshotterId, MiniTarget>()
            var creates = 0
            val frames = listOf(frame, frame.copy(theme = MapTheme.Dark), frame.copy(widthPx = 600), null)
            val routes = listOf(emptyList(), listOf(a), listOf(a, b), listOf(a, b, GeoPoint(-117.2, 32.9)))
            fun apply(event: MiniMapEvent, i: Int) {
                val step = MiniMapReducer.reduce(state, event)
                state = step.state
                try {
                    for (e in step.effects) when (e) {
                        is MiniMapEffect.Create -> {
                            assertTrue("one native snapshotter at a time: $alive", alive.isEmpty())
                            alive += e.id; creates++
                        }
                        is MiniMapEffect.Update -> assertTrue(e.id in alive && e.id !in running)
                        is MiniMapEffect.Start -> {
                            assertTrue("start on a live snapshotter", e.id in alive)
                            assertTrue("one snapshot at a time", running.isEmpty())
                            running[e.id] = e.target
                        }
                        is MiniMapEffect.Release -> {
                            assertTrue("released a running snapshotter ${e.id}", e.id !in running)
                            assertTrue(alive.remove(e.id))
                        }
                    }
                    val own = when (val s = state.snapshotter) {
                        MiniSnapshotter.None -> null
                        is MiniSnapshotter.Idle -> s.id
                        is MiniSnapshotter.Busy -> s.id
                    }
                    assertEquals("every live snapshotter is accounted for", alive, (listOfNotNull(own) + state.retiring).toSet())
                    assertEquals("busy exactly while it runs", state.snapshotter is MiniSnapshotter.Busy, own != null && own in running)
                    for (id in state.retiring) assertTrue("retiring $id must still be running", id in running)
                } catch (e: AssertionError) {
                    fail("seed $seed step $i after $event: ${e.message}")
                }
            }
            for (i in 0 until 2_000) {
                val event = when (rnd.nextInt(8)) {
                    0 -> MiniMapEvent.Configure(frames[rnd.nextInt(frames.size)])
                    1 -> MiniMapEvent.Inputs(routes[rnd.nextInt(routes.size)], if (rnd.nextBoolean()) a else null)
                    2, 3 -> MiniMapEvent.Tick
                    4, 5 -> running.entries.randomOrNull(rnd)?.let { (id, t) ->
                        running -= id
                        if (rnd.nextBoolean()) MiniMapEvent.Done(id, t) else MiniMapEvent.Failed(id)
                    } ?: MiniMapEvent.Tick
                    6 -> if (rnd.nextInt(4) == 0) MiniMapEvent.Disposed else MiniMapEvent.Tick
                    // a duplicated or late callback for something no longer running
                    else -> MiniMapEvent.Failed(SnapshotterId(rnd.nextInt(1, state.nextSnapshotter + 1)))
                }
                // the native side calls back at most once per start
                if (event is MiniMapEvent.Failed && event.snapshotter in running) running -= event.snapshotter
                apply(event, i)
            }
            // dispose and let every callback arrive: nothing stays alive
            apply(MiniMapEvent.Disposed, -1)
            for ((id, t) in running.toList()) { running -= id; apply(MiniMapEvent.Done(id, t), -2) }
            assertTrue("seed $seed leaked $alive", alive.isEmpty())
            assertTrue("seed $seed exercised snapshots", creates > 0)
        }
    }
}
