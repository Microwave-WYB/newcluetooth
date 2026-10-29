package edu.ucsd.sysnet.cluetoothscanner

import android.Manifest
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {
    @Test
    fun androidSRequiresScanConnectAndAdvertisePermissions() {
        val permissions = requiredBluetoothPermissions(Build.VERSION_CODES.S)

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }

    @Test
    fun preAndroidSUsesLegacyBluetoothPermissionsOnly() {
        val permissions = requiredBluetoothPermissions(Build.VERSION_CODES.R)

        assertTrue(Manifest.permission.BLUETOOTH in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADMIN in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in permissions)
    }
}
