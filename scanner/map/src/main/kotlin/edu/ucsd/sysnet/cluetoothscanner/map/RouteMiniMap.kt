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
import edu.ucsd.sysnet.cluetoothscanner.map.stub.Bbox
import edu.ucsd.sysnet.cluetoothscanner.map.stub.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import kotlinx.coroutines.delay
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

    LaunchedEffect(size, theme, baseStyleJson) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        MapLibre.getInstance(context.applicationContext)
        while (true) {
            val camera = miniMapCamera(currentRoute, currentFallback, size.width / density, size.height / density)
            if (camera != null) {
                holder[0]?.cancel()
                // logical size in dp at the screen's pixel ratio, so the labels have their 12 sp size
                val options = MapSnapshotter.Options((size.width / density).toInt(), (size.height / density).toInt())
                    .withPixelRatio(density)
                    .withLogo(false)
                    .withStyleBuilder(miniMapStyle(spec, theme, currentRoute, baseStyleJson))
                    .withCameraPosition(
                        CameraPosition.Builder().target(LatLng(camera.center.lat, camera.center.lon))
                            .zoom(camera.zoom).build(),
                    )
                options.showAttribution = false
                val snapshotter = MapSnapshotter(context, options)
                holder[0] = snapshotter
                snapshotter.start({ snapshot -> image = snapshot.bitmap }, { /* keep the previous image */ })
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
    val builder = if (baseStyleJson != null) Style.Builder().fromJson(baseStyleJson) else Style.Builder().fromUri(spec.baseStyleUrl(theme))
    builder.withSource(GeoJsonSource("route", GeoJson.routes(listOf(route))))
    builder.withSource(GeoJsonSource("ends", GeoJson.ends(route, spec.endProperties)))
    StyleInstaller.layers(spec.miniMapLayers(theme)).forEach(builder::withLayer)
    return builder
}

private const val REFRESH_MS = 10_000L
