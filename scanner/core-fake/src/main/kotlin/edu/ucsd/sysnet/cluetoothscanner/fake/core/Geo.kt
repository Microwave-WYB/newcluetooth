package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small geometry helpers shared by the fake's queries (the same rules as §3.6 and §3.7). */
internal object Geo {
    fun inside(p: GeoPoint, b: Bbox) = p.lon >= b.west && p.lon <= b.east && p.lat >= b.south && p.lat <= b.north

    /** The viewport grown by 50 % (a quarter of its size on each side), as `map_points` loads it. */
    fun grow(b: Bbox): Bbox {
        val dx = (b.east - b.west) / 4
        val dy = (b.north - b.south) / 4
        return Bbox(b.west - dx, b.south - dy, b.east + dx, b.north + dy)
    }

    fun bboxOf(points: Iterable<GeoPoint>): Bbox? {
        var b: Bbox? = null
        for (p in points) {
            b = if (b == null) Bbox(p.lon, p.lat, p.lon, p.lat)
            else Bbox(minOf(b.west, p.lon), minOf(b.south, p.lat), maxOf(b.east, p.lon), maxOf(b.north, p.lat))
        }
        return b
    }

    fun tileX(lon: Double, z: Int): Long = floor((lon + 180) / 360 * (1L shl z)).toLong()
    fun tileY(lat: Double, z: Int): Long {
        val r = lat * PI / 180
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1L shl z)).toLong()
    }

    /** §3.2 `tile17`: `(x17 << 17) | y17`. */
    fun tile17(p: GeoPoint): Long = (tileX(p.lon, 17) shl 17) or tileY(p.lat, 17)

    fun haversineM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    fun abbreviated(n: Int): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 10_000 -> "${n / 1_000}k"
        n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000.0).replace(".0k", "k")
        else -> n.toString()
    }

    fun num(x: Double): String = if (x == floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()
    fun str(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun pointFeature(p: GeoPoint, props: String) =
        """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":[${num(p.lon)},${num(p.lat)}]}}"""

    fun lineFeature(points: List<GeoPoint>) =
        """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""" +
            points.joinToString(",") { "[${num(it.lon)},${num(it.lat)}]" } + "]}}"

    fun collection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

/** MAC ↔ [edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey] (§2.2). */
object DeviceKeyCodec {
    fun key(addr: String): Long = addr.replace(":", "").toLong(16)
    fun addr(key: Long): String = (5 downTo 0).joinToString(":") { "%02X".format((key shr (8 * it)) and 0xff) }
}
