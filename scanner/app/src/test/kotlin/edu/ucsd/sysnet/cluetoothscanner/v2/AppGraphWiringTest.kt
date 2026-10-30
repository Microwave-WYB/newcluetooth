package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.platform.export.AndroidExportShare
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.PlatformGattRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The process graph hands out lane B's real GATT runner and export share, never a placeholder (the Note10 report:
 * GATT did nothing while AppGraph still returned the fake). Checked on the declared types, so no Android is needed.
 */
class AppGraphWiringTest {
    @Test fun gattIsThePlatformRunnerOverTheRealCore() {
        assertEquals(PlatformGattRunner::class.java, AppGraph::class.java.getMethod("getGattRunner").returnType)
    }

    @Test fun exportIsTheAndroidShare() {
        assertEquals(AndroidExportShare::class.java, AppGraph::class.java.getMethod("getExportShare").returnType)
    }

    @Test fun thePlaceholdersNoLongerOfferThem() {
        val names = PlatformPlaceholders::class.java.methods.map { it.name }
        assertFalse(names.toString(), "getGattRunner" in names)
        assertFalse(names.toString(), "getExportShare" in names)
    }
}
