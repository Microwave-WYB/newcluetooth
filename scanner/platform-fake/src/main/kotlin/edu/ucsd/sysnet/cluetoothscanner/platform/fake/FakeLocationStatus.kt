package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import androidx.activity.ComponentActivity
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.Action
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationWarn
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The prototype's `?loc=` demo states (`data.js` `LOC_DEMO`). */
enum class LocationDemo { Ok, Denied, Off, Waiting, Stale }

/** [LocationStatusSource] playing one [LocationDemo]; switch with [set]. */
class FakeLocationStatus(demo: LocationDemo = LocationDemo.Ok) : LocationStatusSource {
    private val _status = MutableStateFlow(statusFor(demo))
    private val _myLocation = MutableStateFlow(locationFor(demo))
    override val status: StateFlow<LocationStatus> = _status.asStateFlow()
    override val myLocation: StateFlow<MyLocation?> = _myLocation.asStateFlow()

    var turnOnCalls = 0
        private set

    /** Plays the user turning location on: the status becomes Ok. */
    override fun turnOn(activity: ComponentActivity): PlatformResult<Unit> {
        turnOnCalls++
        set(LocationDemo.Ok)
        return PlatformResult.Ok(Unit)
    }

    fun set(demo: LocationDemo) {
        _status.value = statusFor(demo)
        _myLocation.value = locationFor(demo)
    }

    companion object {
        /** `data.js` `getMyLocation()`. */
        val DEMO_LOCATION = GeoPoint(lon = -117.23421, lat = 32.88012)
        const val DEMO_ACCURACY_M = 8.0
        private const val WAITING_MS = 45_000L
        private const val STALE_AGE_MS = 5 * 60_000L

        fun statusFor(demo: LocationDemo): LocationStatus = when (demo) {
            LocationDemo.Ok -> LocationStatus.Ok
            LocationDemo.Denied -> LocationStatus(State.Denied, warn = true, action = Action.Allow)
            LocationDemo.Off -> LocationStatus(State.Off, warn = true, action = Action.TurnOn)
            LocationDemo.Waiting -> LocationStatus(State.Waiting, warn = WAITING_MS > LocationWarn.NO_FIX_MS)
            LocationDemo.Stale -> LocationStatus(
                State.Stale,
                warn = STALE_AGE_MS > LocationWarn.STALE_MS,
                ageMin = (STALE_AGE_MS / 60_000).toInt(),
            )
        }

        fun locationFor(demo: LocationDemo): MyLocation? = when (demo) {
            LocationDemo.Ok -> MyLocation(DEMO_LOCATION, DEMO_ACCURACY_M, ageMs = 0)
            LocationDemo.Stale -> MyLocation(DEMO_LOCATION, DEMO_ACCURACY_M, ageMs = STALE_AGE_MS)
            LocationDemo.Denied, LocationDemo.Off, LocationDemo.Waiting -> null
        }
    }
}
