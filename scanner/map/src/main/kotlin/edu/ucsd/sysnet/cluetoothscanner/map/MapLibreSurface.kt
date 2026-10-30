package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.RectF
import android.view.Gravity
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapResolver
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.SelectionProp
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** [MapSurface] over a MapLibre map. Main thread only. */
class MapLibreSurface(
    private val view: MapView,
    private val map: MapLibreMap,
    private val listener: MapSurfaceListener,
    /** `--drag-slop` (G-13). */
    slopDp: Float,
    /** A MapLibre call inside one of the map's own callbacks (a tap's hit test) threw. */
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) : MapSurface {
    private val density = view.resources.displayMetrics.density

    /**
     * The style our layers were installed in, from the setStyle callback. MapLibreMap.getStyle() is null until the
     * style is fully loaded, which a single failed resource (a glyph range offline) can delay indefinitely; the
     * overlay must keep updating meanwhile.
     */
    var style: Style? = null

    override val styleReady: Boolean get() = style != null

    /** For instrumented tests. */
    internal val mapView: MapView get() = view
    internal val maplibre: MapLibreMap get() = map

    private val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
        listener.onMoveStarted(reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)
    }
    private val idle = MapLibreMap.OnCameraIdleListener { listener.onIdle() }
    private val click = MapLibreMap.OnMapClickListener { latLng ->
        // a hit test that throws reports the failure and taps nothing (not an empty-map tap)
        try {
            listener.onTap(hitAt(latLng))
        } catch (e: Exception) {
            onFailure("hitTest", e)
        }
        true
    }

    init {
        map.uiSettings.apply {
            isLogoEnabled = false
            isCompassEnabled = false
            // the camera never rotates or tilts, so every camera rule is plain Web Mercator (MapGeometry)
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            // M-1: compact attribution at the bottom-left
            isAttributionEnabled = true
            attributionGravity = Gravity.BOTTOM or Gravity.START
        }
        // G-13 / M-7: a pointer that moves less than the 8 dp slop is a tap, never a pan
        map.gesturesManager.moveGestureDetector.moveThreshold = slopDp * density
        map.addOnCameraMoveStartedListener(moveStarted)
        map.addOnCameraIdleListener(idle)
        map.addOnMapClickListener(click)
    }

    /** Set by [release]: the MapView is going away, so nothing may reach its map or style any more. */
    var released = false
        private set

    /**
     * Ends this surface's use of MapLibre's native objects, before the MapView is destroyed: the listeners go and the
     * Style is dropped, so a late call (an effect, a style callback) finds no style and does nothing.
     */
    fun release() {
        released = true
        style = null
        map.removeOnCameraMoveStartedListener(moveStarted)
        map.removeOnCameraIdleListener(idle)
        map.removeOnMapClickListener(click)
    }

    /** Keeps the attribution above whatever covers the bottom of the map (the sheet's peek on phone). */
    fun setAttributionInset(bottomDp: Int, leftDp: Int) {
        val m = (ATTRIBUTION_MARGIN_DP * density).toInt()
        map.uiSettings.setAttributionMargins((leftDp * density).toInt() + m, 0, 0, (bottomDp * density).toInt() + m)
    }

    override val viewport: Viewport get() = Viewport(view.width / density.toDouble(), view.height / density.toDouble())

    override val camera: CameraSnapshot
        get() = map.cameraPosition.let { c ->
            val t = c.target ?: LatLng(0.0, 0.0)
            CameraSnapshot(GeoPoint(t.longitude, t.latitude), c.zoom, c.bearing)
        }

    override val visibleBbox: Bbox
        get() = map.projection.visibleRegion.latLngBounds.let { b ->
            Bbox(west = b.longitudeWest, south = b.latitudeSouth, east = b.longitudeEast, north = b.latitudeNorth)
        }

    override fun setGeoJson(source: String, geojson: String) {
        (style?.getSource(source) as? GeoJsonSource)?.setGeoJson(geojson)
    }

    override fun applySelection(props: List<SelectionProp>) {
        style?.let { StyleInstaller.applySelection(it, props) }
    }

    override fun setRouteOpacity(opacity: Double) {
        style?.getLayer("route")?.setProperties(PaintPropertyValue("line-opacity", opacity.toFloat()))
    }

    override fun move(camera: CameraSnapshot, durationMs: Int) {
        val position = CameraPosition.Builder()
            .target(LatLng(camera.center.lat, camera.center.lon))
            .zoom(camera.zoom).bearing(0.0).tilt(0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (durationMs <= 0) map.moveCamera(update) else map.easeCamera(update, durationMs)
    }

    override fun clusterExpansionZoom(hit: Hit): Int? {
        val feature = hit.native as? Feature ?: return null
        return (style?.getSource("devices") as? GeoJsonSource)?.getClusterExpansionZoom(feature)
    }

    /** M-7: the topmost feature in an 8 dp box around the finger, layer by layer from the top. */
    private fun hitAt(latLng: LatLng): Hit? {
        val p = map.projection.toScreenLocation(latLng)
        val r = TapResolver.HIT_BOX_DP * density
        val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        for (layer in TapResolver.LAYERS_TOP_DOWN) {
            val f = map.queryRenderedFeatures(box, layer).firstOrNull() ?: continue
            val at = (f.geometry() as? Point)?.let { GeoPoint(it.longitude(), it.latitude()) }
            val addr = if (f.hasProperty("addr")) f.getStringProperty("addr") else null
            return Hit(layer, addr, at, f)
        }
        return null
    }

    companion object {
        private const val ATTRIBUTION_MARGIN_DP = 4
    }
}
