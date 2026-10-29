package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.data.RouteGeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.model.MapEffect
import edu.ucsd.sysnet.cluetoothscanner.map.model.MapEvent
import edu.ucsd.sysnet.cluetoothscanner.map.model.MapReducer
import edu.ucsd.sysnet.cluetoothscanner.map.model.MapState
import edu.ucsd.sysnet.cluetoothscanner.map.model.Notice
import edu.ucsd.sysnet.cluetoothscanner.map.model.PayloadId
import edu.ucsd.sysnet.cluetoothscanner.map.model.PlatformFailure
import edu.ucsd.sysnet.cluetoothscanner.map.model.QueryKind
import edu.ucsd.sysnet.cluetoothscanner.map.model.RequestId
import edu.ucsd.sysnet.cluetoothscanner.map.model.Source
import edu.ucsd.sysnet.cluetoothscanner.map.model.SourceData
import edu.ucsd.sysnet.cluetoothscanner.map.model.UptimeMs
import edu.ucsd.sysnet.cluetoothscanner.map.model.ViewInfo
import edu.ucsd.sysnet.cluetoothscanner.map.model.RouteLine
import edu.ucsd.sysnet.cluetoothscanner.map.model.Session
import edu.ucsd.sysnet.cluetoothscanner.map.model.ViewState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The map's runner and the [MapRequests] implementation (docs/v2-api.md §5.3, §6.6). Every decision is
 * [MapReducer.reduce]'s; this class only feeds it events and executes the [MapEffect]s it returns:
 * MapLibre calls on the attached [MapSurface], the core queries, and the [MapEvents] notices.
 *
 * It outlives the drawn map: requests made while no map is attached wait in the state and run when one attaches,
 * and every source is replayed when the style reloads (theme change, onStop/onStart).
 *
 * Main thread only; [scope] must run on Dispatchers.Main (the app's ViewModel scope). The state has one owner: this
 * runner, on the main thread. Events (including those a surface reports while an effect runs) go through one queue
 * and are reduced one at a time. A MapLibre call or query that throws becomes an event, never a crash.
 *
 * - [show] with a new scope frames that session itself, without animation (M-10, V-7), from its SessionView.frame.
 *   :app never calls [frame] on a session switch (coordinator decision); [frame] is only the animated framing D-8
 *   asks for (a product sheet opening).
 * - With no scope shown yet (first run, V-6) the map shows only the user's location at zoom 16 (M-9).
 */
class CluetoothMapState(
    private val queries: CoreQueries,
    private val location: LocationStatusSource,
    private val style: MapStyleSpec,
    private val scope: CoroutineScope,
    /** Uptime in ms (android.os.SystemClock.uptimeMillis in the app). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : MapRequests, MapSurfaceListener {

    var events: MapEvents? = null

    private var model = MapState.Initial
    private val _ui = MutableStateFlow(MapReducer.ui(model))
    val ui: StateFlow<MapUiState> = _ui.asStateFlow()

    private var surface: MapSurface? = null
    private val queue = ArrayDeque<MapEvent>()
    private var draining = false

    /** What the runner holds for the state: GeoJSON, or a route's append-only points. */
    private sealed interface Payload {
        class Json(val json: String) : Payload
        class Points(val points: MutableList<GeoPoint>) : Payload
    }

    /** The payloads the state refers to by id; pruned to what the state still references after every drain. */
    private val payloads = HashMap<PayloadId, Payload>()
    private var nextPayload = 1L
    /** What each source last received, so an unchanged payload is not parsed again (a replay always sets). */
    private val applied = HashMap<Source, String>()
    private val route = RouteGeoJson()
    private val running = HashMap<QueryKind, Job>()

    private val failures = CoroutineExceptionHandler { _, e -> dispatch(MapEvent.PlatformFailed(PlatformFailure("coroutine", e.toString()))) }

    init {
        scope.launch(failures) {
            combine(location.status, location.myLocation) { s, m -> s to m }
                .collect { (status, me) -> dispatch(MapEvent.LocationChanged(status, me)) }
        }
        scope.launch(failures) {
            queries.live.collect { delta ->
                dispatch(
                    MapEvent.Live(
                        delta.sessionId, delta.routeAppended,
                        pointsChanged = delta.appended.isNotEmpty() || delta.changed.any { it.best != null },
                    ),
                )
            }
        }
        // §2.6 budget: the map applies live `best` changes and appends at most every 5 s
        scope.launch(failures) {
            while (true) {
                delay(LIVE_POINTS_INTERVAL_MS)
                dispatch(MapEvent.LiveTick)
            }
        }
    }

    // ---- the queue ----

    /**
     * The one entry point for events. Each event is reduced and each of its effects executed inside [guarded], so
     * any exception (in the reducer, a MapLibre call, a MapEvents listener) becomes a PlatformFailed event.
     */
    private fun dispatch(event: MapEvent) {
        queue.addLast(event)
        if (draining) return
        draining = true
        while (true) {
            val next = queue.removeFirstOrNull() ?: break
            val step = guarded("reduce ${next::class.simpleName}") { MapReducer.reduce(model, next) } ?: continue
            model = step.state
            for (effect in step.effects) guarded(effect::class.simpleName ?: "effect") { execute(effect) }
        }
        draining = false
        prunePayloads()
        _ui.value = MapReducer.ui(model)
    }

    /** Keeps the payloads a source or the shown view still refers to. */
    private fun prunePayloads() {
        val live = HashSet<PayloadId>()
        for (data in model.sources.values) when (data) {
            is SourceData.Stored -> live += data.id
            is SourceData.Line -> live += data.route.base
            SourceData.Empty, is SourceData.Ends, is SourceData.Me -> Unit
        }
        val view = ((model.session as? Session.Showing)?.view as? ViewState.Ready)?.info
        if (view != null) live += view.route.base
        payloads.keys.retainAll(live)
    }

    private fun store(json: String): PayloadId = PayloadId(nextPayload++).also { payloads[it] = Payload.Json(json) }

    private fun storeRoute(points: List<GeoPoint>): PayloadId =
        PayloadId(nextPayload++).also { payloads[it] = Payload.Points(points.toMutableList()) }

    // ---- attach / detach ----

    fun attach(surface: MapSurface) {
        this.surface = surface
        val s = sample(surface) ?: return
        dispatch(MapEvent.Attached(s.viewport, s.camera, s.bbox, guarded("styleReady") { surface.styleReady } ?: false))
    }

    fun detach(surface: MapSurface) {
        if (this.surface !== surface) return
        val camera = guarded("camera") { surface.camera } ?: MapReducer.knownCamera(model)
        this.surface = null
        dispatch(MapEvent.Detached(camera))
    }

    /** §1.2.5: drop the cached point data while the UI is hidden (onStop, onTrimMemory(UI_HIDDEN)). */
    fun trimMemory() = dispatch(MapEvent.Trim)

    /** The UI is shown again (onStart): query what [trimMemory] dropped. */
    fun refillAfterTrim() = dispatch(MapEvent.Refill)

    /** The shell is about to set a new style: until it loads, nothing may touch its sources. */
    internal fun onStyleLoading() = dispatch(MapEvent.StyleLoading)

    /** A MapLibre call outside this runner (the composable's shell) threw. */
    internal fun onPlatformFailure(operation: String, e: Throwable) =
        dispatch(MapEvent.PlatformFailed(PlatformFailure(operation, e.toString())))

    // ---- MapRequests ----

    override fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean) = dispatch(MapEvent.Show(scope, filter, routesFaint))

    /** D-8: animated framing of [bbox] (M-10 padding and maximum zoom). */
    override fun frame(bbox: Bbox) = dispatch(MapEvent.Frame(bbox))

    override fun focusDevice(key: DeviceKey, minZoom: Double) = dispatch(MapEvent.Focus(key, minZoom))

    override fun setSelection(key: DeviceKey?) = dispatch(MapEvent.Select(key))

    override fun camera(): CameraSnapshot = surface?.let { s -> guarded("camera") { s.camera } } ?: MapReducer.knownCamera(model)

    /** S-8: the revealed sheet's saved camera, eased back (the prototype's 300 ms). */
    override fun restore(camera: CameraSnapshot) = dispatch(MapEvent.Restore(camera))

    override fun setCover(insets: CoverInsets) = dispatch(MapEvent.Cover(insets))

    /** P-4: ease to the location at zoom max(current, 17) over 600 ms; the button hides again. */
    fun locate() = dispatch(MapEvent.Locate(camera().zoom))

    // ---- surface events ----

    override fun onMoveStarted(byGesture: Boolean) = dispatch(MapEvent.MoveStarted(byGesture, camera().zoom, UptimeMs(clock())))

    override fun onIdle() {
        val s = surface?.let(::sample) ?: return
        dispatch(MapEvent.Idle(s.camera, s.viewport, s.bbox, UptimeMs(clock())))
    }

    override fun onTap(hit: Hit?) = dispatch(MapEvent.Tap(hit, camera().zoom))

    /** Style loads so far (instrumented tests wait on it). */
    internal var styleLoads = 0
        private set

    /** The attached surface (instrumented tests reach MapLibre through it). */
    internal val attachedSurface: MapSurface? get() = surface

    /** The current state (tests). */
    internal val state: MapState get() = model

    override fun onStyleLoaded() {
        styleLoads++
        dispatch(MapEvent.StyleLoaded)
    }

    // ---- effects (each runs inside dispatch's guarded) ----

    private fun execute(effect: MapEffect) {
        when (effect) {
            is MapEffect.SetSource -> setSource(effect.source, effect.data, force = false)
            is MapEffect.ReplaySources -> {
                // a (re)loaded style starts with every source empty: forget what the old one held
                applied.clear()
                for ((source, data) in effect.sources) setSource(source, data, force = true)
            }
            is MapEffect.ApplySelection -> surface?.applySelection(style.selection(effect.key?.let(DeviceAddr::addrOf)))
            is MapEffect.SetRouteOpacity ->
                surface?.setRouteOpacity(if (effect.faint) style.routeOpacityAllSessions else style.routeOpacitySession)
            is MapEffect.Move -> surface?.move(effect.camera, effect.duration.value)
            is MapEffect.QueryClusterExpansion -> {
                val zoom = surface?.clusterExpansionZoom(effect.hit)
                val at = effect.hit.at
                if (zoom != null && at != null) dispatch(MapEvent.ClusterExpansion(at, zoom))
            }
            is MapEffect.Notify -> notify(effect.notice)
            is MapEffect.Cancel -> running.remove(effect.kind)?.cancel()
            is MapEffect.AppendRoute -> when (val p = payloads[effect.base]) {
                is Payload.Points -> p.points.addAll(effect.points)
                is Payload.Json, null -> dispatch(missing(effect.base))
            }
            is MapEffect.LoadView -> launchQuery(effect) {
                val v = queries.sessionView(effect.scope)
                val route = RouteLine(storeRoute(v.route), v.route.size, v.route.firstOrNull(), v.route.lastOrNull())
                MapEvent.ViewLoaded(effect.request, ViewInfo(v.live, v.sessionIds, v.frame, route))
            }
            is MapEffect.LoadPoints -> launchQuery(effect) {
                val data = queries.mapPoints(effect.scope, effect.filter, effect.viewport, effect.zoom)
                MapEvent.PointsLoaded(effect.request, data.mode, store(data.geojson))
            }
            is MapEffect.LoadRoutes -> launchQuery(effect) {
                MapEvent.RoutesLoaded(effect.request, store(queries.routes(effect.scope, effect.viewport, effect.zoom)))
            }
            is MapEffect.LoadSightings -> launchQuery(effect) {
                MapEvent.SightingsLoaded(effect.request, store(GeoJson.sightings(queries.deviceSightings(effect.scope, effect.key))))
            }
            is MapEffect.ResolveFocus -> launchQuery(effect) {
                val at = queries.deviceRows(effect.scope, longArrayOf(effect.key)).firstOrNull()?.best
                    ?: queries.deviceSightings(effect.scope, effect.key).firstOrNull()?.at
                if (at == null) MapEvent.QueryFailed(effect.request) else MapEvent.FocusResolved(effect.request, at, camera().zoom)
            }
        }
    }

    private fun notify(notice: Notice) {
        val e = events ?: return
        when (notice) {
            Notice.GestureStart -> e.onUserGestureStart()
            is Notice.CameraIdle -> e.onCameraIdle(notice.byUser, notice.viewport, notice.zoom)
            is Notice.DeviceTap -> e.onDeviceTap(notice.key)
            Notice.EmptyTap -> e.onEmptyTap()
        }
    }

    private fun setSource(source: Source, data: SourceData, force: Boolean) {
        val json = when (data) {
            SourceData.Empty -> GeoJson.EMPTY
            is SourceData.Stored -> (payloads[data.id] as? Payload.Json)?.json ?: return dispatch(missing(data.id))
            is SourceData.Line -> {
                val points = (payloads[data.route.base] as? Payload.Points)?.points ?: return dispatch(missing(data.route.base))
                if (points.size != data.route.length) return dispatch(missing(data.route.base))
                route.render(points)
            }
            is SourceData.Ends -> GeoJson.ends(listOf(data.start, data.end), style.endProperties)
            is SourceData.Me -> GeoJson.me(data.at)
        }
        if (!force && applied[source] == json) return
        applied[source] = json
        surface?.setGeoJson(source.id, json)
    }

    private fun missing(id: PayloadId) = MapEvent.PlatformFailed(PlatformFailure("payload", "no payload ${id.value}"))

    /** A query on the scope; a newer query of its kind cancels it, and its result (or failure) comes back as an event. */
    private fun launchQuery(effect: MapEffect.Query, block: suspend () -> MapEvent) {
        running.remove(effect.kind)?.cancel()
        running[effect.kind] = scope.launch(failures) {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapEvent.QueryFailed(effect.request)
            }
            dispatch(result)
        }
    }

    /**
     * The runner's one wrapper: every effect, every surface read and every reduce step runs in it, and any exception
     * becomes a PlatformFailed event instead of reaching the main thread.
     */
    private fun <T> guarded(operation: String, call: () -> T): T? = try {
        call()
    } catch (e: Exception) {
        dispatch(MapEvent.PlatformFailed(PlatformFailure(operation, e.toString())))
        null
    }

    private data class Sample(val camera: CameraSnapshot, val viewport: edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport, val bbox: Bbox)

    private fun sample(s: MapSurface): Sample? = guarded("sample") { Sample(s.camera, s.viewport, s.visibleBbox) }

    companion object {
        const val FIRST_RUN_ZOOM = MapReducer.FIRST_RUN_ZOOM
        val FRAME_EASE_MS = MapReducer.FRAME_EASE.value
        val FOCUS_EASE_MS = MapReducer.FOCUS_EASE.value
        val LOCATE_EASE_MS = MapReducer.LOCATE_EASE.value
        val RESTORE_EASE_MS = MapReducer.RESTORE_EASE.value
        val CLUSTER_EASE_MS = MapReducer.CLUSTER_EASE.value
        const val LIVE_POINTS_INTERVAL_MS = 5_000L
        const val CELLS_RELOAD_DZ = MapReducer.CELLS_RELOAD_DZ
    }
}
