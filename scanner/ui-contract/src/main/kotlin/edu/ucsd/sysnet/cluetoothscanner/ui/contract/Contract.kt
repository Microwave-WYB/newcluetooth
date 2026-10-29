// docs/v2-api.md §5.3: the shared UI contract (U0). Lanes C, D and E talk to each other only through these types.
package edu.ucsd.sysnet.cluetoothscanner.ui.contract

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope

/**
 * G-9: the one ordered-keys state. Built once from `deviceOrder` (sorted once by RSSI); changes only by appends;
 * filters intersect, never reorder. Rebuilt on V-7.
 *
 * Not thread-safe: mutate and read it from one thread (the UI's).
 */
class OrderedKeys(initial: LongArray) {
    private var order = LongArray(maxOf(initial.size, 16))
    private val seen = HashSet<Long>(initial.size * 2)

    /** The number of keys in the order. */
    var size: Int = 0
        private set

    init {
        append(initial)
    }

    /** Live appends, area or search new matches; keys already in the order are ignored (dedup). */
    fun append(keys: LongArray) {
        for (k in keys) {
            if (!seen.add(k)) continue
            if (size == order.size) order = order.copyOf(order.size * 2)
            order[size++] = k
        }
    }

    /** order ∩ passing, in the order's order (never passing's). */
    fun visible(passing: DeviceKeys): LongArray {
        val pass = passing.keys.copyOf().also { it.sort() }
        val out = LongArray(minOf(size, pass.size))
        var n = 0
        for (i in 0 until size) {
            val k = order[i]
            if (pass.binarySearch(k) >= 0) out[n++] = k
        }
        return out.copyOf(n)
    }
}

data class CameraSnapshot(val center: GeoPoint, val zoom: Double, val bearing: Double = 0.0)

/** dp the sheet/panel/chrome cover. */
data class CoverInsets(val top: Int, val bottom: Int, val left: Int, val right: Int)

/** Search state (Q-5..Q-7); D owns the instance, C and E read it. */
data class SearchState(
    val query: String,
    val area: Bbox?,
    val order: OrderedKeys,
    val reference: CameraSnapshot,
    val movedAway: Boolean,
    /** Search ignores filters; restored when search ends (D-3a). */
    val savedFilter: DeviceFilter,
)

/** What D asks of the map (S-17, D-8, D-14, Q-5); E implements it, :app connects them. */
interface MapRequests {
    /** The top sheet's point set. */
    fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean)

    /** D-8 / M-10. */
    fun frame(bbox: Bbox)

    /** D-14, avoids the cover. */
    fun focusDevice(key: DeviceKey, minZoom: Double = 18.2)

    /** M-6. */
    fun setSelection(key: DeviceKey?)

    /** S-8. */
    fun camera(): CameraSnapshot

    /** S-8. */
    fun restore(camera: CameraSnapshot)

    fun setCover(insets: CoverInsets)
}

/** What the map reports (M-7, P-6, D-4, Q-6); :app routes them to D (sheets) and C (the "Search this area" chip). */
interface MapEvents {
    /** P-6: D collapses the top sheet. */
    fun onUserGestureStart()

    fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double)

    fun onDeviceTap(key: DeviceKey)

    fun onEmptyTap()
}

/** O-1 Storage "map cache" (E implements). */
interface MapCacheInfo {
    suspend fun sizeBytes(): Long
}
