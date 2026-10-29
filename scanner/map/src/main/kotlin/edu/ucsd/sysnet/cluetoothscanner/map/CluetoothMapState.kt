package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.map.camera.LocateLogic
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MoveClassifier
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapAction
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapResolver
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.stub.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.map.stub.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.map.stub.MapRequests
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * The map's state holder and the [MapRequests] implementation (docs/v2-api.md §5.3, §6.6). It outlives the drawn
 * map: requests made while no map is attached are kept and applied when one attaches, and every source is replayed
 * when the style reloads (theme change, onStop/onStart).
 *
 * Main thread only; [scope] must run on Dispatchers.Main (the app's ViewModel scope).
 *
 * - [show] with a new scope frames that session itself, without animation (M-10, V-7). [frame] is the animated
 *   framing D-8 asks for.
 * - With no scope shown yet (first run, V-6) the map shows only the user's location at zoom 16 (M-9).
 */
class CluetoothMapState(
    private val queries: CoreQueries,
    private val location: LocationStatusSource,
    private val style: MapStyleSpec,
    private val scope: CoroutineScope,
) : MapRequests, MapSurfaceListener {

    var events: MapEvents? = null

    private val _ui = MutableStateFlow(
        MapUiState(selection = null, routesFaint = false, cover = CoverInsets(0, 0, 0, 0), showLocate = false, showWarning = false),
    )
    val ui: StateFlow<MapUiState> = _ui.asStateFlow()

    private var surface: MapSurface? = null
    private var lastCamera = CameraSnapshot(GeoPoint(0.0, 0.0), 1.0)
    /** A camera move asked for while detached; applied on attach. */
    private var pendingCamera: Pair<CameraSnapshot, Int>? = null
    /** The last data of every source, replayed on attach and style reload. */
    private val sourceData = LinkedHashMap<String, String>()

    private var shownScope: Scope? = null
    private var filter = DeviceFilter()
    private var view: SessionView? = null
    private val liveRoute = ArrayList<GeoPoint>()
    private var locatedOnce = false

    private val moves = MoveClassifier()
    private val locate = LocateLogic()

    /** What map_points must serve; null until a scope is shown and a viewport is known. */
    private data class PointsRequest(val scope: Scope, val filter: DeviceFilter, val viewport: Bbox, val zoom: Double, val nonce: Int)
    private data class Loaded(val scope: Scope, val filter: DeviceFilter, val area: Bbox, val zoom: Double, val mode: MapMode)
    private val pointsRequest = MutableStateFlow<PointsRequest?>(null)
    private var loaded: Loaded? = null
    private var nonce = 0
    private var liveDirty = false
    private var viewJob: Job? = null
    private var sightingsJob: Job? = null
    private var focusJob: Job? = null

    init {
        scope.launch { pointsRequest.filterNotNull().collectLatest { load(it) } }
        scope.launch {
            combine(location.status, location.myLocation) { s, m -> s to m }.collect { (status, me) ->
                locate.onStatus(status)
                refreshLocateUi()
                if (shownScope == null) {
                    setSource("me", GeoJson.me(me?.at))
                    // M-9: centre on the location once, at zoom 16, until a session is shown
                    if (me != null && !locatedOnce) {
                        locatedOnce = true
                        moveCamera(CameraSnapshot(me.at, FIRST_RUN_ZOOM), 0)
                    }
                }
            }
        }
        scope.launch {
            queries.live.collect { delta ->
                val v = view ?: return@collect
                if (!v.live || v.sessionIds.singleOrNull() != delta.sessionId) return@collect
                if (delta.routeAppended.isNotEmpty()) {
                    liveRoute += delta.routeAppended
                    setSource("route", GeoJson.routes(listOf(liveRoute)))
                    setSource("ends", GeoJson.ends(liveRoute, style.endProperties))
                }
                if (delta.appended.isNotEmpty() || delta.changed.any { it.best != null }) liveDirty = true
            }
        }
        // §2.6 budget: the map applies live `best` changes and appends at most every 5 s
        scope.launch {
            while (true) {
                delay(LIVE_POINTS_INTERVAL_MS)
                if (liveDirty && view?.live == true) {
                    liveDirty = false
                    requestPoints(force = true)
                }
            }
        }
    }

    // ---- attach / detach ----

    fun attach(surface: MapSurface) {
        this.surface = surface
        replay(surface)
        pendingCamera?.let { (c, d) -> surface.move(c, d) } ?: surface.move(lastCamera, 0)
        pendingCamera = null
        pendingFrame?.let { (b, d) -> pendingFrame = null; frameBbox(b, d) }
        requestPoints(force = false)
    }

    fun detach(surface: MapSurface) {
        if (this.surface !== surface) return
        lastCamera = surface.camera
        this.surface = null
    }

    private fun replay(s: MapSurface) {
        for ((id, json) in sourceData) s.setGeoJson(id, json)
        s.setRouteOpacity(if (_ui.value.routesFaint) style.routeOpacityAllSessions else style.routeOpacitySession)
        s.applySelection(style.selection(_ui.value.selection?.let(DeviceAddr::addrOf)))
    }

    private fun setSource(id: String, json: String) {
        if (sourceData[id] == json) return
        sourceData[id] = json
        surface?.setGeoJson(id, json)
    }

    private fun moveCamera(camera: CameraSnapshot, durationMs: Int) {
        val s = surface
        if (s == null) {
            pendingCamera = camera to durationMs
            lastCamera = camera
        } else s.move(camera, durationMs)
    }

    // ---- MapRequests ----

    override fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean) {
        val scopeChanged = scope != shownScope
        val filterChanged = filter != this.filter
        _ui.update { it.copy(routesFaint = routesFaint) }
        surface?.setRouteOpacity(if (routesFaint) style.routeOpacityAllSessions else style.routeOpacitySession)
        this.filter = filter
        if (scopeChanged) {
            shownScope = scope
            setSelection(null)
            setSource("me", GeoJson.EMPTY)
            loadView(scope)
        } else if (filterChanged) requestPoints(force = true)
    }

    /** D-8: animated framing of [bbox] (M-10 padding and maximum zoom). */
    override fun frame(bbox: Bbox) = frameBbox(bbox, FRAME_EASE_MS)

    private fun frameBbox(bbox: Bbox, durationMs: Int) {
        val vp = surface?.viewport?.takeIf { it.widthDp > 0 && it.heightDp > 0 }
            ?: return run { pendingFrame = bbox to durationMs }
        moveCamera(MapGeometry.fit(bbox, vp, MapGeometry.framePadding(_ui.value.cover)), durationMs)
    }
    /** A frame asked for before any map was attached (the viewport size is unknown until then). */
    private var pendingFrame: Pair<Bbox, Int>? = null

    override fun focusDevice(key: DeviceKey, minZoom: Double) {
        val s = shownScope ?: return
        focusJob?.cancel()
        focusJob = scope.launch {
            val at = runCatchingQuery { queries.deviceRows(s, longArrayOf(key)).firstOrNull()?.best }
                ?: runCatchingQuery { queries.deviceSightings(s, key).firstOrNull()?.at }
                ?: return@launch
            val zoom = max(camera().zoom, minZoom)
            moveCamera(MapGeometry.centerFor(at, zoom, MapGeometry.focusPadding(_ui.value.cover)), FOCUS_EASE_MS)
        }
    }

    override fun setSelection(key: DeviceKey?) {
        _ui.update { it.copy(selection = key) }
        surface?.applySelection(style.selection(key?.let(DeviceAddr::addrOf)))
        sightingsJob?.cancel()
        val s = shownScope
        if (key == null || s == null) {
            setSource("sightings", GeoJson.EMPTY)
            return
        }
        sightingsJob = scope.launch {
            val sightings = runCatchingQuery { queries.deviceSightings(s, key) } ?: return@launch
            if (_ui.value.selection == key) setSource("sightings", GeoJson.sightings(sightings))
        }
    }

    override fun camera(): CameraSnapshot = surface?.camera ?: lastCamera

    /** S-8: the revealed sheet's saved camera, eased back (the prototype's 300 ms). */
    override fun restore(camera: CameraSnapshot) = moveCamera(camera, RESTORE_EASE_MS)

    override fun setCover(insets: CoverInsets) = _ui.update { it.copy(cover = insets) }

    // ---- the locate button (P-4) ----

    /** P-4: ease to the location at zoom max(current, 17) over 600 ms; the button hides again. */
    fun locate() {
        val me = location.myLocation.value ?: return
        locate.onLocate()
        refreshLocateUi()
        val zoom = max(camera().zoom, MapGeometry.LOCATE_MIN_ZOOM)
        moveCamera(MapGeometry.centerFor(me.at, zoom, MapGeometry.locatePadding(_ui.value.cover)), LOCATE_EASE_MS)
    }

    private fun refreshLocateUi() {
        val status = location.status.value
        val hasLocation = location.myLocation.value != null
        _ui.update { it.copy(showLocate = locate.showLocate(status, hasLocation), showWarning = locate.showWarning(status)) }
    }

    // ---- surface events ----

    override fun onMoveStarted(byGesture: Boolean) {
        if (moves.onMoveStarted(byGesture, camera().zoom)) events?.onUserGestureStart()
    }

    override fun onIdle() {
        val s = surface ?: return
        val cam = s.camera
        lastCamera = cam
        val move = moves.onIdle(cam.zoom)
        if (move.byUser) {
            val me = location.myLocation.value?.at
            locate.onUserIdle(move.zoomed, me?.let { MapGeometry.locationOffset(it, cam, s.viewport, _ui.value.cover) })
            refreshLocateUi()
        }
        events?.onCameraIdle(move.byUser, s.visibleBbox, cam.zoom)
        requestPoints(force = false)
    }

    override fun onTap(hit: Hit?) {
        when (val action = TapResolver.resolve(hit)) {
            is TapAction.OpenDevice -> events?.onDeviceTap(action.key)
            is TapAction.ExpandCluster -> {
                val s = surface ?: return
                val z = s.clusterExpansionZoom(action.hit) ?: return
                val at = action.hit.at ?: return
                moveCamera(CameraSnapshot(at, TapResolver.clusterZoom(z)), CLUSTER_EASE_MS)
            }
            is TapAction.ZoomCell -> moveCamera(CameraSnapshot(action.at, TapResolver.cellZoom(camera().zoom)), CLUSTER_EASE_MS)
            TapAction.Ignore -> Unit
            TapAction.Empty -> events?.onEmptyTap()
        }
    }

    /** Style loads so far (instrumented tests wait on it). */
    internal var styleLoads = 0
        private set

    /** The attached surface (instrumented tests reach MapLibre through it). */
    internal val attachedSurface: MapSurface? get() = surface

    override fun onStyleLoaded() {
        styleLoads++
        surface?.let(::replay)
        pendingFrame?.let { (b, d) -> pendingFrame = null; frameBbox(b, d) }
    }

    // ---- data ----

    private fun loadView(s: Scope) {
        viewJob?.cancel()
        loaded = null
        viewJob = scope.launch {
            val v = runCatchingQuery { queries.sessionView(s) } ?: return@launch
            if (shownScope != s) return@launch
            view = v
            liveRoute.clear()
            liveRoute += v.route
            if (_ui.value.routesFaint) {
                setSource("ends", GeoJson.EMPTY)
            } else {
                setSource("route", GeoJson.routes(listOf(v.route)))
                setSource("ends", GeoJson.ends(v.route, style.endProperties))
            }
            // M-10, V-7: a session switch refits without animation
            v.frame?.let { frameBbox(it, 0) }
            requestPoints(force = true)
        }
    }

    private fun requestPoints(force: Boolean) {
        val s = shownScope ?: return
        val surf = surface ?: return
        val vp = surf.visibleBbox
        val zoom = surf.camera.zoom
        if (!force) {
            val l = loaded
            if (l != null && l.scope == s && l.filter == filter && MapGeometry.contains(l.area, vp) &&
                (l.mode == MapMode.Points || abs(l.zoom - zoom) < CELLS_RELOAD_DZ)
            ) return
        }
        pointsRequest.value = PointsRequest(s, filter, vp, zoom, ++nonce)
    }

    /** Superseded queries are dropped by collectLatest (and by the core, §2 "latest wins"). */
    private suspend fun load(r: PointsRequest) {
        val data: MapData = runCatchingQuery { queries.mapPoints(r.scope, r.filter, r.viewport, r.zoom) } ?: return
        if (r.scope != shownScope || r.filter != filter) return
        when (data.mode) {
            MapMode.Points -> {
                setSource("devices", data.geojson)
                setSource(MapStyleSpec.CELLS, GeoJson.EMPTY)
            }
            MapMode.Cells -> {
                setSource("devices", GeoJson.EMPTY)
                setSource(MapStyleSpec.CELLS, data.geojson)
            }
        }
        // M-5: the density wash is always on, one circle per device (per cell in Cells mode)
        setSource("density", data.geojson)
        loaded = Loaded(r.scope, r.filter, MapGeometry.loadedArea(r.viewport), r.zoom, data.mode)
        if (_ui.value.routesFaint) {
            runCatchingQuery { queries.routes(r.scope, r.viewport, r.zoom) }?.let { setSource("route", it) }
        }
    }

    /** A failed or cancelled query leaves the map as it was; cancellation still propagates. */
    private suspend fun <T> runCatchingQuery(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val FIRST_RUN_ZOOM = 16.0
        const val FRAME_EASE_MS = 500
        const val FOCUS_EASE_MS = 600
        const val LOCATE_EASE_MS = 600
        const val RESTORE_EASE_MS = 300
        const val CLUSTER_EASE_MS = 500
        const val LIVE_POINTS_INTERVAL_MS = 5_000L
        /** Cells are re-aggregated per zoom; Points cluster natively and reload only outside the loaded area. */
        const val CELLS_RELOAD_DZ = 0.5
    }
}
