package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M-8 (owner decision, design 8a4ae89): Start and End are not drawn when the session barely moved, that is when
 * every route point lies within [STATIONARY_RADIUS_M] of the first one. The test is the distance from the first
 * point, not the path length, so GPS jitter while standing still never counts as movement. The main map and the
 * R-5 mini map follow the same rule. Pure.
 */
object RouteEnds {
    const val STATIONARY_RADIUS_M = 50.0
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** How far the farthest of [points] is from [first]; 0 for none. */
    fun farthestFrom(first: GeoPoint, points: List<GeoPoint>): Double = points.fold(0.0) { m, p -> max(m, distanceM(first, p)) }

    /** The farthest any point of [route] gets from its first point; 0 for an empty or one-point route. */
    fun farthestFromFirst(route: List<GeoPoint>): Double = route.firstOrNull()?.let { farthestFrom(it, route) } ?: 0.0

    /** Start and End show only for a route of two or more points that got farther than 50 m from its first point. */
    fun shown(length: Int, farthestFromFirstM: Double): Boolean = length > 1 && farthestFromFirstM > STATIONARY_RADIUS_M

    fun shown(route: List<GeoPoint>): Boolean = shown(route.size, farthestFromFirst(route))
}
