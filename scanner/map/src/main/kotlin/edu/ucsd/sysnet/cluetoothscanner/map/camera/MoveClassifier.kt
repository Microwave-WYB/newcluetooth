package edu.ucsd.sysnet.cluetoothscanner.map.camera

import kotlin.math.abs

/**
 * Tells the user's camera moves from the app's (P-4, P-6, D-4, M-11, T-2). A move is the user's when a gesture
 * (MapLibre REASON_API_GESTURE: drag, pinch, fling, double-tap) started it or joined it before the map came to rest.
 * Our own eases, jumps and fits report REASON_API_ANIMATION and are the app's. Mirrors the prototype's
 * movestart/moveend with and without originalEvent.
 *
 * The fling edge: when the finger lifts, MapLibre can report the map at rest and then start the fling as a new
 * gesture move. Its idle is the user's again (so D-4 re-filters and P-4 measures at the final position), but a
 * gesture start within [CONTINUATION_MS] of the user's idle continues that gesture and is no new onUserGestureStart.
 */
class MoveClassifier {
    private var userMove = false
    private var zoomAtStart = 0.0
    private var lastUserIdleMs = Long.MIN_VALUE / 2

    /** A camera move started. Returns true for the first gesture start of a move: report onUserGestureStart once. */
    fun onMoveStarted(byGesture: Boolean, zoom: Double, nowMs: Long): Boolean {
        if (!byGesture || userMove) return false
        userMove = true
        zoomAtStart = zoom
        return nowMs - lastUserIdleMs > CONTINUATION_MS
    }

    /** The map came to rest. zoomed: the user's move changed the zoom (P-4: "after any user zoom"). */
    fun onIdle(zoom: Double, nowMs: Long): Result {
        val r = Result(byUser = userMove, zoomed = userMove && abs(zoom - zoomAtStart) > 0.01)
        if (userMove) lastUserIdleMs = nowMs
        userMove = false
        return r
    }

    data class Result(val byUser: Boolean, val zoomed: Boolean)

    companion object {
        /** A fling starts on the same finger lift as the drag's idle; a new gesture needs a new touch, far later. */
        const val CONTINUATION_MS = 150L
    }
}
