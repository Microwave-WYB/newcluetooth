package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceLive
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.map.stub.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.map.style.SelectionProp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SESSION = "0190a0b0-0000-7000-8000-000000000001"
private val CENTER = GeoPoint(-117.2340, 32.8801)

/** One device per key, along a line east of CENTER; the "name" drives the Q-1 stand-in below. */
private class FakeDevice(val key: DeviceKey, val name: String?, val at: GeoPoint)

private class FakeQueries(val devices: List<FakeDevice>, val isLive: Boolean = false, val cellsAbove: Int = 10_000) : UnusedCoreQueries() {
    override val live = MutableSharedFlow<LiveDelta>(extraBufferCapacity = 16)
    val mapPointCalls = ArrayList<Pair<DeviceFilter, Bbox>>()
    val route = listOf(CENTER, GeoPoint(CENTER.lon + 0.01, CENTER.lat), GeoPoint(CENTER.lon + 0.01, CENTER.lat + 0.01))

    override suspend fun sessionView(scope: Scope) = SessionView(
        scope, live = isLive, sessionIds = listOf(SESSION), deviceCount = devices.size,
        frame = MapGeometry.bboxOf(route + devices.map { it.at }), route = route, routesFaint = scope == Scope.All, seq = 0,
    )
    override suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double) = GeoJson.routes(listOf(route, route.reversed()))
    override suspend fun deviceRows(scope: Scope, keys: LongArray) = devices.filter { it.key in keys }.map {
        DeviceRow(it.key, "", it.name, null, emptyList(), emptyList(), false, false, null, -60, true, null, it.at, 0)
    }
    override suspend fun deviceSightings(scope: Scope, key: DeviceKey) =
        devices.filter { it.key == key }.map { Sighting(it.at, -70, 0) }

    override suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData {
        mapPointCalls += filter to viewport
        // the core's Q-1 in miniature: a name substring
        val q = filter.query
        val shown = devices.filter { d -> q == null || d.name?.contains(q, ignoreCase = true) == true }
        if (shown.size > cellsAbove) return MapData(MapMode.Cells, shown.size, CELLS_JSON, 0)
        val features = shown.joinToString(",") { d ->
            """{"type":"Feature","properties":{"addr":"${edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr.addrOf(d.key)}"},""" +
                """"geometry":{"type":"Point","coordinates":[${d.at.lon},${d.at.lat}]}}"""
        }
        return MapData(MapMode.Points, shown.size, """{"type":"FeatureCollection","features":[$features]}""", 0)
    }

    companion object {
        const val CELLS_JSON = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"point_count":12000,"point_count_abbreviated":"12k"},"geometry":{"type":"Point","coordinates":[0,0]}}]}"""
    }
}

private class FakeLocation(status: LocationStatus, me: MyLocation?) : LocationStatusSource {
    override val status = MutableStateFlow(status)
    override val myLocation = MutableStateFlow(me)
}

/** Behaves like MapLibre for the state: an app move starts with a non-gesture reason and ends in idle. */
private class FakeSurface(override val viewport: Viewport = Viewport(412.0, 915.0)) : MapSurface {
    lateinit var listener: MapSurfaceListener
    override var camera = CameraSnapshot(CENTER, 15.0)
    override val visibleBbox get() = MapGeometry.visibleBbox(camera, viewport)
    val sources = HashMap<String, String>()
    val moves = ArrayList<Pair<CameraSnapshot, Int>>()
    var selection: List<SelectionProp> = emptyList()
    var lineOpacity = -1.0
    var expansionZoom = 14
    /** The state's clock (ms). */
    var now = 0L
    /** MapLibre may report an app move's idle later, not inside move(). */
    var asyncIdle = false

    override fun setGeoJson(source: String, geojson: String) { sources[source] = geojson }
    override fun applySelection(props: List<SelectionProp>) { selection = props }
    override fun setRouteOpacity(opacity: Double) { lineOpacity = opacity }
    override fun move(camera: CameraSnapshot, durationMs: Int) {
        moves += camera to durationMs
        listener.onMoveStarted(byGesture = false)
        this.camera = camera
        if (!asyncIdle) listener.onIdle()
    }
    override fun clusterExpansionZoom(hit: Hit) = expansionZoom

    /** The finger lifted and MapLibre already reported idle: the fling runs as a second gesture move. */
    fun fling(to: CameraSnapshot) {
        now += 16
        listener.onMoveStarted(byGesture = true)
        camera = to
        now += 600
        listener.onIdle()
    }

    /** The user drags (and maybe pinches) the map to [to]. */
    fun userMove(to: CameraSnapshot) {
        now += 1_000 // a new touch
        listener.onMoveStarted(byGesture = true)
        listener.onMoveStarted(byGesture = true)
        camera = to
        listener.onIdle()
    }
}

private class RecordingEvents : MapEvents {
    var gestureStarts = 0
    val idles = ArrayList<Boolean>()
    val taps = ArrayList<Any>()
    override fun onUserGestureStart() { gestureStarts++ }
    override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) { idles += byUser }
    override fun onDeviceTap(key: DeviceKey) { taps += key }
    override fun onEmptyTap() { taps += "empty" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CluetoothMapStateTest {
    private val ok = LocationStatus(LocationStatus.State.Ok, warn = false)
    private val devices = (0 until 5).map { i ->
        FakeDevice(0xAABBCC000000 + i, if (i % 2 == 0) "QT-Sensor $i" else "Other $i", GeoPoint(CENTER.lon + i * 0.001, CENTER.lat))
    }

    private class Rig(val state: CluetoothMapState, val surface: FakeSurface, val events: RecordingEvents,
                      val queries: FakeQueries, val location: FakeLocation)

    private fun TestScope.rig(
        queries: FakeQueries = FakeQueries(devices),
        location: FakeLocation = FakeLocation(ok, MyLocation(CENTER, 5.0, 0)),
    ): Rig {
        val surface = FakeSurface()
        val state = CluetoothMapState(queries, location, Design.style, backgroundScope, clock = { surface.now })
        val events = RecordingEvents()
        state.events = events
        surface.listener = state
        state.attach(surface)
        runCurrent()
        return Rig(state, surface, events, queries, location)
    }

    @Test fun `M-10 V-7 showing a session frames it without animation, with the M-10 padding`() = runTest {
        val r = rig()
        r.state.setCover(CoverInsets(0, 78, 0, 0))
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        val (cam, duration) = r.surface.moves.last()
        assertEquals(0, duration)
        val view = r.queries.sessionView(Scope.session(SESSION))
        assertEquals(MapGeometry.fit(view.frame!!, r.surface.viewport, MapGeometry.framePadding(CoverInsets(0, 78, 0, 0))), cam)
        assertTrue(cam.zoom <= 17.0)
    }

    @Test fun `D-8 frame is animated with the same padding`() = runTest {
        val r = rig()
        r.state.setCover(CoverInsets(0, 600, 0, 0))
        val bbox = Bbox(-117.24, 32.87, -117.23, 32.88)
        r.state.frame(bbox)
        val (cam, duration) = r.surface.moves.last()
        assertEquals(CluetoothMapState.FRAME_EASE_MS, duration)
        assertEquals(MapGeometry.fit(bbox, r.surface.viewport, MapGeometry.framePadding(CoverInsets(0, 600, 0, 0))), cam)
    }

    @Test fun `M-3 M-5 points fill devices and density, Cells fill cells`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        assertTrue(r.surface.sources.getValue("devices").contains("AA:BB:CC:00:00:04"))
        assertEquals(r.surface.sources["devices"], r.surface.sources["density"])
        assertEquals(GeoJson.EMPTY, r.surface.sources["cells"])

        val many = rig(FakeQueries(devices, cellsAbove = 2))
        many.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        assertEquals(GeoJson.EMPTY, many.surface.sources["devices"])
        assertEquals(FakeQueries.CELLS_JSON, many.surface.sources["cells"])
        assertEquals(FakeQueries.CELLS_JSON, many.surface.sources["density"])
    }

    @Test fun `Q-5 filter query reaches map_points and only the matches are drawn`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(query = "qt"), routesFaint = false)
        runCurrent()
        assertEquals("qt", r.queries.mapPointCalls.last().first.query)
        val drawn = r.surface.sources.getValue("devices")
        for (d in devices) {
            val addr = edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr.addrOf(d.key)
            assertEquals(d.name!!.startsWith("QT"), drawn.contains(addr))
        }
        // leaving search: the same scope with the saved filter redraws everything
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        assertTrue(devices.all { r.surface.sources.getValue("devices").contains(edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr.addrOf(it.key)) })
    }

    @Test fun `byUser - app moves report false and never start a gesture, the user's report true once per move`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        r.state.restore(CameraSnapshot(CENTER, 12.0))
        r.state.focusDevice(devices[2].key)
        runCurrent()
        assertEquals(0, r.events.gestureStarts)
        assertTrue(r.events.idles.isNotEmpty() && r.events.idles.none { it })

        r.events.idles.clear()
        r.surface.userMove(CameraSnapshot(GeoPoint(CENTER.lon + 0.05, CENTER.lat), 12.0))
        assertEquals(1, r.events.gestureStarts)
        assertEquals(listOf(true), r.events.idles)
    }

    @Test fun `D-14 focusDevice eases to the device at zoom at least 18_2, clear of the sheet`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        r.state.setCover(CoverInsets(0, 300, 0, 0))
        r.state.focusDevice(devices[3].key)
        runCurrent()
        val (cam, duration) = r.surface.moves.last()
        assertEquals(CluetoothMapState.FOCUS_EASE_MS, duration)
        assertEquals(MapGeometry.centerFor(devices[3].at, 18.2, MapGeometry.focusPadding(CoverInsets(0, 300, 0, 0))), cam)
    }

    @Test fun `M-6 selection applies selectionProps and the device's sightings, clearing restores`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        r.state.setSelection(devices[1].key)
        runCurrent()
        assertEquals(Design.style.selection("AA:BB:CC:00:00:01"), r.surface.selection)
        assertEquals(GeoJson.sightings(listOf(Sighting(devices[1].at, -70, 0))), r.surface.sources["sightings"])
        assertEquals(devices[1].key, r.state.ui.value.selection)
        r.state.setSelection(null)
        assertEquals(Design.style.selection(null), r.surface.selection)
        assertEquals(GeoJson.EMPTY, r.surface.sources["sightings"])
    }

    @Test fun `M-7 M-4 taps - point opens, cluster eases to expansion + 0_2, empty reports, sighting is ignored`() = runTest {
        val r = rig()
        r.state.onTap(Hit("points", "AA:BB:CC:00:00:02", devices[2].at))
        r.state.onTap(null)
        r.state.onTap(Hit("sightings", null, devices[2].at))
        assertEquals(listOf<Any>(devices[2].key, "empty"), r.events.taps)

        r.surface.expansionZoom = 16
        r.state.onTap(Hit("clusters", null, devices[0].at))
        assertEquals(CameraSnapshot(devices[0].at, 16.2) to CluetoothMapState.CLUSTER_EASE_MS, r.surface.moves.last())
        r.state.onTap(Hit("cells", null, devices[0].at))
        assertEquals(18.2, r.surface.moves.last().first.zoom, 1e-9)
        assertEquals(0, r.events.gestureStarts)
    }

    @Test fun `P-4 the locate button shows after a user move away, never after app moves, and hides on locate`() = runTest {
        val r = rig()
        assertFalse(r.state.ui.value.showLocate)
        // an app move far away does not show it
        r.state.restore(CameraSnapshot(GeoPoint(CENTER.lon + 1, CENTER.lat), 15.0))
        assertFalse(r.state.ui.value.showLocate)
        // the user's pan away shows it
        r.surface.userMove(CameraSnapshot(GeoPoint(CENTER.lon + 0.02, CENTER.lat), 15.0))
        assertTrue(r.state.ui.value.showLocate)
        r.state.locate()
        assertFalse(r.state.ui.value.showLocate)
        val (cam, duration) = r.surface.moves.last()
        assertEquals(CluetoothMapState.LOCATE_EASE_MS, duration)
        assertEquals(17.0, cam.zoom, 0.0)
    }

    @Test fun `P-5 an unusable location shows the warning instead`() = runTest {
        val r = rig()
        r.surface.userMove(CameraSnapshot(CENTER, 17.0))
        assertTrue(r.state.ui.value.showLocate)
        r.location.status.value = LocationStatus(LocationStatus.State.Off, warn = true, action = LocationStatus.Action.TurnOn)
        runCurrent()
        assertTrue(r.state.ui.value.showWarning)
        assertFalse(r.state.ui.value.showLocate)
        r.location.status.value = ok
        runCurrent()
        assertFalse(r.state.ui.value.showWarning)
    }

    @Test fun `M-9 with no session only the location shows, at zoom 16`() = runTest {
        val r = rig()
        assertEquals(GeoJson.me(CENTER), r.surface.sources["me"])
        assertEquals(CameraSnapshot(CENTER, 16.0), r.surface.camera)
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        assertEquals(GeoJson.EMPTY, r.surface.sources["me"])
    }

    @Test fun `M-8 one session draws its route with Start and End, All sessions every route faint without them`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        assertEquals(Design.style.routeOpacitySession, r.surface.lineOpacity, 0.0)
        assertEquals(GeoJson.routes(listOf(r.queries.route)), r.surface.sources["route"])
        assertEquals(GeoJson.ends(r.queries.route, Design.style.endProperties), r.surface.sources["ends"])
        assertTrue(r.surface.sources.getValue("ends").contains("\"start\"") && r.surface.sources.getValue("ends").contains("\"end\""))

        r.state.show(Scope.All, DeviceFilter(), routesFaint = true)
        runCurrent()
        assertEquals(Design.style.routeOpacityAllSessions, r.surface.lineOpacity, 0.0)
        assertEquals(GeoJson.EMPTY, r.surface.sources["ends"])
        assertEquals(r.queries.routes(Scope.All, null, 0.0), r.surface.sources["route"])
    }

    @Test fun `live route appends at once, best changes reload points at most every 5 s`() = runTest {
        val q = FakeQueries(devices, isLive = true)
        val r = rig(q)
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        val calls = q.mapPointCalls.size
        val next = GeoPoint(CENTER.lon + 0.02, CENTER.lat + 0.01)
        val state = CoreRecordingState(RecordingPhase.Recording, SESSION, 0, 0, 0, devices.size, 0.0)
        q.live.emit(LiveDelta(SESSION, 1, 0, state, listOf(DeviceLive(devices[0].key, -50, true, ByteArray(0), null, null, next)),
            emptyList(), emptyList(), listOf(next), null, null))
        runCurrent()
        assertEquals(GeoJson.routes(listOf(q.route + next)), r.surface.sources["route"])
        assertEquals(GeoJson.ends(q.route + next, Design.style.endProperties), r.surface.sources["ends"])
        assertEquals(calls, q.mapPointCalls.size)
        advanceTimeBy(CluetoothMapState.LIVE_POINTS_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(calls + 1, q.mapPointCalls.size)
        // nothing changed: no further reload
        advanceTimeBy(CluetoothMapState.LIVE_POINTS_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(calls + 1, q.mapPointCalls.size)
    }

    @Test fun `idle inside the loaded area does not re-query points, outside it does`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        val calls = r.queries.mapPointCalls.size
        val cam = r.surface.camera
        r.surface.userMove(cam.copy(zoom = cam.zoom + 0.5))
        runCurrent()
        assertEquals(calls, r.queries.mapPointCalls.size)
        r.surface.userMove(cam.copy(center = GeoPoint(cam.center.lon + 1.0, cam.center.lat)))
        runCurrent()
        assertEquals(calls + 1, r.queries.mapPointCalls.size)
    }

    @Test fun `requests made before a map attaches are applied on attach, sources replay on style reload`() = runTest {
        val q = FakeQueries(devices)
        val state = CluetoothMapState(q, FakeLocation(ok, null), Design.style, backgroundScope)
        state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        state.setSelection(devices[0].key)
        runCurrent()
        val surface = FakeSurface().also { it.listener = state }
        state.attach(surface)
        runCurrent()
        assertTrue(surface.sources.getValue("devices").contains("AA:BB:CC:00:00:00"))
        assertEquals(Design.style.selection("AA:BB:CC:00:00:00"), surface.selection)
        surface.sources.clear()
        state.onStyleLoaded()
        assertTrue(surface.sources.getValue("devices").contains("AA:BB:CC:00:00:00"))
        assertEquals(GeoJson.routes(listOf(q.route)), surface.sources["route"])
    }

    @Test fun `a session switch costs one map_points query, also when the frame's idle comes later`() = runTest {
        for (async in listOf(false, true)) {
            val r = rig()
            r.surface.asyncIdle = async
            val before = r.queries.mapPointCalls.size
            r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
            runCurrent()
            if (async) { r.surface.listener.onIdle(); runCurrent() }
            assertEquals("asyncIdle=$async", before + 1, r.queries.mapPointCalls.size)
        }
    }

    @Test fun `the fling edge - a fling right after the drag's idle is the user's, but no second gesture start`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        runCurrent()
        r.events.idles.clear()
        val cam = r.surface.camera
        r.surface.userMove(cam.copy(center = GeoPoint(cam.center.lon + 0.001, cam.center.lat)))
        r.surface.fling(cam.copy(center = GeoPoint(cam.center.lon + 0.02, cam.center.lat)))
        assertEquals(1, r.events.gestureStarts)
        assertEquals(listOf(true, true), r.events.idles)
        // P-4 measures at the fling's end: far from the location now
        assertTrue(r.state.ui.value.showLocate)
        // a later, separate touch is a new gesture
        r.surface.userMove(cam)
        assertEquals(2, r.events.gestureStarts)
    }

    @Test fun `trimMemory drops the cached point data and refills it when shown again`() = runTest {
        val r = rig()
        r.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false)
        r.state.setSelection(devices[1].key)
        runCurrent()
        val calls = r.queries.mapPointCalls.size
        r.state.trimMemory()
        r.surface.sources.clear()
        r.state.onStyleLoaded() // a replay right after the trim refills from the core, not the cache
        runCurrent()
        assertEquals(calls + 1, r.queries.mapPointCalls.size)
        assertTrue(r.surface.sources.getValue("devices").contains("AA:BB:CC:00:00:01"))
        assertEquals(GeoJson.sightings(listOf(Sighting(devices[1].at, -70, 0))), r.surface.sources["sightings"])
        assertEquals(GeoJson.routes(listOf(r.queries.route)), r.surface.sources["route"])

        // onStop then onStart, without a style reload
        r.state.trimMemory()
        r.state.refillAfterTrim()
        runCurrent()
        assertEquals(calls + 2, r.queries.mapPointCalls.size)
        r.state.refillAfterTrim() // nothing trimmed: no query
        runCurrent()
        assertEquals(calls + 2, r.queries.mapPointCalls.size)
    }
}
