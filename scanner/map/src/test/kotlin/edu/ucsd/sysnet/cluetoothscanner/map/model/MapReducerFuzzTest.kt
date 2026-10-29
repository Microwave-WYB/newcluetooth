package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Long random event sequences (fixed seeds) through [MapReducer.reduce], with the map's invariants checked after
 * every step. A failure names the seed and step, and the sequence replays exactly.
 */
class MapReducerFuzzTest {
    private val seeds = 1..40
    private val steps = 3_000

    @Test fun `invariants hold after every step of random event sequences`() {
        for (seed in seeds) Fuzz(seed).run(steps)
    }

    @Test fun `the reducer is deterministic`() {
        for (seed in 1..5) {
            val a = Fuzz(seed).run(800)
            val b = Fuzz(seed).run(800)
            assertEquals("seed $seed", a, b)
        }
    }

    private class Fuzz(private val seed: Int) {
        private val rnd = Random(seed)
        private var state = MapState.Initial
        private var now = 0L
        /** Every payload id an event handed the reducer. */
        private val issuedPayloads = HashSet<PayloadId>()
        private var nextPayload = 1L
        /** Queries the reducer asked for, newest last, so results can answer fresh and stale requests alike. */
        private val queries = ArrayList<MapEffect.Query>()
        private var maxRequest = 0L
        /** A gesture started since the last idle notice. */
        private var gestureSinceIdle = false
        /** The newest map_points request the reducer asked for. */
        private var newestPoints: RequestId? = null

        private val center = GeoPoint(-117.234, 32.88)
        private val scopes = listOf(Scope.All, Scope.session("a"), Scope.session("b"))
        private val filters = listOf(DeviceFilter(), DeviceFilter(query = "qt"), DeviceFilter(trackers = true))
        private val keys = listOf(1L, 2L, 0xAABBCC001122)

        fun run(n: Int): MapState {
            for (i in 0 until n) {
                val event = event()
                val before = state
                val step = MapReducer.reduce(state, event)
                state = step.state
                try {
                    check(event, before, step)
                } catch (e: AssertionError) {
                    fail("seed $seed step $i after $event: ${e.message}")
                }
            }
            return state
        }

        private fun point() = GeoPoint(center.lon + rnd.nextDouble(-0.05, 0.05), center.lat + rnd.nextDouble(-0.05, 0.05))
        private fun camera() = CameraSnapshot(point(), rnd.nextDouble(3.0, 19.0))
        private fun viewport() = if (rnd.nextInt(8) == 0) Viewport(0.0, 0.0) else Viewport(412.0, 915.0)
        private fun bbox(): Bbox {
            val p = point()
            return Bbox(p.lon, p.lat, p.lon + rnd.nextDouble(0.0, 0.02), p.lat + rnd.nextDouble(0.0, 0.02))
        }
        private fun payload() = PayloadId(nextPayload++).also { issuedPayloads += it }
        private fun <T> pick(xs: List<T>) = xs[rnd.nextInt(xs.size)]

        /** A result for one of the queries asked so far (often the newest, sometimes a stale one, sometimes made up). */
        private fun request(): RequestId = when {
            queries.isEmpty() || rnd.nextInt(10) == 0 -> RequestId(rnd.nextLong(1, maxRequest + 3))
            rnd.nextInt(3) == 0 -> pick(queries).request
            else -> queries.last().request
        }

