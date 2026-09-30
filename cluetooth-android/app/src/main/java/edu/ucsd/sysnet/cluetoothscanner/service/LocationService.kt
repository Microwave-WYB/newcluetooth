package edu.ucsd.sysnet.cluetoothscanner.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationService(
    private val context: Context,
    private val repository: CluetoothRepository,
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val requestState = ActiveRequestState<LocationCallback>()

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (requestState.isActive) {
            Log.d(TAG, "Location updates already started")
            return
        }

        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services are disabled")
            _currentLocation.value = null
            repository.clearLocation()
            return
        }

        Log.i(TAG, "Starting location updates...")
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL,
        )
            .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL)
            .setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL * 2)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateAgeMillis(LOCATION_UPDATE_INTERVAL)
            .build()

        val activeRequest = requestState.begin { generation ->
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    if (!requestState.accepts(generation)) return
                    val bestLocation = locationResult.locations.minByOrNull { it.accuracy }
                        ?: locationResult.lastLocation

                    bestLocation?.let { location ->
                        val currentLoc = _currentLocation.value
                        if (
                            currentLoc == null ||
                            location.accuracy < currentLoc.accuracy ||
                            (location.time - currentLoc.time) > LOCATION_UPDATE_INTERVAL
                        ) {
                            if (!requestState.accepts(generation)) return
                            _currentLocation.value = location
                            forwardLocation(location)
                            Log.d(
                                TAG,
                                "Location updated: ${location.latitude}, " +
                                    "${location.longitude}, accuracy: ${location.accuracy}m",
                            )
                        }
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                activeRequest.callback,
                Looper.getMainLooper(),
            ).addOnFailureListener { error ->
                handleRegistrationFailure(activeRequest.generation, error)
            }
            if (!requestState.accepts(activeRequest.generation)) return

            Log.i(TAG, "Location updates started")
            getLastKnownLocationAsync(activeRequest.generation)
        } catch (error: Exception) {
            handleRegistrationFailure(activeRequest.generation, error)
        }
    }

    fun stopLocationUpdates() {
        val callback = requestState.stop()
        if (callback == null) {
            Log.d(TAG, "Location updates already stopped")
        } else {
            fusedLocationClient.removeLocationUpdates(callback)
            Log.i(TAG, "Location updates stopped")
        }
        _currentLocation.value = null
        repository.clearLocation()
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationAsync(generation: Long) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (!requestState.accepts(generation)) return@addOnSuccessListener
                location?.let {
                    val currentLoc = _currentLocation.value
                    if (currentLoc == null || it.accuracy < currentLoc.accuracy) {
                        if (!requestState.accepts(generation)) return@addOnSuccessListener
                        _currentLocation.value = it
                        forwardLocation(it)
                        Log.d(
                            TAG,
                            "Last known location: ${it.latitude}, ${it.longitude}, " +
                                "accuracy: ${it.accuracy}m",
                        )
                    }
                }
            }
            .addOnFailureListener { error ->
                if (!requestState.accepts(generation)) return@addOnFailureListener
                if (_currentLocation.value == null) {
                    repository.clearLocation()
                }
                Log.e(TAG, "Failed to get last known location", error)
            }
    }

    private fun handleRegistrationFailure(generation: Long, error: Exception) {
        if (!requestState.fail(generation)) return
        _currentLocation.value = null
        repository.clearLocation()
        Log.e(TAG, "Failed to start location updates", error)
    }

    private fun forwardLocation(location: Location) {
        if (!location.hasAccuracy()) {
            repository.clearLocation()
            return
        }
        repository.updateLocation(
            CoreLocationInput(
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = location.accuracy.toDouble(),
                observedAtMs = location.time,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            ),
        )
    }

    fun getLastKnownLocation(): Location? = _currentLocation.value

    fun isLocationAvailable(): Boolean = _currentLocation.value != null

    fun getCurrentAccuracy(): Float? = _currentLocation.value?.accuracy

    fun isHighAccuracyLocation(): Boolean {
        val accuracy = getCurrentAccuracy()
        return accuracy != null && accuracy <= 10.0f
    }

    fun isLocationEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    fun forceRestartLocationUpdates() {
        Log.i(TAG, "Force restarting location updates")
        stopLocationUpdates()
        startLocationUpdates()
    }

    fun cleanup() {
        stopLocationUpdates()
    }

    companion object {
        private const val TAG = "LocationService"
        private const val LOCATION_UPDATE_INTERVAL = 2000L
    }
}
