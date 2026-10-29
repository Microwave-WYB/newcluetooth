package edu.ucsd.sysnet.cluetoothscanner.v2

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.ExportShare
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.api.platform.SettingsStore
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeExportShare
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakePermissions
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeSettings
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeUploads

/**
 * PLACEHOLDER slots for the platform pieces that are not merged yet (integration step 1). This is the only file of
 * :app that names :platform-fake; each slot is replaced by lane B's real class when it lands:
 * - [settings]: `DataStoreSettings` (#53) — in memory here, so nothing persists across restarts;
 * - [permissions]: `AndroidPermissions` (#53) — this one only reads the current grants; its requests do nothing
 *   (grant on the device, e.g. `adb shell pm grant`);
 * - [uploads], [exportShare], [gattRunner]: lane B's upload worker, export hand-off and GATT runner adapter;
 * - [checkInterruptedRecording]: `InterruptedRecordingNotice.check()` (#53), called once the core is open.
 */
internal class PlatformPlaceholders(context: Context, appVersion: String) {
    val settings: SettingsStore = FakeSettings(Settings.defaults(appVersion))
    val permissions: Permissions = FakePermissions(currentGrants(context))
    val uploads: Uploads = FakeUploads(UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null))
    val exportShare: ExportShare = FakeExportShare()
    val gattRunner: GattRunner = FakeGattRunner({ null })

    /** #53's `InterruptedRecordingNotice(context).check(queries)` goes here. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun checkInterruptedRecording(queries: CoreQueries) = Unit

    private companion object {
        fun currentGrants(context: Context): PermissionState {
            fun perm(vararg names: String) =
                if (names.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) Perm.Granted else Perm.Denied
            val nearby = if (Build.VERSION.SDK_INT >= 31) perm(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else Perm.NotRequired
            val notifications = if (Build.VERSION.SDK_INT >= 33) perm(Manifest.permission.POST_NOTIFICATIONS) else Perm.NotRequired
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            return PermissionState(nearby, perm(Manifest.permission.ACCESS_FINE_LOCATION), notifications, bluetoothOn = runCatching { bt?.isEnabled == true }.getOrDefault(false))
        }
    }
}
