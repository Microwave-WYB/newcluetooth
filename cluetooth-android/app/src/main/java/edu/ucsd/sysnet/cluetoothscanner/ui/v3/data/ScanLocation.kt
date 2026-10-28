package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanLocation
import java.util.Locale

/** The phone's last position fix, without the platform class. */
class LocationFix(val lat: Double, val lon: Double, val accuracyMeters: Float?, val timeMs: Long)

/** A fix older than this is "stale"; the same threshold the recording notice uses. */
fun scanLocationOf(fix: LocationFix?, nowMs: Long): ScanLocation {
    if (fix == null) return ScanLocation.Unavailable
    val ageMs = nowMs - fix.timeMs
    if (ageMs > Notices.STALE_FIX_AFTER_MS) return ScanLocation.Stale(maxOf(1, (ageMs / 60_000).toInt()))
    return ScanLocation.Fix(fix.lat, fix.lon, fix.accuracyMeters?.let { Math.round(it) })
}

/** "32.88012, -117.23421": five decimals, a dot for the decimal point whatever the locale. */
fun coordinatesText(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)
