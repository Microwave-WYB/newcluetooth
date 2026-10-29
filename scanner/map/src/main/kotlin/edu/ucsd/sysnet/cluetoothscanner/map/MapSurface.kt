package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.map.style.SelectionProp

/**
 * What [CluetoothMapState] needs from the drawn map: MapLibre in the app ([MapLibreSurface]), a recorder in the JVM
 * tests. Main thread only.
 */
interface MapSurface {
    /** The style the overlay was installed in has loaded; until then no source, layer or camera call is made. */
    val styleReady: Boolean get() = true

    val viewport: Viewport
    val camera: CameraSnapshot
    val visibleBbox: Bbox

    /** Sets one GeoJSON source of map-style.json (or `cells`); parsed natively off the main thread. */
    fun setGeoJson(source: String, geojson: String)

    /** Applies map-style.js selectionProps (M-6). */
    fun applySelection(props: List<SelectionProp>)

    /** M-8: the route line's opacity (one session, or every route of All sessions). */
    fun setRouteOpacity(opacity: Double)

    /** An app camera move; durationMs 0 jumps. It never counts as the user's (M-11, P-4, P-6). */
    fun move(camera: CameraSnapshot, durationMs: Int)

    /** M-4: the cluster's expansion zoom, or null when the hit is no longer a cluster. */
    fun clusterExpansionZoom(hit: Hit): Int?
}

/** The camera and tap events a surface reports to its state. */
interface MapSurfaceListener {
    /** byGesture: MapLibre REASON_API_GESTURE (drag, pinch, fling, double-tap); our own moves are not. */
    fun onMoveStarted(byGesture: Boolean)
    fun onIdle()
    /** The topmost hit under an 8 dp box around the finger, or null for empty map (M-7). */
    fun onTap(hit: Hit?)
    /** The style (re)loaded: every source is empty again. */
    fun onStyleLoaded()
}
