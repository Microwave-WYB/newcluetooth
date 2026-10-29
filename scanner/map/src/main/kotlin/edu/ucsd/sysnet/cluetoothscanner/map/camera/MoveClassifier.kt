package edu.ucsd.sysnet.cluetoothscanner.map.camera

import kotlin.math.abs

/**
 * Tells the user's camera moves from the app's (P-4, P-6, D-4, M-11, T-2), as an immutable value. A move is the
 * user's when a gesture (MapLibre REASON_API_GESTURE: drag, pinch, fling, double-tap) started it or joined it before
 * the map came to rest. Our own eases, jumps and fits report REASON_API_ANIMATION and are the app's. Mirrors the
 * prototype's movestart/moveend with and without originalEvent.
 *
 * The fling edge: when the finger lifts, MapLibre can report the map at rest and then start the fling as a new
 * gesture move. Its idle is the user's again (so D-4 re-filters and P-4 measures at the final position), but a
 * gesture start within [MoveClassifier.CONTINUATION_MS] of the user's idle continues that gesture and is no new
 * onUserGestureStart.
 */
sealed interface MoveTracking {
    /** No user move under way. [lastUserIdleMs]: when the last user move came to rest, if any. */
    data class Resting(val lastUserIdleMs: Long?) : MoveTracking
    /** A user move is under way since a gesture started at [zoomAtStart]. */
    data class UserMove(val zoomAtStart: Double) : MoveTracking

    /** A camera move started; [Started.reportGestureStart] is true for the first gesture start of a move. */
    fun moveStarted(byGesture: Boolean, zoom: Double, nowMs: Long): Started = when (this) {
        is UserMove -> Started(this, reportGestureStart = false)
        is Resting -> if (!byGesture) Started(this, reportGestureStart = false) else Started(
            UserMove(zoom),
            reportGestureStart = lastUserIdleMs == null || nowMs - lastUserIdleMs > MoveClassifier.CONTINUATION_MS,
        )
    }

    /** The map came to rest. zoomed: the user's move changed the zoom (P-4: "after any user zoom"). */
    fun idle(zoom: Double, nowMs: Long): Rested = when (this) {
        is UserMove -> Rested(Resting(nowMs), MoveClassifier.Result(byUser = true, zoomed = abs(zoom - zoomAtStart) > 0.01))
        is Resting -> Rested(this, MoveClassifier.Result(byUser = false, zoomed = false))
    }

    data class Started(val next: MoveTracking, val reportGestureStart: Boolean)
    data class Rested(val next: MoveTracking, val result: MoveClassifier.Result)

    companion object {
        val Initial: MoveTracking = Resting(lastUserIdleMs = null)
    }
}

/** The stateful form of [MoveTracking], one value replaced per call (kept for its callers and tests). */
class MoveClassifier {
    private var tracking = MoveTracking.Initial

    fun onMoveStarted(byGesture: Boolean, zoom: Double, nowMs: Long): Boolean =
        tracking.moveStarted(byGesture, zoom, nowMs).also { tracking = it.next }.reportGestureStart

    fun onIdle(zoom: Double, nowMs: Long): Result = tracking.idle(zoom, nowMs).also { tracking = it.next }.result

    data class Result(val byUser: Boolean, val zoomed: Boolean)

    companion object {
        /** A fling starts on the same finger lift as the drag's idle; a new gesture needs a new touch, far later. */
        const val CONTINUATION_MS = 150L
    }
}
