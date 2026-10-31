package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** M-8: Start and End hide while every route point is within 50 m of the first (design 8a4ae89). */
class RouteEndsTest {
    private val start = GeoPoint(-117.234, 32.88)

    /** The point [metres] from [from] towards [bearingDeg] (small distances, local flat approximation). */
    private fun offset(from: GeoPoint, metres: Double, bearingDeg: Double): GeoPoint {
        val b = bearingDeg * PI / 180
        val dLat = metres * cos(b) / 111_195.0
        val dLon = metres * sin(b) / (111_195.0 * cos(from.lat * PI / 180))
        return GeoPoint(from.lon + dLon, from.lat + dLat)
    }

    @Test fun `distance is the great-circle metres`() {
        assertEquals(111_195.0, RouteEnds.distanceM(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 1.0)
        assertEquals(0.0, RouteEnds.distanceM(start, start), 0.0)
        assertEquals(50.0, RouteEnds.distanceM(start, offset(start, 50.0, 30.0)), 0.05)
    }

    @Test fun `GPS jitter while standing still never shows Start and End`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            // an hour of fixes scattered up to 25 m around the spot, with the occasional 45 m outlier
            val route = listOf(start) + List(1_800) {
                offset(start, if (rnd.nextInt(50) == 0) rnd.nextDouble(30.0, 45.0) else rnd.nextDouble(0.0, 25.0), rnd.nextDouble(0.0, 360.0))
            }
            assertFalse("seed $seed", RouteEnds.shown(route))
        }
    }

    @Test fun `the distance from the first point counts, not the path length`() {
        // a long zig-zag (kilometres of path) that never gets 40 m from the start
        val zigzag = listOf(start) + List(200) { i -> offset(start, 40.0, if (i % 2 == 0) 0.0 else 180.0) }
        assertTrue(zigzag.zipWithNext { a, b -> RouteEnds.distanceM(a, b) }.sum() > 5_000)
        assertFalse(RouteEnds.shown(zigzag))
        // one step beyond 50 m is movement, and coming back does not undo it
        val outAndBack = listOf(start, offset(start, 60.0, 90.0), start)
        assertTrue(RouteEnds.shown(outAndBack))
    }

    @Test fun `50 m is the boundary, measured from the first point`() {
        assertFalse(RouteEnds.shown(listOf(start, offset(start, 49.9, 45.0))))
        assertTrue(RouteEnds.shown(listOf(start, offset(start, 50.1, 45.0))))
        // a route starting elsewhere is measured from its own first point
        val far = offset(start, 5_000.0, 0.0)
        assertFalse(RouteEnds.shown(listOf(far, offset(far, 20.0, 200.0))))
    }

    @Test fun `no route or a single point shows nothing`() {
        assertFalse(RouteEnds.shown(emptyList()))
        assertFalse(RouteEnds.shown(listOf(start)))
        assertFalse(RouteEnds.shown(length = 1, farthestFromFirstM = 500.0))
    }

    @Test fun `walking away shows them`() {
        val walk = List(30) { i -> offset(start, i * 5.0, 60.0) }
        assertTrue(RouteEnds.shown(walk))
    }
}
