package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab

sealed interface Route {
    data class Tab(val tab: AppTab) : Route
    /** A product (wiki page) from Discover: its image, description, map preview and devices. */
    data class Product(val pageId: String) : Route
    data class ProductMap(val pageId: String) : Route
    data class DeviceMap(val addr: String) : Route
    data class RecordingMap(val id: String) : Route

    /** The pre-v3 sessions, upload and export screen. */
    data object Legacy : Route
}

/** The visible area of the map, reported by the map after the user moved it. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
}
