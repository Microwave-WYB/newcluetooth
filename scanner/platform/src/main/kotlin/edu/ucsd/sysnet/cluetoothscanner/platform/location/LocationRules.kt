package edu.ucsd.sysnet.cluetoothscanner.platform.location

import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.Action
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationWarn

/** §4.2: how hard location is requested. */
enum class LocationProfile(val intervalMs: Long, val minIntervalMs: Long, val highAccuracy: Boolean) {
    /** Recording and not paused: a fix every ~2 s, high accuracy, fed to the core. */
    RECORDING(intervalMs = 2_000, minIntervalMs = 1_000, highAccuracy = true),

    /** The UI is started otherwise: balanced, every 10 s, for `myLocation` and the status only (M-9, P-5). */
    FOREGROUND(intervalMs = 10_000, minIntervalMs = 5_000, highAccuracy = false),
    ;

    companion object {
        /** RECORDING while recording, FOREGROUND while the UI is started, otherwise no request. */
        fun wanted(recording: Boolean, uiStarted: Boolean): LocationProfile? = when {
            recording -> RECORDING
            uiStarted -> FOREGROUND
            else -> null
        }
    }
}

/**
 * SPEC P-5 (`data.js` `LOCATION_WARN`). Ages are in the monotonic clock. [requestedSinceMs] is when the current
 * request began (null: no request), [lastFixAtMs] when the newest fix was taken.
 */
fun locationStatus(
    permitted: Boolean,
    providerOn: Boolean,
    requestedSinceMs: Long?,
    lastFixAtMs: Long?,
    nowMs: Long,
): LocationStatus = when {
    !permitted -> LocationStatus(State.Denied, warn = true, action = Action.Allow)
    !providerOn -> LocationStatus(State.Off, warn = true, action = Action.TurnOn)
    lastFixAtMs == null -> LocationStatus(
        State.Waiting,
        warn = requestedSinceMs != null && nowMs - requestedSinceMs > LocationWarn.NO_FIX_MS,
    )
    nowMs - lastFixAtMs > LocationWarn.STALE_MS ->
        LocationStatus(State.Stale, warn = true, ageMin = ((nowMs - lastFixAtMs) / 60_000).toInt())
    else -> LocationStatus.Ok
}

/** The facts of a fix that decide whether it replaces the current one. */
internal data class FixStamp(val provider: String?, val accuracyMeters: Float, val elapsedRealtimeNanos: Long)

/**
 * The newest fix wins (v3). The one exception is the platform fallback, where GPS and network fixes interleave: a
 * less accurate fix from another provider does not replace a fix less than [PROVIDER_SWITCH_GRACE_NANOS] old.
 */
internal fun acceptsFix(current: FixStamp?, candidate: FixStamp): Boolean {
    if (current == null) return true
    if (candidate.elapsedRealtimeNanos <= current.elapsedRealtimeNanos) return false
    val age = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos
    return candidate.provider == current.provider ||
        candidate.accuracyMeters <= current.accuracyMeters ||
        age >= PROVIDER_SWITCH_GRACE_NANOS
}

private const val PROVIDER_SWITCH_GRACE_NANOS = 5_000_000_000L
