package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Full
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Gestures on the composed sheet layer with real touch injection on the emulator (`sheets-device-test`), measured on
 * the laid-out sheet.
 */
@RunWith(AndroidJUnit4::class)
class SheetGestureUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val h = SheetHarness()
    private val density get() = rule.density.density
    private fun dp(v: Float) = v * density
    private val slopPx get() = dp(8f)

    private fun start(rootHeight: SheetHeight, tablet: Boolean = false) {
        rule.setContent { h.Content(rootHeight, tablet) }
        rule.waitForIdle()
    }

    private val root get() = h.controller.root

    private fun sheetTop(entry: SheetEntry = h.controller.top): Float =
        rule.onNodeWithTag(SheetTags.sheet(entry)).fetchSemanticsNode().boundsInRoot.top

    private val rowPx get() = with(rule.density) { SheetHarness.ROW.roundToPx() }

    private fun scrollPx(entry: SheetEntry): Float {
        val s = rule.runOnIdle { h.scroll(entry.id, rowPx)!! }
        return (s.index * rowPx + s.offset).toFloat()
    }

    private fun down(at: Offset) = rule.onRoot().performTouchInput { down(at) }
    private fun moveBy(dy: Float) = rule.onRoot().performTouchInput { moveBy(Offset(0f, dy)) }
    private fun up() = rule.onRoot().performTouchInput { up() }

    @Test
    fun s10a_headerDragTracksTheFinger1to1_slopIncluded() {
        start(Half)
        val top0 = sheetTop()
        down(Offset(dp(60f), top0 + dp(30f))) // the header, left of the control
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
                assertEquals("finger $finger", finger, moved, 1f) // Δoffset == Δpointer, from the down point
            }
        }
        val ratio = (sheetTop() - top0) / finger
        assertEquals("1:1 ratio", 1f, ratio, 0.01f)
        up()
    }

    @Test
    fun s13_contentDragFromHalfRaisesToFullThenScrolls_sheetPlusScrollEqualsFinger() {
        start(Half)
        val e = root
        val top0 = sheetTop()
        val travel = top0 - h.controller.geometry(e).full
        down(Offset(dp(100f), top0 + dp(150f))) // the list
        var finger = 0f
        val step = -dp(12f)
        while (-finger < travel + dp(400f)) {
            moveBy(step)
            finger += step
            rule.waitForIdle()
            if (-finger < slopPx) continue
            val sheet = top0 - sheetTop()
            val scroll = scrollPx(e)
            assertEquals("finger ${-finger}: sheet $sheet + scroll $scroll", -finger, sheet + scroll, 1.5f)
        }
        assertEquals(h.controller.geometry(e).full, sheetTop(), 1f)
        assertTrue(scrollPx(e) > dp(300f))
        // slowly let go: the sheet stays at Full and the content keeps its scroll
        rule.onRoot().performTouchInput { moveBy(Offset(0f, 0f), delayMillis = 200); up() }
        rule.waitForIdle()
        assertEquals(Full, e.height)
    }

    @Test
    fun s13_atFullAnUpwardContentDragScrollsNatively() {
        start(Full)
        val top0 = sheetTop()
        rule.onNodeWithTag(SheetHarness.list(root.id)).performTouchInput {
            swipe(Offset(centerX, centerY + dp(150f)), Offset(centerX, centerY - dp(150f)), durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(top0, sheetTop(), 0.5f)
        assertEquals(Full, root.height)
        assertTrue(scrollPx(root) > dp(250f))
    }

    @Test
    fun s14_aFastFlingFromCollapsedMovesExactlyOneState() {
        start(Collapsed)
        val top0 = sheetTop()
        rule.onRoot().performTouchInput {
            swipe(Offset(dp(60f), top0 + dp(30f)), Offset(dp(60f), dp(80f)), durationMillis = 120)
        }
        rule.waitForIdle()
        assertEquals(Half, root.height) // past Full, still one state
        assertEquals(h.controller.geometry(root).half, sheetTop(), 1f)
    }

    @Test
    fun s14_aSlowDragSnapsToTheNearestState() {
        start(Collapsed)
        val top0 = sheetTop()
        val target = h.controller.geometry(root).half + dp(20f)
        rule.onRoot().performTouchInput {
            down(Offset(dp(60f), top0 + dp(30f)))
            val n = 40
            repeat(n) { moveBy(Offset(0f, (target - top0) / n), delayMillis = 40) }
            moveBy(Offset.Zero, delayMillis = 200)
            up()
        }
        rule.waitForIdle()
        assertEquals(Half, root.height)
    }

    @Test
    fun s10_aControlTapWorksAndADragFromItDoesNot() {
        start(Half)
        rule.onNodeWithTag(SheetHarness.control(root.id)).performClick()
        rule.waitForIdle()
        assertEquals(1, h.controlClicks)
        assertEquals(Half, root.height)

        val top0 = sheetTop()
        rule.onNodeWithTag(SheetHarness.control(root.id)).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -dp(10f))) }
        }
        rule.waitForIdle()
        assertEquals(top0 - dp(60f), sheetTop(), 1f) // the drag moved the sheet
        up()
        rule.waitForIdle()
        assertEquals("the control's press was cancelled", 1, h.controlClicks)
    }

    @Test
    fun s11_aHeaderBackgroundTapRaisesFromCollapsedAndOtherwiseCollapses() {
        start(Collapsed)
        val tapHeader = { rule.onNodeWithTag(SheetHarness.header(root.id)).performTouchInput { click(Offset(dp(40f), centerY)) } }
        tapHeader()
        rule.waitForIdle()
        assertEquals(Half, root.height)
        tapHeader()
        rule.waitForIdle()
        assertEquals(Collapsed, root.height)
        assertEquals(0, h.controlClicks)
    }

    @Test
    fun s7_hiddenSheetsAreNotComposed_s8_popRestoresHeightAndScroll() {
        start(Full)
        val state = h.stateOf(root.id)
        rule.runOnUiThread { runBlocking { h.scrollTo(root.id, 37, 12, rowPx) } }
        rule.waitForIdle()
        rule.runOnIdle { h.controller.push(SheetKind.Product("device--karr-bt")) }
        rule.waitForIdle()
        rule.onNodeWithTag(SheetTags.sheet(root)).assertDoesNotExist()
        rule.onNodeWithTag(SheetHarness.list(root.id)).assertDoesNotExist()
        assertEquals(Full, h.controller.top.height) // S-6: over a Full sheet it opens at Full

        rule.runOnIdle { h.controller.pop(PopReason.Close) }
        rule.waitForIdle()
        rule.onNodeWithTag(SheetHarness.list(root.id)).assertExists()
        assertNotEquals(state, h.stateOf(root.id)) // a new composition, built from the saved snapshot
        assertEquals(ScrollSnapshot(37, 12), rule.runOnIdle { h.scroll(root.id, rowPx) })
        assertEquals(Full, root.height)
        assertEquals(h.controller.geometry(root).full, sheetTop(), 1f)
    }

    @Test
    fun s9_theScrimShowsOverAFullPushedSheetAndATapOnItPops() {
        start(Full)
        rule.onNodeWithTag(SheetTags.SCRIM).assertDoesNotExist()
        rule.runOnIdle { h.controller.push(SheetKind.Filter) }
        rule.waitForIdle()
        rule.onNodeWithTag(SheetTags.SCRIM).assertExists()
        rule.onNodeWithTag(SheetTags.SCRIM).performTouchInput { click(Offset(centerX, dp(30f))) } // above the sheet
        rule.waitForIdle()
        assertEquals(1, h.controller.entries.size)
        rule.onNodeWithTag(SheetTags.SCRIM).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun s15_wheelDownRaisesToFullAndWheelUpAtTheTopLowersOneState() {
        start(Collapsed)
        rule.onNodeWithTag(SheetTags.sheet(root)).performMouseInput { moveTo(Offset(dp(60f), dp(30f))); scroll(3f) }
        rule.waitForIdle()
        assertEquals(Full, root.height)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag(SheetHarness.list(root.id)).performMouseInput { moveTo(center); scroll(-3f) }
        rule.waitForIdle()
        assertEquals(Half, root.height)
    }

    @Test
    fun t2_tabletPanelHeaderTogglesPartialAndTheChevronOpens() {
        start(Half, tablet = true)
        val panel = { rule.onNodeWithTag(SheetTags.PANEL).fetchSemanticsNode().boundsInRoot }
        val open = panel()
        assertEquals(PanelHeight.Open, root.panel)
        rule.onNodeWithTag(SheetTags.PANEL_MORE).assertDoesNotExist()

        rule.onNodeWithTag(SheetHarness.header(root.id)).performTouchInput { click(Offset(dp(40f), centerY)) }
        rule.waitForIdle()
        assertEquals(PanelHeight.Partial, root.panel)
        val partial = panel()
        assertEquals("the top edge stays put", open.top, partial.top, 0.5f)
        assertTrue(partial.height < open.height)
        assertTrue(partial.height >= dp(300f) - 1f || partial.height == open.height)

        rule.onNodeWithTag(SheetTags.PANEL_MORE).performClick()
        rule.waitForIdle()
        assertEquals(PanelHeight.Open, root.panel)
        assertEquals(open.height, panel().height, 0.5f)

        rule.runOnIdle { h.controller.onMapUserGesture() }
        rule.waitForIdle()
        assertEquals(PanelHeight.Partial, root.panel)
    }
}
