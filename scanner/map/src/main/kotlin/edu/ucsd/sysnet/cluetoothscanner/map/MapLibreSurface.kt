package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.RectF
import android.view.Gravity
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.TapResolver
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.SelectionProp
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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
) : MapSurface {
    private val density = view.resources.displayMetrics.density

    /** For instrumented tests. */
    internal val mapView: MapView get() = view
    internal val maplibre: MapLibreMap get() = map

    private val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
        listener.onMoveStarted(reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)
    }
    private val idle = MapLibreMap.OnCameraIdleListener { listener.onIdle() }
    private val click = MapLibreMap.OnMapClickListener { latLng ->
        listener.onTap(hitAt(latLng))
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

    fun release() {
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
        map.style?.getSourceAs<GeoJsonSource>(source)?.setGeoJson(geojson)
    }

    override fun applySelection(props: List<SelectionProp>) {
        map.style?.let { StyleInstaller.applySelection(it, props) }
    }

    override fun setRouteOpacity(opacity: Double) {
        map.style?.getLayer("route")?.setProperties(PaintPropertyValue("line-opacity", opacity.toFloat()))
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
        return map.style?.getSourceAs<GeoJsonSource>("devices")?.getClusterExpansionZoom(feature)
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
