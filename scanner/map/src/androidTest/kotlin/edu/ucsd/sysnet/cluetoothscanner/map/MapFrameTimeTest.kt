package edu.ucsd.sysnet.cluetoothscanner.map

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §6.6: JankStats p95 < 32 ms while panning 10 k points. Uses the online base map unless the instrumentation
 * argument `blank=true` is given. Reports UI-thread frame times (JankStats) and MapLibre's own render times.
 */
@RunWith(AndroidJUnit4::class)
class MapFrameTimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var harness: MapHarness? = null

    @After fun tearDown() { harness?.close() }

    @Test fun panning10kPoints_jankStatsP95Under32ms() {
        val args = InstrumentationRegistry.getArguments()
        val blank = args.getString("blank") == "true"
        val queries = GridQueries(GridQueries.grid(10_000, spanDeg = 0.04))
        val h = MapHarness(rule, queries, baseStyleJson = if (blank) BLANK_STYLE else null).also { harness = it }
        h.onMain { h.state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false) }
        h.settle()
        h.onMain { h.state.restore(CameraSnapshot(CENTER, 14.5)) }
        h.settle()
        h.waitUntil("points") { queries.lastMode.value == MapMode.Points }
        h.settle(1500) // tiles and the 10 k points

        val ui = CopyOnWriteArrayList<Long>()
        val total = CopyOnWriteArrayList<Long>()
        val render = CopyOnWriteArrayList<Double>()
        lateinit var jank: JankStats
        val renderListener = MapView.OnDidFinishRenderingFrameListener { _, encodingMs, renderingMs -> render += encodingMs + renderingMs }
        h.onMain {
            jank = JankStats.createAndTrack(rule.activity.window) { f ->
                ui += f.frameDurationUiNanos
                (f as? androidx.metrics.performance.FrameDataApi31)?.let { total += it.frameDurationTotalNanos }
            }
            h.surface.mapView.addOnDidFinishRenderingFrameListener(renderListener)
        }

        val c = h.viewCenter()
        val d = h.density
        val span = 160 * d
        repeat(6) {
            h.drag(c, span, 0f); h.drag(c, 0f, span); h.drag(c, -span, 0f); h.drag(c, 0f, -span)
        }
        h.settle()
        h.onMain {
            jank.isTrackingEnabled = false
            h.surface.mapView.removeOnDidFinishRenderingFrameListener(renderListener)
        }

        val uiMs = ui.map { it / 1e6 }.sorted()
        val totalMs = total.map { it / 1e6 }.sorted()
        val renderMs = render.sorted()
        fun p(xs: List<Double>, q: Double) = if (xs.isEmpty()) Double.NaN else xs[((xs.size - 1) * q).toInt()]
        val report = "frames ui=${uiMs.size} p50=%.1f p95=%.1f p99=%.1f max=%.1f | total p95=%.1f | maplibre render=${renderMs.size} p50=%.1f p95=%.1f max=%.1f | base=%s"
            .format(p(uiMs, .5), p(uiMs, .95), p(uiMs, .99), uiMs.lastOrNull() ?: 0.0, p(totalMs, .95),
                p(renderMs, .5), p(renderMs, .95), renderMs.lastOrNull() ?: 0.0, if (blank) "blank" else "openfreemap")
        Log.i("MapFrameTime", report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "MapFrameTime: $report\n") })
        assertTrue("too few frames: $report", uiMs.size > 60)
        assertTrue(report, p(uiMs, .95) < 32.0)
    }
}
