package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
}
