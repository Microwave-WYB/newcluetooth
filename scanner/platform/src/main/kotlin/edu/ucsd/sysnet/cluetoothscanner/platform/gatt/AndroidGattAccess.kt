package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget as CoreGattTarget

/** §4.3 precondition: `BLUETOOTH_CONNECT` (API 31+) and Bluetooth on. */
fun gattAvailable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }
}

/**
 * The link to a target. A random address must be connected with its address type: API 34+ names it
 * (`getRemoteLeDevice`); below that the device object the scanner kept ([scanned]) carries it, and
 * `getRemoteDevice` is the last resort (public addresses).
 */
fun androidGattLinks(context: Context, scanned: (String) -> BluetoothDevice? = { null }): (CoreGattTarget) -> GattLinkFactory? = { target ->
    val adapter = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: SecurityException) {
        null
    }
    val device = when {
        adapter == null -> null
        Build.VERSION.SDK_INT >= 34 && target.addrType != null -> runCatching {
            adapter.getRemoteLeDevice(
                target.addr,
                if (target.addrType == "random") BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC,
            )
        }.getOrNull()
        else -> scanned(target.addr) ?: runCatching { adapter.getRemoteDevice(target.addr) }.getOrNull()
    }
    device?.let { AndroidGattLinkFactory(context.applicationContext, it) }
}

/** The engine's device metadata for gatt/v1. */
fun gattDeviceInfo(appVersion: String) = GattDeviceInfo(
    appVersion = appVersion,
    deviceModel = Build.MODEL?.ifBlank { null } ?: "unknown",
    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
)
