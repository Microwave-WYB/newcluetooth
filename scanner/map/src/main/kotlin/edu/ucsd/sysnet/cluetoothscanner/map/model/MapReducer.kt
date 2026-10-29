package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.map.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapAction
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapResolver
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlin.math.abs
import kotlin.math.max

/**
 * The map's decisions: `(state, event) -> (state, effects)`, pure. Every rule the map implements is here, the same
 * rules CluetoothMapState applied before it became a runner:
 * - M-10, V-7: a new scope frames its session without animation once its view loads; [MapEvent.Frame] is the
 *   animated D-8 framing.
 * - M-9: with no scope shown, the location (once at zoom 16).
 * - Camera moves wait in [MapState.pending] (the last wins) until the style is ready; a frame also waits for a
 *   measured viewport.
 * - map_points: one request covers the viewport + 50 %; an idle inside it (Points mode, or Cells within half a zoom
 *   level) asks nothing new; a session switch costs one query.
 */
object MapReducer {
    const val FIRST_RUN_ZOOM = 16.0
    val FRAME_EASE = Millis(500)
    val FOCUS_EASE = Millis(600)
    val LOCATE_EASE = Millis(600)
    val RESTORE_EASE = Millis(300)
    val CLUSTER_EASE = Millis(500)
    val JUMP = Millis(0)
    /** Cells are re-aggregated per zoom; Points cluster natively and reload only outside the loaded area. */
    const val CELLS_RELOAD_DZ = 0.5
    /** The sources Trim drops: the large, re-queryable ones. */
    val TRIMMED_SOURCES = setOf(Source.Devices, Source.Density, Source.Cells, Source.Sightings)

    fun reduce(state: MapState, event: MapEvent): Step {
        val b = Builder(state)
        when (event) {
            is MapEvent.Attached -> attached(b, event)
            is MapEvent.Detached -> b.state = b.state.copy(surface = SurfaceState.Detached(event.camera))
            MapEvent.StyleLoading -> styleLoading(b)
            MapEvent.StyleLoaded -> styleLoaded(b)
            is MapEvent.MoveStarted -> moveStarted(b, event)
            is MapEvent.Idle -> idle(b, event)
            is MapEvent.Tap -> tap(b, event)
            is MapEvent.ClusterExpansion ->
                b.moveCamera(CameraSnapshot(event.at, TapResolver.clusterZoom(event.zoom)), CLUSTER_EASE)
            is MapEvent.PlatformFailed -> b.state = b.state.copy(lastFailure = event.failure)
            is MapEvent.Show -> show(b, event)
            is MapEvent.Frame -> b.intend(CameraIntent.Frame(event.bbox, FRAME_EASE))
            is MapEvent.Focus -> focus(b, event)
            is MapEvent.Select -> select(b, event)
            is MapEvent.Restore -> b.moveCamera(event.camera, RESTORE_EASE)
            is MapEvent.Cover -> b.state = b.state.copy(cover = event.insets)
            is MapEvent.Locate -> locate(b, event)
            MapEvent.Trim -> trim(b)
            MapEvent.Refill -> b.refill()
            is MapEvent.LocationChanged -> locationChanged(b, event)
            is MapEvent.Live -> live(b, event)
            MapEvent.LiveTick -> liveTick(b)
            is MapEvent.ViewLoaded -> viewLoaded(b, event)
            is MapEvent.PointsLoaded -> pointsLoaded(b, event)
            is MapEvent.RoutesLoaded -> routesLoaded(b, event)
            is MapEvent.SightingsLoaded -> sightingsLoaded(b, event)
            is MapEvent.FocusResolved -> focusResolved(b, event)
            is MapEvent.QueryFailed -> queryFailed(b, event)
        }
        return Step(b.state, b.effects.toList())
    }

    /** The UI state the map exposes (§5.4), a projection of the state. */
    fun ui(state: MapState): MapUiState {
        val showing = state.session as? Session.Showing
        return MapUiState(
            selection = (showing?.selection as? Selection.Device)?.key,
            routesFaint = showing?.routesFaint ?: false,
            cover = state.cover,
            showLocate = state.locate.showLocate(state.status, state.me != null),
            showWarning = state.locate.showWarning(state.status),
        )
    }

