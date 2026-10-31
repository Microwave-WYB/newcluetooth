package edu.ucsd.sysnet.cluetoothscanner.ui.contract

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-11 on screen (Web Mercator): a zoom change of 1 or more, or the reference centre more than a quarter of the
 * viewport from the current centre on either axis.
 */
class MovedAwayTest {
    private val vp = Bbox(-0.5, -0.5, 0.5, 0.5) // near the equator Mercator is almost linear
    private val ref = CameraSnapshot(GeoPoint(0.0, 0.0), 15.0)
    private fun at(lon: Double, lat: Double, zoom: Double = 15.0) = CameraSnapshot(GeoPoint(lon, lat), zoom)

    @Test fun smallMovesStay() {
        assertFalse(movedAway(ref, ref, vp))
        assertFalse(movedAway(ref, at(0.2, 0.2, 15.99), vp))
        assertFalse(movedAway(ref, at(0.0, 0.0, 14.01), vp))
    }

    @Test fun zoomByOneOrMore() {
        assertTrue(movedAway(ref, at(0.0, 0.0, 16.0), vp))
        assertTrue(movedAway(ref, at(0.0, 0.0, 14.0), vp))
    }

    @Test fun centreAQuarterAwayOnEitherAxis() {
        assertTrue(movedAway(ref, at(0.26, 0.0), vp))
        assertTrue(movedAway(ref, at(-0.26, 0.0), vp))
        assertTrue(movedAway(ref, at(0.0, 0.26), vp))
        assertTrue(movedAway(ref, at(0.0, -0.26), vp))
    }

    @Test fun measuredOnScreenNotInDegrees() {
        // A low-zoom view from 0 to 70 degrees north: Mercator stretches the north. From the screen centre
        // (about 44.4 N), 16 degrees north is under a quarter in degrees (16 / 70) but over a quarter on screen,
        // and 18 degrees south is over a quarter in degrees (18 / 70) but under a quarter on screen.
        val tall = Bbox(-40.0, 0.0, 40.0, 70.0)
        val centre = viewportCamera(tall, 3.0)
        fun shifted(dLat: Double) = CameraSnapshot(GeoPoint(0.0, centre.center.lat + dLat), 3.0)
        assertTrue(16.0 / 70.0 < 0.25)
        assertTrue(movedAway(centre, shifted(16.0), tall))
        assertTrue(18.0 / 70.0 > 0.25)
        assertFalse(movedAway(centre, shifted(-18.0), tall))
    }

    @Test fun viewportCameraIsTheCentreOnScreen() {
        val c = viewportCamera(Bbox(-40.0, 0.0, 40.0, 70.0), 3.0)
        assertEquals(0.0, c.center.lon, 1e-9)
        assertTrue("the screen centre of 0..70 N is north of 35 N", c.center.lat > 40.0)
        assertEquals(3.0, c.zoom, 0.0)
    }
}
