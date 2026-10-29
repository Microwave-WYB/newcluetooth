package edu.ucsd.sysnet.cluetoothscanner.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §4.2: the one location client of the process.
 *
 * - RECORDING (2 s, high accuracy) between [startRecording] and [stopRecording]; its fixes go to
 *   `core.ingestLocation`, and a fix without accuracy or the end of the request calls `core.clearLocation()`.
 * - FOREGROUND (balanced, 10 s) while the UI is started otherwise; it feeds only [myLocation] and [status].
 * - `FusedLocationProviderClient` when Play services are available, otherwise the platform providers (v3).
 * - [status] follows SPEC P-5 ([locationStatus]) and is re-evaluated every 5 s while a request is wanted; that tick
 *   also registers a request that was waiting for the permission.
 *
 * Everything runs on the main looper.
 */
@SuppressLint("MissingPermission") // Every registration checks the permission first.
class LocationClient(
    private val context: Context,
    private val core: Deferred<CoreIngest>,
    private val scope: CoroutineScope,
    /** True while the UI is started (`processStarted()` in production). */
    uiStarted: StateFlow<Boolean>,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : RecordingLocation, LocationStatusSource {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val recording = MutableStateFlow(false)
    private val _status = MutableStateFlow(LocationStatus.Ok)
    private val _myLocation = MutableStateFlow<MyLocation?>(null)
    override val status: StateFlow<LocationStatus> = _status.asStateFlow()
    override val myLocation: StateFlow<MyLocation?> = _myLocation.asStateFlow()

    private var wanted: LocationProfile? = null
    private var active: LocationProfile? = null
    private var registration: Registration? = null
    private var requestedSinceMs: Long? = null
    private var lastFix: FixStamp? = null
    private var lastFixAtMs: Long? = null
    private var lastLocation: Location? = null
    private var ticker: Job? = null

    /** One registration with either backend: Play services calls the callback, the platform the listener. */
    private abstract inner class Registration : LocationCallback(), LocationListener {
        var fused = false
    }

    private val providersReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) = evaluate()
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            combine(recording, uiStarted) { rec, ui -> LocationProfile.wanted(rec, ui) }
                .distinctUntilChanged()
                .collect(::want)
        }
    }

    override fun startRecording() {
        recording.value = true
    }

    override fun stopRecording() {
        recording.value = false
        scope.launch { core.await().clearLocation() }
    }

    /** P-5 "Turn on": the Play services settings dialog, else the system location settings. */
    override fun turnOn(activity: ComponentActivity) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LocationProfile.RECORDING.intervalMs).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
        if (!playServices()) return openLocationSettings(activity)
        LocationServices.getSettingsClient(activity).checkLocationSettings(settings)
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) {
                    runCatching { error.startResolutionForResult(activity, TURN_ON_REQUEST) }
                        .onFailure { openLocationSettings(activity) }
                } else {
                    openLocationSettings(activity)
                }
            }
    }

    private fun openLocationSettings(activity: ComponentActivity) {
        activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun want(profile: LocationProfile?) {
        wanted = profile
        if (profile == null) {
            unregister()
            requestedSinceMs = null
            _myLocation.value = null
            ticker?.cancel()
            ticker = null
            runCatching { context.unregisterReceiver(providersReceiver) }
            return
        }
        if (ticker == null) {
            ContextCompat.registerReceiver(
                context, providersReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ticker = scope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    delay(STATUS_TICK_MS)
                    evaluate()
                }
            }
        }
        if (requestedSinceMs == null) requestedSinceMs = elapsedMs()
        register(profile)
        evaluate()
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providerOn() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun evaluate() {
        val profile = wanted
        if (profile != null && active != profile && permitted()) register(profile)
        val now = elapsedMs()
        _status.value = locationStatus(permitted(), providerOn(), requestedSinceMs, lastFixAtMs, now)
        lastLocation?.let { _myLocation.value = it.toMyLocation(now) }
    }

    private fun register(profile: LocationProfile) {
        if (active == profile) return
        unregister()
        if (!permitted()) return
        val registration = object : Registration() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.lastOrNull()?.let { deliver(this, it) }
            }

            override fun onLocationChanged(location: Location) = deliver(this, location)

            @Deprecated("Required on API < 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = evaluate()
            override fun onProviderDisabled(provider: String) = evaluate()
        }
        try {
            if (playServices()) {
                registration.fused = true
                val request = LocationRequest.Builder(
                    if (profile.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    profile.intervalMs,
                )
                    .setMinUpdateIntervalMillis(profile.minIntervalMs)
                    // High accuracy otherwise holds fixes back until they are accurate, starving indoor recordings.
                    .setWaitForAccurateLocation(false)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, registration, Looper.getMainLooper())
            } else {
                val providers = (
                    if (profile.highAccuracy) {
                        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    } else {
                        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                    }
                    ).filter { it in locationManager.allProviders }
                    .let { if (profile.highAccuracy) it else it.take(1) }
                for (provider in providers) {
                    locationManager.requestLocationUpdates(provider, profile.intervalMs, 0f, registration, Looper.getMainLooper())
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Location request failed", error)
            return
        }
        this.registration = registration
        active = profile
    }

    private fun unregister() {
        val current = registration ?: return
        registration = null
        active = null
        if (current.fused) {
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(current as LocationCallback)
        } else {
            locationManager.removeUpdates(current as LocationListener)
        }
    }

    private fun deliver(from: Registration, location: Location) {
        if (from !== registration) return
        val stamp = FixStamp(location.provider, if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE, location.elapsedRealtimeNanos)
        if (!acceptsFix(lastFix, stamp)) return
        lastFix = stamp
        lastFixAtMs = location.elapsedRealtimeNanos / 1_000_000
        lastLocation = location
        if (active == LocationProfile.RECORDING) {
            val fix = if (location.hasAccuracy()) {
                LocationFix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time, location.elapsedRealtimeNanos)
            } else {
                null
            }
            scope.launch {
                val ingest = core.await()
                if (fix != null) ingest.ingestLocation(fix) else ingest.clearLocation()
            }
        }
        evaluate()
    }

    private fun Location.toMyLocation(nowMs: Long) = MyLocation(
        at = GeoPoint(lon = longitude, lat = latitude),
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        ageMs = (nowMs - elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0),
    )

    private fun playServices() = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "LocationClient"
        const val STATUS_TICK_MS = 5_000L
        const val TURN_ON_REQUEST = 4_201

        /** True while any activity of the process is started (the "UI is started" of §4.2). */
        fun processStarted(scope: CoroutineScope): StateFlow<Boolean> {
            val started = MutableStateFlow(false)
            scope.launch(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) }
                    .collect { started.value = it }
            }
            return started.asStateFlow()
        }
    }
}