        private fun event(): MapEvent {
            now += rnd.nextLong(1, 400)
            return when (rnd.nextInt(30)) {
                0 -> { val c = camera(); val v = viewport(); MapEvent.Attached(v, c, MapGeometry.visibleBbox(c, v), rnd.nextBoolean()) }
                1 -> MapEvent.Detached(camera())
                2 -> MapEvent.StyleLoading
                3, 4 -> MapEvent.StyleLoaded
                5, 6 -> MapEvent.MoveStarted(rnd.nextBoolean(), rnd.nextDouble(3.0, 19.0), UptimeMs(now))
                7, 8 -> { val c = camera(); val v = viewport(); MapEvent.Idle(c, v, MapGeometry.visibleBbox(c, v), UptimeMs(now)) }
                9 -> MapEvent.Tap(
                    if (rnd.nextInt(4) == 0) null else Hit(pick(listOf("sightings", "points", "cells", "clusters")), DeviceAddr.addrOf(pick(keys)), point()),
                    rnd.nextDouble(3.0, 19.0),
                )
                10 -> MapEvent.ClusterExpansion(point(), rnd.nextInt(3, 19))
                11, 12 -> MapEvent.Show(pick(scopes), pick(filters), rnd.nextBoolean())
                13 -> MapEvent.Frame(bbox())
                14 -> MapEvent.Focus(pick(keys), 18.2)
                15 -> MapEvent.Select(if (rnd.nextInt(3) == 0) null else pick(keys))
                16 -> MapEvent.Restore(camera())
                17 -> MapEvent.Cover(CoverInsets(0, rnd.nextInt(0, 600), if (rnd.nextBoolean()) 0 else 412, 0))
                18 -> MapEvent.Locate(rnd.nextDouble(3.0, 19.0))
                19 -> if (rnd.nextBoolean()) MapEvent.Trim else MapEvent.Refill
                20 -> MapEvent.LocationChanged(
                    pick(listOf(LocationStatus.Ok, LocationStatus(LocationStatus.State.Off, warn = true), LocationStatus(LocationStatus.State.Waiting, warn = true))),
                    if (rnd.nextInt(4) == 0) null else MyLocation(point(), 5.0, 0),
                )
                21 -> MapEvent.Live(pick(listOf("a", "b")), List(rnd.nextInt(0, 3)) { point() }, rnd.nextBoolean())
                22 -> MapEvent.LiveTick
                23 -> MapEvent.ViewLoaded(
                    request(),
                    run {
                        val n = rnd.nextInt(0, 4)
                        val pts = List(n) { if (rnd.nextBoolean()) point() else GeoPoint(center.lon + rnd.nextDouble(-2e-4, 2e-4), center.lat + rnd.nextDouble(-2e-4, 2e-4)) }
                        val route = RouteLine.of(payload(), pts)
                        ViewInfo(rnd.nextBoolean(), listOf(pick(listOf("a", "b"))), if (rnd.nextBoolean()) bbox() else null, route)
                    },
                )
                24, 25 -> MapEvent.PointsLoaded(newestPoints?.takeIf { rnd.nextBoolean() } ?: request(), pick(MapMode.entries), payload())
                26 -> MapEvent.RoutesLoaded(request(), payload())
                27 -> MapEvent.SightingsLoaded(request(), payload())
                28 -> MapEvent.FocusResolved(request(), point(), rnd.nextDouble(3.0, 19.0))
                else -> MapEvent.QueryFailed(request())
            }
        }

