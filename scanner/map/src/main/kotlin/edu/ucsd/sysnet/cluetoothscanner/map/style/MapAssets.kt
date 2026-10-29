package edu.ucsd.sysnet.cluetoothscanner.map.style

import android.content.Context

/** map-style.json and tokens.json, copied from design/ui-v2 into the assets at build time (map/build.gradle.kts). */
object MapAssets {
    private const val DIR = "cluetooth-map"
    @Volatile private var cached: Pair<MapStyleSpec, MapTokens>? = null

    fun load(context: Context): Pair<MapStyleSpec, MapTokens> = cached ?: synchronized(this) {
        cached ?: run {
            val assets = context.applicationContext.assets
            fun read(name: String) = assets.open("$DIR/$name").bufferedReader().use { it.readText() }
            (MapStyleSpec.parse(read("map-style.json")) to MapTokens.parse(read("tokens.json"))).also { cached = it }
        }
    }

    fun style(context: Context) = load(context).first
    fun tokens(context: Context) = load(context).second

    /**
     * The style used when the base map cannot load (offline): the `--bg` background and the OpenFreeMap glyphs, so
     * the overlay layers still draw on a plain map.
     */
    fun fallbackStyleJson(tokens: MapTokens, theme: MapTheme): String {
        val bg = "#%06x".format(tokens.argb("--bg", theme) and 0xFFFFFF)
        return """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{},""" +
            """"layers":[{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
    }
}
