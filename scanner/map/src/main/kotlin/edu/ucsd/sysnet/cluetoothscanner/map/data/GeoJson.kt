package edu.ucsd.sysnet.cluetoothscanner.map.data

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import kotlinx.serialization.json.JsonObject

/** GeoJSON strings for the sources the map fills itself; devices and cells come from MapData (§2.5). */
object GeoJson {
    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    private fun coord(p: GeoPoint) = "[${p.lon},${p.lat}]"

    private fun collection(features: List<String>) =
        if (features.isEmpty()) EMPTY else features.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}")

    private fun point(p: GeoPoint, properties: String = "{}") =
        """{"type":"Feature","properties":$properties,"geometry":{"type":"Point","coordinates":${coord(p)}}}"""

    /** M-8: one LineString per route with at least two points. */
    fun routes(routes: List<List<GeoPoint>>) = collection(routes.filter { it.size > 1 }.map { r ->
        """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${r.joinToString(",", transform = ::coord)}]}}"""
    })

    /** M-8 Start and End (map-style.js endsOf): none for a route of fewer than two points. */
    fun ends(route: List<GeoPoint>, properties: List<JsonObject>) = collection(
        if (route.size > 1) listOf(point(route.first(), properties[0].toString()), point(route.last(), properties[1].toString()))
        else emptyList(),
    )

    /** M-6: the selected device's sightings, colored by RSSI band. */
    fun sightings(sightings: List<Sighting>) = collection(sightings.map { point(it.at, """{"rssi":${it.rssi}}""") })

    /** M-9: the user's location. */
    fun me(at: GeoPoint?) = collection(listOfNotNull(at?.let { point(it) }))
}
