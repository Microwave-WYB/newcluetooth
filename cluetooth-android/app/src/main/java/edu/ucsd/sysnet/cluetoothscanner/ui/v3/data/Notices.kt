package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import androidx.annotation.StringRes
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataStatus
import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState

/** The one-line notices under a page header, in one place so the copy stays exact. */
object Notices {
    @StringRes val BLUETOOTH_OFF = R.string.notice_bluetooth_off
    @StringRes val SCANNER_UNAVAILABLE = R.string.notice_scanner_unavailable
    @StringRes val PAUSED_BACKPRESSURE = R.string.notice_paused_backpressure
    @StringRes val SCAN_FAILED = R.string.notice_scan_failed
    @StringRes val RECORDING_WAITS_FOR_BLUETOOTH = R.string.notice_recording_waits
    @StringRes val DATA_UNAVAILABLE = R.string.notice_data_unavailable
    @StringRes val IDENTIFICATION_UNAVAILABLE = R.string.notice_identification_unavailable
    @StringRes val NO_FIX = R.string.notice_no_fix
    @StringRes val STALE_FIX = R.string.notice_stale_fix

    /** A fix older than this while recording is reported. */
    const val STALE_FIX_AFTER_MS = 60_000L

    fun scanner(state: ScanOperatingState): Int? = when (state) {
        ScanOperatingState.BLUETOOTH_OFF -> BLUETOOTH_OFF
        ScanOperatingState.SCANNER_UNAVAILABLE -> SCANNER_UNAVAILABLE
        ScanOperatingState.PAUSED_BACKPRESSURE -> PAUSED_BACKPRESSURE
        ScanOperatingState.FAILED -> SCAN_FAILED
        ScanOperatingState.SCANNING, ScanOperatingState.STOPPED -> null
    }

    fun data(state: LocalDataStatus.State): Int? = when (state) {
        LocalDataStatus.State.UNAVAILABLE -> DATA_UNAVAILABLE
        LocalDataStatus.State.IDENTIFICATION_UNAVAILABLE -> IDENTIFICATION_UNAVAILABLE
        LocalDataStatus.State.STARTING, LocalDataStatus.State.READY -> null
    }

    /**
     * Only while recording: no fix at all, or the last one older than [STALE_FIX_AFTER_MS].
     * [fixAgeMs] is null when there is no fix.
     */
    fun location(recording: Boolean, fixAgeMs: Long?): Int? = when {
        !recording -> null
        fixAgeMs == null -> NO_FIX
        fixAgeMs > STALE_FIX_AFTER_MS -> STALE_FIX
        else -> null
    }
}
