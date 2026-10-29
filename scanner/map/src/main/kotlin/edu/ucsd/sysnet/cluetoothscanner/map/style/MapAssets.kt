package edu.ucsd.sysnet.cluetoothscanner.map.style

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** map-style.json, copied from design/ui-v2 into the assets at build time (map/build.gradle.kts). */
object MapAssets {
    private const val DIR = "cluetooth-map"
    @Volatile private var cached: MapStyleSpec? = null

    fun style(context: Context): MapStyleSpec = cached ?: synchronized(this) {
        cached ?: context.applicationContext.assets.open("$DIR/map-style.json").bufferedReader().use { it.readText() }
            .let(MapStyleSpec::parse).also { cached = it }
    }

    /**
     * The style used when the base map cannot load (offline): the `--bg` background and the OpenFreeMap glyphs, so
     * the overlay layers still draw on a plain map.
     */
    fun fallbackStyleJson(theme: MapTheme): String {
        val colors = if (theme == MapTheme.Dark) Tokens.darkColors else Tokens.lightColors
        val bg = "#%06x".format(colors.bg.toArgb() and 0xFFFFFF)
        return """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{},""" +
            """"layers":[{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
    }
}
