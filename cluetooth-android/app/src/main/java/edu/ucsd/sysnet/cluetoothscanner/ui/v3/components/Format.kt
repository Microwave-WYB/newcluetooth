package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Texts
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

/** "23 min", or "45 s" under a minute. */
fun formatDuration(seconds: Int, texts: Texts): String =
    if (seconds < 60) texts.string(R.string.duration_seconds, seconds) else texts.string(R.string.duration_minutes, seconds / 60)

/** "640 m", "1.8 km". */
fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else String.format(Locale.US, "%.1f km", meters / 1000f)

/** "1 sighting", "1.2k sightings". */
fun rowsText(n: Int, texts: Texts): String = texts.plural(R.plurals.rows, n, formatCount(n))

/** "1 device", "48 devices". */
fun devicesText(n: Int, texts: Texts): String = texts.plural(R.plurals.devices, n, n.toString())
