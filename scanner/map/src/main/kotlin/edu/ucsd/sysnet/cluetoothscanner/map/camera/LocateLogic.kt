package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus

/**
 * P-4 and P-5 as an immutable value, as the prototype's updateLocate:
 * - the locate button fades in only after the user's own pan or zoom has taken the location more than 40 dp from
 *   the centre of the visible map area, or after any user zoom since the last recenter;
 * - app camera moves never show it; tapping it recenters and hides it; it never shows without a location;
 * - while the location is unusable (status.warn) the warning takes the spot instead, always visible.
 */
data class LocateModel(val zoomedSinceRecenter: Boolean = false, val shownByUser: Boolean = false) {
    /** The map came to rest after the user's move. offsetDp: [MapGeometry.locationOffset], null without a location. */
    fun onUserIdle(zoomed: Boolean, offsetDp: Double?): LocateModel {
        val zoomedSince = zoomedSinceRecenter || zoomed
        return LocateModel(zoomedSince, shownByUser = offsetDp != null && (zoomedSince || offsetDp > LocateLogic.OFFSET_DP))
    }

    /** The locate button was tapped: the camera eases to the location and the button hides again. */
    fun onLocate() = LocateModel()

    /** An unusable location drops the user-shown state: it comes back only after a new user move. */
    fun onStatus(status: LocationStatus) =
        if (status.warn || status.state != LocationStatus.State.Ok) copy(shownByUser = false) else this

    fun showWarning(status: LocationStatus) = status.warn

    fun showLocate(status: LocationStatus, hasLocation: Boolean) =
        shownByUser && hasLocation && !status.warn && status.state == LocationStatus.State.Ok
}

/** The stateful form of [LocateModel], one value replaced per call (kept for its callers and tests). */
class LocateLogic {
    private var model = LocateModel()

    fun onUserIdle(zoomed: Boolean, offsetDp: Double?) { model = model.onUserIdle(zoomed, offsetDp) }
    fun onLocate() { model = model.onLocate() }
    fun onStatus(status: LocationStatus) { model = model.onStatus(status) }
    fun showWarning(status: LocationStatus) = model.showWarning(status)
    fun showLocate(status: LocationStatus, hasLocation: Boolean) = model.showLocate(status, hasLocation)

    companion object {
        const val OFFSET_DP = 40.0
    }
}
