package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceSort
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState

/**
 * A map sheet's device list (recording, product and device maps, and the product page): sorted by the
 * user's [DeviceSort] once (when the list appears or the sort changes, see [StableOrder]), then held. A
 * row's values (RSSI, "Scanned now") change in place, a new device goes last, and the search and the
 * "this area" filter only hide rows: they never move one.
 */
object MapListOrder {
    fun arrange(rows: List<MapListRowState>, sort: DeviceSort, order: StableOrder, key: String, nowMs: Long): List<MapListRowState> {
        val sorted = when (sort) {
            DeviceSort.Signal -> rows.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            DeviceSort.Recent -> rows.sortedByDescending { it.lastSeenMs }
            DeviceSort.Name -> rows.sortedWith(compareBy({ it.unnamed }, { it.title.lowercase() }, { it.mac }))
        }
        return order.arrange(key, sorted, nowMs) { it.id }
    }
}
