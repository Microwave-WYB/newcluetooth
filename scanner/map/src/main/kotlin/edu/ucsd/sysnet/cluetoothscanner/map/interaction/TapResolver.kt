package edu.ucsd.sysnet.cluetoothscanner.map.interaction

import edu.ucsd.sysnet.cluetoothscanner.map.stub.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.map.stub.GeoPoint

/** What a tap does (M-7, M-4, §6.6 cells). */
sealed interface TapAction {
    /** A point: open its device (S-4). */
    data class OpenDevice(val key: DeviceKey) : TapAction
    /** A cluster: ease to its expansion zoom + 0.2 (M-4). */
    data class ExpandCluster(val hit: Hit) : TapAction
    /** A cell (Cells mode): zoom in by 2 around it. */
    data class ZoomCell(val at: GeoPoint) : TapAction
    /** A sighting of the selected device: nothing, and not an empty-map tap either. */
    data object Ignore : TapAction
    /** Empty map: pop the top pushed sheet, else clear the selection (routed by :app). */
    data object Empty : TapAction
}

/** One rendered feature under the finger's 8 dp box, with the layer it was found in. */
data class Hit(val layer: String, val addr: String?, val at: GeoPoint?, val native: Any? = null)

object TapResolver {
    /** Hit-tested top to bottom; the first layer with a feature wins, as the prototype's queryRenderedFeatures[0]. */
    val LAYERS_TOP_DOWN = listOf("sightings", "points", "cells", "clusters")

    /** M-7: the hit box is 8 dp around the finger. */
    const val HIT_BOX_DP = 8f

    fun resolve(hit: Hit?): TapAction = when (hit?.layer) {
        null -> TapAction.Empty
        "sightings" -> TapAction.Ignore
        "points" -> hit.addr?.let(DeviceAddr::keyOf)?.let { TapAction.OpenDevice(it) } ?: TapAction.Ignore
        "clusters" -> TapAction.ExpandCluster(hit)
        "cells" -> hit.at?.let { TapAction.ZoomCell(it) } ?: TapAction.Ignore
        else -> TapAction.Ignore
    }

    /** M-4: the zoom a cluster tap eases to. */
    fun clusterZoom(expansionZoom: Int) = expansionZoom + 0.2

    /** §6.6: a cell tap zooms by +2. */
    fun cellZoom(current: Double) = current + 2
}

/** DeviceKey (§2.2: the 48-bit MAC as an integer) ⇄ the `addr` property of the map's GeoJSON ("AA:BB:CC:DD:EE:FF"). */
object DeviceAddr {
    fun keyOf(addr: String): DeviceKey? {
        val hex = addr.replace(":", "").replace("-", "")
        if (hex.length != 12) return null
        return hex.toLongOrNull(16)
    }

    fun addrOf(key: DeviceKey): String =
        (5 downTo 0).joinToString(":") { i -> ((key ushr (8 * i)) and 0xFF).toString(16).uppercase().padStart(2, '0') }
}
