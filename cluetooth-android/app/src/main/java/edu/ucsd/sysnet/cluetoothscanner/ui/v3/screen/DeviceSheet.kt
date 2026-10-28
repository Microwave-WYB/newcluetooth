package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.DragSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.DragSheetState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.rememberDragSheetState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState

/** What the device detail can ask for; the screen owner decides what each does. */
class DeviceDetailActions(
    val onWindowChange: (ChartWindow) -> Unit = {},
    val onDiscoverGatt: () -> Unit = {},
    val onCancelGatt: () -> Unit = {},
    val onShowOnMap: () -> Unit = {},
)

/** The half sheet is as tall as its content, but never more than this share of the screen. */
const val HALF_SHEET_MAX_SHARE = 0.6f

/**
 * Half-height content while the sheet is low, the tabbed full page when it is dragged up.
 * [onHalfMeasured] reports the natural height of the half content in pixels.
 */
@Composable
fun ColumnScope.DeviceSheetBody(
    detail: DeviceDetailUiState,
    actions: DeviceDetailActions,
    expanded: Boolean,
    onCollapse: () -> Unit,
    /** Ask the sheet to go to its top level (from the half content's links and buttons). */
    onExpand: () -> Unit,
    onHalfMeasured: (Int) -> Unit,
    tabState: androidx.compose.runtime.MutableState<DetailTab> = remember(detail.device.id) { mutableStateOf(DetailTab.Advertising) },
    expandedVariant: androidx.compose.runtime.MutableState<String?> = remember(detail.device.id) { mutableStateOf(null) },
    /** Extra row above the half content, e.g. a back arrow to the list. */
    backRow: @Composable () -> Unit = {},
) {
    if (expanded) {
        DeviceDetailFull(
            detail = detail,
            tab = tabState.value,
            expandedVariantId = expandedVariant.value,
            onTab = { tabState.value = it },
            onToggleVariant = { id -> expandedVariant.value = if (expandedVariant.value == id) null else id },
            onDiscoverGatt = actions.onDiscoverGatt,
            onCancelGatt = actions.onCancelGatt,
            onCollapse = onCollapse,
            statusBarInset = false,
        )
    } else {
        backRow()
        DeviceDetailHalf(
            detail = detail,
            onWindowChange = actions.onWindowChange,
            onDiscoverGatt = {
                actions.onDiscoverGatt()
                tabState.value = DetailTab.Gatt
                onExpand()
            },
            onShowOnMap = actions.onShowOnMap,
            onExpand = { tabState.value = DetailTab.Advertising; onExpand() },
            modifier = Modifier
                .navigationBarsPadding()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { onHalfMeasured(it.height) },
        )
    }
}

/** Dim layer behind a sheet, fading in with it; tapping it dismisses. */
@Composable
fun BoxScope.SheetScrim(state: DragSheetState, onDismiss: () -> Unit) {
    val alpha = if (LocalStaticRender.current) 0.32f else 0.32f * state.progress
    if (alpha > 0f) {
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = alpha))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }
}

/**
 * The scan page's device sheet: half height (content-sized, at most 60% of the screen), draggable
 * up to the full tabbed page and down to dismiss.
 */
@Composable
fun BoxScope.ScanDeviceSheet(
    detail: DeviceDetailUiState,
    actions: DeviceDetailActions,
    onDismiss: () -> Unit,
    startExpanded: Boolean = false,
) {
    val state = rememberDragSheetState()
    var level by rememberSaveable { mutableIntStateOf(if (startExpanded) 1 else 0) }
    var halfPx by remember { mutableIntStateOf(0) }
    val handlePx = with(LocalDensity.current) { 22.dp.toPx() }
    BackHandler { if (level == 1) level = 0 else onDismiss() }
    SheetScrim(state, onDismiss)
    DragSheet(
        state = state,
        levels = { container ->
            val cap = container * HALF_SHEET_MAX_SHARE
            // Before the content is measured (or in a one-frame preview) assume about half the screen.
            val natural = if (halfPx > 0) halfPx + handlePx else container * 0.5f
            listOf(natural.coerceAtMost(cap), container)
        },
        level = level,
        onLevelChange = { level = it },
        onDismiss = onDismiss,
    ) { expanded ->
        DeviceSheetBody(
            detail = detail,
            actions = actions,
            expanded = expanded,
            onCollapse = { level = 0 },
            onExpand = { level = 1 },
            onHalfMeasured = { halfPx = it },
        )
    }
}