    /** The camera the map has, or will have once its pending move runs (for `camera()` while detached). */
    fun knownCamera(state: MapState): CameraSnapshot = when (val s = state.surface) {
        is SurfaceState.Detached -> (state.pending as? CameraIntent.Move)?.camera ?: s.lastCamera
        is SurfaceState.Attached -> s.camera
    }

    // ---- the surface ----

    private fun attached(b: Builder, e: MapEvent.Attached) {
        val wasDetached = b.state.surface as? SurfaceState.Detached
        // a re-attached map starts where the last one was, unless a move is already waiting
        val pending = b.state.pending ?: wasDetached?.let { CameraIntent.Move(it.lastCamera, JUMP) }
        val style = if (e.styleReady) StyleState.Ready else StyleState.Loading
        b.state = b.state.copy(surface = SurfaceState.Attached(e.viewport, e.camera, e.bbox, style), pending = pending)
        if (style == StyleState.Ready) b.becameReady()
    }

    private fun styleLoading(b: Builder) {
        val s = b.state.surface as? SurfaceState.Attached ?: return
        b.state = b.state.copy(surface = s.copy(style = StyleState.Loading))
    }

    private fun styleLoaded(b: Builder) {
        val s = b.state.surface as? SurfaceState.Attached ?: return
        b.state = b.state.copy(surface = s.copy(style = StyleState.Ready))
        b.becameReady()
    }

    private fun moveStarted(b: Builder, e: MapEvent.MoveStarted) {
        val started = b.state.moves.moveStarted(e.byGesture, e.zoom, e.at.value)
        b.state = b.state.copy(moves = started.next)
        if (started.reportGestureStart) b.notify(Notice.GestureStart)
    }

    private fun idle(b: Builder, e: MapEvent.Idle) {
        val s = b.state.surface as? SurfaceState.Attached ?: return
        val rested = b.state.moves.idle(e.camera.zoom, e.at.value)
        var locate = b.state.locate
        if (rested.result.byUser) {
            val offset = b.state.me?.at?.let { MapGeometry.locationOffset(it, e.camera, e.viewport, b.state.cover) }
            locate = locate.onUserIdle(rested.result.zoomed, offset)
        }
        b.state = b.state.copy(
            surface = s.copy(viewport = e.viewport, camera = e.camera, bbox = e.bbox),
            moves = rested.next,
            locate = locate,
        )
        b.notify(Notice.CameraIdle(rested.result.byUser, e.bbox, e.camera.zoom))
        // a frame waiting for a measured viewport can run now
        if (b.state.pending is CameraIntent.Frame) b.runPending()
        b.requestPoints(force = false)
    }

    private fun tap(b: Builder, e: MapEvent.Tap) {
        when (val action = TapResolver.resolve(e.hit)) {
            is TapAction.OpenDevice -> b.notify(Notice.DeviceTap(action.key))
            is TapAction.ExpandCluster -> if (action.hit.at != null) b.onMap(MapEffect.QueryClusterExpansion(action.hit))
            is TapAction.ZoomCell -> b.moveCamera(CameraSnapshot(action.at, TapResolver.cellZoom(e.zoom)), CLUSTER_EASE)
            TapAction.Ignore -> Unit
            TapAction.Empty -> b.notify(Notice.EmptyTap)
        }
    }

    // ---- requests ----

    private fun show(b: Builder, e: MapEvent.Show) {
        b.onMap(MapEffect.SetRouteOpacity(e.routesFaint))
        when (val session = b.state.session) {
            is Session.Showing -> if (session.scope == e.scope) {
                val filterChanged = session.filter != e.filter
                b.state = b.state.copy(session = session.copy(filter = e.filter, routesFaint = e.routesFaint))
                if (filterChanged) b.requestPoints(force = true)
                return
            }
            is Session.None -> Unit
        }
        // a new scope: clear the selection, hide the location, load the view
        b.clearSelection()
        b.setSource(Source.Me, SourceData.Empty)
        val request = b.newRequest()
        b.state = b.state.copy(
            session = Session.Showing(
                scope = e.scope, filter = e.filter, routesFaint = e.routesFaint, view = ViewState.Loading(request),
                points = PointsState.Unrequested, selection = Selection.None, focus = null, routes = null, liveDirty = false,
            ),
        )
        b.query(MapEffect.LoadView(request, e.scope))
    }

