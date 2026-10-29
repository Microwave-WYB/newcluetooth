package edu.ucsd.sysnet.cluetoothscanner.api.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformApiTest {
    @Test
    fun `settings defaults follow section 4_5 and onboarding starts not done`() {
        val settings = Settings.defaults("0.1.0")
        assertEquals(null, settings.userId)
        assertTrue(settings.recordOnOpen)
        assertFalse(settings.wifiOnlyUpload)
        assertTrue(settings.scanCodedPhy)
        assertTrue("G-12: hide unnamed Apple defaults to on", settings.hideUnnamedApple)
        assertFalse("O-2 runs once for every new install and every v3 upgrader", settings.onboardingDone)
        assertEquals("0.1.0", settings.appVersion)
    }

    @Test
    fun `O-2 user id is lowercase letters and digits, at most 9`() {
        assertTrue(Settings.isValidUserId("abc123"))
        assertTrue(Settings.isValidUserId("a"))
        assertTrue(Settings.isValidUserId("123456789"))
        assertFalse(Settings.isValidUserId(""))
        assertFalse(Settings.isValidUserId("1234567890"))
        assertFalse(Settings.isValidUserId("Abc"))
        assertFalse(Settings.isValidUserId("ab_c"))
    }

    @Test
    fun `D-13 precondition codes are distinct named constants`() {
        assertEquals(-4, GattRunner.GATT_UNAVAILABLE)
        assertEquals(-5, GattRunner.GATT_NO_TARGET)
        assertNotEquals(GattRunner.GATT_UNAVAILABLE, GattRunner.GATT_NO_TARGET)
    }

    @Test
    fun `P-5 thresholds match data_js LOCATION_WARN`() {
        assertEquals(30_000L, LocationWarn.NO_FIX_MS)
        assertEquals(120_000L, LocationWarn.STALE_MS)
        assertFalse(LocationStatus.Ok.warn)
    }

    @Test
    fun `O-2 permission groups depend on the API level`() {
        assertArrayEquals(
            arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            Permissions.nearby(31),
        )
        assertEquals(0, Permissions.nearby(30).size)
        assertArrayEquals(
            arrayOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            Permissions.location(),
        )
        assertArrayEquals(arrayOf("android.permission.POST_NOTIFICATIONS"), Permissions.notifications(33))
        assertEquals(0, Permissions.notifications(32).size)
    }

    @Test
    fun `recorder starts idle`() {
        assertEquals(RecorderState.Phase.Idle, RecorderState.Idle.phase)
        assertEquals(null, RecorderState.Idle.sessionId)
        assertEquals(null, RecorderState.Idle.error)
    }
}
