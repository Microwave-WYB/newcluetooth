package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.map.camera.LocateModel
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MoveClassifier
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MoveTracking
import edu.ucsd.sysnet.cluetoothscanner.map.camera.RouteEnds
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets

/*
 * The map's logic as data (AGENTS.md design principles): one immutable [MapState], the [MapEvent]s that change it,
 * the [MapEffect]s it asks for, and the pure [MapReducer.reduce] that decides. CluetoothMapState is the runner: it
 * owns MapLibre and the queries, executes the effects and feeds every result back as an event.
 *
 * Large GeoJSON never enters the state: the runner keeps it and the state refers to it by [PayloadId]. Per-frame
 * camera values stay out too: the logic sees a camera only where an event samples it (attach, gesture start, idle,
 * and the requests whose rule reads the current zoom).
 */

/** A GeoJSON payload the runner holds for the state. */
@JvmInline value class PayloadId(val value: Long)

/** One query the state asked for; a result whose id is not the latest of its kind is stale and dropped. */
@JvmInline value class RequestId(val value: Long)

/** An animation length; zero jumps. */
@JvmInline value class Millis(val value: Int)

/** Uptime, for the fling continuation (MoveTracking). */
@JvmInline value class UptimeMs(val value: Long)

/**
 * What a source shows. Only small values live here; points, cells, routes and sightings are [Stored]. Each source
 * accepts only its own kind ([RouteData], [EndsData], [PointsData], [MeData]), so a pairing such as the location on
 * the devices source does not compile.
 */
sealed interface SourceData {
    sealed interface RouteData : SourceData
    sealed interface EndsData : SourceData
    sealed interface PointsData : SourceData
    sealed interface MeData : SourceData

    data object Empty : RouteData, EndsData, PointsData, MeData
    /** A payload the runner holds (map points or cells, the faint routes, a device's sightings). */
    data class Stored(val id: PayloadId) : RouteData, PointsData
    /** The one-session route; its points stay in the runner (see [RouteLine]). */
    data class Line(val route: RouteLine) : RouteData
    /** M-8 Start and End. */
    data class Ends(val start: GeoPoint, val end: GeoPoint) : EndsData
    /** M-9 the user's location. */
    data class Me(val at: GeoPoint) : MeData
}

/** The GeoJSON sources of map-style.json, plus `cells`, each typed by the data it accepts. */
sealed class Source<D : SourceData>(val id: String) {
    /** [data] as this source's kind, or null when it is another kind. */
    abstract fun accept(data: SourceData): D?

    data object Route : Source<SourceData.RouteData>("route") { override fun accept(data: SourceData) = data as? SourceData.RouteData }
    data object Ends : Source<SourceData.EndsData>("ends") { override fun accept(data: SourceData) = data as? SourceData.EndsData }
    data object Density : Source<SourceData.PointsData>("density") { override fun accept(data: SourceData) = data as? SourceData.PointsData }
    data object Devices : Source<SourceData.PointsData>("devices") { override fun accept(data: SourceData) = data as? SourceData.PointsData }
    data object Sightings : Source<SourceData.PointsData>("sightings") { override fun accept(data: SourceData) = data as? SourceData.PointsData }
    data object Me : Source<SourceData.MeData>("me") { override fun accept(data: SourceData) = data as? SourceData.MeData }
    data object Cells : Source<SourceData.PointsData>(MapStyleSpec.CELLS) { override fun accept(data: SourceData) = data as? SourceData.PointsData }
}

/**
 * What each source shows, as far as the state knows; a source missing here was dropped (Trim) and is not replayed.
 * Built only through [with], which pairs a source with its own kind of data.
 */
class Sources private constructor(private val map: Map<Source<*>, SourceData>) {
    operator fun <D : SourceData> get(source: Source<D>): D? = map[source]?.let(source::accept)
    operator fun contains(source: Source<*>) = source in map
    fun <D : SourceData> with(source: Source<D>, data: D) = Sources(map + (source to data))
    fun without(sources: Set<Source<*>>) = Sources(map - sources)
    val values: Collection<SourceData> get() = map.values
    val entries: Set<Map.Entry<Source<*>, SourceData>> get() = map.entries

    override fun equals(other: Any?) = other is Sources && other.map == map
    override fun hashCode() = map.hashCode()
    override fun toString() = "Sources($map)"

    companion object {
        val None = Sources(emptyMap())
    }
}

enum class StyleState { Loading, Ready }

/** The drawn map. The logic issues MapLibre effects only while it is [Attached] with a [StyleState.Ready] style. */
sealed interface SurfaceState {
    /** No map; [lastCamera] is where it was (or will start). */
    data class Detached(val lastCamera: CameraSnapshot) : SurfaceState
    /** A map, its size, and its camera and visible bbox as of the last sampled event. */
    data class Attached(val viewport: Viewport, val camera: CameraSnapshot, val bbox: Bbox, val style: StyleState) : SurfaceState
}

