package edu.ucsd.sysnet.cluetoothscanner.map.style

import edu.ucsd.sysnet.cluetoothscanner.map.Design
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** M-4, M-8: the map's label font is bundled, under the URL the styles request. */
class BundledGlyphsTest {
    private val font = Design.style.font

    @Test fun `map-style json names one font, and it is bundled for every range`() {
        assertEquals("Noto Sans Bold", font)
        for (range in BundledGlyphs.RANGES) {
            val file = File("src/main/assets/cluetooth-map-glyphs/${BundledGlyphs.dirOf(font)}/$range.pbf")
            assertTrue("$file is bundled", file.isFile && file.length() > 10_000)
        }
    }

    @Test fun `the seeded URL is the one MapLibre requests from the OpenFreeMap glyph template`() {
        // as logged by MapLibre: https://tiles.openfreemap.org/fonts/Noto%20Sans%20Bold/0-255.pbf
        assertEquals("https://tiles.openfreemap.org/fonts/Noto%20Sans%20Bold/0-255.pbf", BundledGlyphs.urlFor(font, "0-255"))
    }

    @Test fun `the offline fallback style uses the same glyph template`() {
        val fallback = Json.parseToJsonElement(MapAssets.fallbackStyleJson(MapTheme.Light)).jsonObject
        assertEquals(BundledGlyphs.GLYPHS_URL, fallback.getValue("glyphs").jsonPrimitive.content)
    }

    @Test fun `every label the map writes is in a bundled range`() {
        // counts (digits, "k", "M", ".") and the Start/End properties
        val text = "0123456789.kM" + Design.style.endProperties.joinToString("") { it.toString() }
        assertTrue(text.all { it.code in 0..255 })
    }
}
