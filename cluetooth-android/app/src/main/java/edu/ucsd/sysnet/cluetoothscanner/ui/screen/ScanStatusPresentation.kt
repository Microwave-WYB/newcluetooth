package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState

fun scanNoticeText(state: ScanOperatingState): String? = when (state) {
    ScanOperatingState.BLUETOOTH_OFF -> "Turn on Bluetooth to continue scanning."
    ScanOperatingState.SCANNER_UNAVAILABLE -> "Bluetooth scanning is temporarily unavailable. Retrying…"
    ScanOperatingState.PAUSED_BACKPRESSURE -> "Saving scans. Scanning will resume automatically."
    ScanOperatingState.FAILED -> "Scanning could not start. Check Bluetooth permissions."
    ScanOperatingState.SCANNING,
    ScanOperatingState.STOPPED,
    -> null
}