/** A camera move asked for while no MapLibre effect may run; the last one wins. */
sealed interface CameraIntent {
    data class Move(val camera: CameraSnapshot, val duration: Millis) : CameraIntent
    /** M-10 framing; it needs the viewport, so it resolves only once the map is measured. */
    data class Frame(val bbox: Bbox, val duration: Millis) : CameraIntent
}

/** What a map_points request covers (the viewport + 50 %, §2.5). */
data class Coverage(val scope: Scope, val filter: DeviceFilter, val area: Bbox, val zoom: Double)

sealed interface PointsState {
    data object Unrequested : PointsState
    data class Requested(val request: RequestId, val coverage: Coverage, val viewport: Bbox) : PointsState
    data class Loaded(val coverage: Coverage, val mode: MapMode) : PointsState
}

/**
 * A route held by the runner as an append-only point list under [base]; the state only knows its length, its ends
 * and how far it got from its first point ([farthestFromStartM], for M-8's stationary rule), so a live delta appends
 * its new points ([MapEffect.AppendRoute]) instead of copying the route.
 */
data class RouteLine(val base: PayloadId, val length: Int, val start: GeoPoint?, val end: GeoPoint?, val farthestFromStartM: Double) {
    fun appended(points: List<GeoPoint>): RouteLine {
        val first = start ?: points.firstOrNull() ?: return this
        return copy(
            length = length + points.size, start = first, end = points.lastOrNull() ?: end,
            farthestFromStartM = maxOf(farthestFromStartM, RouteEnds.farthestFrom(first, points)),
        )
    }

    companion object {
        /** The line for a route the runner stores under [base]. */
        fun of(base: PayloadId, points: List<GeoPoint>) =
            RouteLine(base, points.size, points.firstOrNull(), points.lastOrNull(), RouteEnds.farthestFromFirst(points))
    }
}

/** The session view's facts the map needs (SessionView, §5.1); its route is held by the runner. */
data class ViewInfo(val live: Boolean, val sessionIds: List<String>, val frame: Bbox?, val route: RouteLine)

sealed interface ViewState {
    data class Loading(val request: RequestId) : ViewState
    data class Ready(val info: ViewInfo) : ViewState
}

/** M-6. It lives inside [Session.Showing], so a selection always belongs to the shown scope. */
sealed interface Selection {
    data object None : Selection
    data class Device(val key: DeviceKey, val sightings: RequestId) : Selection
}

/** D-14: the device the camera is about to ease to. */
data class FocusRequest(val request: RequestId, val minZoom: Double)

sealed interface Session {
    /** First run (V-6): no scope shown, the map shows the location (M-9); [located]: centred on it once. */
    data class None(val located: Boolean) : Session
    data class Showing(
        val scope: Scope,
        val filter: DeviceFilter,
        val routesFaint: Boolean,
        val view: ViewState,
        val points: PointsState,
        val selection: Selection,
        val focus: FocusRequest?,
        val routes: RequestId?,
        /** §2.6: a live best point or append arrived since the last points reload. */
        val liveDirty: Boolean,
    ) : Session
}

/** §1.2.5: the point data was dropped while hidden and is queried again when shown. */
enum class Memory { Full, Trimmed }

/** A MapLibre call that threw, as the runner reported it. */
data class PlatformFailure(val operation: String, val message: String)

data class MapState(
    val surface: SurfaceState,
    val pending: CameraIntent?,
    val session: Session,
    val sources: Sources,
    val cover: CoverInsets,
    val moves: MoveTracking,
    val locate: LocateModel,
    val status: LocationStatus,
    val me: MyLocation?,
    val memory: Memory,
    val nextRequest: Long,
    val lastFailure: PlatformFailure?,
) {
    companion object {
        val Initial = MapState(
            surface = SurfaceState.Detached(CameraSnapshot(GeoPoint(0.0, 0.0), 1.0)),
            pending = null,
            session = Session.None(located = false),
            sources = Sources.None,
            cover = CoverInsets(0, 0, 0, 0),
            moves = MoveTracking.Initial,
            locate = LocateModel(),
            status = LocationStatus.Ok,
            me = null,
            memory = Memory.Full,
            nextRequest = 1,
            lastFailure = null,
        )
    }
}

