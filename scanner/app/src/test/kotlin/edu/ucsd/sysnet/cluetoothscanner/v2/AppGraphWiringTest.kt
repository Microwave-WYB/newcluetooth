package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.platform.export.AndroidExportShare
import edu.ucsd.sysnet.cluetoothscanner.platform.gatt.PlatformGattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.upload.PlatformUploads
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The process graph hands out lane B's real GATT runner, export share and uploads, never a placeholder (the Note10 report:
 * GATT did nothing while AppGraph still returned the fake). Checked on the declared types, so no Android is needed.
 */
class AppGraphWiringTest {
    @Test fun gattIsThePlatformRunnerOverTheRealCore() {
        assertEquals(PlatformGattRunner::class.java, AppGraph::class.java.getMethod("getGattRunner").returnType)
    }

    @Test fun exportIsTheAndroidShare() {
        assertEquals(AndroidExportShare::class.java, AppGraph::class.java.getMethod("getExportShare").returnType)
    }

    @Test fun uploadsAreThePlatformWorker() {
        assertEquals(PlatformUploads::class.java, AppGraph::class.java.getMethod("getUploads").returnType)
    }
}
