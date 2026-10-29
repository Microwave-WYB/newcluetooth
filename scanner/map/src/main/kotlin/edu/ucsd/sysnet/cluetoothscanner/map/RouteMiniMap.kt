package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Padding
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * R-5: the recorder panel's route mini map. Non-interactive: a MapSnapshotter image of the route with Start and End
 * (M-8) and no caption, refreshed when it appears (the panel opens) and every 10 s while shown. With no route yet
 * it shows [fallback] (the user's location). Taps belong to the caller (lane C: collapse, close, fit).
 */
@Composable
fun RouteMiniMap(
    route: List<GeoPoint>,
    fallback: GeoPoint?,
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    baseStyleJson: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val spec = remember { MapAssets.style(context) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    val currentRoute by rememberUpdatedState(route)
    val currentFallback by rememberUpdatedState(fallback)
    val theme = if (dark) MapTheme.Dark else MapTheme.Light
    val holder = remember { arrayOfNulls<MapSnapshotter>(1) }
    DisposableEffect(Unit) { onDispose { holder[0]?.cancel(); holder[0] = null } }

    // One snapshotter per size and theme, reused: each refresh only moves its camera and updates its two sources,
    // and a refresh with the same route and camera takes no new snapshot.
    LaunchedEffect(size, theme, baseStyleJson) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        MapLibre.getInstance(context.applicationContext)
        holder[0]?.cancel()
        holder[0] = null
        var rendered: Pair<List<GeoPoint>, CameraSnapshot>? = null
        var inFlight = false
        while (true) {
            val r = currentRoute
            val camera = miniMapCamera(r, currentFallback, size.width / density, size.height / density)
            if (camera != null && !inFlight && rendered != (r to camera)) {
                val position = CameraPosition.Builder().target(LatLng(camera.center.lat, camera.center.lon)).zoom(camera.zoom).build()
                var snapshotter = holder[0]
                if (snapshotter == null) {
                    // logical size in dp at the screen's pixel ratio, so the labels have their 12 sp size
                    val options = MapSnapshotter.Options((size.width / density).toInt(), (size.height / density).toInt())
                        .withPixelRatio(density)
                        .withLogo(false)
                        .withStyleBuilder(miniMapStyle(spec, theme, r, baseStyleJson))
                        .withCameraPosition(position)
                    options.showAttribution = false
                    snapshotter = MapSnapshotter(context, options).also { holder[0] = it }
                    MiniMapStats.created++
                } else {
                    (snapshotter.getSource("route") as? GeoJsonSource)?.setGeoJson(GeoJson.routes(listOf(r)))
                    (snapshotter.getSource("ends") as? GeoJsonSource)?.setGeoJson(GeoJson.ends(r, spec.endProperties))
                    snapshotter.setCameraPosition(position)
                }
                inFlight = true
                val target = r to camera
                snapshotter.start(
                    { snapshot -> image = snapshot.bitmap; rendered = target; inFlight = false; MiniMapStats.snapshots++ },
                    { inFlight = false /* keep the previous image */ },
                )
            }
            delay(REFRESH_MS)
        }
    }

    Box(modifier.onSizeChanged { size = it }) {
        image?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop) }
    }
}

/** The prototype's miniMap framing: the route's bounds with 28 dp padding, at most zoom 16. */
internal fun miniMapCamera(route: List<GeoPoint>, fallback: GeoPoint?, widthDp: Float, heightDp: Float) =
    (MapGeometry.bboxOf(route) ?: fallback?.let { Bbox(it.lon, it.lat, it.lon, it.lat) })?.let { bbox ->
        MapGeometry.fit(bbox, Viewport(widthDp.toDouble(), heightDp.toDouble()), Padding(28.0, 28.0, 28.0, 28.0), maxZoom = 16.0)
    }

private fun miniMapStyle(spec: MapStyleSpec, theme: MapTheme, route: List<GeoPoint>, baseStyleJson: String?): Style.Builder {
    if (baseStyleJson != null) return Style.Builder().fromJson(miniMapStyleJson(baseStyleJson, spec, theme, route))
    val builder = Style.Builder().fromUri(spec.baseStyleUrl(theme))
    builder.withSource(GeoJsonSource("route", GeoJson.routes(listOf(route))))
    builder.withSource(GeoJsonSource("ends", GeoJson.ends(route, spec.endProperties)))
    StyleInstaller.layers(spec.miniMapLayers(theme)).forEach(builder::withLayer)
    return builder
}

/**
 * A JSON base style with the mini map's sources and layers written into it. MapLibre's snapshotter loads a JSON
 * style inside its constructor and then adds builder sources through a native peer that does not exist yet
 * ("invalid native peer"), so with a JSON base nothing goes through the builder.
 */
internal fun miniMapStyleJson(baseStyleJson: String, spec: MapStyleSpec, theme: MapTheme, route: List<GeoPoint>): String {
    val base = Json.parseToJsonElement(baseStyleJson).jsonObject
    fun source(geojson: String) = JsonObject(mapOf("type" to JsonPrimitive("geojson"), "data" to Json.parseToJsonElement(geojson)))
    val sources = JsonObject((base["sources"]?.jsonObject ?: JsonObject(emptyMap())) + mapOf(
        "route" to source(GeoJson.routes(listOf(route))),
        "ends" to source(GeoJson.ends(route, spec.endProperties)),
    ))
    val layers = JsonArray((base["layers"]?.jsonArray ?: JsonArray(emptyList())) + spec.miniMapLayers(theme))
    return JsonObject(base + mapOf("sources" to sources, "layers" to layers)).toString()
}

private const val REFRESH_MS = 10_000L

/** Instrumented tests check that refreshes reuse one snapshotter. */
internal object MiniMapStats {
    var created = 0
    var snapshots = 0
}
