package edu.ucsd.sysnet.cluetoothscanner.map

import android.graphics.RectF
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.DeviceAddr
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.sources.GeoJsonSource

/** The map on the cluetooth-test AVD (docs/v2-api.md §6.6 Done when). */
@RunWith(AndroidJUnit4::class)
class MapInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var harness: MapHarness? = null

    @After fun tearDown() { harness?.close() }

    private fun harness(devices: List<Pair<Long, GeoPoint>>, dark: Boolean = false) =
        MapHarness(rule, GridQueries(devices), dark = dark).also { harness = it }

    private fun MapHarness.showAt(camera: CameraSnapshot) {
        onMain { state.show(Scope.session(SESSION), DeviceFilter(), routesFaint = false) }
        settle()
        onMain { state.restore(camera) }
        settle()
    }

    private fun MapHarness.rendered(vararg layers: String) = onMain {
        val v = surface.mapView
        map.queryRenderedFeatures(RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), *layers)
    }

    @Test fun m2_styleLayersInstalledInMapStyleJsonOrder_lightAndDark() {
        val h = harness(emptyList())
        for ((i, theme) in listOf(MapTheme.Light, MapTheme.Dark, MapTheme.Light).withIndex()) {
            if (i > 0) {
                rule.runOnIdle { h.dark.value = theme == MapTheme.Dark }
                h.awaitStyleLoads(i + 1)
            }
            // MapLibre's own (empty) legacy annotation layer is not part of the style
            val ids = h.onMain { h.map.style!!.layers.map { it.id }.filterNot { it.startsWith("org.maplibre.") } }
            val expected = listOf("background") + h.style.layers(theme).map { it.getValue("id").jsonPrimitive.content }
            assertEquals(expected, ids)
        }
    }

    @Test fun m7_hitBoxIs8dpAroundTheFinger() {
        val key = 0xAABBCC001122
        val h = harness(listOf(key to CENTER))
        h.showAt(CameraSnapshot(CENTER, 16.0))
        val p = h.screenOf(CENTER)
        // the point is radius 5 + stroke 1.5; the 8 dp box reaches 8 + 6.5 = 14.5 dp from its centre
        h.tap(android.graphics.PointF(p.x + 12 * h.density, p.y))
        h.waitUntil("device tap") { h.events.deviceTaps.isNotEmpty() }
        assertEquals(listOf(key), h.events.deviceTaps.toList())
        h.tap(android.graphics.PointF(p.x + 17 * h.density, p.y))
        h.waitUntil("empty tap") { h.events.emptyTaps.isNotEmpty() }
        assertEquals(1, h.events.deviceTaps.size)
        assertEquals(0, h.events.gestureStarts.size)
    }

    @Test fun m4_clusterTapEasesToExpansionZoomPlus02() {
        val a = GeoPoint(CENTER.lon, CENTER.lat)
        val b = GeoPoint(CENTER.lon + 0.0004, CENTER.lat)
        val h = harness(listOf(1L to a, 2L to b))
        h.showAt(CameraSnapshot(CENTER, 13.0))
        val clusters = h.rendered("clusters")
        assertEquals(1, clusters.size)
        val expansion = h.onMain { h.map.style!!.getSourceAs<GeoJsonSource>("devices")!!.getClusterExpansionZoom(clusters[0]) }
        val at = clusters[0].geometry() as org.maplibre.geojson.Point
        h.tap(h.screenOf(GeoPoint(at.longitude(), at.latitude())))
        h.waitUntil("cluster zoom") { h.onMain { kotlin.math.abs(h.map.cameraPosition.zoom - (expansion + 0.2)) < 1e-6 } }
        assertTrue(h.events.deviceTaps.isEmpty() && h.events.emptyTaps.isEmpty())
        // an app move: never a user gesture
        assertEquals(0, h.events.gestureStarts.size)
    }

    @Test fun m7_emptyTapAndATapBelowTheSlopIsNeverAPan() {
        val h = harness(listOf(1L to CENTER))
        h.showAt(CameraSnapshot(CENTER, 16.0))
        val before = h.onMain { h.map.cameraPosition.target }
        val c = h.viewCenter()
        // far from the only point; the finger wiggles 6 dp, under the 8 dp slop
        h.tap(android.graphics.PointF(c.x - 120 * h.density, c.y - 200 * h.density), wiggleDp = 6f)
        h.waitUntil("empty tap") { h.events.emptyTaps.isNotEmpty() }
        h.settle()
        assertEquals(before, h.onMain { h.map.cameraPosition.target })
        assertEquals(0, h.events.gestureStarts.size)
        assertTrue(h.events.idles.none { it })
        // past the slop it is a drag: a user gesture, an idle by the user, and no tap
        h.drag(android.graphics.PointF(c.x - 120 * h.density, c.y - 200 * h.density), 80 * h.density, 0f)
        h.settle()
        assertEquals(1, h.events.gestureStarts.size)
        assertTrue(h.events.idles.last())
        assertEquals(1, h.events.emptyTaps.size)
    }

    @Test fun pointsAndCellsSwitchOnA50kDeviceFake() {
        val devices = GridQueries.grid(50_000, spanDeg = 0.6)
        val queries = GridQueries(devices)
        val h = MapHarness(rule, queries).also { harness = it }
        // the whole set in view: more than 10 000 → Cells, drawn by the cloned layers
        h.showAt(CameraSnapshot(CENTER, 10.0))
        h.waitUntil("cells") { queries.lastMode.value == MapMode.Cells && h.rendered("cells").isNotEmpty() }
        assertTrue(h.rendered("points", "clusters").isEmpty())
        // zoomed in: under 10 000 in the viewport + 50 % → Points, clustered natively
        h.onMain { h.state.restore(CameraSnapshot(CENTER, 15.0)) }
        h.settle()
        h.waitUntil("points") { queries.lastMode.value == MapMode.Points && h.rendered("points", "clusters").isNotEmpty() }
        assertTrue(h.rendered("cells").isEmpty())
        // and back out
        h.onMain { h.state.restore(CameraSnapshot(CENTER, 10.0)) }
        h.settle()
        h.waitUntil("cells again") { queries.lastMode.value == MapMode.Cells && h.rendered("cells").isNotEmpty() }
        assertTrue(h.rendered("points", "clusters").isEmpty())
    }

    @Test fun m6_selectionDimsOthersAndShowsSightings() {
        val key = 0xAABBCC001122
        val h = harness(listOf(key to CENTER, 2L to GeoPoint(CENTER.lon + 0.002, CENTER.lat)))
        h.showAt(CameraSnapshot(CENTER, 16.0))
        h.onMain { h.state.setSelection(key) }
        h.waitUntil("sightings") { h.rendered("sightings").isNotEmpty() && h.rendered("point-selected").isNotEmpty() }
        val selected = h.rendered("point-selected")
        assertEquals(listOf(DeviceAddr.addrOf(key)), selected.map { it.getStringProperty("addr") })
        h.onMain { h.state.setSelection(null) }
        h.waitUntil("cleared") { h.rendered("sightings").isEmpty() && h.rendered("point-selected").isEmpty() }
    }

    /**
     * M-4, M-8: cluster counts and Start/End labels render offline, from the bundled glyphs. The ambient cache is
     * cleared (no glyphs fetched by earlier tests), seeded from the assets, and MapLibre is cut off the network.
     */
    @Test fun m4_m8_labelsRenderOfflineFromBundledGlyphs() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val cleared = java.util.concurrent.CountDownLatch(1)
        rule.runOnUiThread {
            org.maplibre.android.MapLibre.getInstance(context)
            org.maplibre.android.offline.OfflineManager.getInstance(context).clearAmbientCache(
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() = cleared.countDown()
                    override fun onError(message: String) = cleared.countDown()
                },
            )
        }
        assertTrue(cleared.await(10, java.util.concurrent.TimeUnit.SECONDS))
        rule.runOnUiThread {
            edu.ucsd.sysnet.cluetoothscanner.map.style.BundledGlyphs.seed(context, "Noto Sans Bold")
            org.maplibre.android.MapLibre.setConnected(false)
        }
        try {
            val a = GeoPoint(CENTER.lon, CENTER.lat)
            val b = GeoPoint(CENTER.lon + 0.0004, CENTER.lat)
            val route = listOf(GeoPoint(CENTER.lon - 0.01, CENTER.lat - 0.005), GeoPoint(CENTER.lon + 0.01, CENTER.lat + 0.005))
            val offline = edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets.fallbackStyleJson(edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme.Light)
            val h = MapHarness(rule, GridQueries(listOf(1L to a, 2L to b), route), baseStyleJson = offline).also { harness = it }
            h.showAt(CameraSnapshot(CENTER, 13.0))
            h.waitUntil("a cluster") { h.rendered("clusters").isNotEmpty() }
            h.waitUntil("the cluster's count label", 20_000) { h.rendered("cluster-count").isNotEmpty() }
            assertEquals("2", h.rendered("cluster-count").single().getStringProperty("point_count_abbreviated"))
            h.waitUntil("Start and End labels", 20_000) { h.rendered("end-labels").size == 2 }
            assertEquals(setOf("Start", "End"), h.rendered("end-labels").map { it.getStringProperty("label") }.toSet())
        } finally {
            rule.runOnUiThread { org.maplibre.android.MapLibre.setConnected(null) }
        }
    }
}
