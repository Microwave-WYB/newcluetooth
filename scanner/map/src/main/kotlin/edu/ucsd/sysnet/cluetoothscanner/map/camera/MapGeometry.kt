package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Padding in dp, as MapLibre takes it (logical pixels). */
data class Padding(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    companion object { val Zero = Padding(0.0, 0.0, 0.0, 0.0) }
}

/** The map view in dp. The camera never rotates or tilts (rotate and tilt gestures are off), so bearing is 0. */
data class Viewport(val widthDp: Double, val heightDp: Double)

/**
 * Web Mercator as MapLibre uses it: 512 dp tiles, so the world is 512 × 2^zoom dp wide. Every camera rule of the
 * map is computed here in plain Kotlin, so the tests cover the same code the map runs.
 */
object MapGeometry {
    private const val TILE = 512.0
    private const val MAX_LAT = 85.0511287798066

    /** M-10: the framing maximum zoom. */
    const val FRAME_MAX_ZOOM = 17.0

    /** P-4: the locate target zoom, max(current, 17). */
    const val LOCATE_MIN_ZOOM = 17.0

    /** D-14: the device sheet's covered height the camera keeps the device clear of (the prototype's 320). */
    const val FOCUS_MIN_COVER_DP = 320.0

    /** World x, y in [0, 1). */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180
        val x = (p.lon + 180) / 360
        val y = (1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2
        return x to y
    }

    fun unproject(x: Double, y: Double): GeoPoint {
        val lon = x * 360 - 180
        val n = PI - 2 * PI * y
        val lat = 180 / PI * atan(0.5 * (exp(n) - exp(-n)))
        return GeoPoint(lon, lat)
    }

    fun worldSize(zoom: Double) = TILE * 2.0.pow(zoom)

    /** Screen position (dp, from the top-left) of [p] for a camera centred on [camera] in [view]. */
    fun toScreen(p: GeoPoint, camera: CameraSnapshot, view: Viewport): Pair<Double, Double> {
        val (px, py) = project(p)
        val (cx, cy) = project(camera.center)
        val w = worldSize(camera.zoom)
        return view.widthDp / 2 + (px - cx) * w to view.heightDp / 2 + (py - cy) * w
    }

    /** The visible bbox of a camera. */
    fun visibleBbox(camera: CameraSnapshot, view: Viewport): Bbox {
        val (cx, cy) = project(camera.center)
        val w = worldSize(camera.zoom)
        val nw = unproject(cx - view.widthDp / 2 / w, cy - view.heightDp / 2 / w)
        val se = unproject(cx + view.widthDp / 2 / w, cy + view.heightDp / 2 / w)
        return Bbox(west = nw.lon, south = se.lat, east = se.lon, north = nw.lat)
    }

    /**
     * The camera that puts [target] at the centre of the padded area: the padding only moves the centre, it never
     * becomes the map's own padding (which would shift every later camera move).
     */
    fun centerFor(target: GeoPoint, zoom: Double, padding: Padding): CameraSnapshot {
        val (tx, ty) = project(target)
        val w = worldSize(zoom)
        val dx = (padding.right - padding.left) / 2 / w
        val dy = (padding.bottom - padding.top) / 2 / w
        return CameraSnapshot(unproject(tx + dx, ty + dy), zoom)
    }

    /** M-10 / D-8: fit [bbox] inside the padded view, at most [maxZoom]. A point-sized bbox gets [maxZoom]. */
    fun fit(bbox: Bbox, view: Viewport, padding: Padding, maxZoom: Double = FRAME_MAX_ZOOM): CameraSnapshot {
        val (x1, y1) = project(GeoPoint(bbox.west, bbox.north))
        val (x2, y2) = project(GeoPoint(bbox.east, bbox.south))
        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        val availW = max(1.0, view.widthDp - padding.left - padding.right)
        val availH = max(1.0, view.heightDp - padding.top - padding.bottom)
        val scale = min(if (dx > 0) availW / dx else Double.POSITIVE_INFINITY, if (dy > 0) availH / dy else Double.POSITIVE_INFINITY)
        val zoom = if (scale.isInfinite()) maxZoom else min(maxZoom, log2(scale / TILE))
        val center = unproject((x1 + x2) / 2, (y1 + y2) / 2)
        return centerFor(center, zoom, padding)
    }

    /** M-10: top 90, bottom = sheet cover + 40, left = panel cover + 40 (40 on phone), right 40. */
    fun framePadding(cover: CoverInsets) =
        Padding(left = cover.left + 40.0, top = 90.0, right = 40.0, bottom = cover.bottom + 40.0)

    /** P-4: the locate ease keeps the location centred in what the sheets leave visible. */
    fun locatePadding(cover: CoverInsets) = Padding(left = cover.left.toDouble(), top = 0.0, right = 0.0, bottom = cover.bottom.toDouble())

    /** D-14: keep the device clear of the sheet; on tablet (no bottom cover) only the panel's side. */
    fun focusPadding(cover: CoverInsets) = Padding(
        left = cover.left.toDouble(), top = 0.0, right = 0.0,
        bottom = if (cover.bottom > 0) max(cover.bottom.toDouble(), FOCUS_MIN_COVER_DP) else 0.0,
    )

    /**
     * P-4: how far (dp) [location] is from the centre of the map area the sheets leave visible (the view minus the
     * left panel and the bottom sheet).
     */
    fun locationOffset(location: GeoPoint, camera: CameraSnapshot, view: Viewport, cover: CoverInsets): Double {
        val (x, y) = toScreen(location, camera, view)
        val w = view.widthDp - cover.left
        val h = view.heightDp - cover.bottom
        return hypot(x - cover.left - w / 2, y - h / 2)
    }

    /**
     * M-11: the view has moved away from [reference] when the zoom differs by 1 or more, or the reference centre is
     * more than 25 % of the viewport away from the current centre on either axis.
     */
    fun movedAway(reference: CameraSnapshot, current: CameraSnapshot, view: Viewport): Boolean {
        if (abs(current.zoom - reference.zoom) >= 1) return true
        val (x, y) = toScreen(reference.center, current, view)
        return abs(x - view.widthDp / 2) / view.widthDp > 0.25 || abs(y - view.heightDp / 2) / view.heightDp > 0.25
    }

    /**
     * The area map_points loaded for [viewport]: the core serves the viewport + 50 % (§2.5), which is a quarter of
     * the width and height added on each side (confirmed with lane A).
     */
    fun loadedArea(viewport: Bbox): Bbox {
        val dx = (viewport.east - viewport.west) / 4
        val dy = (viewport.north - viewport.south) / 4
        return Bbox(viewport.west - dx, viewport.south - dy, viewport.east + dx, viewport.north + dy)
    }

    fun contains(outer: Bbox, inner: Bbox) =
        inner.west >= outer.west && inner.east <= outer.east && inner.south >= outer.south && inner.north <= outer.north

    fun bboxOf(points: List<GeoPoint>): Bbox? {
        if (points.isEmpty()) return null
        return Bbox(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
    }
}
