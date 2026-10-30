package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.style.BundledGlyphs
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.map.style.StyleInstaller
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniFrame
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniMapEffect
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniMapEvent
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniMapReducer
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniMapState
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniSnapshotter
import edu.ucsd.sysnet.cluetoothscanner.map.model.MiniTarget
import edu.ucsd.sysnet.cluetoothscanner.map.model.SnapshotterId
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
 *
 * It fits :chrome's `RecorderControl(miniMap: @Composable (Modifier) -> Unit)` slot, which already clips it to the
 * concentric radius and handles the tap; :app passes it in, since :chrome does not depend on :map:
 * ```
 * RecorderControl(ui, actions, tablet, miniMap = { m -> RouteMiniMap(ui.route, fallback = myLocation?.at, modifier = m) })
 * ```
 */
@Composable
fun RouteMiniMap(
    route: List<GeoPoint>,
    fallback: GeoPoint?,
    modifier: Modifier = Modifier,
    dark: Boolean = CluetoothTheme.isDark,
    baseStyleJson: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val spec = remember { MapAssets.style(context) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val runner = remember { MiniMapRunner(context, spec) }
    val theme = if (dark) MapTheme.Dark else MapTheme.Light
    DisposableEffect(runner) { onDispose { runner.dispatch(MiniMapEvent.Disposed) } }
    LaunchedEffect(route, fallback) { runner.dispatch(MiniMapEvent.Inputs(route, fallback)) }

    // refreshed when it appears and every 10 s while shown; a size is used once it held for FRAME_SETTLE_MS (the
    // recorder panel's morph resizes it every frame, and each new frame means a new native snapshotter)
    LaunchedEffect(size, density, theme, baseStyleJson) {
        val frame = if (size.width == 0 || size.height == 0) null else MiniFrame(size.width, size.height, density, theme, baseStyleJson)
        if (frame == null) return@LaunchedEffect runner.dispatch(MiniMapEvent.Configure(null))
        delay(MiniMapReducer.FRAME_SETTLE_MS)
        runner.dispatch(MiniMapEvent.Configure(frame))
        while (true) {
            runner.dispatch(MiniMapEvent.Tick)
            delay(MiniMapReducer.REFRESH_MS)
        }
    }

    Box(modifier.onSizeChanged { size = it }) {
        runner.image?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop) }
    }
}

/**
 * Strong references to every MapSnapshotter a mini map has built and not yet released, for the whole process (main
 * thread only). MapLibre holds a snapshotter only weakly, and a snapshotter collected while its style loads crashes
 * natively (see MiniSnapshotter); a disposed mini map's runner would be garbage too, so the keeper, not the
 * composition, holds them until the reducer releases them.
 */
internal object SnapshotterKeeper {
    private val held = HashSet<MapSnapshotter>()
    fun hold(s: MapSnapshotter) { held += s }
    fun release(s: MapSnapshotter) { held -= s }
    val size: Int get() = held.size
}

/**
 * The mini map's runner: it owns the MapSnapshotters (main thread) and executes [MiniMapReducer]'s effects; every
 * MapLibre call that throws becomes a Failed event.
 */
private class MiniMapRunner(private val context: android.content.Context, private val spec: MapStyleSpec) {
    private var state = MiniMapState.Initial
    private val snapshotters = HashMap<SnapshotterId, MapSnapshotter>()
    /** Snapshotters with a start whose callback has not come yet (a release of one would be the crash). */
    private val running = HashSet<SnapshotterId>()
    var image by mutableStateOf<Bitmap?>(null)
        private set

    /** Reduces [event] and executes its effects; each effect runs in [guarded], so a throw is a Failed event. */
    fun dispatch(event: MiniMapEvent) {
        val step = try {
            MiniMapReducer.reduce(state, event)
        } catch (e: Exception) {
            return
        }
        state = step.state
        for (effect in step.effects) execute(effect)
    }

