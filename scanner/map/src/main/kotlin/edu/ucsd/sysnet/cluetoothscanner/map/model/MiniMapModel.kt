package edu.ucsd.sysnet.cluetoothscanner.map.model

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Padding
import edu.ucsd.sysnet.cluetoothscanner.map.camera.RouteEnds
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot

/*
 * R-5's mini map as data: one snapshotter per frame (size and theme), reused; a refresh with the same route and
 * camera takes no snapshot; at most one snapshot in flight. RouteMiniMap is the runner.
 */

/** What a snapshotter is built for: its size in px, the screen's pixel ratio, the theme and the base style. */
data class MiniFrame(val widthPx: Int, val heightPx: Int, val density: Float, val theme: MapTheme, val baseStyleJson: String?) {
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density
}

/** Snapshotters are counted; a result from an older one (cancelled with its frame) is dropped. */
@JvmInline value class SnapshotterId(val value: Int)

/** What one snapshot shows; [showEnds]: M-8's Start and End (hidden when the session barely moved). */
data class MiniTarget(val route: List<GeoPoint>, val camera: CameraSnapshot, val showEnds: Boolean)

/**
 * A MapSnapshotter's native lifetime. MapLibre 12.3.1 holds the Java snapshotter only weakly; if it is garbage-collected
 * while its URL style is still loading, its finalizer frees the native observer that the style response then calls
 * (SIGSEGV in MapSnapshotter::Impl::onDidFinishLoadingStyle, Note10). So a [Busy] snapshotter, whose style load or
 * snapshot is running, is never released: it is only ever released from [Idle], or after its last callback.
 */
sealed interface MiniSnapshotter {
    data object None : MiniSnapshotter
    /** Built for [frame]; nothing running, so it may be released. */
    data class Idle(val id: SnapshotterId, val frame: MiniFrame) : MiniSnapshotter
    /** A snapshot of [target] (and, the first time, the style load) is running. */
    data class Busy(val id: SnapshotterId, val frame: MiniFrame, val target: MiniTarget) : MiniSnapshotter
}

data class MiniMapState(
    val frame: MiniFrame?,
    val route: List<GeoPoint>,
    val fallback: GeoPoint?,
    val snapshotter: MiniSnapshotter,
    /** Snapshotters left busy by a frame change or dispose, kept alive until their last callback; then released. */
    val retiring: Set<SnapshotterId>,
    /** What the image shows, if anything. */
    val rendered: MiniTarget?,
    val nextSnapshotter: Int,
) {
    companion object {
        val Initial = MiniMapState(null, emptyList(), null, MiniSnapshotter.None, emptySet(), null, 1)
    }
}

sealed interface MiniMapEvent {
    /** The composable's size, density, theme or base style settled on a new value; null while it has no size. */
    data class Configure(val frame: MiniFrame?) : MiniMapEvent
    data class Inputs(val route: List<GeoPoint>, val fallback: GeoPoint?) : MiniMapEvent
    /** When it appears (the panel opens) and every 10 s while shown. */
    data object Tick : MiniMapEvent
    data class Done(val snapshotter: SnapshotterId, val target: MiniTarget) : MiniMapEvent
    data class Failed(val snapshotter: SnapshotterId) : MiniMapEvent
    data object Disposed : MiniMapEvent
}

sealed interface MiniMapEffect {
    data class Create(val id: SnapshotterId, val frame: MiniFrame, val target: MiniTarget) : MiniMapEffect
    data class Update(val id: SnapshotterId, val target: MiniTarget) : MiniMapEffect
    data class Start(val id: SnapshotterId, val target: MiniTarget) : MiniMapEffect
    /** Drop the snapshotter: it is idle (its style loaded, no snapshot running), so it may be collected. */
    data class Release(val id: SnapshotterId) : MiniMapEffect
}

data class MiniStep(val state: MiniMapState, val effects: List<MiniMapEffect>)

object MiniMapReducer {
    const val REFRESH_MS = 10_000L
    /** The recorder panel's morph resizes the mini map every frame; a frame is used once its size held this long. */
    const val FRAME_SETTLE_MS = 300L

