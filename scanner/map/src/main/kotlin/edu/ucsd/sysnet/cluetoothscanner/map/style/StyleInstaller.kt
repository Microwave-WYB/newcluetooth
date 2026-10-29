package edu.ucsd.sysnet.cluetoothscanner.map.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Adds map-style.json's sources and layers to a MapLibre style unchanged (M-2): every paint and layout value and
 * filter is handed to the native style parser as the same JSON it would read from a style file. Only the layer
 * types map-style.js uses exist here; there is no HeatmapLayer (it crashed on an Adreno 640).
 */
object StyleInstaller {

    fun sources(spec: MapStyleSpec, only: Set<String>? = null): List<GeoJsonSource> =
        spec.sources.filter { only == null || it.id in only }.map { s ->
            val options = GeoJsonOptions()
            if (s.cluster) {
                options.withCluster(true)
                s.clusterRadius?.let(options::withClusterRadius)
                s.clusterMaxZoom?.let(options::withClusterMaxZoom)
            }
            GeoJsonSource(s.id, EMPTY_COLLECTION, options)
        }

    /** The layers MapLibre can build, in order; a layer of an unknown type is left out (MapStyleSpecTest rules it out). */
    fun layers(json: List<JsonObject>): List<Layer> = json.mapNotNull(::layer)

    /** Adds the sources and layers above the base map's own layers. */
    fun install(style: Style, spec: MapStyleSpec, theme: MapTheme) {
        sources(spec).forEach(style::addSource)
        layers(spec.layers(theme)).forEach(style::addLayer)
    }

    /** One map-style.json layer, or null when its id, source or type is missing or unknown. */
    fun layer(json: JsonObject): Layer? {
        val id = (json["id"] as? JsonPrimitive)?.content ?: return null
        val source = (json["source"] as? JsonPrimitive)?.content ?: return null
        val layer = when ((json["type"] as? JsonPrimitive)?.content) {
            "circle" -> CircleLayer(id, source)
            "line" -> LineLayer(id, source)
            "symbol" -> SymbolLayer(id, source)
            else -> return null
        }
        val props = ArrayList<PropertyValue<*>>()
        (json["layout"] as? JsonObject)?.forEach { (k, v) -> props += LayoutPropertyValue(k, native(v)) }
        (json["paint"] as? JsonObject)?.forEach { (k, v) -> props += PaintPropertyValue(k, native(v)) }
        layer.setProperties(*props.toTypedArray())
        json["filter"]?.let { setFilter(layer, it) }
        return layer
    }

    fun setFilter(layer: Layer, filter: JsonElement) {
        val e = Expression.raw(filter.toString())
        when (layer) {
            is CircleLayer -> layer.setFilter(e)
            is SymbolLayer -> layer.setFilter(e)
            is LineLayer -> layer.setFilter(e)
            // map-style.js only has circle, line and symbol layers; no other layer type is ever built here
            else -> Unit
        }
    }

    /** Applies map-style.js selectionProps (M-6) to the style's layers. */
    fun applySelection(style: Style, props: List<SelectionProp>) {
        for (p in props) {
            val layer = style.getLayer(p.layer) ?: continue
            when (p.kind) {
                SelectionProp.Kind.Paint -> p.property?.let { layer.setProperties(PaintPropertyValue(it, native(p.value))) }
                SelectionProp.Kind.Filter -> setFilter(layer, p.value)
            }
        }
    }

    /**
     * JSON as the values MapLibre's native converter reads (the same form Expression.toArray produces): arrays as
     * Object[], numbers as Float, plus strings and booleans. The native side decides what is an expression.
     */
    fun native(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            // a JSON literal that is neither string nor boolean is a number
            else -> e.doubleOrNull?.toFloat() ?: e.content
        }
        is JsonArray -> e.map(::native).toTypedArray()
        is JsonObject -> HashMap(e.mapValues { native(it.value) })
    }

    private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
}
