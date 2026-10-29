package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.map.stub.LocationStatus

/**
 * P-4 and P-5, as the prototype's updateLocate:
 * - the locate button fades in only after the user's own pan or zoom has taken the location more than 40 dp from
 *   the centre of the visible map area, or after any user zoom since the last recenter;
 * - app camera moves never show it; tapping it recenters and hides it; it never shows without a location;
 * - while the location is unusable (status.warn) the warning takes the spot instead, always visible.
 */
class LocateLogic {
    private var zoomedSinceRecenter = false
    private var shownByUser = false

    /** The map came to rest after the user's move. offsetDp: [MapGeometry.locationOffset], null without a location. */
    fun onUserIdle(zoomed: Boolean, offsetDp: Double?) {
        if (zoomed) zoomedSinceRecenter = true
        shownByUser = offsetDp != null && (zoomedSinceRecenter || offsetDp > OFFSET_DP)
    }

    /** The locate button was tapped: the camera eases to the location and the button hides again. */
    fun onLocate() {
        zoomedSinceRecenter = false
        shownByUser = false
    }

    /** An unusable location drops the user-shown state: it comes back only after a new user move. */
    fun onStatus(status: LocationStatus) {
        if (status.warn || status.state != LocationStatus.State.Ok) shownByUser = false
    }

    fun showWarning(status: LocationStatus) = status.warn

    fun showLocate(status: LocationStatus, hasLocation: Boolean) =
        shownByUser && hasLocation && !status.warn && status.state == LocationStatus.State.Ok

    companion object {
        const val OFFSET_DP = 40.0
    }
}
