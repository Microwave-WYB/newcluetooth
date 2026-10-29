package edu.ucsd.sysnet.cluetoothscanner.map.style

import android.content.Context
import org.maplibre.android.offline.OfflineManager
import java.net.URLEncoder

/**
 * The map's own labels (M-4 cluster and cell counts, M-8 Start and End) draw from glyphs bundled in the assets, so
 * they render offline and without waiting for the glyph server.
 *
 * The bundled range is put into MapLibre's ambient cache under the exact URL every style here asks for (the
 * OpenFreeMap base styles and the offline fallback share [GLYPHS_URL]), so a glyph request is a cache hit with or
 * without a network. MapLibre's resource transform cannot do this: it applies only to HTTP requests, which cannot
 * load asset:// URLs. The entry is written again on every process start, so cache eviction cannot lose it.
 */
object BundledGlyphs {
    /** The glyph URL template of the OpenFreeMap styles (liberty, dark) and of the fallback style. */
    const val GLYPHS_URL = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /** The ranges in assets/cluetooth-map-glyphs/<font dir>/ (see its NOTICE.md). */
    val RANGES = listOf("0-255")
    private const val DIR = "cluetooth-map-glyphs"
    /** Far enough ahead that MapLibre never revalidates the entry: about 100 years, in seconds. */
    private const val NEVER_EXPIRES_S = 100L * 365 * 24 * 3600
    @Volatile private var seeded = false

    /** Seeds the cache once per process. Call after MapLibre.getInstance and before a map or snapshotter loads. */
    fun install(context: Context, spec: MapStyleSpec) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            seed(context, spec.font)
            seeded = true
        }
    }

    /** Writes every bundled range of [font] into the ambient cache. */
    internal fun seed(context: Context, font: String) {
        val app = context.applicationContext
        val now = System.currentTimeMillis() / 1000
        for (range in RANGES) {
            val bytes = app.assets.open("$DIR/${dirOf(font)}/$range.pbf").use { it.readBytes() }
            OfflineManager.getInstance(app).putResourceWithUrl(urlFor(font, range), bytes, now, now + NEVER_EXPIRES_S, null, false)
        }
    }

    /** The URL MapLibre requests for [font] and [range]: the font stack percent-encoded, spaces as %20. */
    fun urlFor(font: String, range: String): String =
        GLYPHS_URL.replace("{fontstack}", URLEncoder.encode(font, "UTF-8").replace("+", "%20")).replace("{range}", range)

    /** "Noto Sans Bold" → "noto-sans-bold". */
    fun dirOf(font: String) = font.lowercase().replace(' ', '-')
}
