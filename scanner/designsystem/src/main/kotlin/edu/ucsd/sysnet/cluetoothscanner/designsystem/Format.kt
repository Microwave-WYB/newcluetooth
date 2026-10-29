package edu.ucsd.sysnet.cluetoothscanner.designsystem

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** G-4: the RSSI bands. */
enum class RssiBand { Strong, Fair, Weak }

/** Formatting helpers shared by every lane (`app.js` `minus`, `band`, `duration`, `distance`, `dayLabel`, …). */
object Format {
    /** "−" (U+2212) is the minus sign for every number shown (C-5, D-9). */
    const val MINUS = '−'

    /** G-4: strong ≥ −65 dBm, fair −80 … −65, weak below −80. */
    fun band(rssi: Int): RssiBand = when {
        rssi >= -65 -> RssiBand.Strong
        rssi >= -80 -> RssiBand.Fair
        else -> RssiBand.Weak
    }

    /** A number with the typographic minus: −56. */
    fun signed(n: Int): String = if (n < 0) "$MINUS${-n}" else n.toString()

    /** "−56 dBm". */
    fun dbm(rssi: Int): String = Strings.dbm(signed(rssi))

    /** L-2 durations: "23 min", "1 h", "1 h 5 min". */
    fun duration(minutes: Long): String =
        if (minutes >= 60) "${minutes / 60} h" + (if (minutes % 60 != 0L) " ${minutes % 60} min" else "") else "$minutes min"

    /** Active milliseconds as an L-2 duration (rounded to the minute). */
    fun durationMs(ms: Long): String = duration((ms / 60_000.0).roundToLong())

    /** L-2 distances: "300 m", "1.8 km". */
    fun distance(meters: Double): String =
        if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000) else "${meters.roundToInt()} m"

    /** L-2 session sub line: "23 min · 1.8 km · 214 devices"; the distance is left out when 0. */
    fun sessionSummary(activeMs: Long, distanceM: Double, devices: Int): String =
        listOfNotNull(durationMs(activeMs), if (distanceM.roundToInt() > 0) distance(distanceM) else null, Strings.devices(devices))
            .joinToString(" · ")

    /** R-3 elapsed time: "4:05", "12:34", "1:02:03". */
    fun elapsed(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun fmt(pattern: String, zone: TimeZone) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }

    /** "7:12 PM". */
    fun time(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String = fmt("h:mm a", zone).format(Date(epochMs))

    /** The calendar day of an instant as yyyymmdd, for grouping sessions by day (L-2). */
    fun dayKey(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone, Locale.US).run {
            timeInMillis = epochMs
            get(Calendar.YEAR) * 10_000 + (get(Calendar.MONTH) + 1) * 100 + get(Calendar.DAY_OF_MONTH)
        }

    /** L-2 day heads: "Today · Oct 28", "Yesterday · Oct 27", then "Oct 25". */
    fun dayLabel(epochMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val md = fmt("MMM d", zone).format(Date(epochMs))
        val day = dayKey(epochMs, zone)
        val yesterday = Calendar.getInstance(zone, Locale.US).run { timeInMillis = nowMs; add(Calendar.DAY_OF_MONTH, -1); timeInMillis }
        return when (day) {
            dayKey(nowMs, zone) -> "${Strings.Today} · $md"
            dayKey(yesterday, zone) -> "${Strings.Yesterday} · $md"
            else -> md
        }
    }

    /** "just now", "4 min ago", "2 h ago", "3 d ago" (data.js `last`). */
    fun ago(thenMs: Long, nowMs: Long): String {
        val s = (nowMs - thenMs).coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** R-4 location: "32.88012, -117.23421 ±8 m" (coordinates keep the ASCII minus, as the prototype). */
    fun location(lat: Double, lon: Double, accuracyM: Double): String =
        String.format(Locale.US, "%.5f, %.5f ±%d m", lat, lon, accuracyM.roundToInt())

    /** Storage sizes: "0 B", "812 KB", "4.2 MB", "1.3 GB" (decimal units). */
    fun bytes(n: Long): String {
        val a = abs(n.toDouble())
        return when {
            a < 1000 -> "$n B"
            a < 1e6 -> "${(a / 1e3).roundToInt()} KB"
            a < 1e9 -> String.format(Locale.US, if (a < 1e7) "%.1f MB" else "%.0f MB", a / 1e6)
            else -> String.format(Locale.US, "%.1f GB", a / 1e9)
        }
    }

    /** D-12 Type column: "0x09". */
    fun adType(type: Int): String = String.format(Locale.US, "0x%02X", type)

    /** D-12 Data column: lowercase hex, as the prototype. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }

    /** A 48-bit device key as its MAC, "AA:BB:CC:DD:EE:FF". */
    fun mac(key: Long): String = (5 downTo 0).joinToString(":") { String.format(Locale.US, "%02X", (key shr (it * 8)) and 0xff) }
}
