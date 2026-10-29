package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.DragSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.MiniRecordSlotWidth
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SearchField
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.appendMac
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.rememberDragSheetState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState

/** What the map page asks its owner to do. */
class MapActions(
    val onBack: () -> Unit = {},
    val onRowClick: (MapListRowState) -> Unit = {},
    val onBackToList: () -> Unit = {},
    val onSortClick: () -> Unit = {},
    val onSearchToggle: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onAreaChip: () -> Unit = {},
    val onLayerToggle: () -> Unit = {},
    val detail: DeviceDetailActions = DeviceDetailActions(),
)

private const val PEEK_DP = 148f

/**
 * Recording detail, product map and device map: a full-screen map with a search-style bottom
 * sheet. Without a selection the sheet lists devices (or MACs); selecting one switches the sheet
 * to the shared device detail and the map to that device's points.
 *
 * [map] draws the map; it is told how much of its height the sheet covers (0..1) so overlays
 * and camera padding can stay in the visible part.
 */
@Composable
fun MapPage(
    state: MapUiState,
    actions: MapActions,
    modifier: Modifier = Modifier,
    /** A notice over the map, e.g. that the base map is offline. */
    notice: String? = null,
    map: @Composable (coveredFraction: () -> Float) -> Unit,
) {
    val sheet = rememberDragSheetState()
    val selecting = state.selected != null
    // Level 1 is the half sheet; a selection or a new search starts there.
    var level by rememberSaveable(state.selected?.device?.id) { mutableIntStateOf(1) }
    var halfPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val handlePx = with(density) { 22.dp.toPx() }
    val backRowPx = with(density) { 48.dp.toPx() }

    BackHandler {
        when {
            level == 2 -> level = 1
            selecting -> actions.onBackToList()
            else -> actions.onBack()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val containerPx = with(density) { maxHeight.toPx() }
        // A lambda, so a sheet drag does not recompose the map; it is read when drawing or fitting.
        map { (sheet.height.value / containerPx).coerceIn(0f, 1f).let { if (it == 0f) 0.5f else it } }

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingSurface(CircleShape) { BackButton(actions.onBack) }
                FloatingSurface(RoundedCornerShape(22.dp), Modifier.weight(1f)) {
                    Column(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // The shell draws the floating mini record button here.
                Spacer(Modifier.width(MiniRecordSlotWidth - 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.showAreaChip) MapChip(if (state.areaOnly) "显示全部" else "只看此区域", actions.onAreaChip)
                    if (state.estimate != null) EstimateLegend(state.estimateAccuracyMeters)
                }
                FloatingSurface(CircleShape, onClick = actions.onLayerToggle) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Layers, "热力图",
                            tint = if (state.heatmap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (notice != null) {
                Text(
                    notice,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.emptyNote != null) {
                Text(
                    state.emptyNote,
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DragSheet(
            state = sheet,
            levels = { container ->
                val peek = with(density) { PEEK_DP.dp.toPx() }
                if (selecting) {
                    val natural = if (halfPx > 0) halfPx + handlePx + backRowPx else container * 0.5f
                    listOf(peek, natural.coerceAtMost(container * HALF_SHEET_MAX_SHARE), container)
                } else {
                    listOf(peek, container * 0.5f, container * 0.92f)
                }
            },
            level = level,
            onLevelChange = { level = it },
        ) { expanded ->
            if (state.selected != null) {
                DeviceSheetBody(
                    detail = state.selected,
                    actions = DeviceDetailActions(
                        onWindowChange = actions.detail.onWindowChange,
                        onDiscoverGatt = actions.detail.onDiscoverGatt,
                        onCancelGatt = actions.detail.onCancelGatt,
                        onShowOnMap = { level = 0; actions.detail.onShowOnMap() },
                    ),
                    expanded = expanded,
                    onCollapse = { level = 1 },
                    onExpand = { level = 2 },
                    onHalfMeasured = { halfPx = it },
                    backRow = { Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(actions.onBackToList)
                        Text("设备列表", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                )
            } else {
                DeviceListSheet(state, actions)
            }
        }

        // OpenStreetMap and OpenFreeMap require visible attribution.
        Text(
            "© OpenMapTiles · © OpenStreetMap contributors · OpenFreeMap",
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp)
                .offset { IntOffset(0, -sheet.height.value.toInt() - 2.dp.roundToPx()) },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FloatingSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (onClick != null) {
        Surface(onClick, modifier, shape = shape, color = colors.surface, shadowElevation = 3.dp, border = BorderStroke(0.5.dp, colors.outlineVariant), content = content)
    } else {
        Surface(modifier, shape = shape, color = colors.surface, shadowElevation = 3.dp, border = BorderStroke(0.5.dp, colors.outlineVariant), content = content)
    }
}

@Composable
private fun MapChip(text: String, onClick: () -> Unit) {
    FloatingSurface(RoundedCornerShape(50), onClick = onClick) {
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
private fun EstimateLegend(accuracyMeters: Int?) {
    FloatingSurface(RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(
                if (accuracyMeters != null) "估计位置 ±$accuracyMeters m" else "估计位置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sheet list: count, sort and search, then one row per device (or MAC). */
@Composable
private fun DeviceListSheet(state: MapUiState, actions: MapActions) {
    Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.mode == MapMode.Product) "${state.rows.size} 个 MAC" else "${state.rows.size} 台设备",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = actions.onSortClick).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" ${state.sort.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = actions.onSearchToggle), contentAlignment = Alignment.Center) {
            Icon(
                if (state.searching) Icons.Filled.Close else Icons.Filled.Search,
                if (state.searching) "关闭搜索" else "搜索",
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.searching) {
        SearchField(state.query, actions.onQueryChange, "搜索名称、MAC 或产品", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
        items(state.rows, key = { it.id }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.onRowClick(row) }.padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (row.title == "未命名") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildAnnotatedString {
                            if (row.mac != null) appendMac(row.mac)
                            if (row.mac != null && row.detail.isNotEmpty()) append(" · ")
                            append(row.detail)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.rssi != null) RssiText(row.rssi, Modifier.width(38.dp))
            }
        }
    }
}
