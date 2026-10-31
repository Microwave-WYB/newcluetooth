package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.maps.MapLibreMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

const val SESSION = "0190a0b0-0000-7000-8000-000000000001"
val CENTER = GeoPoint(-117.2340, 32.8801)

/** A blank base map with the OpenFreeMap glyphs: hermetic tests do not need tiles. */
const val BLANK_STYLE = """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#fcfcfc"}}]}"""

/**
 * A core stand-in for the map: [devices] with the §3.7 rule (≤ 10 000 in the viewport + 50 % → Points, else Cells
 * aggregated to about 1/16 of the viewport side).
 */
class GridQueries(private val devices: List<Pair<DeviceKey, GeoPoint>>, private val route: List<GeoPoint> = emptyList()) : UnusedCoreQueries() {
    override val live = MutableSharedFlow<LiveDelta>()
    override val events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 4)
    val lastMode = MutableStateFlow<MapMode?>(null)
    /** Whether the shown session is recording; Stop sets it false and emits SessionsChanged. */
    @Volatile var isLive = false

    override suspend fun sessionView(scope: Scope) = SessionView(
        scope, live = isLive, sessionIds = listOf(SESSION), deviceCount = devices.size,
        frame = MapGeometry.bboxOf(devices.map { it.second } + route), route = route, routesFaint = false, seq = 0,
    )
    override suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double) = """{"type":"FeatureCollection","features":[]}"""
    override suspend fun deviceRows(scope: Scope, keys: LongArray) = devices.filter { it.first in keys }.map { (k, at) ->
        DeviceRow(k, DeviceAddr.addrOf(k), null, null, emptyList(), emptyList(), false, false, null, -60, true, null, at, 0)
    }
    override suspend fun deviceSightings(scope: Scope, key: DeviceKey) =
        devices.filter { it.first == key }.map { Sighting(it.second, -60, 0) }

    override suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData {
        val area = MapGeometry.loadedArea(viewport)
        val inside = devices.filter { (_, p) -> p.lon in area.west..area.east && p.lat in area.south..area.north }
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        if (inside.size <= 10_000) {
            inside.forEachIndexed { i, (k, p) ->
                if (i > 0) sb.append(',')
                sb.append("""{"type":"Feature","properties":{"addr":"${DeviceAddr.addrOf(k)}","rssi":-60},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}""")
            }
            lastMode.value = MapMode.Points
            return MapData(MapMode.Points, inside.size, sb.append("]}").toString(), 0)
        }
        val cw = (viewport.east - viewport.west) / 16
        val ch = (viewport.north - viewport.south) / 16
        val cells = inside.groupBy { (_, p) -> floor(p.lon / cw).toLong() to floor(p.lat / ch).toLong() }
        cells.values.forEachIndexed { i, members ->
            if (i > 0) sb.append(',')
            val n = members.size
            val lon = members.sumOf { it.second.lon } / n
            val lat = members.sumOf { it.second.lat } / n
            val abbr = if (n >= 1000) "${n / 1000}k" else "$n"
            sb.append("""{"type":"Feature","properties":{"point_count":$n,"point_count_abbreviated":"$abbr"},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}""")
        }
        lastMode.value = MapMode.Cells
        return MapData(MapMode.Cells, inside.size, sb.append("]}").toString(), 0)
    }

    companion object {
        /** n devices on a square grid of side [spanDeg] around [center]. */
        fun grid(n: Int, spanDeg: Double, center: GeoPoint = CENTER): List<Pair<DeviceKey, GeoPoint>> {
            val side = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
            return (0 until n).map { i ->
                val x = i % side
                val y = i / side
                (0x100000000000L + i) to GeoPoint(center.lon - spanDeg / 2 + spanDeg * x / side, center.lat - spanDeg / 2 + spanDeg * y / side)
            }
        }
    }
}

class StaticLocation(me: GeoPoint?) : LocationStatusSource {
    override val status = MutableStateFlow(LocationStatus(LocationStatus.State.Ok, warn = false))
    override val myLocation = MutableStateFlow(me?.let { MyLocation(it, 5.0, 0) })
}

