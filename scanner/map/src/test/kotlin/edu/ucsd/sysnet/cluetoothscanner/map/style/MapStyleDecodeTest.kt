package edu.ucsd.sysnet.cluetoothscanner.map.style

import edu.ucsd.sysnet.cluetoothscanner.map.Design
import org.junit.Assert.assertTrue
import org.junit.Test

/** A broken map-style.json is a value, never a throw (the app then draws the base map without the overlay). */
class MapStyleDecodeTest {
    @Test fun `the committed export decodes`() =
        assertTrue(MapStyleSpec.decode(Design.styleJson) is MapStyleSpec.Decoded.Ok)

    @Test fun `broken or incomplete JSON is an Err`() {
        for (json in listOf("", "{", "{}", """{"baseStyle":{}}""", Design.styleJson.replace("\"runtime\"", "\"other\""))) {
            assertTrue(json.take(20), MapStyleSpec.decode(json) is MapStyleSpec.Decoded.Err)
        }
    }

    @Test fun `the fallback spec is complete and draws nothing`() {
        val f = MapStyleSpec.Fallback
        for (theme in MapTheme.entries) assertTrue(f.layers(theme).isEmpty() && f.miniMapLayers(theme).isEmpty())
        assertTrue(f.selection("00:00:00:00:00:00").isEmpty())
    }
}
