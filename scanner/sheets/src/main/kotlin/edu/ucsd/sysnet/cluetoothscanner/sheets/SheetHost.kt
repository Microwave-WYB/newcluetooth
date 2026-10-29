package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/** The colors the engine draws itself; from :designsystem tokens once it merges. */
internal data class SheetColors(
    val panel: Color,     // --panel
    val scrim: Color,     // --scrim
    val chevron: Color,   // --t2 (T-2)
)

internal object SheetTags {
    fun sheet(entry: SheetEntry) = "sheet-${entry.id}"
    const val SCRIM = "sheet-scrim"
    const val PANEL = "sheet-panel"
    const val PANEL_MORE = "sheet-panel-more"
}

/**
 * The phone sheet layer (S-1, S-5..S-10a, S-13..S-15): fills the screen above the map and below the top chrome,
 * and draws only what [SheetStackController.rendered] holds. It takes no touches outside the sheets, so the map
 * under it stays usable (S-9 at Half and Collapsed).
 */
@Composable
internal fun PhoneSheetHost(
    controller: SheetStackController,
    colors: SheetColors,
    modifier: Modifier = Modifier,
    metrics: SheetMetrics = SheetMetrics(),
    sheet: @Composable (SheetEntry) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = constraints.maxHeight.toFloat()
        val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
        SideEffect { controller.setScreen(heightPx, density.density, bottomInset) }
        val sheetHeight = maxHeight - metrics.topZone
        val top = controller.top
        val scrimAlpha by animateFloatAsState(
            if (controller.scrimVisible) 1f else 0f,
            tween(if (metrics.reducedMotion) 0 else metrics.pageMs, easing = metrics.ease),
            label = "scrim",
        )
        val slopPx = with(density) { metrics.slop.toPx() }
        for (entry in controller.rendered) {
            if (entry === top && scrimAlpha > 0f) {
                key("scrim") { Scrim(colors.scrim, scrimAlpha) { controller.pop(PopReason.Scrim) } }
            }
            key(entry.id) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(sheetHeight)
                        .sheetOffset(controller, entry)
                        .testTag(SheetTags.sheet(entry))
                        .clip(RoundedCornerShape(topStart = metrics.sheetRadius, topEnd = metrics.sheetRadius))
                        .background(colors.panel)
                        .sheetWheel(controller, entry)
                        .sheetGestures(controller, entry),
                ) {
                    SheetContentSlop(slopPx) { sheet(entry) }
                }
            }
        }
    }
}

@Composable
private fun Scrim(color: Color, alpha: Float, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(SheetTags.SCRIM)
            .alpha(alpha)
            .background(color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    )
}

/**
 * The tablet panel (T-1, T-2): one floating column at the left holding only the top level. Open is the full column;
 * Partial keeps the top edge and shrinks to 45 % of it (at least [SheetMetrics.panelPartialMin]), with a fade and a
 * chevron that opens it again. A header tap toggles; there is no drag, no Half and no scrim.
 */
@Composable
internal fun TabletPanelHost(
    controller: SheetStackController,
    colors: SheetColors,
    modifier: Modifier = Modifier,
    metrics: SheetMetrics = SheetMetrics(),
    sheet: @Composable (SheetEntry) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val column = maxHeight - metrics.topZone - metrics.edge
        val partial = min(column, max(column * metrics.panelPartialFraction, metrics.panelPartialMin))
        val top = controller.top
        val open = top.panel == PanelHeight.Open
        val ms = if (metrics.reducedMotion) 0 else metrics.stdMs
        val height by animateDpAsState(if (open) column else partial, tween(ms, easing = metrics.ease), label = "panel")
        val moreAlpha by animateFloatAsState(if (open) 0f else 1f, tween(ms, easing = metrics.ease), label = "more")
        Box(
            Modifier
                .offset(x = metrics.edge, y = metrics.topZone)
                .width(metrics.panelWidth)
                .height(height)
                .testTag(SheetTags.PANEL)
                .clip(RoundedCornerShape(metrics.panelRadius))
                .background(colors.panel),
        ) {
            key(top.id) {
                Box(Modifier.fillMaxSize().sheetGestures(controller, top)) { sheet(top) }
            }
            if (moreAlpha > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(metrics.panelFade)
                        .alpha(moreAlpha)
                        .background(Brush.verticalGradient(listOf(colors.panel.copy(alpha = 0f), colors.panel))),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(metrics.panelMoreWidth)
                        .height(metrics.floatButton)
                        .alpha(moreAlpha)
                        .testTag(SheetTags.PANEL_MORE)
                        .clickable(enabled = !open, onClick = controller::expandPanel),
                    contentAlignment = Alignment.Center,
                ) {
                    Chevron(colors.chevron)
                }
            }
        }
    }
}

/** T-2's shallow V, handle-sized. */
@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(width = 36.dp, height = 10.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width / 2, size.height), Offset(size.width, 0f), stroke, StrokeCap.Round)
    }
}

/**
 * A sheet's frame: the header (on phone with the handle zone above it) and the body. The header's measured
 * bottom is the sheet's peek (S-5) and the boundary between header drags and content gestures (S-10, S-13).
 */
@Composable
internal fun SheetFrame(
    controller: SheetStackController,
    entry: SheetEntry,
    metrics: SheetMetrics = SheetMetrics(),
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().onSizeChanged { entry.peek = it.height.toFloat() }) {
            if (!controller.tablet) Box(Modifier.fillMaxWidth().height(metrics.handleZone))
            header()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { body() }
    }
}

/** The stack for one session view, animating in the composition's frame clock. */
@Composable
internal fun rememberSheetStackController(
    metrics: SheetMetrics = SheetMetrics(),
    hooks: SheetStackHooks = SheetStackHooks(),
    root: SheetKind = SheetKind.Discover,
    rootHeight: SheetHeight = SheetHeight.Collapsed,
): SheetStackController {
    val scope = rememberCoroutineScope()
    return remember { SheetStackController(scope, composeOffsetAnimator(metrics), metrics, hooks, root, rootHeight) }
}
