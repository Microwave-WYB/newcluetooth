package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * C-14 BlinkingDot: an 8 dp `--rec` circle blinking at `--t-blink` with a step (on for the first half, off for the
 * second, `steps(1, end)`). [paused] it is a static `--t3` dot. Reduced motion keeps it on.
 */
@Composable
fun BlinkingDot(paused: Boolean, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val alpha = if (paused || m.reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "blink")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = m.blinkMs
                    1f at 0 using StepEasing
                    0f at m.blinkMs / 2 using StepEasing
                },
                RepeatMode.Restart,
            ),
            label = "blinkAlpha",
        )
        a
    }
    Box(
        modifier
            .size(Css.RecDot.width)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(if (paused) c.t3 else c.rec),
    )
}

/** A hard step: holds the start value until the next keyframe. */
private val StepEasing = androidx.compose.animation.core.Easing { if (it < 1f) 0f else 1f }

/**
 * C-15 Handle: a 36 × 4 dp `--div` pill centered in the 14 dp handle zone at the top of a phone sheet. Tablet has no
 * handle zone, so it draws nothing there.
 */
@Composable
fun Handle(modifier: Modifier = Modifier, tablet: Boolean = false) {
    if (tablet) return
    Box(modifier.fillMaxWidth().height(Tokens.handleZone), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(Css.HandleBefore.width, Css.HandleBefore.height)
                .clip(RoundedCornerShape(Css.HandleBefore.borderRadius))
                .background(CluetoothTheme.colors.div),
        )
    }
}

/**
 * C-16 SheetFade: while the content is [scrolled] away from the top, a 24 dp gradient from the sheet background
 * ([color], `--panel`) to transparent shows just under the header; it fades over `--t-fast` and takes no touches.
 */
@Composable
fun SheetFade(scrolled: Boolean, modifier: Modifier = Modifier, color: Color = CluetoothTheme.colors.panel) {
    val a by animateFloatAsState(if (scrolled) 1f else Css.SheetFadeAfter.opacity, CluetoothTheme.motion.fast(), label = "fade")
    Box(
        modifier
            .fillMaxWidth()
            .height(Css.SheetFadeAfter.height)
            .alpha(a)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))) },
    )
}
