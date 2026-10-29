package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.map.stub.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.map.stub.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class MapGeometryTest {
    private val view = Viewport(412.0, 915.0)
    private val sd = GeoPoint(-117.2340, 32.8801)

    @Test fun `projection round-trips`() {
        val (x, y) = MapGeometry.project(sd)
        val back = MapGeometry.unproject(x, y)
        assertEquals(sd.lon, back.lon, 1e-9)
        assertEquals(sd.lat, back.lat, 1e-9)
    }

    @Test fun `M-10 padding is top 90, bottom cover + 40, left cover + 40, right 40`() {
        assertEquals(Padding(left = 40.0, top = 90.0, right = 40.0, bottom = 118.0), MapGeometry.framePadding(CoverInsets(0, 78, 0, 0)))
        // tablet: the panel covers the left (edge + 400)
        assertEquals(Padding(left = 452.0, top = 90.0, right = 40.0, bottom = 40.0), MapGeometry.framePadding(CoverInsets(0, 0, 412, 0)))
    }

    @Test fun `M-10 fit puts the bbox inside the padded area, touching it on one axis`() {
        val bbox = Bbox(-117.25, 32.87, -117.22, 32.89)
        val pad = MapGeometry.framePadding(CoverInsets(0, 300, 0, 0))
        val cam = MapGeometry.fit(bbox, view, pad)
        val (x1, y1) = MapGeometry.toScreen(GeoPoint(bbox.west, bbox.north), cam, view)
        val (x2, y2) = MapGeometry.toScreen(GeoPoint(bbox.east, bbox.south), cam, view)
        val e = 1e-6
        assertTrue(x1 >= pad.left - e && x2 <= view.widthDp - pad.right + e)
        assertTrue(y1 >= pad.top - e && y2 <= view.heightDp - pad.bottom + e)
        val touchesX = kotlin.math.abs(x1 - pad.left) < 1e-6 && kotlin.math.abs(x2 - (view.widthDp - pad.right)) < 1e-6
        val touchesY = kotlin.math.abs(y1 - pad.top) < 1e-6 && kotlin.math.abs(y2 - (view.heightDp - pad.bottom)) < 1e-6
        assertTrue(touchesX || touchesY)
        // centred in the padded area
        assertEquals((pad.left + view.widthDp - pad.right) / 2, (x1 + x2) / 2, 1e-6)
        assertEquals((pad.top + view.heightDp - pad.bottom) / 2, (y1 + y2) / 2, 1e-6)
    }

    @Test fun `M-10 maximum zoom 17, and a single point frames at 17`() {
        val tiny = Bbox(-117.2341, 32.8801, -117.2340, 32.8802)
        assertEquals(17.0, MapGeometry.fit(tiny, view, Padding.Zero).zoom, 0.0)
        val point = Bbox(sd.lon, sd.lat, sd.lon, sd.lat)
        val cam = MapGeometry.fit(point, view, MapGeometry.framePadding(CoverInsets(0, 78, 0, 0)))
        assertEquals(17.0, cam.zoom, 0.0)
        val (x, y) = MapGeometry.toScreen(sd, cam, view)
        assertEquals(view.widthDp / 2, x, 1e-6)
        assertEquals((90 + view.heightDp - 118) / 2, y, 1e-6)
    }

    @Test fun `D-14 focus keeps the device clear of the sheet, at least 320 dp`() {
        assertEquals(320.0, MapGeometry.focusPadding(CoverInsets(0, 78, 0, 0)).bottom, 0.0)
        assertEquals(613.0, MapGeometry.focusPadding(CoverInsets(0, 613, 0, 0)).bottom, 0.0)
        assertEquals(Padding(412.0, 0.0, 0.0, 0.0), MapGeometry.focusPadding(CoverInsets(0, 0, 412, 0)))
        val cam = MapGeometry.centerFor(sd, 18.2, MapGeometry.focusPadding(CoverInsets(0, 613, 0, 0)))
        val (x, y) = MapGeometry.toScreen(sd, cam, view)
        assertEquals(view.widthDp / 2, x, 1e-6)
        assertEquals((view.heightDp - 613) / 2, y, 1e-6)
    }

    @Test fun `M-11 a zoom change of 1 or more is moved away, less is not`() {
        val ref = CameraSnapshot(sd, 15.0)
        assertFalse(MapGeometry.movedAway(ref, ref.copy(zoom = 15.99), view))
        assertTrue(MapGeometry.movedAway(ref, ref.copy(zoom = 16.0), view))
        assertTrue(MapGeometry.movedAway(ref, ref.copy(zoom = 14.0), view))
    }

    @Test fun `M-11 the reference centre more than 25 percent of the viewport away on either axis`() {
        val ref = CameraSnapshot(sd, 15.0)
        val w = MapGeometry.worldSize(15.0)
        val (cx, cy) = MapGeometry.project(sd)
        fun shifted(dxDp: Double, dyDp: Double) = CameraSnapshot(MapGeometry.unproject(cx + dxDp / w, cy + dyDp / w), 15.0)
        assertFalse(MapGeometry.movedAway(ref, shifted(0.24 * view.widthDp, 0.0), view))
        assertTrue(MapGeometry.movedAway(ref, shifted(0.26 * view.widthDp, 0.0), view))
        assertTrue(MapGeometry.movedAway(ref, shifted(-0.26 * view.widthDp, 0.0), view))
        assertFalse(MapGeometry.movedAway(ref, shifted(0.0, 0.24 * view.heightDp), view))
        assertTrue(MapGeometry.movedAway(ref, shifted(0.0, 0.26 * view.heightDp), view))
        assertFalse(MapGeometry.movedAway(ref, shifted(0.24 * view.widthDp, -0.24 * view.heightDp), view))
    }

    @Test fun `P-4 location offset is measured from the centre of the uncovered area`() {
        val cover = CoverInsets(0, 315, 0, 0)
        // the location at the centre of the area above the sheet: offset 0
        val cam = MapGeometry.centerFor(sd, 16.0, MapGeometry.locatePadding(cover))
        assertEquals(0.0, MapGeometry.locationOffset(sd, cam, view, cover), 1e-6)
        // the plain map centre is half the cover below that centre
        assertEquals(315.0 / 2, MapGeometry.locationOffset(sd, CameraSnapshot(sd, 16.0), view, cover), 1e-6)
        val tablet = CoverInsets(0, 0, 412, 0)
        val wide = Viewport(1024.0, 768.0)
        assertEquals(412.0 / 2, MapGeometry.locationOffset(sd, CameraSnapshot(sd, 16.0), wide, tablet), 1e-6)
    }

    @Test fun `visible bbox and loaded area`() {
        val cam = CameraSnapshot(sd, 16.0)
        val b = MapGeometry.visibleBbox(cam, view)
        assertTrue(b.west < sd.lon && sd.lon < b.east && b.south < sd.lat && sd.lat < b.north)
        val loaded = MapGeometry.loadedArea(b)
        assertTrue(MapGeometry.contains(loaded, b))
        assertEquals((b.east - b.west) * 1.5, loaded.east - loaded.west, 1e-9)
        val panned = MapGeometry.visibleBbox(MapGeometry.centerFor(sd, 16.0, Padding(0.0, 0.0, max(0.0, view.widthDp), 0.0)), view)
        assertFalse(MapGeometry.contains(loaded, panned))
    }
}
