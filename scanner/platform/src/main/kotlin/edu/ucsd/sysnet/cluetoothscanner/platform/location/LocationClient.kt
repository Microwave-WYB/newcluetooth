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
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.platform.failureHandler
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The decisions are in [LocationMachine]; this class is its runner. One actor on the main thread owns the state and
 * the Android objects (the registrations, the status tick and the providers receiver); callbacks become events, and
 * any exception from a registration becomes a failure event.
 */
@SuppressLint("MissingPermission") // A registration is only requested when the reducer saw the permission granted.
class LocationClient(
    private val context: Context,
    private val core: Deferred<CoreIngest>,
    private val scope: CoroutineScope,
    /** True while the UI is started (`processStarted()` in production). */
    uiStarted: StateFlow<Boolean>,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : RecordingLocation, LocationStatusSource {
    private val locationManager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val _status = MutableStateFlow(LocationStatus.Ok)
    private val _myLocation = MutableStateFlow<MyLocation?>(null)
    override val status: StateFlow<LocationStatus> = _status.asStateFlow()
    override val myLocation: StateFlow<MyLocation?> = _myLocation.asStateFlow()

    private val inbox = Channel<LocEvent>(Channel.UNLIMITED)

    /** Every coroutine this runner launches carries it: an escaped exception leaves the status as it was. */
    private val guard = failureHandler(TAG)

    // ---- Owned by the actor (main thread) ----
    private val registrations = mutableMapOf<RegistrationId, Listener>()
    private var ticker: Job? = null

    /** One registration with either backend: Play services calls the callback, the platform the listener. */
    private inner class Listener(val id: RegistrationId, val fused: Boolean) : LocationCallback(), LocationListener {
        override fun onLocationResult(result: LocationResult) {
            result.locations.lastOrNull()?.let { send(LocEvent.Fixed(id, it.reading())) }
        }

        override fun onLocationChanged(location: Location) = send(LocEvent.Fixed(id, location.reading()))

        @Deprecated("Required on API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = send(LocEvent.Tick)
        override fun onProviderDisabled(provider: String) = send(LocEvent.Tick)
    }

    private val providersReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) = send(LocEvent.Tick)
    }

    init {
        scope.launch(Dispatchers.Main.immediate + guard) {
            var state = LocState()
            for (event in inbox) {
                val transition = LocationMachine.reduce(state, event, env())
                state = transition.state
                _status.value = state.status
                _myLocation.value = state.shown
                transition.effects.forEach(::runEffect)
            }
        }
        scope.launch(Dispatchers.Main.immediate + guard) { uiStarted.collect { send(LocEvent.UiStarted(it)) } }
    }

    override fun startRecording() = send(LocEvent.Recording(true))

    override fun stopRecording() = send(LocEvent.Recording(false))

    /** P-5 "Turn on": the Play services settings dialog, else the system location settings. */
    override fun turnOn(activity: ComponentActivity): PlatformResult<Unit> {
        if (!playServices()) return openLocationSettings(activity)
        return try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LocationProfile.RECORDING.intervalMs).build()
            val settings = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
            LocationServices.getSettingsClient(activity).checkLocationSettings(settings)
                .addOnFailureListener { error ->
                    val resolved = (error as? ResolvableApiException)?.let {
                        runCatching { it.startResolutionForResult(activity, TURN_ON_REQUEST) }.isSuccess
                    } ?: false
                    // Asynchronous: a fallback that fails here can only be logged.
                    if (!resolved) openLocationSettings(activity)
                }
            PlatformResult.Ok(Unit)
        } catch (failure: Exception) {
            log("Location settings check failed", failure)
            openLocationSettings(activity)
        }
    }

    private fun openLocationSettings(activity: ComponentActivity): PlatformResult<Unit> = try {
        activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        PlatformResult.Ok(Unit)
    } catch (failure: RuntimeException) {
        log("No location settings screen", failure)
        PlatformResult.Err(PlatformError.Unavailable(failure.message ?: failure::class.java.simpleName))
    }

    private fun send(event: LocEvent) {
        inbox.trySend(event)
    }

    private fun env() = LocEnv(LocElapsedMs(elapsedMs()), permitted(), providerOn())

    private fun permitted() = try {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun providerOn() = try {
        val manager = locationManager
        manager != null && (manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    } catch (_: Exception) {
        false
    }

    /** The one wrapper every effect goes through: any exception becomes a typed [LocEvent.EffectFailed]. */
    private fun runEffect(effect: LocEffect) {
        try {
            execute(effect)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("effect $effect failed", failure)
            send(LocEvent.EffectFailed(effect))
        }
    }

    private fun execute(effect: LocEffect) {
        when (effect) {
            is LocEffect.Register -> register(effect.id, effect.profile)
            is LocEffect.Unregister -> unregister(effect.id)
            LocEffect.StartTicking -> {
                runCatching {
                    ContextCompat.registerReceiver(
                        context, providersReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.onFailure { log("Providers receiver not registered", it) }
                ticker?.cancel()
                ticker = scope.launch(Dispatchers.Main.immediate + guard) {
                    while (isActive) {
                        delay(STATUS_TICK_MS)
                        send(LocEvent.Tick)
                    }
                }
            }
            LocEffect.StopTicking -> {
                ticker?.cancel()
                ticker = null
                runCatching { context.unregisterReceiver(providersReceiver) }
            }
            is LocEffect.Ingest -> toCore { it.ingestLocation(effect.fix) }
            LocEffect.ClearCore -> toCore { it.clearLocation() }
        }
    }

    private fun toCore(call: (CoreIngest) -> Unit) {
        scope.launch(guard) {
            try {
                call(core.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Location not passed to the core", failure)
            }
        }
    }

    private fun register(id: RegistrationId, profile: LocationProfile) {
        val fused = playServices()
        val listener = Listener(id, fused)
        try {
            val manager = locationManager
            if (fused) {
                val request = LocationRequest.Builder(
                    if (profile.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    profile.intervalMs,
                )
                    .setMinUpdateIntervalMillis(profile.minIntervalMs)
                    // High accuracy otherwise holds fixes back until they are accurate, starving indoor recordings.
                    .setWaitForAccurateLocation(false)
                    .build()
                val callback: LocationCallback = listener
                LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnFailureListener { send(LocEvent.RegistrationFailed(id)) }
            } else if (manager != null) {
                val providers = (
                    if (profile.highAccuracy) {
                        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    } else {
                        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                    }
                    ).filter { it in manager.allProviders }
                    .let { if (profile.highAccuracy) it else it.take(1) }
                val platform: LocationListener = listener
                for (provider in providers) {
                    manager.requestLocationUpdates(provider, profile.intervalMs, 0f, platform, Looper.getMainLooper())
                }
            } else {
                send(LocEvent.RegistrationFailed(id))
                return
            }
            registrations[id] = listener
        } catch (failure: Exception) {
            log("Location request failed", failure)
            send(LocEvent.RegistrationFailed(id))
        }
    }

    private fun unregister(id: RegistrationId) {
        val listener = registrations.remove(id) ?: return
        try {
            if (listener.fused) {
                val callback: LocationCallback = listener
                LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback)
            } else {
                val platform: LocationListener = listener
                locationManager?.removeUpdates(platform)
            }
        } catch (failure: Exception) {
            log("Location updates not removed", failure)
        }
    }

    private fun Location.reading() = FixReading(
        provider = provider,
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        timeMs = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )

    private fun log(message: String, failure: Throwable) {
        runCatching { Log.w(TAG, message, failure) }
    }

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
            scope.launch(Dispatchers.Main.immediate + failureHandler(TAG)) {
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) }
                    .collect { started.value = it }
            }
            return started.asStateFlow()
        }
    }
}
