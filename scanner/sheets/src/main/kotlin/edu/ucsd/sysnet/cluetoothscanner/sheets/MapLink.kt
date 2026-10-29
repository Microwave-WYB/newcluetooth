package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests

/**
 * The stack's map hooks over :ui-contract's [MapRequests]: the camera is saved when a sheet is covered and restored
 * when it is revealed (S-8). [onTopChanged] and [onRemoved] carry what the top sheet shows (S-17) and the GATT
 * cancel (D-13.8); the sheet contents supply them.
 */
internal fun mapStackHooks(
    map: MapRequests,
    onTopChanged: (entry: SheetEntry, opening: Boolean) -> Unit = { _, _ -> },
    onRemoved: (SheetEntry) -> Unit = {},
) = SheetStackHooks(
    camera = map::camera,
    restoreCamera = map::restore,
    onTopChanged = onTopChanged,
    onRemoved = onRemoved,
)

/**
 * The sheets' side of :ui-contract's [MapEvents]; :app routes the map's events here (and to lane C).
 * - P-6, T-2: a user pan or zoom collapses the top sheet (phone) or moves the panel to Partial (tablet).
 * - M-7, S-8: an empty-map tap pops the top pushed sheet; with only the root left there is nothing to pop.
 * - M-7, S-4: a point opens its device (replacing a device sheet on top); D-14: from a map tap the camera stays.
 * - D-4, Q-6: camera idle goes to [cameraIdle] (This area and "Search this area" live in the sheet contents).
 */
internal class SheetMapEvents(
    private val stack: SheetStackController,
    private val cameraIdle: (byUser: Boolean, viewport: Bbox, zoom: Double) -> Unit = { _, _, _ -> },
) : MapEvents {
    override fun onUserGestureStart() = stack.onMapUserGesture()

    override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) = cameraIdle(byUser, viewport, zoom)

    override fun onDeviceTap(key: DeviceKey) {
        stack.openDevice(key)
    }

    override fun onEmptyTap() {
        stack.pop(PopReason.EmptyMap)
    }
}
