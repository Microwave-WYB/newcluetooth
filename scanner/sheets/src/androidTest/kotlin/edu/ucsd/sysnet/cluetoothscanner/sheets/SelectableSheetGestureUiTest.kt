package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * D-15 on the composed sheet layer (emulator): selectable text in the header and the content changes no gesture
 * (S-10a, S-11, S-13), and selecting it never moves the sheet.
 */
@RunWith(AndroidJUnit4::class)
class SelectableSheetGestureUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val h = SheetHarness(selectable = true)
    private val density get() = rule.density.density
    private fun dp(v: Float) = v * density
    private val slopPx get() = dp(8f)
    private val root get() = h.controller.root

    private fun start(rootHeight: SheetHeight) {
        rule.setContent { h.Content(rootHeight) }
        rule.waitForIdle()
    }

    private val rowPx get() = with(rule.density) { SheetHarness.ROW.roundToPx() }

    /** The app's window: the selection handles open popups of their own. */
    private fun screen() = rule.onAllNodes(isRoot()).onFirst()

    private fun sheetTop(): Float = rule.onNodeWithTag(SheetTags.sheet(h.controller.top)).fetchSemanticsNode().boundsInRoot.top
    private fun center(text: String): Offset = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center
    private fun moveBy(dy: Float, delay: Long = 16) = screen().performTouchInput { moveBy(Offset(0f, dy), delayMillis = delay) }

    /** A long press at [at]: down, then still for longer than the long-press timeout. */
    private fun longPress(at: Offset) = screen().performTouchInput {
        down(at)
        moveBy(Offset.Zero, delayMillis = viewConfiguration.longPressTimeoutMillis + 100)
    }

    @Test
    fun s10a_aHeaderDragStartingOnTextTracksTheFinger1to1_slopIncluded() {
        start(Half)
        val top0 = sheetTop()
        screen().performTouchInput { down(center(SheetHarness.headerText(root.id))) }
        var finger = 0f
        var started = false
        val steps = listOf(-0.3f, -0.3f, -0.3f, -0.5f, -2f, 0.8f, -3f, 1.5f, -1f, 2.5f, 0.4f, -8f).map { it * slopPx }
        for (dy in steps) {
            moveBy(dy)
            finger += dy
            rule.waitForIdle()
            val moved = sheetTop() - top0
            if (!started && abs(finger) < slopPx) {
                assertEquals("inside the slop the sheet stays", 0f, moved, 0.5f)
            } else {
                started = true
                assertEquals("finger $finger", finger, moved, 1f)
            }
        }
        screen().performTouchInput { up() }
    }

    @Test
    fun s13_aContentDragStartingOnTextRaisesToFullThenScrolls_sheetPlusScrollEqualsFinger() {
        start(Half)
        val top0 = sheetTop()
        val travel = top0 - h.controller.geometry(root).full
        screen().performTouchInput { down(center(SheetHarness.rowText(2))) }
        var finger = 0f
        val step = -dp(12f)
        while (-finger < travel + dp(200f)) {
            moveBy(step)
            finger += step
            rule.waitForIdle()
            if (-finger < slopPx) continue
            val sheet = top0 - sheetTop()
            val s = rule.runOnIdle { h.scroll(root.id, rowPx)!! }
            val scroll = (s.index * rowPx + s.offset).toFloat()
            assertEquals("finger ${-finger}: sheet $sheet + scroll $scroll", -finger, sheet + scroll, 1.5f)
        }
        screen().performTouchInput { up() }
    }

    @Test
    fun d15_selectingContentTextNeverMovesTheSheet() {
        start(Half)
        val top0 = sheetTop()
        longPress(center(SheetHarness.rowText(2)))
        repeat(10) { moveBy(dp(12f)); rule.waitForIdle() } // extends the selection
        assertEquals(top0, sheetTop(), 0.5f)
        screen().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(top0, sheetTop(), 0.5f)
        assertEquals(Half, root.height)
    }

    @Test
    fun d15_aLongPressOnALabelThenADragUpRaisesTheSheet() {
        start(Half)
        val top0 = sheetTop()
        longPress(center(SheetHarness.rowLabel(2))) // in the selection area, but not selectable
        var finger = 0f
        repeat(10) {
            moveBy(-dp(12f))
            finger -= dp(12f)
            rule.waitForIdle()
            if (abs(finger) > slopPx) assertEquals("finger $finger", finger, sheetTop() - top0, 1.5f)
        }
        screen().performTouchInput { up() }
    }

    @Test
    fun d15_aLongPressOnHeaderTextSelectsWithoutTheHeaderTap() {
        start(Collapsed)
        longPress(center(SheetHarness.headerText(root.id)))
        screen().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(Collapsed, root.height) // not raised by an S-11 tap
    }
}
