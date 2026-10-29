package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Weights
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.BlinkingDot
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import kotlinx.coroutines.delay

/**
 * R-3: the elapsed time, ticked locally from the recorder's active time before the current run and the run's start
 * (elapsed = before + (now − since)); it stands still while paused (since is null).
 */
@Composable
fun rememberElapsedMs(activeBeforeMs: Long, runningSinceMs: Long?, now: () -> Long = System::currentTimeMillis): Long {
    var t by remember { mutableLongStateOf(now()) }
    LaunchedEffect(runningSinceMs) {
        while (runningSinceMs != null) {
            t = now()
            delay(1000 - t % 1000)
        }
    }
    return activeBeforeMs + (if (runningSinceMs != null) (t - runningSinceMs).coerceAtLeast(0) else 0)
}

/** The callbacks of the recorder control; :app forwards them to `Recorder` (B) and the R-5 fit action. */
class RecorderActions(
    val onStart: () -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onStop: () -> Unit,
    /** R-5: collapse the panel, close any page and fit the main map to the route. */
    val onMiniMapClick: () -> Unit,
)

/**
 * R-1 … R-9, T-7: the record FAB, the island capsule and its panel as one element that morphs (`--t-morph`).
 * It fills the screen box it is given and positions itself ([RecorderGeometry]). While it is a [RecorderUi.Form.Fab]
 * :app places it in the layer above the map and below the sheets (P-3); recording, above the sheets (R-3).
 * While the panel is open a full-screen catch layer collapses it on a tap outside (R-4).
 *
 * @param miniMap the R-5 route mini map (E's `RouteMiniMap`), drawn non-interactive inside the panel
 */
