package edu.ucsd.sysnet.cluetoothscanner.platform.location

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation

/**
 * The location client (§4.2, SPEC P-5, M-9) as one immutable value and a pure reducer; [LocationClient] is its runner.
 *
 * - RECORDING while recording, FOREGROUND while the UI is started otherwise ([LocationProfile.wanted]).
 * - Only RECORDING fixes reach the core; a fix without accuracy, and the end of a recording, clear it.
 * - The P-5 status is re-evaluated on every event while a request is wanted (the runner ticks every 5 s); an
 *   evaluation also registers a request that was waiting for the permission or whose registration failed.
 * - A registration carries a [RegistrationId]; fixes of any other registration are ignored by the state.
 */
internal object LocationMachine {
    fun reduce(state: LocState, event: LocEvent, env: LocEnv): LocTransition = Builder(state, env).apply { on(event) }.build()

    private class Builder(var state: LocState, val env: LocEnv) {
        val effects = mutableListOf<LocEffect>()

        fun build() = LocTransition(state, effects.toList())

        fun on(event: LocEvent) {
            when (event) {
                is LocEvent.Recording -> {
                    if (!event.recording && state.recording) effects += LocEffect.ClearCore
                    state = state.copy(recording = event.recording)
                    want()
                }
                is LocEvent.UiStarted -> {
                    state = state.copy(uiStarted = event.started)
                    want()
                }
                LocEvent.Tick -> evaluate()
                is LocEvent.RegistrationFailed -> registrationFailed(event.id)
                is LocEvent.EffectFailed -> when (val effect = event.effect) {
                    is LocEffect.Register -> registrationFailed(effect.id)
                    // Unregistering, ticking or the core failing changes no decision; the next event re-evaluates.
                    is LocEffect.Unregister, LocEffect.StartTicking, LocEffect.StopTicking, is LocEffect.Ingest, LocEffect.ClearCore -> Unit
                }
                is LocEvent.Fixed -> fixed(event.id, event.fix)
            }
        }

        /** A fused request can fail after the runner kept its listener: drop it, the next tick registers anew. */
        private fun registrationFailed(id: RegistrationId) {
            val request = state.request as? LocRequest.Active ?: return
            if (request.registration?.id != id) return
            effects += LocEffect.Unregister(id)
            state = state.copy(request = request.copy(registration = null))
        }

        private fun want() {
            val profile = LocationProfile.wanted(state.recording, state.uiStarted)
            val request = state.request
            when (request) {
                LocRequest.None -> if (profile == null) return
                is LocRequest.Active -> if (profile == request.profile) return
            }
            if (profile == null) {
                (request as? LocRequest.Active)?.registration?.let { effects += LocEffect.Unregister(it.id) }
                effects += LocEffect.StopTicking
                state = state.copy(request = LocRequest.None, shown = null)
                return
            }
            val since = when (request) {
                LocRequest.None -> {
                    effects += LocEffect.StartTicking
                    env.now
                }
                is LocRequest.Active -> {
                    request.registration?.let { effects += LocEffect.Unregister(it.id) }
                    request.since
                }
            }
            state = state.copy(request = LocRequest.Active(profile, since, registration = null))
            evaluate()
        }

        /** P-5 status and the shown location; registers the wanted request when it may. */
        private fun evaluate() {
            val request = state.request as? LocRequest.Active ?: return
            if (request.registration == null && env.permitted) {
                val id = state.nextRegistration
                state = state.copy(
                    request = request.copy(registration = Registration(id, request.profile)),
                    nextRegistration = id.next(),
                )
                effects += LocEffect.Register(id, request.profile)
            }
            val since = (state.request as? LocRequest.Active)?.since
            state = state.copy(
                status = locationStatus(env.permitted, env.providerOn, since?.value, state.lastFix?.atMs, env.now.value),
                shown = state.lastFix?.let { it.reading.toMyLocation(env.now) },
            )
        }

        private fun fixed(id: RegistrationId, reading: FixReading) {
            val request = state.request as? LocRequest.Active ?: return
            val registration = request.registration ?: return
            if (registration.id != id) return
            val stamp = reading.stamp()
            if (!acceptsFix(state.lastFix?.stamp, stamp)) return
            state = state.copy(lastFix = LastFix(stamp, reading))
            if (registration.profile == LocationProfile.RECORDING) {
                effects += reading.toCoreFix()?.let(LocEffect::Ingest) ?: LocEffect.ClearCore
            }
            evaluate()
        }
    }
}

/** Identifies one location registration; fixes of any other are stale. */
@JvmInline
internal value class RegistrationId(val value: Long) {
    fun next() = RegistrationId(value + 1)
}

/** A point on the monotonic clock in milliseconds. */
@JvmInline
internal value class LocElapsedMs(val value: Long)

internal data class Registration(val id: RegistrationId, val profile: LocationProfile)

internal sealed interface LocRequest {
    data object None : LocRequest

    /** A wanted request since [since]; [registration] is null while it waits for the permission or after a failure. */
    data class Active(val profile: LocationProfile, val since: LocElapsedMs, val registration: Registration?) : LocRequest
}

/** One fix as Android reported it; [accuracyM] is null when it has none. */
internal data class FixReading(
    val provider: String?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
) {
    fun stamp() = FixStamp(provider, accuracyM ?: Float.MAX_VALUE, elapsedRealtimeNanos)

    fun toCoreFix(): LocationFix? = accuracyM?.let { LocationFix(lat, lon, it.toDouble(), timeMs, elapsedRealtimeNanos) }

    fun toMyLocation(now: LocElapsedMs) = MyLocation(
        at = GeoPoint(lon = lon, lat = lat),
        accuracyM = accuracyM?.toDouble() ?: 0.0,
        ageMs = (now.value - elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0),
    )
}

internal data class LastFix(val stamp: FixStamp, val reading: FixReading) {
    val atMs: Long get() = reading.elapsedRealtimeNanos / 1_000_000
}

internal data class LocState(
    val recording: Boolean = false,
    val uiStarted: Boolean = false,
    val request: LocRequest = LocRequest.None,
    val lastFix: LastFix? = null,
    val status: LocationStatus = LocationStatus.Ok,
    /** `myLocation`: the last fix while a request is wanted. */
    val shown: MyLocation? = null,
    val nextRegistration: RegistrationId = RegistrationId(1),
)

/** The facts the runner samples when it reduces an event. */
internal data class LocEnv(val now: LocElapsedMs, val permitted: Boolean, val providerOn: Boolean)

internal sealed interface LocEvent {
    data class Recording(val recording: Boolean) : LocEvent
    data class UiStarted(val started: Boolean) : LocEvent

    /** The 5 s status tick, a provider change or a provider callback. */
    data object Tick : LocEvent
    data class RegistrationFailed(val id: RegistrationId) : LocEvent
    data class Fixed(val id: RegistrationId, val fix: FixReading) : LocEvent

    /** The runner could not execute [effect] (any exception, as a value). */
    data class EffectFailed(val effect: LocEffect) : LocEvent
}

internal sealed interface LocEffect {
    data class Register(val id: RegistrationId, val profile: LocationProfile) : LocEffect
    data class Unregister(val id: RegistrationId) : LocEffect

    /** The 5 s status tick and the providers-changed receiver. */
    data object StartTicking : LocEffect
    data object StopTicking : LocEffect
    data class Ingest(val fix: LocationFix) : LocEffect
    data object ClearCore : LocEffect
}

internal data class LocTransition(val state: LocState, val effects: List<LocEffect>)
