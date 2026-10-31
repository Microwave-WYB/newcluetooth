package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Handle
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SheetFade
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Icon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import kotlin.math.roundToInt
import androidx.compose.ui.unit.min

/** The colors the engine draws itself (G-1): `--panel`, `--scrim` and `--t2` for the T-2 chevron. */
data class SheetColors(val panel: Color, val scrim: Color, val chevron: Color) {
    companion object {
        @Composable
        fun theme() = CluetoothTheme.colors.let { SheetColors(panel = it.panel, scrim = it.scrim, chevron = it.t2) }
    }
}

object SheetTags {
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
fun PhoneSheetHost(
    controller: SheetStackController,
    colors: SheetColors = SheetColors.theme(),
    modifier: Modifier = Modifier,
    metrics: SheetMetrics = SheetMetrics(),
    sheet: @Composable (SheetEntry) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = constraints.maxHeight.toFloat()
        val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
        // the phone's top row sits below the status bar (the app is edge to edge); Full sits below that row (S-5)
        val topInset = WindowInsets.statusBars.getTop(density).toFloat()
        SideEffect { controller.setScreen(heightPx, density.density, bottomInset, topInset) }
        val sheetHeight = maxHeight - metrics.topZone - with(density) { topInset.toDp() }
        val top = controller.top
        val scrimAlpha by animateFloatAsState(
            if (controller.scrimVisible) 1f else 0f,
            tween(if (metrics.reducedMotion) 0 else metrics.pageMs, easing = metrics.ease),
            label = "scrim",
        )
        val slopPx = with(density) { metrics.slop.toPx() }
        val sheetShape = RoundedCornerShape(topStart = metrics.sheetRadius, topEnd = metrics.sheetRadius)
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
                        .tokenShadow(CluetoothTheme.shadows.shadowSheet, sheetShape)
                        .clip(sheetShape)
                        .background(colors.panel)
                        .sheetWheel(controller, entry)
                        .sheetGestures(controller, entry),
                ) {
                    SheetContentSlop(slopPx) { sheet(entry) }
                    // S-1: resting at Collapsed, the strip under the header above the navigation bar stays plain panel,
                    // so the content's first row never shows behind the gesture bar.
                    if (entry.height == SheetHeight.Collapsed && entry.liveOffset == null && !entry.peek.isNaN()) {
                        Box(
                            Modifier
                                .offset { IntOffset(0, entry.peek.roundToInt()) }
                                .fillMaxWidth()
                                .height(with(density) { bottomInset.toDp() })
                                .background(colors.panel),
                        )
                    }
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
 * Partial keeps the top edge and shrinks to `--panel-partial-h` (45 %, at least about three rows), with a fade and a
 * chevron that opens it again. A header tap toggles; there is no drag, no Half and no scrim.
 */
@Composable
fun TabletPanelHost(
    controller: SheetStackController,
    colors: SheetColors = SheetColors.theme(),
    modifier: Modifier = Modifier,
    metrics: SheetMetrics = SheetMetrics(),
    sheet: @Composable (SheetEntry) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val column = maxHeight - metrics.topZone - metrics.edge
        val partial = min(column, metrics.panelPartial(maxHeight))
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
                .tokenShadow(CluetoothTheme.shadows.shadowRaised, RoundedCornerShape(metrics.panelRadius))
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
                    // `.panel-more-btn svg`: the handle's width (36 × 8 dp) with a 2 dp stroke; Icon's size is the height
                    Icon(Glyphs.panelMore, Strings.ExpandPanel, size = Css.PanelMoreBtn_Svg.height, tint = colors.chevron, strokeWidth = Css.PanelMoreBtn_Svg.strokeWidth)
                }
            }
        }
    }
}

/**
 * A sheet's frame: the header (on phone with the handle zone above it) and the body. The header's measured
 * bottom is the sheet's peek (S-5) and the boundary between header drags and content gestures (S-10, S-13).
 */
@Composable
fun SheetFrame(
    controller: SheetStackController,
    entry: SheetEntry,
    metrics: SheetMetrics = SheetMetrics(),
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().onSizeChanged { controller.setPeek(entry, it.height.toFloat()) }) {
            Handle(tablet = controller.tablet)
            // S-2, S-3, T-3: the header line's centre sits on the corner arc's centre (48 dp deep on phone, under
            // the handle zone; 30 dp on tablet). Like `.sheet-top`'s min-height, a one-line header gets 8 dp under
            // it; a taller one (the device head) ends at its own bottom.
            Box(
                Modifier.fillMaxWidth().padding(top = Concentric.cornerButton(controller.tablet))
                    .defaultMinSize(minHeight = Tokens.sheetHeaderH + HEADER_BELOW),
            ) { header() }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            body()
            // C-16: while the content is scrolled away from its top, a fade under the header; it takes no touches
            SheetFade(scrolled = entry.content?.canScrollBackward == true, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The stack for one session view, animating in the composition's frame clock. */
@Composable
fun rememberSheetStackController(
    metrics: SheetMetrics = SheetMetrics(),
    hooks: SheetStackHooks = SheetStackHooks(),
    rootHeight: SheetHeight = SheetHeight.Collapsed,
): SheetStackController {
    val scope = rememberCoroutineScope()
    return remember { SheetStackController(scope, composeOffsetAnimator(metrics), metrics, hooks, rootHeight) }
}

/** The space under a sheet's header line: `.sheet-top` min-height = corner + header / 2 + 8 px. */
private val HEADER_BELOW = Tokens.panelPad