    private fun execute(effect: MiniMapEffect) {
        when (effect) {
            is MiniMapEffect.Create -> guarded(effect.id) {
                MapLibre.getInstance(context.applicationContext)
                BundledGlyphs.install(context, spec)
                val f = effect.frame
                // logical size in dp at the screen's pixel ratio, so the labels have their 12 sp size
                val options = MapSnapshotter.Options(f.widthDp.toInt(), f.heightDp.toInt())
                    .withPixelRatio(f.density)
                    .withLogo(false)
                    .withStyleBuilder(miniMapStyle(spec, f.theme, effect.target, f.baseStyleJson))
                    .withCameraPosition(position(effect.target.camera))
                options.showAttribution = false
                val s = MapSnapshotter(context, options)
                snapshotters[effect.id] = s
                SnapshotterKeeper.hold(s)
                MiniMapStats.created++
            }
            is MiniMapEffect.Update -> guarded(effect.id) {
                val s = snapshotters[effect.id] ?: return@guarded
                (s.getSource("route") as? GeoJsonSource)?.setGeoJson(GeoJson.routes(listOf(effect.target.route)))
                (s.getSource("ends") as? GeoJsonSource)?.setGeoJson(endsJson(spec, effect.target))
                s.setCameraPosition(position(effect.target.camera))
            }
            is MiniMapEffect.Start -> guarded(effect.id) {
                val s = snapshotters[effect.id] ?: return@guarded dispatch(MiniMapEvent.Failed(effect.id))
                running += effect.id
                s.start(
                    { snapshot ->
                        running -= effect.id
                        MiniMapStats.snapshots++
                        dispatch(MiniMapEvent.Done(effect.id, effect.target))
                        // only the current snapshotter's picture is shown (a retired one was for another frame)
                        if ((state.snapshotter as? MiniSnapshotter.Idle)?.id == effect.id) image = snapshot.bitmap
                    },
                    {
                        running -= effect.id
                        dispatch(MiniMapEvent.Failed(effect.id))
                    },
                )
            }
            is MiniMapEffect.Release -> {
                if (effect.id in running) MiniMapStats.releasedWhileRunning++
                snapshotters.remove(effect.id)?.let(SnapshotterKeeper::release)
            }
        }
    }

    /** The runner's one wrapper around every MapLibre call; a throw fails the snapshot of [id]. */
    private fun guarded(id: SnapshotterId, call: () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            running -= id
            dispatch(MiniMapEvent.Failed(id))
        }
    }

    private fun position(camera: CameraSnapshot) =
        CameraPosition.Builder().target(LatLng(camera.center.lat, camera.center.lon)).zoom(camera.zoom).build()
}

/** The prototype's miniMap framing: the route's bounds with 28 dp padding, at most zoom 16. */
internal fun miniMapCamera(route: List<GeoPoint>, fallback: GeoPoint?, widthDp: Float, heightDp: Float) =
    MiniMapReducer.camera(route, fallback, widthDp, heightDp)

/** M-8: the target's Start and End, or none when the reducer hid them (the session barely moved). */
private fun endsJson(spec: MapStyleSpec, target: MiniTarget) =
    if (target.showEnds) GeoJson.ends(target.route, spec.endProperties) else GeoJson.EMPTY

private fun miniMapStyle(spec: MapStyleSpec, theme: MapTheme, target: MiniTarget, baseStyleJson: String?): Style.Builder {
    val route = target.route
    if (baseStyleJson != null) return Style.Builder().fromJson(miniMapStyleJson(baseStyleJson, spec, theme, target))
    val builder = Style.Builder().fromUri(spec.baseStyleUrl(theme))
    builder.withSource(GeoJsonSource("route", GeoJson.routes(listOf(route))))
    builder.withSource(GeoJsonSource("ends", endsJson(spec, target)))
    StyleInstaller.layers(spec.miniMapLayers(theme)).forEach(builder::withLayer)
    return builder
}

/**
 * A JSON base style with the mini map's sources and layers written into it. MapLibre's snapshotter loads a JSON
 * style inside its constructor and then adds builder sources through a native peer that does not exist yet
 * ("invalid native peer"), so with a JSON base nothing goes through the builder.
 */
internal fun miniMapStyleJson(baseStyleJson: String, spec: MapStyleSpec, theme: MapTheme, target: MiniTarget): String {
    val route = target.route
    val base = Json.parseToJsonElement(baseStyleJson).jsonObject
    fun source(geojson: String) = JsonObject(mapOf("type" to JsonPrimitive("geojson"), "data" to Json.parseToJsonElement(geojson)))
    val sources = JsonObject((base["sources"]?.jsonObject ?: JsonObject(emptyMap())) + mapOf(
        "route" to source(GeoJson.routes(listOf(route))),
        "ends" to source(endsJson(spec, target)),
    ))
    val layers = JsonArray((base["layers"]?.jsonArray ?: JsonArray(emptyList())) + spec.miniMapLayers(theme))
    return JsonObject(base + mapOf("sources" to sources, "layers" to layers)).toString()
}

/** Instrumented tests check that refreshes reuse one snapshotter. */
internal object MiniMapStats {
    var created = 0
    var snapshots = 0
    /** Releases of a snapshotter whose start had not called back; any is the Note10 crash's precondition. */
    var releasedWhileRunning = 0
}
