package edu.ucsd.sysnet.cluetoothscanner

import org.junit.Assert.assertTrue
import org.junit.Test

class V2ReleaseInvariantTest {
    @Test
    fun versionZeroZeroFiveCannotBuildWithoutV2Uploader() {
        if (BuildConfig.VERSION_NAME.startsWith("0.0.5")) {
            assertTrue(BuildConfig.V2_UPLOADER_ENABLED)
        }
    }
}
