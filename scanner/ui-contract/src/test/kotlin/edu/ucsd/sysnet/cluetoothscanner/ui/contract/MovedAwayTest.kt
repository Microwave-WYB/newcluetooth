package edu.ucsd.sysnet.cluetoothscanner.ui.contract

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-11: a zoom change of 1 or more, or the centre more than a quarter of the viewport away on either axis. */
class MovedAwayTest {
    private val viewport = Bbox(0.0, 0.0, 1.0, 1.0)
    private val ref = CameraSnapshot(GeoPoint(0.5, 0.5), 15.0)
    private fun at(lon: Double, lat: Double, zoom: Double = 15.0) = CameraSnapshot(GeoPoint(lon, lat), zoom)

    @Test fun smallMovesStay() {
        assertFalse(movedAway(ref, ref, viewport))
        assertFalse(movedAway(ref, at(0.75, 0.75, 15.99), viewport)) // exactly 25 % and under 1 zoom: not yet
        assertFalse(movedAway(ref, at(0.5, 0.5, 14.01), viewport))
    }

    @Test fun zoomByOneOrMore() {
        assertTrue(movedAway(ref, at(0.5, 0.5, 16.0), viewport))
        assertTrue(movedAway(ref, at(0.5, 0.5, 14.0), viewport))
    }

    @Test fun centreAQuarterAwayOnEitherAxis() {
        assertTrue(movedAway(ref, at(0.76, 0.5), viewport))
        assertTrue(movedAway(ref, at(0.24, 0.5), viewport))
        assertTrue(movedAway(ref, at(0.5, 0.76), viewport))
        assertTrue(movedAway(ref, at(0.5, 0.24), viewport))
    }

    @Test fun relativeToTheViewport() {
        val wide = Bbox(0.0, 0.0, 4.0, 4.0)
        assertFalse(movedAway(ref, at(1.4, 0.5), wide))
        assertTrue(movedAway(ref, at(1.6, 0.5), wide))
    }
}
