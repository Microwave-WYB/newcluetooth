package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import java.util.Locale

/** 12:34, or 1:02:03 once an hour has passed. */
fun formatClock(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 842 -> "842", 1204 -> "1.2k". */
fun formatCount(n: Int): String =
    if (n < 1000) n.toString() else String.format(Locale.US, "%.1fk", n / 1000f)

/** "23 分钟", or "45 秒" under a minute. */
fun formatDuration(seconds: Int): String =
    if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分钟"

/** "640 m", "1.8 km". */
fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else String.format(Locale.US, "%.1f km", meters / 1000f)
