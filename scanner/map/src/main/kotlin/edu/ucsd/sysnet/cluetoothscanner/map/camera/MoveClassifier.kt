package edu.ucsd.sysnet.cluetoothscanner.map.camera

import kotlin.math.abs

/**
 * Tells the user's camera moves from the app's (P-4, P-6, D-4, M-11, T-2). A move is the user's when a gesture
 * (drag, pinch, fling, double-tap) started it or joined it before the map came to rest; MapLibre reports those as
 * REASON_API_GESTURE. Every other start (our ease, jump or fit) is the app's. Mirrors the prototype's
 * movestart/moveend with and without originalEvent.
 */
class MoveClassifier {
    private var userMove = false
    private var zoomAtStart = 0.0

    /** A camera move started. Returns true for the first gesture start of a move: report onUserGestureStart once. */
    fun onMoveStarted(byGesture: Boolean, zoom: Double): Boolean {
        if (!byGesture || userMove) return false
        userMove = true
        zoomAtStart = zoom
        return true
    }

    /** The map came to rest. zoomed: the user's move changed the zoom (P-4: "after any user zoom"). */
    fun onIdle(zoom: Double): Result {
        val r = Result(byUser = userMove, zoomed = userMove && abs(zoom - zoomAtStart) > 0.01)
        userMove = false
        return r
    }

    data class Result(val byUser: Boolean, val zoomed: Boolean)
}