    private fun focus(b: Builder, e: MapEvent.Focus) {
        val session = b.state.session as? Session.Showing ?: return
        val request = b.newRequest()
        b.state = b.state.copy(session = session.copy(focus = FocusRequest(request, e.minZoom)))
        b.query(MapEffect.ResolveFocus(request, session.scope, e.key))
    }

    private fun focusResolved(b: Builder, e: MapEvent.FocusResolved) {
        val session = b.state.session as? Session.Showing ?: return
        val focus = session.focus?.takeIf { it.request == e.request } ?: return
        b.state = b.state.copy(session = session.copy(focus = null))
        val zoom = max(e.zoom, focus.minZoom)
        b.moveCamera(MapGeometry.centerFor(e.at, zoom, MapGeometry.focusPadding(b.state.cover)), FOCUS_EASE)
    }

    private fun select(b: Builder, e: MapEvent.Select) {
        val key = e.key ?: return b.clearSelection()
        val session = b.state.session as? Session.Showing ?: return
        val request = b.newRequest()
        b.state = b.state.copy(session = session.copy(selection = Selection.Device(key, request)))
        b.onMap(MapEffect.ApplySelection(key))
        b.query(MapEffect.LoadSightings(request, session.scope, key))
    }

    private fun sightingsLoaded(b: Builder, e: MapEvent.SightingsLoaded) {
        val session = b.state.session as? Session.Showing ?: return
        val selection = session.selection as? Selection.Device ?: return
        if (selection.sightings == e.request) b.setSource(Source.Sightings, SourceData.Stored(e.payload))
    }

    private fun locate(b: Builder, e: MapEvent.Locate) {
        val me = b.state.me ?: return
        b.state = b.state.copy(locate = b.state.locate.onLocate())
        val zoom = max(e.zoom, MapGeometry.LOCATE_MIN_ZOOM)
        b.moveCamera(MapGeometry.centerFor(me.at, zoom, MapGeometry.locatePadding(b.state.cover)), LOCATE_EASE)
    }

    private fun trim(b: Builder) {
        val session = b.state.session
        b.state = b.state.copy(
            sources = b.state.sources - TRIMMED_SOURCES,
            memory = Memory.Trimmed,
            session = if (session is Session.Showing) session.copy(points = PointsState.Unrequested) else session,
        )
    }

    private fun locationChanged(b: Builder, e: MapEvent.LocationChanged) {
        b.state = b.state.copy(status = e.status, me = e.me, locate = b.state.locate.onStatus(e.status))
        val session = b.state.session as? Session.None ?: return
        b.setSource(Source.Me, e.me?.let { SourceData.Me(it.at) } ?: SourceData.Empty)
        // M-9: centre on the location once, at zoom 16, until a session is shown
        if (e.me != null && !session.located) {
            b.state = b.state.copy(session = Session.None(located = true))
            b.moveCamera(CameraSnapshot(e.me.at, FIRST_RUN_ZOOM), JUMP)
        }
    }

    // ---- data ----

    private fun viewLoaded(b: Builder, e: MapEvent.ViewLoaded) {
        val session = b.state.session as? Session.Showing ?: return
        val loading = session.view as? ViewState.Loading ?: return
        if (loading.request != e.request) return
        b.state = b.state.copy(session = session.copy(view = ViewState.Ready(e.info)))
        if (session.routesFaint) {
            b.setSource(Source.Ends, SourceData.Empty)
        } else {
            b.setSource(Source.Route, SourceData.Line(e.info.route))
            b.setSource(Source.Ends, endsOf(e.info.route))
        }
        // M-10, V-7: a session switch refits without animation, and asks for the points of the framed viewport
        // (that move's idle then finds them covered, so the switch costs one map_points query)
        val frame = e.info.frame
        if (frame != null) b.intend(CameraIntent.Frame(frame, JUMP))
        b.requestPoints(force = false)
    }

