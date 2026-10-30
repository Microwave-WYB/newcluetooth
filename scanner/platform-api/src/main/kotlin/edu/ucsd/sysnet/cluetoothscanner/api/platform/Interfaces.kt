// docs/v2-api.md §4: the platform's interfaces. :platform implements them on Android, :platform-fake for the
// gallery and tests.
package edu.ucsd.sysnet.cluetoothscanner.api.platform

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * §4.4: the in-process recording singleton (no binding, no AIDL). Scanning runs only while recording and not
 * paused (V-1). `RecordingService` observes [state].
 */
interface Recorder {
    val state: StateFlow<RecorderState>

    /**
     * Checks the grants (§1.2.3), starts the foreground service and waits for its confirmation, then calls
     * `core.startRecording`; Ok carries the session id. A missing grant ([PlatformError.MissingGrants]) or a refused
     * service ([PlatformError.ServiceRefused]) fails with no session created.
     */
    suspend fun start(): PlatformResult<String>

    /** The service stays in the foreground while paused. Not recording is Ok (nothing to do). */
    suspend fun pause(): PlatformResult<Unit>

    /** Not paused is Ok; the core may refuse ([PlatformError.Core], e.g. storage still full, R-10). */
    suspend fun resume(): PlatformResult<Unit>

    /** Ok carries the finished session id (R-7, V-3). */
    suspend fun stop(): PlatformResult<String>
}

/** §4.3, SPEC D-13. */
interface GattRunner {
    /**
     * Cold flow: collecting starts one discovery of [key]; cancelling the collector cancels it (D-13.8). Emits
     * progress views; the last is final. The runner saves the gatt/v1 result itself through `CoreIngest.ingestGatt`
     * (cancelled runs too: outcome "cancelled", never shown). One discovery runs at a time process-wide; a new
     * collection cancels the previous one.
     */
    fun discover(key: DeviceKey): Flow<GattView>

    companion object {
        /**
         * `GattFailure.Other` status of a failed Connecting step when `BLUETOOTH_CONNECT` is missing or Bluetooth is
         * off; the UI shows the onboarding piece. Nothing connects and no gatt/v1 object is written.
         */
        const val GATT_UNAVAILABLE = -4

        /** `GattFailure.Other` status of a failed Connecting step when `core.gattTarget(key)` is null. */
        const val GATT_NO_TARGET = -5
    }
}

/**
 * §4.5. DataStore `cluetooth_settings` with a one-time migration from `cluetooth_prefs`. Defaults:
 * [Settings.defaults].
 */
interface SettingsStore {
    val settings: StateFlow<Settings>

    /**
     * Applies [transform] atomically. An invalid user id is [PlatformError.InvalidUserId] and a failed write
     * [PlatformError.Io]; neither changes the stored settings.
     */
    suspend fun update(transform: (Settings) -> Settings): PlatformResult<Unit>
}

/** SPEC O-1 "Pending uploads". */
interface Uploads {
    val status: StateFlow<UploadStatus>

    /** Restarts the upload work at once, expedited where possible. */
    fun uploadNow(): PlatformResult<Unit>
}

/** SPEC O-1 item 10. */
interface ExportShare {
    /** The system share sheet; the export is acknowledged at the next export or the next app start. */
    fun share(export: PreparedExport): PlatformResult<Unit>

    /** Copies the ZIP into Downloads, then acknowledges the export; a failed copy is [PlatformError.Io]. */
    suspend fun saveToDownloads(export: PreparedExport): PlatformResult<Unit>
}

/** SPEC O-2 and the "only the missing piece" rule. */
interface Permissions {
    val state: StateFlow<PermissionState>

    /**
     * Input: the Android permission names to request, usually one group ([nearby], [location], [notifications]) per
     * O-2 "Allow"; an empty array requests every missing one. The result refreshes [state].
     */
    fun requestLauncherContract(): ActivityResultContract<Array<String>, Map<String, Boolean>>
    fun openAppSettings(): PlatformResult<Unit>
    fun requestEnableBluetooth(activity: ComponentActivity): PlatformResult<Unit>

    /** The permission names behind each [PermissionState] field; empty where the API level needs none. */
    companion object {
        /** Empty on API 24–30: there Bluetooth scanning needs only the [location] grant. */
        fun nearby(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }

        fun location(): Array<String> =
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun notifications(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }
}

/** §4.2, SPEC P-4, P-5, M-9. */
interface LocationStatusSource {
    val status: StateFlow<LocationStatus>
    val myLocation: StateFlow<MyLocation?>

    /**
     * P-5 "Turn on" ([LocationStatus.Action.TurnOn]): `:platform` shows the Play services location settings dialog,
     * falling back to the system location settings, which is also this default. "Allow" goes through [Permissions].
     */
    fun turnOn(activity: ComponentActivity): PlatformResult<Unit> = try {
        activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        PlatformResult.Ok(Unit)
    } catch (failure: RuntimeException) {
        // No settings activity on this device, or Android refused the launch.
        PlatformResult.Err(PlatformError.Unavailable(failure.message ?: failure::class.java.simpleName))
    }
}
