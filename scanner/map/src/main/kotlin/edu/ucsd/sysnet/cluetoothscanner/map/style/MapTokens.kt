package edu.ucsd.sysnet.cluetoothscanner.map.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The few tokens.json values :map reads directly (G-1: never copied by hand). TEMPORARY for the components:
 * once :designsystem (C1) merges, the locate and warning buttons use its FloatButton and theme instead.
 */
class MapTokens private constructor(private val tokens: JsonObject) {
    /** "#AARRGGBB" of a color token. */
    fun argb(name: String, theme: MapTheme): Long =
        entry(name).getValue(if (theme == MapTheme.Dark) "darkArgb" else "lightArgb").jsonPrimitive.content.drop(1).toLong(16)

    /** A "12px" length token in dp. */
    fun dp(name: String): Float = entry(name).getValue("light").jsonPrimitive.content.removeSuffix("px").toFloat()

    /** A duration token in ms. */
    fun ms(name: String): Int = entry(name).getValue("ms").jsonPrimitive.content.toInt()

    private fun entry(name: String) = tokens.getValue(name).jsonObject

    companion object {
        fun parse(json: String) = MapTokens(Json.parseToJsonElement(json).jsonObject.getValue("tokens").jsonObject)
    }
}