    private fun pointsLoaded(b: Builder, e: MapEvent.PointsLoaded) {
        val session = b.state.session as? Session.Showing ?: return
        val requested = session.points as? PointsState.Requested ?: return
        if (requested.request != e.request) return
        val payload = SourceData.Stored(e.payload)
        when (e.mode) {
            MapMode.Points -> {
                b.setSource(Source.Devices, payload)
                b.setSource(Source.Cells, SourceData.Empty)
            }
            MapMode.Cells -> {
                b.setSource(Source.Devices, SourceData.Empty)
                b.setSource(Source.Cells, payload)
            }
        }
        // M-5: the density wash is always on, one circle per device (per cell in Cells mode)
        b.setSource(Source.Density, payload)
        var next = session.copy(points = PointsState.Loaded(requested.coverage, e.mode))
        if (session.routesFaint) {
            val routes = b.newRequest()
            next = next.copy(routes = routes)
            b.query(MapEffect.LoadRoutes(routes, session.scope, requested.viewport, requested.coverage.zoom))
        }
        b.state = b.state.copy(session = next)
    }

    private fun routesLoaded(b: Builder, e: MapEvent.RoutesLoaded) {
        val session = b.state.session as? Session.Showing ?: return
        if (session.routes == e.request && session.routesFaint) b.setSource(Source.Route, SourceData.Stored(e.payload))
    }

    private fun queryFailed(b: Builder, e: MapEvent.QueryFailed) {
        val session = b.state.session as? Session.Showing ?: return
        val points = session.points
        // a failed points request leaves nothing covered, so the next idle asks again
        if (points is PointsState.Requested && points.request == e.request) {
            b.state = b.state.copy(session = session.copy(points = PointsState.Unrequested))
        }
        if (session.focus?.request == e.request) b.state = b.state.copy(session = session.copy(focus = null))
    }

    private fun live(b: Builder, e: MapEvent.Live) {
        val session = b.state.session as? Session.Showing ?: return
        val view = (session.view as? ViewState.Ready)?.info ?: return
        if (!view.live || view.sessionIds.singleOrNull() != e.sessionId) return
        var next = session
        if (e.routeAppended.isNotEmpty()) {
            // only the new points travel: the runner appends them to the route it holds
            val route = view.route.appended(e.routeAppended)
            next = next.copy(view = ViewState.Ready(view.copy(route = route)))
            b.state = b.state.copy(session = next)
            b.appendRoute(MapEffect.AppendRoute(route.base, e.routeAppended))
            b.setSource(Source.Route, SourceData.Line(route))
            b.setSource(Source.Ends, endsOf(route))
        }
        if (e.pointsChanged) next = next.copy(liveDirty = true)
        b.state = b.state.copy(session = next)
    }

    /** §2.6 budget: live best points and appends reach the map at most every 5 s. */
    private fun liveTick(b: Builder) {
        val session = b.state.session as? Session.Showing ?: return
        val live = (session.view as? ViewState.Ready)?.info?.live ?: false
        if (!session.liveDirty || !live) return
        b.state = b.state.copy(session = session.copy(liveDirty = false))
        b.requestPoints(force = true)
    }

    private fun endsOf(route: RouteLine): SourceData {
        val start = route.start
        val end = route.end
        return if (route.length > 1 && start != null && end != null) SourceData.Ends(start, end) else SourceData.Empty
    }

    /** Accumulates the next state and its effects for one event. */
    private class Builder(var state: MapState) {
        val effects = ArrayList<MapEffect>()

        private val ready: SurfaceState.Attached?
            get() = (state.surface as? SurfaceState.Attached)?.takeIf { it.style == StyleState.Ready }

        fun notify(notice: Notice) { effects += MapEffect.Notify(notice) }

        fun query(q: MapEffect.Query) { effects += q }

        fun appendRoute(a: MapEffect.AppendRoute) { effects += a }

        /** A MapLibre effect, dropped while the style is not ready (its state is replayed when it is). */
        fun onMap(effect: MapEffect.OnMap) { if (ready != null) effects += effect }

        fun newRequest(): RequestId = RequestId(state.nextRequest).also { state = state.copy(nextRequest = state.nextRequest + 1) }

