package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The stock top app bar's height. */
private val TopAppBarHeight = 64.dp

/** The stock sheet drag handle (BottomSheetDefaults.DragHandle: 4 dp bar, 22 dp above and below). */
private val DragHandleHeight = 48.dp

/**
 * How tall a sheet's content may be so the sheet (its handle included) ends below the status bar
 * and the top app bar: the page's title, subtitle and actions and the record pill stay clear.
 */
@Composable
fun sheetContentMaxHeight(): Dp = with(LocalDensity.current) {
    LocalWindowHeight.current - WindowInsets.statusBars.getTop(this).toDp() - TopAppBarHeight - DragHandleHeight
}

/** The height of the app's window (edge to edge), measured by the shell; sheets size against it. */
val LocalWindowHeight = compositionLocalOf { 800.dp }

/**
 * The Scan page's device detail in a stock [ModalBottomSheet]: default drag handle, insets and scrim;
 * it opens fully to its content, which is capped by [sheetContentMaxHeight] and scrolls inside.
 * Drag down, a tap on the scrim and Back all close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDeviceSheet(detail: edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState, actions: DeviceDetailActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DeviceDetailContent(detail, actions, Modifier.heightIn(max = sheetContentMaxHeight()))
    }
}
