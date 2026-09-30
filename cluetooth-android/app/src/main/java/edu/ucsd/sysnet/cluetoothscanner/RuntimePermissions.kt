package edu.ucsd.sysnet.cluetoothscanner

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

@SuppressLint("InlinedApi")
internal fun requiredBluetoothPermissions(sdkInt: Int): List<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        )
    }

internal fun requiredRuntimePermissions(sdkInt: Int): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    addAll(requiredBluetoothPermissions(sdkInt))
}
