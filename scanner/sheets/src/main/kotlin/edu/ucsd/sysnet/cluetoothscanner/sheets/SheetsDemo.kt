package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Gallery-only demos of the sheet engine on placeholder content, until :designsystem and the real sheets land.
 * A grey "map" fills the screen: dragging it is a user pan (P-6, T-2), tapping it is an empty-map tap (S-8).
 */
object SheetsDemo {
    @Composable
    fun PhoneStack() = Demo(tablet = false)

    @Composable
    fun TabletPanel() = Demo(tablet = true)
}

@Composable
private fun Demo(tablet: Boolean) {
    val controller = rememberSheetStackController()
    SideEffect { controller.setLayout(tablet) }
    val colors = SheetColors(panel = Color.White, scrim = Color.Black.copy(alpha = 0.32f), chevron = Color.Gray)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.LightGray)
                .pointerInput(controller) { detectDragGestures(onDragStart = { controller.onMapUserGesture() }) { _, _ -> } }
                .pointerInput(controller) { detectTapGestures { controller.pop(PopReason.EmptyMap) } },
        )
        val sheet: @Composable (SheetEntry) -> Unit = { entry -> DemoSheet(controller, entry) }
        if (tablet) TabletPanelHost(controller, colors, sheet = sheet) else PhoneSheetHost(controller, colors, sheet = sheet)
    }
}

@Composable
private fun DemoSheet(controller: SheetStackController, entry: SheetEntry) {
    val title = when (val k = entry.kind) {
        SheetKind.Discover -> "Discover"
        is SheetKind.Product -> "Product ${k.pageId}"
        is SheetKind.Device -> "Device ${k.key}"
        SheetKind.Filter -> "Filter"
    }
    SheetFrame(
        controller, entry,
        header = {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, Modifier.weight(1f))
                if (controller.isPushed(entry)) {
                    BasicText("Close", Modifier.clickable { controller.pop(PopReason.Close) }.padding(8.dp))
                } else {
                    BasicText("Filter", Modifier.clickable { controller.onRootHeaderControl(); controller.push(SheetKind.Filter) }.padding(8.dp))
                }
            }
        },
    ) {
        val state = rememberSheetListState(entry)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(60) { i ->
                BasicText(
                    if (i % 5 == 0) "Product $i" else "Device $i",
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (i % 5 == 0) controller.push(SheetKind.Product("p$i")) else controller.openDevice(i.toLong()) }
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                )
            }
        }
    }
}