sealed interface MapEvent {
    // ---- the surface (sampled by the runner) ----
    data class Attached(val viewport: Viewport, val camera: CameraSnapshot, val bbox: Bbox, val styleReady: Boolean) : MapEvent
    data class Detached(val camera: CameraSnapshot) : MapEvent
    data object StyleLoading : MapEvent
    data object StyleLoaded : MapEvent
    data class MoveStarted(val byGesture: Boolean, val zoom: Double, val at: UptimeMs) : MapEvent
    data class Idle(val camera: CameraSnapshot, val viewport: Viewport, val bbox: Bbox, val at: UptimeMs) : MapEvent
    data class Tap(val hit: Hit?, val zoom: Double) : MapEvent
    data class ClusterExpansion(val at: GeoPoint, val zoom: Int) : MapEvent
    data class PlatformFailed(val failure: PlatformFailure) : MapEvent

    // ---- MapRequests and the locate button ----
    data class Show(val scope: Scope, val filter: DeviceFilter, val routesFaint: Boolean) : MapEvent
    data class Frame(val bbox: Bbox) : MapEvent
    data class Focus(val key: DeviceKey, val minZoom: Double) : MapEvent
    data class Select(val key: DeviceKey?) : MapEvent
    data class Restore(val camera: CameraSnapshot) : MapEvent
    data class Cover(val insets: CoverInsets) : MapEvent
    data class Locate(val zoom: Double) : MapEvent
    data object Trim : MapEvent
    data object Refill : MapEvent

    // ---- sources the runner observes ----
    data class LocationChanged(val status: LocationStatus, val me: MyLocation?) : MapEvent
    data class Live(val sessionId: String, val routeAppended: List<GeoPoint>, val pointsChanged: Boolean) : MapEvent
    data object LiveTick : MapEvent

    // ---- query results ----
    data class ViewLoaded(val request: RequestId, val info: ViewInfo) : MapEvent
    data class PointsLoaded(val request: RequestId, val mode: MapMode, val payload: PayloadId) : MapEvent
    data class RoutesLoaded(val request: RequestId, val payload: PayloadId) : MapEvent
    data class SightingsLoaded(val request: RequestId, val payload: PayloadId) : MapEvent
    data class FocusResolved(val request: RequestId, val at: GeoPoint, val zoom: Double) : MapEvent
    /** A query failed or found nothing; the map stays as it was. */
    data class QueryFailed(val request: RequestId) : MapEvent
}

/** What MapEvents receive (§5.3). */
sealed interface Notice {
    data object GestureStart : Notice
    data class CameraIdle(val byUser: Boolean, val viewport: Bbox, val zoom: Double) : Notice
    data class DeviceTap(val key: DeviceKey) : Notice
    data object EmptyTap : Notice
}

/** The queries' kinds: a new request of a kind cancels the running one (§2 "latest wins"). */
enum class QueryKind { View, Points, Routes, Sightings, Focus }

sealed interface MapEffect {
    /** A MapLibre call: issued only while the style is ready. */
    sealed interface OnMap : MapEffect
    data class SetSource<D : SourceData>(val source: Source<D>, val data: D) : OnMap
    /** After a style load every source is empty again: set them all, whatever MapLibre last held. */
    data class ReplaySources(val sources: Sources) : OnMap
    /** §1.2.5 Trim: empty these sources on the map (when it can take it) and forget their GeoJSON in the runner. */
    data class DropSources(val sources: Set<Source<*>>) : MapEffect
    data class ApplySelection(val key: DeviceKey?) : OnMap
    data class SetRouteOpacity(val faint: Boolean) : OnMap
    data class Move(val camera: CameraSnapshot, val duration: Millis) : OnMap
    /** M-4: ask MapLibre for the hit cluster's expansion zoom; the answer comes back as ClusterExpansion. */
    data class QueryClusterExpansion(val hit: Hit) : OnMap

    data class Notify(val notice: Notice) : MapEffect

    sealed interface Query : MapEffect { val request: RequestId; val kind: QueryKind }
    data class LoadView(override val request: RequestId, val scope: Scope) : Query { override val kind get() = QueryKind.View }
    data class LoadPoints(override val request: RequestId, val scope: Scope, val filter: DeviceFilter, val viewport: Bbox, val zoom: Double) : Query {
        override val kind get() = QueryKind.Points
    }
    data class LoadRoutes(override val request: RequestId, val scope: Scope, val viewport: Bbox, val zoom: Double) : Query {
        override val kind get() = QueryKind.Routes
    }
    data class LoadSightings(override val request: RequestId, val scope: Scope, val key: DeviceKey) : Query {
        override val kind get() = QueryKind.Sightings
    }
    data class ResolveFocus(override val request: RequestId, val scope: Scope, val key: DeviceKey) : Query {
        override val kind get() = QueryKind.Focus
    }
    data class Cancel(val kind: QueryKind) : MapEffect
    /** Appends a live delta's new points to the runner's route [base] (whatever the style's state). */
    data class AppendRoute(val base: PayloadId, val points: List<GeoPoint>) : MapEffect
}

data class Step(val state: MapState, val effects: List<MapEffect>)
