package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.app.GeoBounds
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.Signal
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.signalOf
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** What the map draws. [fitKey] changing asks the camera to frame the content. */
data class MapOverlay(
    val route: List<LatLon>,
    val points: List<SightingPoint>,
    val estimate: LatLon?,
    val heat: List<LatLon>,
    val fitKey: Any,
)

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

private const val SRC_ROUTE = "v3-route"
private const val SRC_ENDS = "v3-ends"
private const val SRC_POINTS = "v3-points"
private const val SRC_ESTIMATE = "v3-estimate"
private const val SRC_HEAT = "v3-heat"

private fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)
private val GREEN = Color.parseColor("#1e9e55")
private val YELLOW = Color.parseColor("#e0a100")
private val RED = Color.parseColor("#e5483f")
private val BLUE = Color.parseColor("#0064d4")

private fun signalHex(rssi: Int) = hex(
    when (signalOf(rssi)) {
        Signal.Strong -> GREEN
        Signal.Fair -> YELLOW
        Signal.Weak -> RED
    },
)

private fun LatLon.toPoint() = Point.fromLngLat(lon, lat)

/**
 * MapLibre with OpenFreeMap tiles (liberty by day, the dark style at night) and our layers on top:
 * the route with its start and end, sighting points sized and coloured by signal, the estimated
 * position and a density heatmap. The caller shows the OpenStreetMap/OpenFreeMap attribution.
 *
 * @param coveredFraction the share of the map's height hidden behind the sheet; the camera keeps
 *   the content above it
 */
@Composable
fun MapLibreMap(
    overlay: MapOverlay,
    coveredFraction: Float,
    onGesture: () -> Unit,
    onViewport: (GeoBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }
    var map by remember { androidx.compose.runtime.mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentOnViewport by rememberUpdatedState(onViewport)
    val currentCovered by rememberUpdatedState(coveredFraction)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentOnGesture()
            }
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                currentOnViewport(GeoBounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
            }
            map = m
        }
    }

    // The base style follows the system theme; our layers are re-added each time it loads.
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { style ->
            addLayers(style)
            styleVersion++
        }
    }

    LaunchedEffect(map, styleVersion, overlay) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        pushData(style, overlay)
    }

    LaunchedEffect(map, overlay.fitKey, styleVersion) {
        val m = map ?: return@LaunchedEffect
        val all = overlay.route.map { LatLng(it.lat, it.lon) } +
            overlay.points.map { LatLng(it.at.lat, it.at.lon) } +
            listOfNotNull(overlay.estimate?.let { LatLng(it.lat, it.lon) })
        if (all.isEmpty()) return@LaunchedEffect
        val height = mapView.height.takeIf { it > 0 } ?: 1600
        val bottom = (height * currentCovered).toInt() + 48
        if (all.size == 1 || all.all { it == all.first() }) {
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(all.first(), 17.0), 500)
        } else {
            val bounds = LatLngBounds.Builder().includes(all).build()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56, 220, 56, bottom), 600)
        }
    }

    AndroidView({ mapView }, modifier.fillMaxSize())
}

private fun addLayers(style: Style) {
    val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
    listOf(SRC_HEAT, SRC_ROUTE, SRC_ENDS, SRC_POINTS, SRC_ESTIMATE).forEach { style.addSource(GeoJsonSource(it, empty)) }
    style.addLayer(
        HeatmapLayer("v3-heat-layer", SRC_HEAT).withProperties(
            PropertyFactory.heatmapRadius(26f),
            PropertyFactory.heatmapOpacity(0.75f),
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.literal(0), Expression.rgba(255, 138, 0, 0.0),
                    Expression.literal(0.3), Expression.rgba(255, 138, 0, 0.3),
                    Expression.literal(1), Expression.rgba(235, 60, 0, 0.8),
                ),
            ),
        ),
    )
    style.addLayer(
        LineLayer("v3-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(Color.WHITE), PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        LineLayer("v3-route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(BLUE), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        CircleLayer("v3-ends-layer", SRC_ENDS).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        CircleLayer("v3-points-layer", SRC_POINTS).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.get("rssi"),
                    Expression.stop(-100, 3.5), Expression.stop(-40, 9.5),
                ),
            ),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleOpacity(0.88f),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(1f),
        ),
    )
    style.addLayer(
        CircleLayer("v3-estimate-halo", SRC_ESTIMATE).withProperties(
            PropertyFactory.circleRadius(30f), PropertyFactory.circleColor(BLUE), PropertyFactory.circleOpacity(0.16f),
            PropertyFactory.circleStrokeColor(BLUE), PropertyFactory.circleStrokeWidth(1f), PropertyFactory.circleStrokeOpacity(0.5f),
        ),
    )
    style.addLayer(
        CircleLayer("v3-estimate-dot", SRC_ESTIMATE).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(BLUE),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

private fun pushData(style: Style, o: MapOverlay) {
    fun source(id: String) = style.getSourceAs<GeoJsonSource>(id)
    source(SRC_HEAT)?.setGeoJson(FeatureCollection.fromFeatures(o.heat.map { Feature.fromGeometry(it.toPoint()) }))
    val route = if (o.route.size >= 2) {
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(o.route.map { it.toPoint() })))
    } else FeatureCollection.fromFeatures(emptyList<Feature>())
    source(SRC_ROUTE)?.setGeoJson(route)
    val ends = if (o.route.size >= 2) {
        listOf(
            Feature.fromGeometry(o.route.first().toPoint()).also { it.addStringProperty("color", hex(GREEN)) },
            Feature.fromGeometry(o.route.last().toPoint()).also { it.addStringProperty("color", hex(RED)) },
        )
    } else emptyList()
    source(SRC_ENDS)?.setGeoJson(FeatureCollection.fromFeatures(ends))
    source(SRC_POINTS)?.setGeoJson(
        FeatureCollection.fromFeatures(
            o.points.map { p ->
                Feature.fromGeometry(p.at.toPoint()).also {
                    it.addNumberProperty("rssi", p.rssi)
                    it.addStringProperty("color", signalHex(p.rssi))
                }
            },
        ),
    )
    source(SRC_ESTIMATE)?.setGeoJson(
        FeatureCollection.fromFeatures(listOfNotNull(o.estimate?.let { Feature.fromGeometry(it.toPoint()) })),
    )
}
