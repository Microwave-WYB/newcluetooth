// docs/v2-api.md §4.4, §4.5 and §5.2: the platform's models.
package edu.ucsd.sysnet.cluetoothscanner.api.platform

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint

/**
 * SPEC P-5. [warn] is true when the warning button shows: always for [State.Denied] and [State.Off], after
 * [LocationWarn.NO_FIX_MS] without a first fix for [State.Waiting], and past [LocationWarn.STALE_MS] for
 * [State.Stale] ([ageMin] set).
 */
data class LocationStatus(val state: State, val warn: Boolean, val ageMin: Int? = null, val action: Action? = null) {
    enum class State { Ok, Denied, Off, Waiting, Stale }
    enum class Action { Allow, TurnOn }

    companion object {
        val Ok = LocationStatus(State.Ok, warn = false)
    }
}

/** The P-5 thresholds (`LOCATION_WARN` in `data.js`). */
object LocationWarn {
    /** "Waiting for location…" warns once there has been no first fix for more than this. */
    const val NO_FIX_MS = 30_000L

    /** "Location is N min old" warns once the last fix is older than this. */
    const val STALE_MS = 2 * 60_000L
}

/** SPEC P-4, M-9: the user's location; [ageMs] is the fix's age when it was published. */
data class MyLocation(val at: GeoPoint, val accuracyM: Double, val ageMs: Long)

/** SPEC O-2. [nearby] is `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+, else [Perm.NotRequired]). */
data class PermissionState(val nearby: Perm, val location: Perm, val notifications: Perm, val bluetoothOn: Boolean)

enum class Perm { Granted, Denied, PermanentlyDenied, NotRequired }

/** §4.4. Elapsed = [activeBeforeMs] + (now − [runningSinceMs]) while recording; the UI ticks it locally (R-3). */
data class RecorderState(
    val phase: Phase,
    val sessionId: String?,
    val activeBeforeMs: Long,
    val runningSinceMs: Long?,
    val deviceCount: Int,
    /** Recording with Bluetooth off: the session stays open and the scanner waits for Bluetooth (§4.1). */
    val scannerDeferred: Boolean,
    /** Scan observations dropped by the inbound queue (§4.1). */
    val dropped: Long,
    /** A problem the user should see, such as a missing grant or a refused foreground service. */
    val error: String?,
) {
    enum class Phase { Idle, Starting, Recording, Paused, Stopping }

    companion object {
        val Idle = RecorderState(Phase.Idle, null, 0, null, 0, scannerDeferred = false, dropped = 0, error = null)
    }
}

/**
 * §4.5, SPEC O-1 and O-2. Stored in DataStore `cluetooth_settings`; [userId], [wifiOnlyUpload] and [scanCodedPhy]
 * are migrated once from the v3 `cluetooth_prefs`. [onboardingDone] is not migrated, so an upgrader from v3 sees the
 * v2 onboarding once; Done in O-2 sets it.
 */
data class Settings(
    val userId: String?,
    val recordOnOpen: Boolean,
    val wifiOnlyUpload: Boolean,
    val scanCodedPhy: Boolean,
    val hideUnnamedApple: Boolean,
    val appVersion: String,
    val onboardingDone: Boolean = false,
) {
    companion object {
        /** O-2 step 3: "Lowercase letters and numbers only, max 9 characters." */
        val USER_ID = Regex("^[a-z0-9]{1,9}$")

        fun isValidUserId(id: String) = USER_ID.matches(id)

        fun defaults(appVersion: String) = Settings(
            userId = null,
            recordOnOpen = true,
            wifiOnlyUpload = false,
            scanCodedPhy = true,
            hideUnnamedApple = true,
            appVersion = appVersion,
        )
    }
}