@Composable
fun RecorderControl(
    ui: RecorderUi,
    actions: RecorderActions,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    bottomCover: Dp = 0.dp,
    searching: Boolean = false,
    miniMap: @Composable (Modifier) -> Unit = {},
) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val timeStyle = CluetoothTheme.type.body.copy(fontWeight = Weights.Medium).tabular()
    val time = Format.elapsed(ui.elapsedMs)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeW = with(density) { measurer.measure(time, timeStyle).size.width.toDp() }
    val groupW = Tokens.recDot + Css.RecOpen.gap + timeW
    BoxWithConstraints(modifier.fillMaxSize()) {
        val g = RecorderGeometry.compute(ui.form, tablet, maxWidth, maxHeight, groupW, bottomCover, searching)
        val x by animateDpAsState(g.x, m.morph(), label = "recX")
        val y by animateDpAsState(g.y, m.morph(), label = "recY")
        val w by animateDpAsState(g.width, m.morph(), label = "recW")
        val h by animateDpAsState(g.height, m.morph(), label = "recH")
        val r by animateDpAsState(g.radius, m.morph(), label = "recR")
        val groupX by animateDpAsState(g.groupX, m.morph(), label = "recGroup")
        val rowTop by animateDpAsState(g.rowTop, m.morph(), label = "recRowTop")
        val rowH by animateDpAsState(g.rowHeight, m.morph(), label = "recRowH")
        val fab = ui.form == RecorderUi.Form.Fab
        val panel = ui.form == RecorderUi.Form.Panel
        val bg by animateColorAsState(if (fab) c.rec else c.panel, m.std(), label = "recBg")
        val dotAlpha by animateFloatAsState(if (fab) 1f else 0f, m.fast(), label = "fabDotA")
        val dotScale by animateFloatAsState(if (fab) 1f else FabDotCollapsedScale, m.std(), label = "fabDotS")
        val rowAlpha by animateFloatAsState(if (fab) 0f else 1f, if (fab) m.fast() else m.spec(Tokens.tFastMs, Tokens.tFastMs), label = "rowA")
        val extraAlpha by animateFloatAsState(if (panel) 1f else 0f, if (panel) m.spec(Tokens.tFastMs, Tokens.tFastMs) else m.fast(), label = "extraA")
        val shape = RoundedCornerShape(r)

        if (panel) {
            Box(
                Modifier.fillMaxSize().testTag("recorderCatch")
                    .clickable(remember { MutableInteractionSource() }, null, onClick = actions.onCollapse),
            )
        }
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                .size(w, h)
                .tokenShadow(if (panel) CluetoothTheme.shadows.shadowRaised else CluetoothTheme.shadows.shadowFloat, shape)
                .clip(shape)
                .background(bg)
                .testTag("recorder")
                .then(
                    if (fab) Modifier.semantics { contentDescription = Strings.Record }
                        .clickable(remember { MutableInteractionSource() }, null, onClick = actions.onStart)
                    else Modifier.semantics { contentDescription = Strings.Recording },
                ),
        ) {
            // the FAB's white dot
            Box(
                Modifier.align(Alignment.Center).size(Tokens.fabDot).scale(dotScale).alpha(dotAlpha)
                    .clip(CircleShape).background(c.onRec),
            )
            if (!fab || rowAlpha > 0f) {
                // the panel's extras: below the row on phone, above it on tablet
                val extrasTop = if (g.extrasBelowRow) rowTop + rowH else Dp(0f)
                if (panel || extraAlpha > 0f) {
                    Box(Modifier.offset(y = extrasTop).fillMaxWidth().alpha(extraAlpha)) {
                        val metaTop = if (g.extrasBelowRow) Css.RecorderTop_RecMeta.paddingTop else Css.RecMeta.padding.top
                        Text(
                            Strings.recorderMeta(ui.deviceCount, ui.fix?.let { Format.location(it.lat, it.lon, it.accuracyM) }),
                            Modifier.padding(start = Css.RecMeta.padding.left, end = Css.RecMeta.padding.right, top = metaTop),
                            style = CluetoothTheme.type.sub,
                            color = c.t2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val mapShape = RoundedCornerShape(edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric.recorderMap(tablet))
                        Box(
                            Modifier
                                .padding(top = metaTop + Css.RecMeta.lineHeight + Css.RecMap.margin.top)
                                .padding(horizontal = Css.RecMap.margin.left)
                                .fillMaxWidth()
                                .height(Css.RecMap.height)
                                .clip(mapShape)
                                .background(c.fill2)
                                .testTag("recorderMiniMap")
                                .clickable(remember { MutableInteractionSource() }, null, enabled = panel, onClick = actions.onMiniMapClick),
                        ) { miniMap(Modifier.fillMaxSize()) }
                    }
                }
                // the row: dot + time (tap: expand or collapse), Pause/Resume and Stop at the concentric inset
                Box(Modifier.offset(y = rowTop).fillMaxWidth().height(rowH).alpha(rowAlpha)) {
                    Row(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = groupX)
                            .testTag("recorderOpen")
                            .semantics { contentDescription = Strings.ShowRecordingDetails }
                            .clickable(remember { MutableInteractionSource() }, null, enabled = !fab) {
                                if (panel) actions.onCollapse() else actions.onExpand()
                            },
                        horizontalArrangement = Arrangement.spacedBy(Css.RecOpen.gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BlinkingDot(paused = ui.paused)
                        Text(time, style = timeStyle, maxLines = 1, softWrap = false)
                    }
                    Row(
                        Modifier.align(Alignment.CenterEnd).padding(end = g.buttonsInset),
                        horizontalArrangement = Arrangement.spacedBy(g.buttonGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ui.paused) {
                            IconButton(Glyphs.play, Strings.Resume, actions.onResume, size = g.buttonSize, enabled = !fab)
                        } else {
                            IconButton(Glyphs.pause, Strings.Pause, actions.onPause, size = g.buttonSize, enabled = !fab)
                        }
                        IconButton(Glyphs.stop, Strings.Stop, actions.onStop, kind = IconButtonKind.Rec, size = g.buttonSize, enabled = !fab)
                    }
                }
            }
        }
    }
}

/** `.recorder.capsule::after { transform: scale(0.3) }`: the FAB's dot shrinks as it fades. */
private const val FabDotCollapsedScale = 0.3f
