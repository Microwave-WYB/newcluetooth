package edu.ucsd.sysnet.cluetoothscanner.map.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    fun layers(json: List<JsonObject>): List<Layer> = json.map(::layer)

    /** Adds the sources and layers above the base map's own layers. */
    fun install(style: Style, spec: MapStyleSpec, theme: MapTheme) {
        sources(spec).forEach(style::addSource)
        layers(spec.layers(theme)).forEach(style::addLayer)
    }

    fun layer(json: JsonObject): Layer {
        val id = json.getValue("id").jsonPrimitive.content
        val source = json.getValue("source").jsonPrimitive.content
        val layer = when (val type = json.getValue("type").jsonPrimitive.content) {
            "circle" -> CircleLayer(id, source)
            "line" -> LineLayer(id, source)
            "symbol" -> SymbolLayer(id, source)
            else -> error("map-style.json layer $id has unsupported type $type")
        }
        val props = ArrayList<PropertyValue<*>>()
        json["layout"]?.jsonObject?.forEach { (k, v) -> props += LayoutPropertyValue(k, native(v)) }
        json["paint"]?.jsonObject?.forEach { (k, v) -> props += PaintPropertyValue(k, native(v)) }
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
            else -> error("no filter on ${layer.id}")
        }
    }

    /** Applies map-style.js selectionProps (M-6) to the style's layers. */
    fun applySelection(style: Style, props: List<SelectionProp>) {
        for (p in props) {
            val layer = style.getLayer(p.layer) ?: continue
            when (p.kind) {
                SelectionProp.Kind.Paint -> layer.setProperties(PaintPropertyValue(requireNotNull(p.property), native(p.value)))
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
            else -> e.doubleOrNull?.toFloat() ?: error("not a number: $e")
        }
        is JsonArray -> e.map(::native).toTypedArray()
        is JsonObject -> HashMap(e.mapValues { native(it.value) })
    }

    private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
}