class RecordingEvents : MapEvents {
    val gestureStarts = CopyOnWriteArrayList<Long>()
    val idles = CopyOnWriteArrayList<Boolean>()
    val deviceTaps = CopyOnWriteArrayList<DeviceKey>()
    val emptyTaps = CopyOnWriteArrayList<Long>()
    override fun onUserGestureStart() { gestureStarts += SystemClock.uptimeMillis() }
    override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) { idles += byUser }
    override fun onDeviceTap(key: DeviceKey) { deviceTaps += key }
    override fun onEmptyTap() { emptyTaps += SystemClock.uptimeMillis() }
}

/** The map in a test activity, with helpers that inject real touches. */
class MapHarness(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    val queries: CoreQueries,
    val location: LocationStatusSource = StaticLocation(null),
    baseStyleJson: String? = BLANK_STYLE,
    dark: Boolean = false,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val events = RecordingEvents()
    val dark: MutableState<Boolean> = mutableStateOf(dark)
    val style = MapAssets.style(instrumentation.targetContext)
    lateinit var state: CluetoothMapState

    init {
        rule.runOnUiThread { state = CluetoothMapState(queries, location, style, scope) }
        rule.setContent { CluetoothMap(state, events, Modifier.fillMaxSize(), dark = this.dark.value, baseStyleJson = baseStyleJson) }
        awaitStyleLoads(1)
    }

    val surface: MapLibreSurface get() = state.attachedSurface as MapLibreSurface
    val map: MapLibreMap get() = surface.maplibre

    fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        rule.runOnUiThread { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    fun awaitStyleLoads(n: Int, timeoutMs: Long = 30_000) = waitUntil("style load $n", timeoutMs) {
        onMain { state.attachedSurface != null && state.styleLoads >= n && map.style?.isFullyLoaded == true }
    }

    /** Waits through the compose rule, so the composition's effects (setStyle) run while waiting. */
    fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition().also { if (!it) SystemClock.sleep(50) } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    /** Waits until the camera has been idle for a moment and the map has rendered. */
    fun settle(ms: Long = 600) {
        var last = onMain { map.cameraPosition }
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(ms)
            rule.waitForIdle()
            val now = onMain { map.cameraPosition }
            if (now == last) return
            last = now
        }
    }

    val density: Float get() = instrumentation.targetContext.resources.displayMetrics.density

    /** Map view coordinates (px) of a geographic point. */
    fun screenOf(p: GeoPoint): PointF = onMain {
        map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
    }

    fun viewCenter(): PointF = onMain { PointF(surface.mapView.width / 2f, surface.mapView.height / 2f) }

    /**
     * Touches go straight to the MapView (view coordinates), through MapLibre's own gesture detectors; the
     * emulator refuses window-targeted injection while another window (the keyguard) has focus.
     */
    private fun send(action: Int, down: Long, x: Float, y: Float) = onMain {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        surface.mapView.dispatchTouchEvent(e)
        e.recycle()
    }

    /** A tap; [wiggleDp] moves the finger that far before lifting (still within the slop when < 8). */
    fun tap(p: PointF, wiggleDp: Float = 0f) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, p.x, p.y)
        if (wiggleDp > 0) {
            val steps = 4
            for (i in 1..steps) {
                SystemClock.sleep(10)
                send(MotionEvent.ACTION_MOVE, down, p.x + wiggleDp * density * i / steps, p.y)
            }
        }
        SystemClock.sleep(40)
        send(MotionEvent.ACTION_UP, down, p.x + wiggleDp * density, p.y)
    }

    /** A drag of (dx, dy) px over [durationMs] at about 60 Hz. */
    fun drag(from: PointF, dx: Float, dy: Float, durationMs: Long = 300) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, from.x, from.y)
        val steps = (durationMs / 16).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, down, from.x + dx * i / steps, from.y + dy * i / steps)
        }
        // hold still before lifting, so the drag ends without a fling
        SystemClock.sleep(120)
        send(MotionEvent.ACTION_MOVE, down, from.x + dx, from.y + dy)
        send(MotionEvent.ACTION_UP, down, from.x + dx, from.y + dy)
    }

    fun close() = scope.cancel()
}
