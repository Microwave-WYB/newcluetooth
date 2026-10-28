package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/** A guess at where a device is, from where it was heard. */
data class PositionEstimate(val at: LatLon, val radiusMeters: Int)

/**
 * RSSI-weighted centroid of the strongest sightings: weight 10^(rssi/10), so a point 10 dB
 * stronger counts ten times as much. The radius is the weighted spread of those points, at
 * least 5 m. This is a plain heuristic, not a trilateration.
 */
fun estimatePosition(points: List<SightingPoint>, top: Int = 8): PositionEstimate? {
    if (points.isEmpty()) return null
    val best = points.sortedByDescending { it.rssi }.take(top)
    val weights = best.map { 10.0.pow((it.rssi - best.first().rssi) / 10.0) }
    val total = weights.sum()
    val lat = best.indices.sumOf { best[it].at.lat * weights[it] } / total
    val lon = best.indices.sumOf { best[it].at.lon * weights[it] } / total
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
    val spread = best.indices.sumOf { hypot((best[it].at.lat - lat) * mPerDegLat, (best[it].at.lon - lon) * mPerDegLon) * weights[it] } / total
    return PositionEstimate(LatLon(lat, lon), spread.toInt().coerceAtLeast(5))
}