        fun setSource(source: Source, data: SourceData) {
            if (state.sources[source] == data) return
            state = state.copy(sources = state.sources + (source to data))
            onMap(MapEffect.SetSource(source, data))
        }

        fun clearSelection() {
            val session = state.session
            if (session is Session.Showing) state = state.copy(session = session.copy(selection = Selection.None))
            effects += MapEffect.Cancel(QueryKind.Sightings)
            onMap(MapEffect.ApplySelection(null))
            setSource(Source.Sightings, SourceData.Empty)
        }

        fun moveCamera(camera: CameraSnapshot, duration: Millis) = intend(CameraIntent.Move(camera, duration))

        /** A camera move: now if the map can take it, else it waits (the last one wins). */
        fun intend(intent: CameraIntent) {
            state = state.copy(pending = intent)
            runPending()
        }

        /** Runs the pending move if the style is ready (and, for a frame, the viewport measured). */
        fun runPending() {
            val s = ready ?: return
            val target = when (val p = state.pending ?: return) {
                is CameraIntent.Move -> p.camera to p.duration
                is CameraIntent.Frame -> {
                    if (!measured(s.viewport)) return
                    MapGeometry.fit(p.bbox, s.viewport, MapGeometry.framePadding(state.cover)) to p.duration
                }
            }
            val (camera, duration) = target
            effects += MapEffect.Move(camera, duration)
            // until its idle arrives, the map is taken to be where this move goes (an instant move is there now)
            state = state.copy(
                pending = null,
                surface = if (duration == JUMP) s.copy(camera = camera, bbox = MapGeometry.visibleBbox(camera, s.viewport)) else s,
            )
        }

        /** The style is ready: replay every source, the opacity and the selection, run the pending move, refill. */
        fun becameReady() {
            effects += MapEffect.ReplaySources(state.sources)
            val session = state.session as? Session.Showing
            effects += MapEffect.SetRouteOpacity(session?.routesFaint ?: false)
            effects += MapEffect.ApplySelection((session?.selection as? Selection.Device)?.key)
            runPending()
            refill()
            requestPoints(force = false)
        }

        /** The UI is shown again (onStart, or a replay): query what Trim dropped. */
        fun refill() {
            if (state.memory != Memory.Trimmed || state.surface !is SurfaceState.Attached) return
            state = state.copy(memory = Memory.Full)
            requestPoints(force = true)
            val session = state.session as? Session.Showing ?: return
            val selection = session.selection as? Selection.Device ?: return
            val request = newRequest()
            state = state.copy(session = session.copy(selection = selection.copy(sightings = request)))
            query(MapEffect.LoadSightings(request, session.scope, selection.key))
        }

        /** Asks map_points for the map's viewport unless the last request already covers it. */
        fun requestPoints(force: Boolean) {
            val session = state.session as? Session.Showing ?: return
            val s = state.surface as? SurfaceState.Attached ?: return
            val vp = s.bbox
            val zoom = s.camera.zoom
            if (!force && covers(session.points, session.scope, session.filter, vp, zoom)) return
            val request = newRequest()
            val coverage = Coverage(session.scope, session.filter, MapGeometry.loadedArea(vp), zoom)
            val current = state.session as? Session.Showing ?: return
            state = state.copy(session = current.copy(points = PointsState.Requested(request, coverage, vp)))
            effects += MapEffect.Cancel(QueryKind.Routes)
            query(MapEffect.LoadPoints(request, session.scope, session.filter, vp, zoom))
        }

        private fun covers(points: PointsState, scope: Scope, filter: DeviceFilter, vp: Bbox, zoom: Double): Boolean {
            val (coverage, mode) = when (points) {
                PointsState.Unrequested -> return false
                // in flight: its mode is unknown, so hold it to the Cells rule
                is PointsState.Requested -> points.coverage to null
                is PointsState.Loaded -> points.coverage to points.mode
            }
            return coverage.scope == scope && coverage.filter == filter && MapGeometry.contains(coverage.area, vp) &&
                (mode == MapMode.Points || abs(coverage.zoom - zoom) < CELLS_RELOAD_DZ)
        }
    }

    /** The viewport is measured: a frame can be fitted into it. */
    fun measured(viewport: Viewport) = viewport.widthDp > 0 && viewport.heightDp > 0
}
