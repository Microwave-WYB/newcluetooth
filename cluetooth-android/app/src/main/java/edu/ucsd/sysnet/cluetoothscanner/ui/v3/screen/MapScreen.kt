package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControl
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetLayer
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetSurface
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily

private const val LIST_SHEET = 0.50f
private const val DETAIL_SHEET = 0.62f

/**
 * Recording detail and product map: a full-screen map with a search-style bottom sheet.
 * Without a selection the sheet lists devices (or MACs); selecting one switches the sheet to
 * the shared device-detail component and the map to that device's points.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    callbacks: RecorderCallbacks,
    modifier: Modifier = Modifier,
    controlBarOpen: Boolean = false,
    onBack: () -> Unit = {},
    onRowClick: (MapListRowState) -> Unit = {},
    onBackToList: () -> Unit = {},
) {
    val sheetFraction = if (state.selected != null) DETAIL_SHEET else LIST_SHEET
    val selecting = state.selected != null
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PlaceholderMap(
            bottomCovered = sheetFraction,
            // The route is the default view; a selected device shows only its own points.
            route = if (selecting || state.mode == MapMode.Product) emptyList() else state.route,
            points = if (selecting) state.selectedPoints else state.productPoints,
            estimate = state.estimate,
            heat = if (state.heatmap) state.heat else emptyList(),
        )

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingSurface(CircleShape) { BackButton(onBack) }
                FloatingSurface(RoundedCornerShape(22.dp), Modifier.weight(1f)) {
                    Column(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FloatingSurface(RoundedCornerShape(22.dp)) { RecordControl(state.recorder, RecordPlacement.Mini, callbacks) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.showAreaChip) MapChip("只看此区域")
                    if (state.estimate != null) EstimateLegend()
                }
                FloatingSurface(CircleShape) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Layers, "热力图",
                            tint = if (state.heatmap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            SheetSurface(Modifier.fillMaxHeight(sheetFraction)) {
                if (state.selected != null) {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onBackToList)
                        Text("设备列表", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DeviceDetailHalf(state.selected, {}, {}, {}, {})
                } else {
                    DeviceListSheet(state, onRowClick)
                }
            }
        }
        if (controlBarOpen) {
            Box(Modifier.fillMaxSize()) { SheetLayer { RecordControlBar(state.recorder, callbacks) } }
        }
    }
}

@Composable
private fun FloatingSurface(shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun MapChip(text: String) {
    FloatingSurface(RoundedCornerShape(50)) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Not tappable: says what the blue ring on the map is. */
@Composable
private fun EstimateLegend() {
    FloatingSurface(RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text("估计位置 ±12 m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sheet list: count, sort and search, then one row per device (or MAC). */
@Composable
private fun DeviceListSheet(state: MapUiState, onRowClick: (MapListRowState) -> Unit) {
    Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.mode == MapMode.Product) "${state.rows.size} 个 MAC" else "${state.rows.size} 台设备",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" ${state.sort.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, "搜索", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.rows, key = { it.id }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onRowClick(row) }.padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (row.titleMono) MonoFamily else FontFamily.Default,
                        color = if (row.title == "未命名") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                RssiText(row.rssi, Modifier.width(38.dp))
            }
        }
    }
}
