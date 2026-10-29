package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.map.stub.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey

/** docs/v2-api.md §5.4: the map's UI state. showLocate is P-4, showWarning is P-5. */
data class MapUiState(
    val selection: DeviceKey?,
    val routesFaint: Boolean,
    val cover: CoverInsets,
    val showLocate: Boolean,
    val showWarning: Boolean,
)
