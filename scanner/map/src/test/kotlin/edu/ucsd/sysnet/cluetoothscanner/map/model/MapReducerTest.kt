package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reducer alone: request ordering against the surface, the style and stale results (no MapLibre, no device). */
class MapReducerTest {
    private val sd = GeoPoint(-117.234, 32.88)
    private val view = Viewport(412.0, 915.0)
    private val cam = CameraSnapshot(sd, 15.0)
    private val session = Scope.session("s1")
    private val bbox = Bbox(-117.25, 32.87, -117.22, 32.89)

    private class Run(var state: MapState = MapState.Initial) {
        val log = ArrayList<Pair<MapEvent, List<MapEffect>>>()
        fun send(e: MapEvent): List<MapEffect> = MapReducer.reduce(state, e).also { state = it.state; log += e to it.effects }.effects
    }

    private fun attached(ready: Boolean = true) = MapEvent.Attached(view, cam, MapGeometry.visibleBbox(cam, view), styleReady = ready)
    private fun idle(c: CameraSnapshot, at: Long = 0) = MapEvent.Idle(c, view, MapGeometry.visibleBbox(c, view), UptimeMs(at))
    private fun List<MapEffect>.onMap() = filterIsInstance<MapEffect.OnMap>()
    private inline fun <reified T : MapEffect> List<MapEffect>.only(): List<T> = filterIsInstance<T>()
    private val route = RouteLine(PayloadId(100), 2, sd, GeoPoint(sd.lon + 0.01, sd.lat))
    private fun viewInfo(frame: Bbox? = bbox, live: Boolean = false) =
        ViewInfo(live = live, sessionIds = listOf("s1"), frame = frame, route = route)

    @Test fun `requests that arrive before the map and its style wait, then run in order on style load`() {
        val r = Run()
        // no map yet: the view query runs, nothing touches MapLibre
        val shown = r.send(MapEvent.Show(session, DeviceFilter(), routesFaint = false))
        val loadView = shown.only<MapEffect.LoadView>().single()
        assertTrue(shown.onMap().isEmpty())
        assertTrue(r.send(MapEvent.Frame(bbox)).isEmpty())
        val selected = r.send(MapEvent.Select(7L))
        assertTrue(selected.onMap().isEmpty())
        assertEquals(1, selected.only<MapEffect.LoadSightings>().size)
        r.send(MapEvent.ViewLoaded(loadView.request, viewInfo(frame = null)))
        // a map whose style is still loading: still nothing on MapLibre, no points without a camera in place
        val att = r.send(attached(ready = false))
        assertTrue(att.onMap().isEmpty())
        assertTrue(att.only<MapEffect.LoadPoints>().isEmpty())
        // the style loads: replay, opacity, selection, then the waiting frame, then the points of that frame
        val loaded = r.send(MapEvent.StyleLoaded)
        val replay = loaded.indexOfFirst { it is MapEffect.ReplaySources }
        val move = loaded.indexOfFirst { it is MapEffect.Move }
        val points = loaded.indexOfFirst { it is MapEffect.LoadPoints }
        assertTrue("$loaded", replay in 0 until move && move < points)
        val m = loaded.only<MapEffect.Move>().single()
        assertEquals(MapGeometry.fit(bbox, view, MapGeometry.framePadding(CoverInsets(0, 0, 0, 0))), m.camera)
        assertEquals(MapReducer.FRAME_EASE, m.duration)
        assertEquals(MapEffect.ApplySelection(7L), loaded.only<MapEffect.ApplySelection>().single())
        val replayed = loaded.only<MapEffect.ReplaySources>().single().sources
        assertEquals(SourceData.Line(route), replayed[Source.Route])
        assertEquals(null, r.state.pending)
    }

    @Test fun `the last camera request wins while the map cannot move`() {
        val r = Run()
        r.send(MapEvent.Frame(bbox))
        r.send(MapEvent.Restore(CameraSnapshot(sd, 12.0)))
        val moves = r.send(attached()).only<MapEffect.Move>()
        assertEquals(listOf(MapEffect.Move(CameraSnapshot(sd, 12.0), MapReducer.RESTORE_EASE)), moves)
    }

