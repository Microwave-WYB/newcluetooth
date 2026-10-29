package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * The sheet layer of one session view: the phone bottom-sheet stack, or the tablet's floating panel (T-1), over the
 * map and under the top chrome. [tablet] follows the layout mode (G-2); crossing it keeps the stack (T-9).
 */
@Composable
fun SheetsLayer(state: SheetsState, tablet: Boolean, modifier: Modifier = Modifier) {
    SideEffect { state.stack.setLayout(tablet) }
    val content: @Composable (SheetEntry) -> Unit = { entry -> SheetContent(state, entry) }
    if (tablet) TabletPanelHost(state.stack, modifier = modifier, metrics = state.metrics, sheet = content)
    else PhoneSheetHost(state.stack, modifier = modifier, metrics = state.metrics, sheet = content)
}

@Composable
private fun SheetContent(state: SheetsState, entry: SheetEntry) {
    when (val kind = entry.kind) {
        SheetKind.Discover -> DiscoverSheet(state, entry)
        is SheetKind.Product -> ProductSheet(state, entry, kind.pageId)
        is SheetKind.Device -> DeviceSheet(state, entry, kind.key)
        SheetKind.Filter -> FilterSheet(state, entry)
    }
}
