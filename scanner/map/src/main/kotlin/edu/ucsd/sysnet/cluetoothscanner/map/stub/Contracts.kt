package edu.ucsd.sysnet.cluetoothscanner.map.stub

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope

/*
 * TEMPORARY. Until :ui-contract (U0) merges, these are verbatim copies of the frozen §5.3 types that :map uses, and
 * only those. When it lands, this file is deleted and the imports switch to edu.ucsd.sysnet.cluetoothscanner.ui.contract.
 * Nothing here is a design decision of lane E; do not extend it beyond the contract.
 */

// ---- :ui-contract (§5.3) ----

data class CameraSnapshot(val center: GeoPoint, val zoom: Double, val bearing: Double = 0.0)
data class CoverInsets(val top: Int, val bottom: Int, val left: Int, val right: Int)

interface MapRequests {
    fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean)
    fun frame(bbox: Bbox)
    fun focusDevice(key: DeviceKey, minZoom: Double = 18.2)
    fun setSelection(key: DeviceKey?)
    fun camera(): CameraSnapshot; fun restore(camera: CameraSnapshot)
    fun setCover(insets: CoverInsets)
}
interface MapEvents {
    fun onUserGestureStart()
    fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double)
    fun onDeviceTap(key: DeviceKey); fun onEmptyTap()
}
interface MapCacheInfo { suspend fun sizeBytes(): Long }