    @Test fun `a frame request during a user gesture runs at once, and the move stays the user's`() {
        val r = Run()
        r.send(attached())
        val started = r.send(MapEvent.MoveStarted(byGesture = true, zoom = 15.0, at = UptimeMs(1_000)))
        assertEquals(listOf(MapEffect.Notify(Notice.GestureStart)), started)
        val framed = r.send(MapEvent.Frame(bbox))
        assertEquals(1, framed.only<MapEffect.Move>().size)
        // MapLibre reports our ease as a non-gesture start inside the same move: no new gesture, no reattribution
        assertTrue(r.send(MapEvent.MoveStarted(byGesture = false, zoom = 15.0, at = UptimeMs(1_010))).isEmpty())
        val rested = r.send(idle(CameraSnapshot(sd, 16.0), at = 1_500))
        val notice = rested.only<MapEffect.Notify>().map { it.notice }.filterIsInstance<Notice.CameraIdle>().single()
        assertTrue(notice.byUser)
    }

    @Test fun `an app move alone is never the user's and starts no gesture`() {
        val r = Run()
        r.send(attached())
        r.send(MapEvent.Restore(CameraSnapshot(sd, 12.0)))
        assertTrue(r.send(MapEvent.MoveStarted(byGesture = false, zoom = 15.0, at = UptimeMs(10))).isEmpty())
        val notice = r.send(idle(CameraSnapshot(sd, 12.0), 400)).only<MapEffect.Notify>().single().notice
        assertEquals(false, (notice as Notice.CameraIdle).byUser)
    }

    @Test fun `a frame waits for a measured viewport`() {
        val r = Run()
        r.send(MapEvent.Attached(Viewport(0.0, 0.0), cam, MapGeometry.visibleBbox(cam, view), styleReady = true))
        r.send(MapEvent.Frame(bbox)).let { assertTrue(it.only<MapEffect.Move>().isEmpty()) }
        assertTrue(r.state.pending is CameraIntent.Frame)
        val moved = r.send(idle(cam)).only<MapEffect.Move>()
        assertEquals(1, moved.size)
        assertEquals(null, r.state.pending)
    }

    @Test fun `results of an older view, points or sightings request are dropped`() {
        val r = Run()
        r.send(attached())
        val a = r.send(MapEvent.Show(session, DeviceFilter(), false)).only<MapEffect.LoadView>().single()
        val b = r.send(MapEvent.Show(Scope.All, DeviceFilter(), true)).only<MapEffect.LoadView>().single()
        assertTrue(r.send(MapEvent.ViewLoaded(a.request, viewInfo())).isEmpty())
        val loaded = r.send(MapEvent.ViewLoaded(b.request, viewInfo()))
        val points = loaded.only<MapEffect.LoadPoints>().single()
        // a filter change asks again; the first answer is stale
        val again = r.send(MapEvent.Show(Scope.All, DeviceFilter(query = "qt"), true)).only<MapEffect.LoadPoints>().single()
        assertTrue(r.send(MapEvent.PointsLoaded(points.request, MapMode.Points, PayloadId(1))).isEmpty())
        val applied = r.send(MapEvent.PointsLoaded(again.request, MapMode.Points, PayloadId(2)))
        assertTrue(MapEffect.SetSource(Source.Devices, SourceData.Stored(PayloadId(2))) in applied)
        // sightings of a device that is no longer selected
        val s1 = r.send(MapEvent.Select(1L)).only<MapEffect.LoadSightings>().single()
        r.send(MapEvent.Select(2L))
        assertTrue(r.send(MapEvent.SightingsLoaded(s1.request, PayloadId(3))).isEmpty())
    }

