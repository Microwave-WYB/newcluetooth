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

    @Test fun `no route and no fallback, nothing to show`() {
        val r = Run()
        r.send(MiniMapEvent.Configure(frame))
        assertTrue(r.send(MiniMapEvent.Tick).isEmpty())
        r.send(MiniMapEvent.Inputs(emptyList(), a))
        assertTrue(r.send(MiniMapEvent.Tick).any { it is MiniMapEffect.Create })
    }

    @Test fun `a new frame cancels the snapshotter and a late result of the old one is dropped`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val old = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        assertEquals(listOf(MiniMapEffect.Cancel), r.send(MiniMapEvent.Configure(frame.copy(theme = MapTheme.Dark))))
        assertTrue(r.send(MiniMapEvent.Done(old.id, old.target)).isEmpty())
        val fresh = r.send(MiniMapEvent.Tick)
        val created = fresh.filterIsInstance<MiniMapEffect.Create>().single()
        assertTrue(created.id != old.id)
        assertEquals(MapTheme.Dark, created.frame.theme)
    }

    @Test fun `a failed snapshot keeps the previous image and tries again on the next tick`() {
        val r = Run()
        r.send(MiniMapEvent.Inputs(listOf(a, b), null))
        r.send(MiniMapEvent.Configure(frame))
        val s = r.send(MiniMapEvent.Tick).filterIsInstance<MiniMapEffect.Start>().single()
        r.send(MiniMapEvent.Failed(s.id))
        assertEquals(MiniShot.Idle(null), r.state.shot)
        assertTrue(r.send(MiniMapEvent.Tick).any { it is MiniMapEffect.Start })
    }

    /** Random sequences: at most one snapshot in flight, one snapshotter per frame, starts only on a snapshotter. */
    @Test fun `invariants hold after every step of random event sequences`() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            var state = MiniMapState.Initial
            var live: SnapshotterId? = null
            var creates = 0
            var inFlight: SnapshotterId? = null
            val frames = listOf(frame, frame.copy(theme = MapTheme.Dark), frame.copy(widthPx = 600), null)
            val routes = listOf(emptyList(), listOf(a), listOf(a, b), listOf(a, b, GeoPoint(-117.2, 32.9)))
            val started = ArrayList<MiniMapEffect.Start>()
            for (i in 0 until 2_000) {
                val event = when (rnd.nextInt(7)) {
                    0 -> MiniMapEvent.Configure(frames[rnd.nextInt(frames.size)])
                    1 -> MiniMapEvent.Inputs(routes[rnd.nextInt(routes.size)], if (rnd.nextBoolean()) a else null)
                    2, 3 -> MiniMapEvent.Tick
                    4 -> started.randomOrNull(rnd)?.let { MiniMapEvent.Done(it.id, it.target) } ?: MiniMapEvent.Tick
                    5 -> started.randomOrNull(rnd)?.let { MiniMapEvent.Failed(it.id) } ?: MiniMapEvent.Tick
                    else -> if (rnd.nextInt(10) == 0) MiniMapEvent.Disposed else MiniMapEvent.Tick
                }
                val step = MiniMapReducer.reduce(state, event)
                state = step.state
                try {
                    for (e in step.effects) when (e) {
                        is MiniMapEffect.Create -> { assertEquals(null, live); live = e.id; creates++ }
                        is MiniMapEffect.Update -> assertEquals(live, e.id)
                        is MiniMapEffect.Start -> {
                            assertEquals(live, e.id)
                            assertEquals("one snapshot at a time", null, inFlight)
                            inFlight = e.id
                            started += e
                        }
                        MiniMapEffect.Cancel -> { live = null; inFlight = null }
                    }
                    val shot = state.shot
                    if (shot is MiniShot.Idle) inFlight = null
                    assertEquals(shot is MiniShot.InFlight, inFlight != null)
                    val s = state.snapshotter
                    assertEquals((s as? MiniSnapshotter.Created)?.id, live)
                    if (s is MiniSnapshotter.Created) assertEquals("a snapshotter serves its own frame", state.frame, s.frame)
                    if (state.frame == null) assertTrue(step.effects.none { it is MiniMapEffect.Create || it is MiniMapEffect.Start })
                } catch (e: AssertionError) {
                    fail("seed $seed step $i after $event: ${e.message}")
                }
            }
            assertTrue("seed $seed exercised snapshots", creates > 0)
        }
    }
}
