package edu.ucsd.sysnet.cluetoothscanner.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRestartPolicyTest {
    @Test
    fun `the periodic restart runs with the screen on`() {
        assertTrue(ScanRestartPolicy.periodicRestartNow(screenOn = true, runningMs = 60_000))
    }

    @Test
    fun `the periodic restart is held back while the screen is off`() {
        assertFalse(ScanRestartPolicy.periodicRestartNow(screenOn = false, runningMs = 60_000))
        assertFalse(ScanRestartPolicy.periodicRestartNow(screenOn = false, runningMs = 19 * 60_000))
    }

    @Test
    fun `a scan never runs past twenty minutes without a restart even with the screen off`() {
        assertEquals(20 * 60_000L, ScanRestartPolicy.SCREEN_OFF_MAX_RUN_MS)
        assertTrue(ScanRestartPolicy.periodicRestartNow(screenOn = false, runningMs = ScanRestartPolicy.SCREEN_OFF_MAX_RUN_MS))
    }

    @Test
    fun `turning the screen on restarts at once when a restart was skipped`() {
        assertTrue(ScanRestartPolicy.screenOnRestartNow(skippedWhileOff = true, appliedCodedPhy = true, wantedCodedPhy = true))
    }

    @Test
    fun `turning the screen on applies a changed coded phy setting immediately`() {
        assertTrue(ScanRestartPolicy.screenOnRestartNow(skippedWhileOff = false, appliedCodedPhy = true, wantedCodedPhy = false))
        assertTrue(ScanRestartPolicy.screenOnRestartNow(skippedWhileOff = false, appliedCodedPhy = false, wantedCodedPhy = true))
    }

    @Test
    fun `turning the screen on does nothing when nothing is pending`() {
        assertFalse(ScanRestartPolicy.screenOnRestartNow(skippedWhileOff = false, appliedCodedPhy = true, wantedCodedPhy = true))
    }
}
