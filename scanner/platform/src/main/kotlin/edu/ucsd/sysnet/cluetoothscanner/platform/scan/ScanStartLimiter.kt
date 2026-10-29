package edu.ucsd.sysnet.cluetoothscanner.platform.scan

/**
 * Android refuses an app's sixth `startScan` within 30 s (it logs "App is scanning too frequently" and the scan
 * silently delivers nothing). Every start path asks [waitMs] first and, over the limit, starts later instead of
 * dropping the start; [record] counts a start. Times are monotonic milliseconds.
 */
internal class ScanStartLimiter(
    private val nowMs: () -> Long,
    private val maxStarts: Int = MAX_STARTS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val starts = ArrayDeque<Long>()

    /** 0 when a start is allowed now, else how long until the oldest counted start leaves the window. */
    fun waitMs(): Long {
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        return if (starts.size < maxStarts) 0 else windowMs - (now - starts.first())
    }

    fun record() {
        starts.addLast(nowMs())
    }

    companion object {
        const val MAX_STARTS = 5

        /** Android's 30 s window plus a margin for clock skew between us and the Bluetooth stack. */
        const val WINDOW_MS = 30_500L
    }
}
