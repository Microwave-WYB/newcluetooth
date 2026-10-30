package edu.ucsd.sysnet.cluetoothscanner.map.style

import edu.ucsd.sysnet.cluetoothscanner.map.Design
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-1, M-2, M-3, M-4, M-5, M-6, M-8: the style is map-style.json, unchanged, per theme. */
class MapStyleSpecTest {
    private val raw = Json.parseToJsonElement(Design.styleJson).jsonObject
    private val spec = Design.style

    private fun rawLayers(theme: String) = raw.getValue("layers").jsonObject.getValue(theme).jsonArray.map { it.jsonObject }
    private fun ids(layers: List<JsonObject>) = layers.map { it.getValue("id").jsonPrimitive.content }

    @Test fun `M-2 layer ids and order equal map-style json per theme, with the cells clones after cluster-count`() {
        for (theme in MapTheme.entries) {
            val expected = ids(rawLayers(theme.key)).toMutableList()
            expected.addAll(expected.indexOf("cluster-count") + 1, listOf("cells", "cell-count"))
            assertEquals(expected, ids(spec.layers(theme)))
        }
        assertEquals(
            listOf("route", "density", "clusters", "cluster-count", "cells", "cell-count", "points", "sightings",
                "point-selected", "me", "end-dots", "end-labels"),
            ids(spec.layers(MapTheme.Light)),
        )
    }

    @Test fun `M-2 every exported layer is passed through unchanged`() {
        for (theme in MapTheme.entries) assertEquals(rawLayers(theme.key), spec.styleLayers(theme))
    }

    @Test fun `only the types the installer knows, and the one heatmap is density, below the devices`() {
        for (theme in MapTheme.entries) {
            val layers = spec.layers(theme)
            val types = layers.map { it.getValue("type").jsonPrimitive.content }.toSet()
            assertTrue("$types", types.all { it in setOf("circle", "line", "symbol", "heatmap") })
            val ids = layers.map { it.getValue("id").jsonPrimitive.content }
            assertEquals(listOf("density"), layers.filter { it.getValue("type").jsonPrimitive.content == "heatmap" }.map { it.getValue("id").jsonPrimitive.content })
            assertTrue(ids.indexOf("density") < ids.indexOf("clusters") && ids.indexOf("density") < ids.indexOf("points"))
        }
    }

    @Test fun `cells layers clone clusters and cluster-count with identical paint, layout and filter`() {
        for (theme in MapTheme.entries) {
            val all = spec.layers(theme).associateBy { it.getValue("id").jsonPrimitive.content }
            for ((clone, original) in listOf("cells" to "clusters", "cell-count" to "cluster-count")) {
                val c = all.getValue(clone)
                val o = all.getValue(original)
                assertEquals("cells", c.getValue("source").jsonPrimitive.content)
                for (k in listOf("type", "paint", "layout", "filter")) assertEquals("$clone.$k", o[k], c[k])
            }
        }
    }

