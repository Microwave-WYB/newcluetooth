package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.TopBarIconButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.PositionEstimateState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

/** The map pages' stock top app bar: back, the recording or product, its one-line summary, the density layer toggle. */
@Composable
fun MapTopBar(state: MapUiState, actions: MapActions, menu: (@Composable () -> Unit)? = null) {
    PageTopBar(
        title = state.title,
        subtitle = state.subtitle,
        navigation = { BackButton(actions.onBack) },
        actions = {
            TopBarIconButton(
                Icons.Filled.Layers, stringResource(R.string.map_density), actions.onLayerToggle,
                tint = if (state.heatmap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            menu?.invoke()
        },
    )
}

/**
 * Recording detail, product map and device map, under the page's top app bar: the map with a stock
 * persistent [BottomSheetScaffold] sheet. Without a selection the sheet lists devices (or MACs);
 * selecting one switches it to the shared device detail and the map to that device's points. The
 * sheet peeks, and expands to its content, at most up to the top app bar (it lives below it).
 *
 * [map] draws the map; it is told how much of its height the sheet covers (0..1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapContent(
    state: MapUiState,
    actions: MapActions,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    /** A notice over the map, e.g. that the base map is offline. */
    notice: Int? = null,
    /** For previews: start with the sheet expanded and on this tab. */
    initiallyExpanded: Boolean = false,
    initialTab: DetailTab = DetailTab.Advertising,
    map: @Composable (coveredFraction: () -> Float) -> Unit,
) {
    val sheetState = rememberStandardBottomSheetState(
        initialValue = if (initiallyExpanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val selecting = state.selected != null
    val tab = remember(state.selected?.device?.id) { mutableStateOf(initialTab) }
    var layoutPx by remember { mutableFloatStateOf(0f) }
    val visibleSheetPx = { runCatching { (layoutPx - sheetState.requireOffset()).coerceAtLeast(0f) }.getOrDefault(0f) }

    // A new selection (or going back to the list) starts with the sheet peeking.
    LaunchedEffect(state.selected?.device?.id) { if (!initiallyExpanded) runCatching { sheetState.partialExpand() } }

    BackHandler {
        when {
            sheetState.currentValue == SheetValue.Expanded -> scope.launch { sheetState.partialExpand() }
            selecting -> actions.onBackToList()
            else -> actions.onBack()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).onSizeChanged { layoutPx = it.height.toFloat() }) {
        val peek = if (selecting) maxHeight * 0.42f else maxHeight * 0.3f
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetContent = {
                if (state.selected != null) {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(actions.onBackToList)
                        Text(stringResource(R.string.map_device_list), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DeviceDetailContent(
                        detail = state.selected.copy(
                            positionEstimate = state.estimate?.let { PositionEstimateState(it, state.estimateAccuracyMeters) },
                        ),
                        actions = DeviceDetailActions(
                            onDiscoverGatt = actions.detail.onDiscoverGatt,
                            onCancelGatt = actions.detail.onCancelGatt,
                            onShowOnMap = { scope.launch { sheetState.partialExpand() }; actions.detail.onShowOnMap() },
                        ),
                        showLiveChart = false,
                        tabState = tab,
                    )
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                } else {
                    DeviceListSheet(state, actions)
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                // A lambda, so a sheet drag does not recompose the map; it is read when drawing or fitting.
                map { if (layoutPx > 0f) (visibleSheetPx() / layoutPx).coerceIn(0f, 1f) else 0.3f }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (state.showAreaChip) {
                        FilterChip(
                            selected = state.areaOnly,
                            onClick = actions.onAreaChip,
                            label = { Text(stringResource(if (state.areaOnly) R.string.map_show_all else R.string.map_this_area)) },
                        )
                    }
                    val message = notice?.let { stringResource(it) } ?: state.emptyNote
                    if (message != null) {
                        Card(Modifier.padding(top = 8.dp)) {
                            Text(
                                message,
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // OpenStreetMap and OpenFreeMap require visible attribution, just above the sheet.
                Text(
                    stringResource(R.string.map_attribution),
                    Modifier.align(Alignment.BottomStart).padding(start = 8.dp)
                        .offset { IntOffset(0, -visibleSheetPx().roundToInt() - 2.dp.roundToPx()) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sheet list: count, sort and search, then one stock [ListItem] per device (or MAC). */
@Composable
private fun DeviceListSheet(state: MapUiState, actions: MapActions) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.listHint ?: LocalTexts.current.plural(if (state.mode == MapMode.Product) R.plurals.macs else R.plurals.devices, state.rows.size, state.rows.size),
            Modifier.weight(1f),
            style = if (state.listHint != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = actions.onSortClick) {
            Icon(Icons.AutoMirrored.Filled.Sort, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(state.sort.label))
        }
        IconButton(onClick = actions.onSearchToggle) {
            Icon(
                if (state.searching) Icons.Filled.Close else Icons.Filled.Search,
                stringResource(if (state.searching) R.string.action_close_search else R.string.action_search),
            )
        }
    }
    if (state.searching) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.search_hint_map)) },
            singleLine = true,
        )
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.rows, key = { it.id }) { row ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        row.title,
                        color = if (row.unnamed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // The address, then what is known of it on its own line (no dot-joined run of facts).
                supportingContent = {
                    Column {
                        row.mac?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (row.detail.isNotEmpty()) Text(row.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingContent = row.rssi?.let { { RssiText(it) } },
                modifier = Modifier.clickable { actions.onRowClick(row) },
            )
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}
