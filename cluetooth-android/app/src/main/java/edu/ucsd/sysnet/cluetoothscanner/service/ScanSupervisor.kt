package edu.ucsd.sysnet.cluetoothscanner.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the supervisor needs from the BLE scanner. */
internal interface ScannerPort {
    /** Starts scanning (no-op when already scanning); may be deferred or rejected. */
    fun start(): ScannerStartOutcome
    suspend fun stopAndWait()
    fun isScanning(): Boolean
    fun setRowWriting(enabled: Boolean)

    /** Returns once every callback delivered so far has been processed. */
    suspend fun fence()
}

internal interface LocationPort {
    fun start()
    fun stop()
}

/**
 * Decides when the scanner and location updates run, and when rows are written.
 *
 * - **Live scanning** while the app is in the foreground: advertisements feed the in-memory UI
 *   flow and nothing else. No core session, no rows, no upload.
 * - **Recording**: rows are written (and uploaded) only between [beginRowWriting] and
 *   [endRowWriting]; the recording layer opens and closes the core session around those calls.
 *   A recording keeps the scanner and location running in the background (the foreground service
 *   keeps the process alive); without one, the scanner stops when the app leaves the foreground.
 */
class ScanSupervisor internal constructor(
    private val scanner: ScannerPort,
    private val location: LocationPort,
    /** Process-lifetime scope for [foregroundChanged]; it must outlive any ViewModel. */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    @Volatile
    private var foreground = false

    @Volatile
    private var rowWriting = false

    /** Location is wanted exactly while the app is in the foreground or a recording writes rows. */
    val wantsLocation: Boolean
        get() = foreground || rowWriting

    /**
     * The Activity moved to or from the foreground. Runs in the application scope, so it is not
     * cancelled with a ViewModel and a stop can never be lost half way.
     */
    fun foregroundChanged(on: Boolean) {
        scope.launch { setForeground(on) }
    }

    /** (Re)starts location updates if (and only if) something wants them; used by retry loops. */
    suspend fun startLocationIfWanted() = mutex.withLock {
        if (wantsLocation) location.start()
    }

    /** The app is in the foreground (true) or not (false). Starts or stops live scanning. */
    suspend fun setForeground(on: Boolean) = mutex.withLock {
        foreground = on
        reconcile()
    }

    /**
     * A recording starts (or resumes): make sure the scanner runs, then let rows through. The
     * outcome is the scanner's, so a recording can be deferred (Bluetooth off) or rejected.
     */
    suspend fun beginRowWriting(): ScannerStartOutcome = mutex.withLock {
        location.start()
        val outcome = if (scanner.isScanning()) ScannerStartOutcome.STARTED else scanner.start()
        if (outcome != ScannerStartOutcome.REJECTED) {
            rowWriting = true
            scanner.setRowWriting(true)
        } else {
            reconcile()
        }
        outcome
    }

    /**
     * A recording pauses or stops: rows stop first, the callbacks already in flight are drained,
     * and only then may the caller finish the session. The scanner itself keeps running when the
     * app is in the foreground (live scanning) and stops otherwise.
     */
    suspend fun endRowWriting() = mutex.withLock {
        rowWriting = false
        scanner.setRowWriting(false)
        scanner.fence()
        reconcile()
    }

    /** The scanner stopped by itself (Bluetooth off, backpressure, failure) and will be asked again. */
    suspend fun liveRecoveryAvailable() = mutex.withLock {
        if (foreground && !rowWriting) scanner.start()
    }

    private suspend fun reconcile() {
        if (foreground || rowWriting) {
            location.start()
            if (!scanner.isScanning()) {
                // A deferred or rejected live start is retried when the platform recovers.
                scanner.start()
            }
        } else {
            if (scanner.isScanning()) scanner.stopAndWait()
            location.stop()
        }
    }
}
