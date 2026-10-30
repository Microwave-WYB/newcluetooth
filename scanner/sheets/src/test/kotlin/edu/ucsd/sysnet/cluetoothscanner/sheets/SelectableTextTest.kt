package edu.ucsd.sysnet.cluetoothscanner.sheets

import android.content.ClipboardManager
import android.content.Context
import android.widget.Magnifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** D-15: a long press selects the sheet's text and offers the system copy toolbar; buttons are not selectable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1600dp-420dpi", shadows = [NoMagnifier::class]) // tall: a field shows its toolbar only on screen
class SelectableTextTest {
    @get:Rule val compose = createComposeRule()

    /** The system copy toolbar, recorded: the menu while it is shown. */
    private class Toolbar : TextContextMenuProvider {
        var shown: TextContextMenuDataProvider? = null
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            try { awaitCancellation() } finally { shown = null }
        }

        /** The menu's Copy item, when the menu is shown with one. */
        val copy: (() -> Unit)? get() = shown?.data()?.components.orEmpty().filterIsInstance<TextContextMenuItem>()
            .firstOrNull { it.key == TextContextMenuKeys.CopyKey }
            ?.let { item -> { item.onClick(object : TextContextMenuSession { override fun close() = Unit }) } }
    }

    private val uuid = "0000fe2c-0000-1000-8000-00805f9b34fb"
    private val hex = byteArrayOf(0x4c, 0x00, 0x10, 0x05, 0x01, 0x18, 0x2a, 0x7b, 0x3c, 0x5d, 0x6e, 0x7f, 0x11, 0x22, 0x33, 0x44)
    private val d = DeviceDetail(
        key = 1, addr = "A4:C1:38:9F:0B:2E", name = "AirPods Pro", page = null, categories = emptyList(), traits = emptyList(),
        readable = false, vulnerable = false, vulnerabilities = emptyList(), wikiUrl = null, icon = null, rssi = -60,
        inRange = true, live = false, connectable = true, format = AdvFormat.Legacy, companyId = null, companyName = null,
        serviceUuid = uuid, txPower = null, structures = listOf(AdStructure(17, 0xff, hex)), tail = null, decoded = null, lastSeenMs = 0,
    )
    private val read = GattView(
        "g", 1, 0, ok = true,
        steps = GattStepKind.entries.map { GattStep(it, GattStepState.Done, 800, null, null, null, null) },
        services = listOf(GattServiceView("180F", "Battery", listOf(GattCharView("2A19", "Battery Level", listOf("read"), "Level78")))),
    )

    private val toolbar = Toolbar()

    private fun show(yieldTo: Modifier = Modifier) {
        val gatt = GattController(1, CoroutineScope(Dispatchers.Unconfined), object : GattRunner {
            override fun discover(key: DeviceKey): Flow<GattView> = emptyFlow()
        }).apply { showLast(read) }
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar, LocalSheetSelectable provides yieldTo) {
                CluetoothTheme(darkTheme = false, reducedMotion = true) {
                    Column { DeviceHead(d, -60); Advertising(d); GattTab(gatt) }
                }
            }
        }
    }

    /** Long-presses the text, then copies what was selected; null when nothing could be copied. */
    private fun longPressAndCopy(text: String, substring: Boolean = false): String? {
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).performTouchInput { longClick() }
        return copy()
    }

    /** Copies the current selection with the toolbar's Copy; null when the toolbar offers none. */
    private fun copy(): String? {
        compose.waitForIdle()
        val item = toolbar.copy ?: return null
        compose.runOnIdle { item() }
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun d15_oneLongPressOnTheMacSelectsTheWholeMac() {
        show()
        // one long press, anywhere on it: the whole MAC (the system's word rules alone picked "38:")
        assertEquals(d.addr, longPressAndCopy(d.addr))
    }

    @Test
    fun d15_oneLongPressOnAUuidSelectsTheWholeUuid() {
        show()
        assertEquals(uuid, longPressAndCopy(uuid))
    }

    @Test
    fun d15_longPressingACharacteristicValueSelectsIt() {
        show()
        assertEquals("Level78", longPressAndCopy("Level78"))
    }

    @Test
    fun d15_aRawDataCellCopiesAsTheExactHex() {
        show()
        assertEquals(Format.hex(hex), longPressAndCopy(Format.hex(hex)))
    }

    @Test
    fun d15_onlySelectableTextsGiveTheSheetsGestureUp() {
        // the sheet's sheetSelectable, recorded: a long press that reached it would make the sheet give the gesture up
        val yielded = mutableListOf<Offset>()
        show(Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                awaitLongPressOrCancellation(down.id)?.let { yielded += it.position }
            }
        })
        fun longPress(node: SemanticsNodeInteraction): Int {
            yielded.clear()
            node.performTouchInput { longClick() }
            compose.waitForIdle()
            return yielded.size
        }
        for (text in listOf(d.addr, "AirPods Pro", "Level78", Format.hex(hex), Strings.Yes)) {
            assertEquals("$text is selectable", 1, longPress(compose.onNodeWithText(text, useUnmergedTree = true)))
        }
        // labels, the note and buttons: the gesture stays the sheet's (S-13 after a long press)
        for (label in listOf(Strings.Connectable, Strings.RawData, Strings.Len, Strings.Details)) {
            assertEquals("$label is a label", 0, longPress(compose.onNodeWithText(label, useUnmergedTree = true)))
        }
        assertEquals(0, longPress(compose.onNodeWithTag(DeviceTags.NOTE)))
        assertEquals(0, longPress(compose.onNodeWithTag(DeviceTags.DISCOVER)))
    }

    @Test
    fun g3_aLongRawDataCellStillBreaksAnywhere_withNoBreakCharactersInIt() {
        val long = ByteArray(29) { (it * 37).toByte() }
        compose.setContent { CluetoothTheme(darkTheme = false, reducedMotion = true) { RawTable(d.copy(structures = listOf(AdStructure(30, 0xff, long)))) } }
        val node = compose.onNodeWithText(Format.hex(long), useUnmergedTree = true).fetchSemanticsNode() // the exact hex
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        assertTrue("wraps: ${layout.lineCount} lines", layout.lineCount > 1)
        assertFalse("clipped", layout.hasVisualOverflow)
        // the first line runs to the cell's edge, as with `word-break: break-all`: less than one character is left
        val charW = layout.getLineRight(0) / layout.getLineEnd(0)
        assertTrue("line 0 ends ${layout.size.width - layout.getLineRight(0)} px short", layout.size.width - layout.getLineRight(0) < charW)
    }

    @Test
    fun d15_buttonsAndLabelsAreNotSelectable() {
        show()
        compose.onNodeWithTag(DeviceTags.DISCOVER).performTouchInput { longClick() }
        compose.waitForIdle()
        assertNull(toolbar.copy)
        for (label in listOf(Strings.Connectable, Strings.RawData)) { // a Details key, a section label
            compose.onNodeWithText(label, useUnmergedTree = true).performTouchInput { longClick() }
            compose.waitForIdle()
            assertNull(label, toolbar.copy)
        }
    }
}

/** Robolectric draws no magnifier window (its dismiss throws); the selection itself is real. */
@Implements(Magnifier::class)
class NoMagnifier {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

