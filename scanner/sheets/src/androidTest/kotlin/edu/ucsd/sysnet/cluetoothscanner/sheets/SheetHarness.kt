package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * A sheet layer with a 40 dp header holding one control at the right, over 200 rows of [ROW], as a LazyColumn
 * ([lazy], the Discover list) or a scrolling Column (the device sheet). With [selectable] the header and every row
 * hold selectable text, and each row a label that is not, in one selection area, as the device sheet does (D-15).
 */
internal class SheetHarness(private val lazy: Boolean = true, private val selectable: Boolean = false) {
    lateinit var controller: SheetStackController
    private val lists = mutableMapOf<Long, LazyListState>()
    private val columns = mutableMapOf<Long, ScrollState>()
    var controlClicks = 0

    /** The content scroll of [id] in rows and px within the first row; null when not composed. */
    fun scroll(id: Long, rowPx: Int): ScrollSnapshot? =
        lists[id]?.let { ScrollSnapshot(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) }
            ?: columns[id]?.let { ScrollSnapshot(it.value / rowPx, it.value % rowPx) }

    /** Jumps [id]'s content to [index] rows plus [offset] px. */
    suspend fun scrollTo(id: Long, index: Int, offset: Int, rowPx: Int) {
        lists[id]?.scrollToItem(index, offset) ?: columns.getValue(id).scrollTo(index * rowPx + offset)
    }

    /** Identity of [id]'s current scroll state, to tell a restored composition from the old one. */
    fun stateOf(id: Long): Any? = lists[id] ?: columns[id]

    companion object {
        val ROW = 50.dp
        fun header(id: Long) = "header-$id"
        fun control(id: Long) = "control-$id"
        fun list(id: Long) = "list-$id"
        fun headerText(id: Long) = "header text $id"
        fun rowText(i: Int) = "row text $i"
        fun rowLabel(i: Int) = "label $i"
    }

    @Composable
    fun Content(rootHeight: SheetHeight, tablet: Boolean = false) {
        val controller = rememberSheetStackController(rootHeight = rootHeight)
        SideEffect { controller.setLayout(tablet) }
        this.controller = controller
        val colors = SheetColors(panel = Color.White, scrim = Color.Black.copy(alpha = 0.32f), chevron = Color.Gray)
        if (tablet) TabletPanelHost(controller, colors) { Sheet(it) } else PhoneSheetHost(controller, colors) { Sheet(it) }
    }

    @Composable
    private fun Sheet(entry: SheetEntry) {
        SheetFrame(
            controller, entry,
            header = {
                Box(Modifier.fillMaxWidth().height(40.dp).testTag(header(entry.id))) {
                    if (selectable) SelectionContainer(Modifier.align(Alignment.CenterStart).padding(start = 14.dp)) { BasicText(headerText(entry.id), Modifier.sheetSelectable(controller, entry)) }
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp)
                            .size(40.dp)
                            .testTag(control(entry.id))
                            .clickable { controlClicks++ },
                    )
                }
            },
        ) {
            if (lazy) {
                val state = rememberSheetListState(entry)
                DisposableEffect(state) { lists[entry.id] = state; onDispose { lists.remove(entry.id) } }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag(list(entry.id))) {
                    items(200) { i ->
                        if (selectable) SelectionContainer {
                            Row(Modifier.fillMaxWidth().height(ROW)) {
                                DisableSelection { BasicText(rowLabel(i), Modifier.width(120.dp)) }
                                BasicText(rowText(i), Modifier.sheetSelectable(controller, entry))
                            }
                        }
                        else Box(Modifier.fillMaxWidth().height(ROW))
                    }
                }
            } else {
                val state = rememberSheetScrollState(entry)
                DisposableEffect(state) { columns[entry.id] = state; onDispose { columns.remove(entry.id) } }
                Column(Modifier.fillMaxSize().verticalScroll(state).testTag(list(entry.id))) {
                    repeat(200) { Box(Modifier.fillMaxWidth().height(ROW)) }
                }
            }
        }
    }
}
