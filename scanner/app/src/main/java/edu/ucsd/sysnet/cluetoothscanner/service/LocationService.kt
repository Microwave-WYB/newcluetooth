package edu.ucsd.sysnet.cluetoothscanner.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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

    /** One registration with either backend: Play services calls the callback, the platform the listener. */
    private abstract class Registration : LocationCallback(), LocationListener

    private val requestState = ActiveRequestState<Registration>()
    private var activeProfile: LocationProfile? = null
    private var usesFused = true

    /**
     * Starts location updates at [profile]. Already running at the same profile is a no-op; running
     * at another one swaps the request in place and keeps the current fix.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(profile: LocationProfile = LocationProfile.LIVE) {
        if (requestState.isActive) {
            if (activeProfile == profile) {
                Log.d(TAG, "Location updates already started")
                return
            }
            Log.i(TAG, "Location profile ${activeProfile?.name} -> ${profile.name}")
            requestState.stop()?.let(::unregister)
        }

        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services are disabled")
            _currentLocation.value = null
            repository.clearLocation()
            return
        }

        Log.i(TAG, "Starting location updates (${profile.name})...")
        activeProfile = profile
        val activeRequest = requestState.begin { generation ->
            object : Registration() {
                override fun onLocationResult(locationResult: LocationResult) {
                    // A batch is oldest first; the newest fix is the one that matters.
                    locationResult.locations.lastOrNull()?.let { deliver(generation, it) }
                }

                override fun onLocationChanged(location: Location) = deliver(generation, location)

                @Deprecated("Required on API < 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
        }

        try {
            register(profile, activeRequest.callback) { error ->
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
        val registration = requestState.stop()
        activeProfile = null
        if (registration == null) {
            Log.d(TAG, "Location updates already stopped")
        } else {
            unregister(registration)
            Log.i(TAG, "Location updates stopped")
        }
        _currentLocation.value = null
        repository.clearLocation()
    }

    /** Play services fused provider when available, otherwise the platform GPS and network providers. */
    @SuppressLint("MissingPermission")
    private fun register(profile: LocationProfile, registration: Registration, onFailure: (Exception) -> Unit) {
        usesFused = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        if (usesFused) {
            val request = LocationRequest.Builder(
                if (profile.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                profile.intervalMs,
            )
                .setMinUpdateIntervalMillis(profile.minIntervalMs)
                .setMinUpdateDistanceMeters(profile.minDistanceMeters)
                // The default for high accuracy is to hold fixes back until they are accurate, which
                // starves a stationary or indoor recording of points.
                .setWaitForAccurateLocation(false)
                .build()
            fusedLocationClient.requestLocationUpdates(request, registration, Looper.getMainLooper())
                .addOnFailureListener(onFailure)
        } else {
            val providers = if (profile.highAccuracy) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            }.filter(locationManager::isProviderEnabled)
                .let { if (profile.highAccuracy) it else it.take(1) }
            for (provider in providers) {
                locationManager.requestLocationUpdates(
                    provider,
                    profile.intervalMs,
                    profile.minDistanceMeters,
                    registration,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    private fun unregister(registration: Registration) {
        if (usesFused) {
            fusedLocationClient.removeLocationUpdates(registration)
        } else {
            locationManager.removeUpdates(registration)
        }
    }

    private fun deliver(generation: Long, location: Location) {
        if (!requestState.accepts(generation)) return
        if (!acceptsFix(_currentLocation.value?.stamp(), location.stamp())) return
        _currentLocation.value = location
        forwardLocation(location)
        Log.d(
            TAG,
            "Location updated: ${location.latitude}, ${location.longitude}, " +
                "accuracy: ${location.accuracy}m, provider: ${location.provider}",
        )
    }

    private fun Location.stamp() = FixStamp(provider, if (hasAccuracy()) accuracy else Float.MAX_VALUE, elapsedRealtimeNanos)

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationAsync(generation: Long) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (!requestState.accepts(generation)) return@addOnSuccessListener
                location?.let {
                    if (_currentLocation.value == null) {
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
        val profile = activeProfile ?: LocationProfile.LIVE
        stopLocationUpdates()
        startLocationUpdates(profile)
    }

    fun cleanup() {
        stopLocationUpdates()
    }

    companion object {
        private const val TAG = "LocationService"
    }
}
