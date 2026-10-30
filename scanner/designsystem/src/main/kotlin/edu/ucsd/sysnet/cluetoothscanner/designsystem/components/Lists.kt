package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.RssiBand
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Weights
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** G-4: the band color of an RSSI. */
@Composable
fun bandColor(rssi: Int): Color = bandColor(Format.band(rssi))

@Composable
fun bandColor(band: RssiBand): Color = when (band) {
    RssiBand.Strong -> CluetoothTheme.colors.rssiStrong
    RssiBand.Fair -> CluetoothTheme.colors.rssiFair
    RssiBand.Weak -> CluetoothTheme.colors.rssiWeak
}

/**
 * C-5 DeviceRow: two columns, at least 64 dp, 8 × 16 dp padding, radius 12 (22 in the tablet panel, G-13a).
 * Left the name (one line, or "No name" in `--t3`) over the MAC (13/20 `--t2`, tabular); right the RSSI number,
 * 34 dp, right-aligned, weight 500, with "−", in the band color (G-4). Rows have no sparkline: the RSSI chart is only
 * in the device sheet (D-10). [stale] rows use `--dim`; pressed and [selected] rows are `--fill2`.
 */
@Composable
fun DeviceRow(
    name: String?,
    mac: String,
    rssi: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    selected: Boolean = false,
    radius: Dp = Css.Row.borderRadius,
) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    val alpha by animateFloatAsState(if (stale) Css.RowStale.opacity else 1f, CluetoothTheme.motion.std(), label = "stale")
    val band = rssi?.let { bandColor(it) } ?: c.t3
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Css.Row.minHeight)
            .alpha(alpha)
            .clip(shape)
            .background(pressedFill(source, selected))
            .plainClickable(source, onClick = onClick)
            .focusRing(source, shape)
            .padding(horizontal = Css.Row.padding.left, vertical = Css.Row.padding.top),
        horizontalArrangement = Arrangement.spacedBy(Css.Row.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name ?: Strings.NoName, color = if (name == null) c.t3 else c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mac, style = t.sub.tabular(), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            rssi?.let { Format.signed(it) } ?: "",
            Modifier.width(Css.RowRssi.width),
            style = t.body.copy(fontWeight = Weights.Medium).tabular(),
            color = band,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** RSSI samples as `core-api` sends them (the device chart, D-10): one byte per bucket, −RSSI clamped to 30..100, 0 a gap (§2.5). */
fun sparkRssi(b: Byte): Int? = (b.toInt() and 0xff).takeIf { it != 0 }?.let { -it }

/**
 * C-12 ListItem: at least 56 dp, 8 × 16 dp padding, radius 12, a 12 dp gap: the title, an optional sub line
 * (13/20 `--t2`) and an optional trailing value, chevron or control. Pressed and [selected] are `--fill2`.
 * Without [onClick] it is a plain row (Settings rows with a switch).
 */
@Composable
fun ListItem(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    end: String? = null,
    chevron: Boolean = false,
    selected: Boolean = false,
    radius: Dp = Css.Item.borderRadius,
    titleColor: Color = Color.Unspecified,
    /** The side padding: `--gutter`; 0 inside the onboarding body (`.ob-body .item`). */
    horizontalPadding: Dp = Css.Item.padding.left,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Css.Item.minHeight)
            .clip(shape)
            .background(pressedFill(source, selected))
            .then(if (onClick != null) Modifier.plainClickable(source, onClick = onClick).focusRing(source, shape) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = Css.Item.padding.top),
        horizontalArrangement = Arrangement.spacedBy(Css.Item.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor)
            if (sub != null) Text(sub, style = t.sub.tabular(), color = c.t2)
        }
        if (end != null) Text(end, style = t.body.tabular(), color = c.t2, maxLines = 1)
        trailing?.invoke()
        if (chevron) Icon(Glyphs.chevron, null, tint = c.t3)
    }
}

/** C-13 Section label: 12.5/18 `--t3`, padding 16 16 4. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(
            start = Css.Sect.padding.left,
            top = Css.Sect.padding.top,
            end = Css.Sect.padding.right,
            bottom = Css.Sect.padding.bottom,
        ),
        style = CluetoothTheme.type.small,
        color = CluetoothTheme.colors.t3,
    )
}

/** D-5 / Q-4 empty text (`.empty`): 14 sp `--t2`, centered, 24 × 16 dp padding; [action] below it (Q-4). */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Css.Empty.padding.left, vertical = Css.Empty.padding.top),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = CluetoothTheme.type.control.copy(fontSize = Css.Empty.fontSize.sp, lineHeight = Tokens.bodyLine.sp),
            color = CluetoothTheme.colors.t2,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Box(Modifier.padding(top = Css.TextBtn.marginTop)) { action() }
        }
    }
}