        private fun check(event: MapEvent, before: MapState, step: Step) {
            val after = step.state
            val ready = (after.surface as? SurfaceState.Attached)?.style == StyleState.Ready

            // 1. no MapLibre call unless a map with a loaded style is there
            val onMap = step.effects.filterIsInstance<MapEffect.OnMap>()
            assertTrue("MapLibre effects $onMap without a ready style (${after.surface})", onMap.isEmpty() || ready)

            // 2. a user gesture always marks the move as the user's; nothing else does
            if (event is MapEvent.MoveStarted && event.byGesture) gestureSinceIdle = true
            for (n in step.effects.filterIsInstance<MapEffect.Notify>().map { it.notice }) {
                if (n is Notice.CameraIdle) {
                    val userMoveWasOn = before.moves is edu.ucsd.sysnet.cluetoothscanner.map.camera.MoveTracking.UserMove
                    assertEquals("idle attribution", userMoveWasOn, n.byUser)
                    if (gestureSinceIdle) assertTrue("a gesture's move must be the user's", n.byUser)
                    if (n.byUser) assertTrue("a user move needs a gesture", gestureSinceIdle)
                    gestureSinceIdle = false
                }
            }

            // 3. the selection always belongs to the shown scope, and its sightings are asked for in that scope
            val ui = MapReducer.ui(after)
            val showing = after.session as? Session.Showing
            if (ui.selection != null) assertTrue("selection without a scope", showing != null)
            for (q in step.effects.filterIsInstance<MapEffect.LoadSightings>()) {
                assertEquals(showing?.scope, q.scope)
                assertEquals((showing?.selection as? Selection.Device)?.key, q.key)
            }

            // 4a. the point sources only ever take the payload of the newest points request
            for (source in listOf(Source.Devices, Source.Cells, Source.Density)) {
                val now = after.sources[source]
                if (now is SourceData.Stored && now != before.sources[source]) {
                    assertTrue("$source took $now from $event", event is MapEvent.PointsLoaded && event.payload == now.id)
                    assertEquals("$source took a stale points result", newestPoints, (event as? MapEvent.PointsLoaded)?.request)
                }
            }
            step.effects.filterIsInstance<MapEffect.LoadPoints>().lastOrNull()?.let { newestPoints = it.request }

            // 4b. after a trim the point data is gone from the state, and the runner is told to forget it
            if (event == MapEvent.Trim) {
                for (source in MapReducer.TRIMMED_SOURCES) assertTrue("$source kept after Trim", source !in after.sources)
                assertTrue(step.effects.any { it is MapEffect.DropSources && it.sources == MapReducer.TRIMMED_SOURCES })
            }

            // 4c. every payload a source shows was handed in by an event; routes grow only by their new points
            for (data in after.sources.values) when (data) {
                is SourceData.Stored -> assertTrue("unknown payload $data", data.id in issuedPayloads)
                is SourceData.Line -> assertTrue("unknown route $data", data.route.base in issuedPayloads)
                SourceData.Empty, is SourceData.Ends, is SourceData.Me -> Unit
            }
            for (a in step.effects.filterIsInstance<MapEffect.AppendRoute>()) {
                assertTrue(event is MapEvent.Live)
                assertEquals((event as? MapEvent.Live)?.routeAppended, a.points)
            }

            // 5. a camera intent waits only while the map cannot take it
            when (val p = after.pending) {
                null -> Unit
                is CameraIntent.Move -> assertTrue("a move waits while ready", !ready)
                is CameraIntent.Frame -> {
                    val measured = (after.surface as? SurfaceState.Attached)?.viewport?.let(MapReducer::measured) ?: false
                    assertTrue("a frame waits while ready and measured: $p", !(ready && measured))
                }
            }

            // 6. queries: fresh ids, points only for a shown scope on an attached map, and the in-flight request is the newest asked
            for (q in step.effects.filterIsInstance<MapEffect.Query>()) {
                assertTrue("request ids grow", q.request.value > maxRequest)
                maxRequest = q.request.value
                queries += q
                if (queries.size > 50) queries.removeAt(0)
            }
            for (q in step.effects.filterIsInstance<MapEffect.LoadPoints>()) {
                assertTrue(showing != null && after.surface is SurfaceState.Attached)
                assertEquals(showing?.scope, q.scope)
                assertEquals(showing?.filter, q.filter)
                assertEquals(q.request, (showing?.points as? PointsState.Requested)?.request)
            }

            // 7. the locate button and the warning never show together; no location, no button
            assertTrue(!(ui.showLocate && ui.showWarning))
            if (after.me == null) assertTrue(!ui.showLocate)

            // 8. a ready style is always replayed in full when it (re)loads
            if (event == MapEvent.StyleLoaded && ready) {
                val replay = step.effects.filterIsInstance<MapEffect.ReplaySources>().single()
                assertEquals(after.sources, replay.sources)
            }

            // 10. M-8: Start and End are set only for a route that got farther than 50 m from its first point
            if (after.sources[Source.Ends] is SourceData.Ends && after.sources[Source.Ends] != before.sources[Source.Ends]) {
                val route = ((showing?.view as? ViewState.Ready)?.info?.route)
                assertTrue("Start/End on a stationary route $route", route != null && route.length > 1 && route.farthestFromStartM > 50.0)
            }

            // 9. the first-run location shows only while no scope is shown
            if (showing != null && event is MapEvent.LocationChanged) {
                assertTrue(step.effects.none { it is MapEffect.SetSource<*> && it.source == Source.Me })
            }
        }
    }
}
