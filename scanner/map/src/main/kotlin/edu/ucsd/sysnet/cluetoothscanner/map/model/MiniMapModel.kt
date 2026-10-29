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

sealed interface MiniSnapshotter {
    data object None : MiniSnapshotter
    data class Created(val id: SnapshotterId, val frame: MiniFrame) : MiniSnapshotter
}

sealed interface MiniShot {
    /** [rendered]: what the image shows, if anything. */
    data class Idle(val rendered: MiniTarget?) : MiniShot
    data class InFlight(val target: MiniTarget, val rendered: MiniTarget?) : MiniShot
}

data class MiniMapState(
    val frame: MiniFrame?,
    val route: List<GeoPoint>,
    val fallback: GeoPoint?,
    val snapshotter: MiniSnapshotter,
    val shot: MiniShot,
    val nextSnapshotter: Int,
) {
    companion object {
        val Initial = MiniMapState(null, emptyList(), null, MiniSnapshotter.None, MiniShot.Idle(null), 1)
    }
}

sealed interface MiniMapEvent {
    /** The composable's size, density, theme or base style changed; null while it has no size. */
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
    data object Cancel : MiniMapEffect
}

data class MiniStep(val state: MiniMapState, val effects: List<MiniMapEffect>)

object MiniMapReducer {
    const val REFRESH_MS = 10_000L

    fun reduce(state: MiniMapState, event: MiniMapEvent): MiniStep = when (event) {
        is MiniMapEvent.Configure -> configure(state, event.frame)
        is MiniMapEvent.Inputs -> MiniStep(state.copy(route = event.route, fallback = event.fallback), emptyList())
        MiniMapEvent.Tick -> tick(state)
        is MiniMapEvent.Done -> finished(state, event.snapshotter) { MiniShot.Idle(event.target) }
        is MiniMapEvent.Failed -> finished(state, event.snapshotter) { shot ->
            // keep the previous image
            MiniShot.Idle(shot.rendered)
        }
        MiniMapEvent.Disposed ->
            MiniStep(state.copy(snapshotter = MiniSnapshotter.None, shot = MiniShot.Idle(null)), listOf(MiniMapEffect.Cancel))
    }

    /** A new frame drops the old snapshotter (and what it rendered); the next tick builds one for the new frame. */
    private fun configure(state: MiniMapState, frame: MiniFrame?): MiniStep {
        if (frame == state.frame) return MiniStep(state, emptyList())
        val cancel = if (state.snapshotter is MiniSnapshotter.Created) listOf(MiniMapEffect.Cancel) else emptyList()
        return MiniStep(state.copy(frame = frame, snapshotter = MiniSnapshotter.None, shot = MiniShot.Idle(null)), cancel)
    }

    private fun tick(state: MiniMapState): MiniStep {
        val frame = state.frame ?: return MiniStep(state, emptyList())
        val shot = state.shot as? MiniShot.Idle ?: return MiniStep(state, emptyList())
        val camera = camera(state.route, state.fallback, frame.widthDp, frame.heightDp) ?: return MiniStep(state, emptyList())
        val target = MiniTarget(state.route, camera, showEnds = RouteEnds.shown(state.route))
        if (shot.rendered == target) return MiniStep(state, emptyList())
        return when (val s = state.snapshotter) {
            MiniSnapshotter.None -> {
                val id = SnapshotterId(state.nextSnapshotter)
                MiniStep(
                    state.copy(
                        snapshotter = MiniSnapshotter.Created(id, frame), shot = MiniShot.InFlight(target, shot.rendered),
                        nextSnapshotter = state.nextSnapshotter + 1,
                    ),
                    listOf(MiniMapEffect.Create(id, frame, target), MiniMapEffect.Start(id, target)),
                )
            }
            is MiniSnapshotter.Created -> MiniStep(
                state.copy(shot = MiniShot.InFlight(target, shot.rendered)),
                listOf(MiniMapEffect.Update(s.id, target), MiniMapEffect.Start(s.id, target)),
            )
        }
    }

    private fun finished(state: MiniMapState, id: SnapshotterId, next: (MiniShot.InFlight) -> MiniShot): MiniStep {
        val s = state.snapshotter as? MiniSnapshotter.Created ?: return MiniStep(state, emptyList())
        val shot = state.shot as? MiniShot.InFlight ?: return MiniStep(state, emptyList())
        if (s.id != id) return MiniStep(state, emptyList())
        return MiniStep(state.copy(shot = next(shot)), emptyList())
    }

    /** The prototype's miniMap framing: the route's bounds with 28 dp padding, at most zoom 16. */
    fun camera(route: List<GeoPoint>, fallback: GeoPoint?, widthDp: Float, heightDp: Float): CameraSnapshot? =
        (MapGeometry.bboxOf(route) ?: fallback?.let { Bbox(it.lon, it.lat, it.lon, it.lat) })?.let { bbox ->
            MapGeometry.fit(bbox, Viewport(widthDp.toDouble(), heightDp.toDouble()), Padding(28.0, 28.0, 28.0, 28.0), maxZoom = 16.0)
        }
}