    @Test fun `M-1 base styles are OpenFreeMap liberty and dark`() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", spec.baseStyleUrl(MapTheme.Light))
        assertEquals("https://tiles.openfreemap.org/styles/dark", spec.baseStyleUrl(MapTheme.Dark))
    }

    @Test fun `M-2 M-4 sources, devices clustered at radius 50 to zoom 18, plus cells`() {
        assertEquals(listOf("route", "ends", "density", "devices", "sightings", "me", "cells"), spec.sources.map { it.id })
        val devices = spec.sources.single { it.id == "devices" }
        assertTrue(devices.cluster)
        assertEquals(50, devices.clusterRadius)
        assertEquals(18, devices.clusterMaxZoom)
        assertFalse(spec.sources.single { it.id == "cells" }.cluster)
    }

    @Test fun `M-8 route opacities and M-6 dim come from the tokens`() {
        assertEquals(0.35, spec.routeOpacitySession, 0.0)
        assertEquals(0.18, spec.routeOpacityAllSessions, 0.0)
        assertEquals(0.42, spec.dim, 0.0)
    }

    @Test fun `M-6 selection equals map-style js selectionProps for an address`() {
        val addr = "AA:BB:CC:00:11:22"
        val match = JsonArray(listOf(JsonPrimitive("=="), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("addr"))), JsonPrimitive(addr)))
        val case = JsonArray(listOf(JsonPrimitive("case"), match, JsonPrimitive(1), JsonPrimitive(0.42)))
        val dim = JsonPrimitive(0.42)
        val expected = listOf(
            SelectionProp("points", SelectionProp.Kind.Paint, "circle-opacity", case),
            SelectionProp("points", SelectionProp.Kind.Paint, "circle-stroke-opacity", case),
            SelectionProp("clusters", SelectionProp.Kind.Paint, "circle-opacity", dim),
            SelectionProp("clusters", SelectionProp.Kind.Paint, "circle-stroke-opacity", dim),
            SelectionProp("cluster-count", SelectionProp.Kind.Paint, "text-opacity", dim),
            SelectionProp("point-selected", SelectionProp.Kind.Filter, null, match),
            SelectionProp("cells", SelectionProp.Kind.Paint, "circle-opacity", dim),
            SelectionProp("cells", SelectionProp.Kind.Paint, "circle-stroke-opacity", dim),
            SelectionProp("cell-count", SelectionProp.Kind.Paint, "text-opacity", dim),
        )
        assertEquals(expected, spec.selection(addr))
    }

    @Test fun `M-6 clearing restores every opacity and empties the selected filter`() {
        val one = JsonPrimitive(1)
        val none = JsonArray(listOf(JsonPrimitive("=="), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("addr"))), JsonPrimitive("")))
        val expected = listOf(
            SelectionProp("points", SelectionProp.Kind.Paint, "circle-opacity", one),
            SelectionProp("points", SelectionProp.Kind.Paint, "circle-stroke-opacity", one),
            SelectionProp("clusters", SelectionProp.Kind.Paint, "circle-opacity", one),
            SelectionProp("clusters", SelectionProp.Kind.Paint, "circle-stroke-opacity", one),
            SelectionProp("cluster-count", SelectionProp.Kind.Paint, "text-opacity", one),
            SelectionProp("point-selected", SelectionProp.Kind.Filter, null, none),
            SelectionProp("cells", SelectionProp.Kind.Paint, "circle-opacity", one),
            SelectionProp("cells", SelectionProp.Kind.Paint, "circle-stroke-opacity", one),
            SelectionProp("cell-count", SelectionProp.Kind.Paint, "text-opacity", one),
        )
        assertEquals(expected, spec.selection(null))
    }

    /**
     * M-5 (design b0fa4d5): the ramp runs transparent -> blue -> cyan -> yellow -> red in both themes and never ends
     * near white, so the densest spot reads as the hottest instead of a hole in the light base map.
     */
    @Test fun `M-5 heatmap ramp is transparent, blue, cyan, yellow, red and its top is not near white`() {
        for (theme in MapTheme.entries) {
            val density = spec.layers(theme).single { it.getValue("id").jsonPrimitive.content == "density" }
            val ramp = density.getValue("paint").jsonObject.getValue("heatmap-color").jsonArray
            assertEquals(Json.parseToJsonElement("""["heatmap-density"]"""), ramp[2])
            val stops = ramp.drop(3).chunked(2).map { (at, color) -> at.jsonPrimitive.content.toDouble() to rgba(color.jsonPrimitive.content) }
            assertEquals(0.0, stops.first().first, 0.0)
            assertEquals(1.0, stops.last().first, 0.0)
            assertTrue("stops ascend", stops.zipWithNext().all { (a, b) -> a.first < b.first })
            assertEquals("transparent at 0", 0.0, stops.first().second[3], 0.0)
            assertEquals(listOf("blue", "cyan", "yellow", "red"), stops.drop(1).map { hue(it.second) })
            val top = stops.last().second
            assertTrue("top stop luminance ${luminance(top)}", luminance(top) < 0.7)
        }
    }

    private fun rgba(css: String): List<Double> {
        val v = Regex("""rgba\(([^)]*)\)""").find(css)?.groupValues?.get(1)?.split(",")?.map { it.trim().toDouble() }
            ?: throw AssertionError("not rgba(): $css")
        return listOf(v[0] / 255, v[1] / 255, v[2] / 255, v[3])
    }

    /** WCAG relative luminance of an sRGB color. */
    private fun luminance(c: List<Double>): Double {
        fun lin(x: Double) = if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
    }

    private fun hue(c: List<Double>): String {
        val (r, g, b) = c
        return when {
            b > 0.6 && r < 0.3 && g < 0.6 -> "blue"
            b > 0.6 && g > 0.6 && r < 0.3 -> "cyan"
            r > 0.8 && g > 0.6 && b < 0.3 -> "yellow"
            r > 0.6 && g < 0.3 && b < 0.3 -> "red"
            else -> "other $c"
        }
    }

    @Test fun `M-3 M-4 M-5 M-8 paint values come from the tokens, light and dark`() {
        for (theme in MapTheme.entries) {
            val t = Design.tokens
            val hex = { name: String -> "#%06x".format(t.argb(name, theme) and 0xFFFFFF) }
            val layers = spec.layers(theme).associateBy { it.getValue("id").jsonPrimitive.content }
            fun paint(layer: String, key: String) = layers.getValue(layer).getValue("paint").jsonObject.getValue(key)
            assertEquals(JsonPrimitive(hex("--blue")), paint("points", "circle-color"))
            assertEquals(JsonPrimitive(5), paint("points", "circle-radius"))
            assertEquals(JsonPrimitive(hex("--blue")), paint("clusters", "circle-color"))
            // M-5: the native heatmap, the same in both themes: weight 1 per device (a cell's count), radius 24,
            // opacity 0.6 fading out from zoom 16 to 18
            assertEquals(JsonPrimitive("heatmap"), layers.getValue("density").getValue("type"))
            assertEquals(JsonPrimitive(24), paint("density", "heatmap-radius"))
            assertEquals(Json.parseToJsonElement("""["coalesce",["get","point_count"],1]"""), paint("density", "heatmap-weight"))
            assertEquals(Json.parseToJsonElement("""["interpolate",["linear"],["zoom"],16,0.6,18,0]"""), paint("density", "heatmap-opacity"))
            assertEquals(JsonPrimitive(hex("--map-route")), paint("route", "line-color"))
        }
    }
}
