package edu.ucsd.sysnet.cluetoothscanner.service

/**
 * When the scanner is restarted. Android ends long scans, so the scanner restarts itself every
 * minute, but a scan started while the screen is off delivers nothing on some phones (observed on
 * a Galaxy Note10), while one already running keeps delivering.
 */
internal object ScanRestartPolicy {
    /** A scan may run this long with the screen off before restarting anyway. */
    const val SCREEN_OFF_MAX_RUN_MS = 20 * 60 * 1_000L

    /** The once-a-minute restart: skipped while the screen is off, but never for too long. */
    fun periodicRestartNow(screenOn: Boolean, runningMs: Long): Boolean =
        screenOn || runningMs >= SCREEN_OFF_MAX_RUN_MS

    /**
     * The screen just turned on: restart immediately when a restart was skipped while it was
     * off, or when the Coded PHY switch changed in the meantime (so the new setting applies now
     * instead of at the next minute).
     */
    fun screenOnRestartNow(skippedWhileOff: Boolean, appliedCodedPhy: Boolean, wantedCodedPhy: Boolean): Boolean =
        skippedWhileOff || appliedCodedPhy != wantedCodedPhy
}
