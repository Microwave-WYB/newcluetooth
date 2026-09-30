package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.scanNoticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanStatusPresentationTest {
    @Test
    fun exceptionalStatesUseExactPlainLanguageCopy() {
        assertEquals(
            "Turn on Bluetooth to continue scanning.",
            scanNoticeText(ScanOperatingState.BLUETOOTH_OFF),
        )
        assertEquals(
            "Bluetooth scanning is temporarily unavailable. Retrying…",
            scanNoticeText(ScanOperatingState.SCANNER_UNAVAILABLE),
        )
        assertEquals(
            "Saving scans. Scanning will resume automatically.",
            scanNoticeText(ScanOperatingState.PAUSED_BACKPRESSURE),
        )
        assertEquals(
            "Scanning could not start. Check Bluetooth permissions.",
            scanNoticeText(ScanOperatingState.FAILED),
        )
    }

    @Test
    fun normalStatesProduceNoNotice() {
        assertNull(scanNoticeText(ScanOperatingState.SCANNING))
        assertNull(scanNoticeText(ScanOperatingState.STOPPED))
    }
}
