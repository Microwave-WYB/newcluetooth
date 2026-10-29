package edu.ucsd.sysnet.cluetoothscanner.map.data

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import kotlinx.serialization.json.JsonObject

/**
 * One route as the `route` source's GeoJSON (M-8), serialized incrementally: a live delta appends its points to the
 * coordinate text instead of re-serializing the whole route every second. The output equals
 * GeoJson.routes(listOf(points)).
 */
class RouteGeoJson {
    private val coords = StringBuilder()
    private var count = 0
    private var first: GeoPoint? = null
    private var last: GeoPoint? = null
    private var cached: String? = null

    fun reset(points: List<GeoPoint>) {
        coords.setLength(0)
        count = 0
        first = null
        last = null
        append(points)
    }

    fun append(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        for (p in points) {
            if (count > 0) coords.append(',')
            coords.append('[').append(p.lon).append(',').append(p.lat).append(']')
            count++
        }
        if (first == null) first = points.first()
        last = points.last()
        cached = null
    }

    fun geojson(): String = cached ?: (
        if (count < 2) GeoJson.EMPTY
        else """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
        ).also { cached = it }

    /** Start and End, from the first and last points only. */
    fun ends(properties: List<JsonObject>): String =
        if (count < 2) GeoJson.EMPTY else GeoJson.ends(listOf(first!!, last!!), properties)
}
