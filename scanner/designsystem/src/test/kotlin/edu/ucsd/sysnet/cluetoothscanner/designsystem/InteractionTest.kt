package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SegmentedSize
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class InteractionTest {
    @get:Rule val compose = createComposeRule()

    /** C-2: a 40 dp (and the island's 36 dp) IconButton takes taps up to its 44 dp touch target. */
    @Test fun touchTargetIs44() {
        var taps = 0
        compose.setContent {
            CluetoothTheme {
                Box(Modifier.padding(40.dp)) {
                    IconButton(Glyphs.stop, "Stop", { taps++ }, kind = IconButtonKind.Rec, size = Tokens.islandIb)
                }
            }
        }
        val node = compose.onNodeWithContentDescription("Stop")
        node.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        val px = with(compose.density) { 3.5.dp.toPx() } // 36 → 44 adds 4 dp per side
        node.performTouchInput { click(Offset(-px, height / 2f)) }
        node.performTouchInput { click(Offset(width + px, height / 2f)) }
        compose.runOnIdle { assertEquals(2, taps) }
        node.performTouchInput { click(Offset(-with(compose.density) { 6.dp.toPx() }, height / 2f)) }
        compose.runOnIdle { assertEquals(2, taps) }
    }

    /** A disabled Button, IconButton and FloatButton take no clicks and report themselves disabled. */
    @Test fun disabledControls() {
        var taps = 0
        compose.setContent {
            CluetoothTheme {
                androidx.compose.foundation.layout.Column {
                    edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button("Next", { taps++ }, enabled = false)
                    IconButton(Glyphs.pause, "Pause", { taps++ }, enabled = false)
                    edu.ucsd.sysnet.cluetoothscanner.designsystem.components.FloatButton(Glyphs.menu, "Menu", { taps++ }, enabled = false)
                }
            }
        }
        compose.onNodeWithText("Next").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Pause").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Menu").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, taps) }
    }

    @Composable
    private fun Probe(target: Boolean, out: (Float) -> Unit) {
        val v by animateDpAsState(if (target) Tokens.swapX else 0.dp, CluetoothTheme.motion.std(), label = "probe")
        out(v.value)
        Box(Modifier.testTag("probe"))
    }

    /** Two frames after the target changes, under [reduced] motion or not. */
    private fun afterTwoFrames(reduced: Boolean): Pair<Float, Float> {
        var target by mutableStateOf(false)
        var value = -1f
        compose.mainClock.autoAdvance = false
        compose.setContent { CluetoothTheme(reducedMotion = reduced) { Probe(target) { value = it } } }
        compose.mainClock.advanceTimeByFrame()
        target = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = value
        compose.mainClock.advanceTimeBy(Tokens.tStdMs.toLong() + 100)
        return early to value
    }

    /** G-7: with reduced motion a transition reaches its end on the first frame. */
    @Test fun reducedMotionIsInstant() {
        val (early, end) = afterTwoFrames(reduced = true)
        assertEquals(Tokens.swapX.value, early)
        assertEquals(Tokens.swapX.value, end)
    }

    /** G-7: without it, the same transition runs over `--t-std`. */
    @Test fun motionAnimates() {
        val (early, end) = afterTwoFrames(reduced = false)
        assert(early < Tokens.swapX.value) { "early $early" }
        assertEquals(Tokens.swapX.value, end)
    }

    /**
     * C-4: a label is never cut by its option's padding. "This area" in the 184 dp header (90 dp per option, 58 dp
     * after the 16 dp padding) keeps its full width, as the CSS's overflowing nowrap text does.
     */
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real text widths
    @Test fun segmentedLabelsAreNotClipped() {
        var full = 0
        compose.setContent {
            CluetoothTheme {
                // the label's natural width, in the unselected option's style
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                full = measurer.measure(Strings.ThisArea, CluetoothTheme.type.control, maxLines = 1, softWrap = false).size.width
                Segmented(listOf(Strings.ThisArea, Strings.All), 1, {}, Modifier.width(184.dp), size = SegmentedSize.Header)
            }
        }
        compose.waitForIdle()
        val shown = compose.onNodeWithTag("segLabel:0", useUnmergedTree = true).fetchSemanticsNode().size.width
        assert(full > with(compose.density) { 58.dp.roundToPx() }) { "the label is wider than the padded option ($full px)" }
        assertEquals(full, shown)
    }
}
