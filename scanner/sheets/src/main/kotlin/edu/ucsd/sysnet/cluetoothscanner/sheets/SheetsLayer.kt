package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier

/**
 * The sheet layer of one session view: the phone bottom-sheet stack, or the tablet's floating panel (T-1), over the
 * map and under the top chrome. [tablet] follows the layout mode (G-2); crossing it keeps the stack (T-9).
 *
 * The sheet contents here are placeholders (a title per level); the Discover, product, device and Filter screens
 * replace them in #58 without changing this API.
 */
@Composable
fun SheetsLayer(state: SheetsState, tablet: Boolean, modifier: Modifier = Modifier) {
    SideEffect { state.stack.setLayout(tablet) }
    val content: @Composable (SheetEntry) -> Unit = { entry -> PlaceholderSheet(state, entry) }
    if (tablet) TabletPanelHost(state.stack, SheetColors.Placeholder, modifier = modifier, metrics = state.metrics, sheet = content)
    else PhoneSheetHost(state.stack, SheetColors.Placeholder, modifier = modifier, metrics = state.metrics, sheet = content)
}

@Composable
private fun PlaceholderSheet(state: SheetsState, entry: SheetEntry) {
    val title = when (val k = entry.kind) {
        SheetKind.Discover -> "Discover"
        is SheetKind.Product -> "Product ${k.pageId ?: "unidentified"}"
        is SheetKind.Device -> "Device ${k.key}"
        SheetKind.Filter -> "Filter"
    }
    SheetFrame(state.stack, entry, state.metrics, header = { BasicText(title, Modifier.fillMaxWidth().padding(state.metrics.edge)) }) {}
}
