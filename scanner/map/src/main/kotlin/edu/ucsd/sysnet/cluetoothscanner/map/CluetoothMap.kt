package edu.ucsd.sysnet.cluetoothscanner.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The main map (M-1 to M-11): MapLibre with the OpenFreeMap base style for the theme and map-style.json's layers
 * on top. [state] carries the data and camera and outlives this composable; [events] get the user's gestures,
 * idle cameras and taps. The map stops with the lifecycle (§1.2: on onStop :map stops MapLibre).
 *
 * baseStyleJson replaces the online base map (tests, offline previews).
 */
@Composable
fun CluetoothMap(
    state: CluetoothMapState,
    events: MapEvents,
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    baseStyleJson: String? = null,
) {
    val context = LocalContext.current
    val (spec, tokens) = remember { MapAssets.load(context) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        // TextureView: the map is an ordinary view in the window, so Compose can slide, clip and fade it with the
        // rest of the screen (L-1 slides the whole main screen; the tablet panel and sheets draw over it), and
        // its frames are the window's frames (JankStats).
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var surface by remember { mutableStateOf<MapLibreSurface?>(null) }
    var offline by remember { mutableStateOf(false) }
    state.events = events

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> {
                    mapView.onStart()
                    state.refillAfterTrim()
                }
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    state.trimMemory()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // the observer already paused and stopped the view if the lifecycle got there first; never twice
            val current = lifecycle.currentState
            if (current.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (current.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // §1.2.5: drop the cached point data when the UI is hidden or memory runs low
    DisposableEffect(context) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) state.trimMemory()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = state.trimMemory()
        }
        context.applicationContext.registerComponentCallbacks(callbacks)
        onDispose { context.applicationContext.unregisterComponentCallbacks(callbacks) }
    }

    DisposableEffect(mapView) {
        var attached: MapLibreSurface? = null
        mapView.getMapAsync { m ->
            val s = MapLibreSurface(mapView, m, state, tokens.dp("--drag-slop"))
            attached = s
            map = m
            surface = s
            state.attach(s)
        }
        // A base map that does not load (offline) is replaced by a plain background carrying the same layers.
        val failed = MapView.OnDidFailLoadingMapListener { if (map?.style?.isFullyLoaded != true) offline = true }
        mapView.addOnDidFailLoadingMapListener(failed)
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failed)
            attached?.let { state.detach(it); it.release() }
        }
    }

    val theme = if (dark) MapTheme.Dark else MapTheme.Light
    LaunchedEffect(map, theme, offline, baseStyleJson) {
        val m = map ?: return@LaunchedEffect
        val builder = when {
            baseStyleJson != null -> Style.Builder().fromJson(baseStyleJson)
            offline -> Style.Builder().fromJson(MapAssets.fallbackStyleJson(tokens, theme))
            else -> Style.Builder().fromUri(spec.baseStyleUrl(theme))
        }
        m.setStyle(builder) { style ->
            StyleInstaller.install(style, spec, theme)
            state.onStyleLoaded()
        }
    }

    val ui by state.ui.collectAsState()
    LaunchedEffect(surface, ui.cover) { surface?.setAttributionInset(ui.cover.bottom, ui.cover.left) }

    AndroidView(factory = { mapView }, modifier = modifier)
}
