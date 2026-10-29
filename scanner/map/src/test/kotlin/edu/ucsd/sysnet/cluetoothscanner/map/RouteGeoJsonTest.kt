package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.data.RouteGeoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** M-8: the incrementally built route equals the one-shot serialization at every step. */
class RouteGeoJsonTest {
    private val ends = Design.style.endProperties

    @Test fun `appending equals serializing the whole route`() {
        val r = RouteGeoJson()
        val all = ArrayList<GeoPoint>()
        assertEquals(GeoJson.EMPTY, r.geojson())
        for (i in 0 until 50) {
            val batch = (0..i % 3).map { GeoPoint(-117.0 + (all.size + it) * 1e-4, 32.0 + it * 1e-5) }
            r.append(batch)
            all += batch
            assertEquals(GeoJson.routes(listOf(all)), r.geojson())
            assertEquals(GeoJson.ends(all, ends), r.ends(ends))
        }
        r.reset(all.take(1))
        assertEquals(GeoJson.EMPTY, r.geojson())
        assertEquals(GeoJson.EMPTY, r.ends(ends))
    }

    @Test fun `the string is cached until the route changes`() {
        val r = RouteGeoJson()
        r.reset(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)))
        assertSame(r.geojson(), r.geojson())
        r.append(emptyList())
        assertSame(r.geojson(), r.geojson())
    }
}