    @Test fun `a new scope clears the selection, and nothing selects without a scope`() {
        val r = Run()
        r.send(attached())
        assertTrue(r.send(MapEvent.Select(5L)).only<MapEffect.LoadSightings>().isEmpty())
        assertEquals(null, MapReducer.ui(r.state).selection)
        r.send(MapEvent.Show(session, DeviceFilter(), false))
        r.send(MapEvent.Select(5L))
        assertEquals(5L, MapReducer.ui(r.state).selection)
        val switched = r.send(MapEvent.Show(Scope.All, DeviceFilter(), true))
        assertEquals(null, MapReducer.ui(r.state).selection)
        assertTrue(MapEffect.ApplySelection(null) in switched)
        assertEquals(SourceData.Empty, r.state.sources[Source.Sightings])
    }

    @Test fun `a style reload holds MapLibre calls until it loads, then replays them`() {
        val r = Run()
        r.send(attached())
        r.send(MapEvent.Show(session, DeviceFilter(), false))
        r.send(MapEvent.StyleLoading)
        assertTrue(r.send(MapEvent.Select(9L)).onMap().isEmpty())
        assertTrue(r.send(MapEvent.Restore(cam)).onMap().isEmpty())
        val loaded = r.send(MapEvent.StyleLoaded)
        assertTrue(MapEffect.ApplySelection(9L) in loaded)
        assertTrue(MapEffect.Move(cam, MapReducer.RESTORE_EASE) in loaded)
    }

    @Test fun `a re-attached map starts where the last one was`() {
        val r = Run()
        r.send(attached())
        val where = CameraSnapshot(sd, 13.5)
        r.send(MapEvent.Detached(where))
        assertEquals(where, MapReducer.knownCamera(r.state))
        assertEquals(listOf(MapEffect.Move(where, MapReducer.JUMP)), r.send(attached()).only<MapEffect.Move>())
    }

    @Test fun `an instant frame moves the known camera, so its own idle asks for no second points query`() {
        val r = Run()
        r.send(attached())
        val load = r.send(MapEvent.Show(session, DeviceFilter(), false)).only<MapEffect.LoadView>().single()
        val effects = r.send(MapEvent.ViewLoaded(load.request, viewInfo()))
        val move = effects.only<MapEffect.Move>().single()
        assertEquals(MapReducer.JUMP, move.duration)
        val points = effects.only<MapEffect.LoadPoints>().single()
        assertEquals(MapGeometry.visibleBbox(move.camera, view), points.viewport)
        assertTrue(r.send(idle(move.camera)).only<MapEffect.LoadPoints>().isEmpty())
    }

    @Test fun `trim drops the point data and a refill asks for it again`() {
        val r = Run()
        r.send(attached())
        val load = r.send(MapEvent.Show(session, DeviceFilter(), false)).only<MapEffect.LoadView>().single()
        val p = r.send(MapEvent.ViewLoaded(load.request, viewInfo())).only<MapEffect.LoadPoints>().single()
        r.send(MapEvent.PointsLoaded(p.request, MapMode.Points, PayloadId(1)))
        r.send(MapEvent.Trim)
        assertFalse(Source.Devices in r.state.sources)
        assertEquals(1, r.send(MapEvent.Refill).only<MapEffect.LoadPoints>().size)
        assertTrue(r.send(MapEvent.Refill).isEmpty())
    }

    @Test fun `a live delta sends only its new points, never the route`() {
        val r = Run()
        r.send(attached())
        val load = r.send(MapEvent.Show(session, DeviceFilter(), false)).only<MapEffect.LoadView>().single()
        r.send(MapEvent.ViewLoaded(load.request, viewInfo(live = true)))
        val next = GeoPoint(sd.lon + 0.02, sd.lat)
        val effects = r.send(MapEvent.Live("s1", listOf(next), pointsChanged = false))
        assertEquals(MapEffect.AppendRoute(route.base, listOf(next)), effects.only<MapEffect.AppendRoute>().single())
        assertEquals(SourceData.Line(route.copy(length = 3, end = next)), r.state.sources[Source.Route])
        assertEquals(SourceData.Ends(sd, next), r.state.sources[Source.Ends])
        // another session's delta is ignored
        assertTrue(r.send(MapEvent.Live("other", listOf(next), pointsChanged = true)).isEmpty())
    }
}
