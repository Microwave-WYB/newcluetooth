package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.FakeData
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState

/**
 * Debug builds only (this is the debug source set): fake map data for
 * `am start ... --es v3_demo_map route|selected|heat|product`. `V3ViewModel` finds this class by name,
 * so a release build has neither it nor [FakeData].
 */
object DebugDemoMaps {
    @JvmStatic
    fun state(variant: String, heat: Boolean, panned: Boolean): MapUiState {
        val base = when (variant) {
            "selected" -> FakeData.recordingMapSelected
            "product" -> FakeData.productMap
            else -> FakeData.recordingMap
        }
        val showHeat = heat || variant == "heat"
        return base.copy(
            heatmap = showHeat,
            heat = if (showHeat) FakeData.recordingMapPanned.heat else emptyList(),
            showAreaChip = panned,
        )
    }
}
