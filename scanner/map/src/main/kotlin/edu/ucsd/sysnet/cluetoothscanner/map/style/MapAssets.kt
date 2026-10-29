package edu.ucsd.sysnet.cluetoothscanner.map.style

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.atomic.AtomicReference
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** map-style.json, copied from design/ui-v2 into the assets at build time (map/build.gradle.kts). */
object MapAssets {
    private const val DIR = "cluetooth-map"
    private val cached = AtomicReference<StyleLoad?>(null)

    /** Loading the asset, as a value. */
    sealed interface StyleLoad {
        data class Loaded(val spec: MapStyleSpec) : StyleLoad
        /** The asset is missing or broken: the map draws its base style without the overlay. */
        data class Failed(val reason: String) : StyleLoad
    }

    fun load(context: Context): StyleLoad = cached.get() ?: read(context).also { cached.compareAndSet(null, it) }

    /** The overlay style, or [MapStyleSpec.Fallback] when the asset cannot be read. */
    fun style(context: Context): MapStyleSpec = when (val load = load(context)) {
        is StyleLoad.Loaded -> load.spec
        is StyleLoad.Failed -> MapStyleSpec.Fallback
    }

    private fun read(context: Context): StyleLoad {
        val json = try {
            context.applicationContext.assets.open("$DIR/map-style.json").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            return StyleLoad.Failed("map-style.json: $e")
        }
        return MapStyleSpec.decode(json)?.let { StyleLoad.Loaded(it) } ?: StyleLoad.Failed("map-style.json does not decode")
    }

    /**
     * The style used when the base map cannot load (offline): the `--bg` background and the OpenFreeMap glyph URL,
     * which [BundledGlyphs] serves from the cache, so the overlay layers and their labels still draw on a plain map.
     */
    fun fallbackStyleJson(theme: MapTheme): String {
        val colors = if (theme == MapTheme.Dark) Tokens.darkColors else Tokens.lightColors
        val bg = "#%06x".format(colors.bg.toArgb() and 0xFFFFFF)
        return """{"version":8,"glyphs":"${BundledGlyphs.GLYPHS_URL}","sources":{},""" +
            """"layers":[{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
    }
}