    fun reduce(state: MiniMapState, event: MiniMapEvent): MiniStep = when (event) {
        is MiniMapEvent.Configure -> configure(state, event.frame)
        is MiniMapEvent.Inputs -> MiniStep(state.copy(route = event.route, fallback = event.fallback), emptyList())
        MiniMapEvent.Tick -> tick(state)
        is MiniMapEvent.Done -> finished(state, event.snapshotter, event.target)
        is MiniMapEvent.Failed -> finished(state, event.snapshotter, null)
        MiniMapEvent.Disposed -> configure(state, null)
    }

    /**
     * A new frame (or dispose) leaves the current snapshotter: an idle one is released, a busy one retires and is
     * released after its last callback. The next tick builds one for the new frame.
     */
    private fun configure(state: MiniMapState, frame: MiniFrame?): MiniStep {
        if (frame == state.frame && frame != null) return MiniStep(state, emptyList())
        return when (val s = state.snapshotter) {
            MiniSnapshotter.None -> MiniStep(state.copy(frame = frame, rendered = null), emptyList())
            is MiniSnapshotter.Idle ->
                MiniStep(state.copy(frame = frame, snapshotter = MiniSnapshotter.None, rendered = null), listOf(MiniMapEffect.Release(s.id)))
            is MiniSnapshotter.Busy ->
                MiniStep(state.copy(frame = frame, snapshotter = MiniSnapshotter.None, retiring = state.retiring + s.id, rendered = null), emptyList())
        }
    }

    private fun tick(state: MiniMapState): MiniStep {
        val frame = state.frame ?: return MiniStep(state, emptyList())
        val camera = camera(state.route, state.fallback, frame.widthDp, frame.heightDp) ?: return MiniStep(state, emptyList())
        val target = MiniTarget(state.route, camera, showEnds = RouteEnds.shown(state.route))
        if (state.rendered == target) return MiniStep(state, emptyList())
        return when (val s = state.snapshotter) {
            // one native snapshotter at a time: a new one waits until the retiring ones are released
            MiniSnapshotter.None -> if (state.retiring.isNotEmpty()) MiniStep(state, emptyList()) else {
                val id = SnapshotterId(state.nextSnapshotter)
                MiniStep(
                    state.copy(snapshotter = MiniSnapshotter.Busy(id, frame, target), nextSnapshotter = state.nextSnapshotter + 1),
                    listOf(MiniMapEffect.Create(id, frame, target), MiniMapEffect.Start(id, target)),
                )
            }
            is MiniSnapshotter.Idle -> MiniStep(
                state.copy(snapshotter = MiniSnapshotter.Busy(s.id, s.frame, target)),
                listOf(MiniMapEffect.Update(s.id, target), MiniMapEffect.Start(s.id, target)),
            )
            is MiniSnapshotter.Busy -> MiniStep(state, emptyList())
        }
    }

    /** A snapshot ended ([target] null: it failed, and the previous image stays). */
    private fun finished(state: MiniMapState, id: SnapshotterId, target: MiniTarget?): MiniStep {
        if (id in state.retiring) return MiniStep(state.copy(retiring = state.retiring - id), listOf(MiniMapEffect.Release(id)))
        val s = state.snapshotter as? MiniSnapshotter.Busy ?: return MiniStep(state, emptyList())
        if (s.id != id) return MiniStep(state, emptyList())
        return MiniStep(state.copy(snapshotter = MiniSnapshotter.Idle(s.id, s.frame), rendered = target ?: state.rendered), emptyList())
    }

    /** The prototype's miniMap framing: the route's bounds with 28 dp padding, at most zoom 16. */
    fun camera(route: List<GeoPoint>, fallback: GeoPoint?, widthDp: Float, heightDp: Float): CameraSnapshot? =
        (MapGeometry.bboxOf(route) ?: fallback?.let { Bbox(it.lon, it.lat, it.lon, it.lat) })?.let { bbox ->
            MapGeometry.fit(bbox, Viewport(widthDp.toDouble(), heightDp.toDouble()), Padding(28.0, 28.0, 28.0, 28.0), maxZoom = 16.0)
        }
}
