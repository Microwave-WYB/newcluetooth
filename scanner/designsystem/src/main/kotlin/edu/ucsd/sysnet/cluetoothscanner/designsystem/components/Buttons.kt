package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Weights
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyph
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * C-1 FloatButton: a 44 dp `--fill1` circle with a 20 dp `--t1` glyph and `--shadow-float`; [on] is `--fill2`.
 * [tint] overrides the glyph color (the location warning uses `--warn`).
 */
@Composable
fun FloatButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val c = CluetoothTheme.colors
    val bg by animateColorAsState(if (on) c.fill2 else c.fill1, CluetoothTheme.motion.fast(), label = "rb")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .disabledDim(enabled)
            .size(Css.Rb.width)
            .tokenShadow(CluetoothTheme.shadows.shadowFloat, CircleShape)
            .clip(CircleShape)
            .background(bg)
            .plainClickable(source, enabled, label = contentDescription, onClick = onClick)
            .focusRing(source, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, null, size = Tokens.rbIcon, tint = if (tint == Color.Unspecified) c.t1 else tint)
    }
}

/** C-2 IconButton kinds: neutral `--fill2`/`--t1`, rec `--rec-red`/white, selected `--chip-on-bg`/`--chip-on-fg`
 *  (the Filter button while filters are on), and plain (no fill; the search bar's X). */
enum class IconButtonKind { Neutral, Rec, Selected, Plain }

/**
 * C-2 IconButton: a 40 dp circle (`--icon-btn`; the island uses 36 dp) with a 16 dp glyph. The touch target is at
 * least 44 dp (the theme's minimum touch target), without changing the layout. [badge] draws the D-1 count badge.
 */
@Composable
fun IconButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IconButtonKind = IconButtonKind.Neutral,
    size: Dp = Tokens.iconBtn,
    badge: Int? = null,
    enabled: Boolean = true,
) {
    val c = CluetoothTheme.colors
    val (bgTarget, fg) = when (kind) {
        IconButtonKind.Neutral -> c.fill2 to c.t1
        IconButtonKind.Rec -> c.recRed to c.onRec
        IconButtonKind.Selected -> c.chipOnBg to c.chipOnFg
        IconButtonKind.Plain -> Color.Transparent to c.t1
    }
    val bg by animateColorAsState(bgTarget, CluetoothTheme.motion.fast(), label = "ib")
    val source = remember { MutableInteractionSource() }
    Box(modifier.disabledDim(enabled).size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .plainClickable(source, enabled, label = contentDescription, onClick = onClick)
                .focusRing(source, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, null, size = Tokens.iconBtnGlyph, tint = fg)
        }
        if (badge != null && badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The Filter button's count (`.count-badge`): a `--count-badge` pill in `--blue` with an `--on-blue` 11 sp tabular
 * number and a 2 dp `--panel` ring, inside the button's box so a rounded parent never clips it.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val ring = Css.CountBadge.boxShadowLengths[3]
    val pill = RoundedCornerShape(Tokens.rPill)
    Box(
        modifier
            .drawBehind {
                val r = ring.toPx()
                drawRoundRect(
                    c.panel,
                    topLeft = Offset(-r, -r),
                    size = Size(size.width + 2 * r, size.height + 2 * r),
                    cornerRadius = CornerRadius(size.height / 2 + r),
                )
            }
            .defaultMinSize(minWidth = Css.CountBadge.minWidth)
            .height(Css.CountBadge.height)
            .clip(pill)
            .background(c.blue)
            .padding(horizontal = Css.CountBadge.padding.left),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = CluetoothTheme.type.small.copy(
                fontSize = Css.CountBadge.fontSize.sp,
                lineHeight = Css.CountBadge.fontSize.sp,
                fontWeight = Weights.Medium,
            ).tabular(),
            color = c.onBlue,
        )
    }
}

/**
 * A disabled control is drawn at `--dim`, the opacity of stale rows (the prototype has no disabled style of its
 * own); it still takes no clicks.
 */
internal fun Modifier.disabledDim(enabled: Boolean): Modifier = if (enabled) this else alpha(Tokens.dim)

/** C-10 Button kinds: primary `--blue`/`--on-blue`, tonal `--fill2`/`--t1`. */
enum class ButtonKind { Primary, Tonal }

/** C-10 Button: a 40 dp pill, 18 dp padding, weight 500; [small] is 32 dp, 14 dp padding, 14 sp. */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    small: Boolean = false,
    enabled: Boolean = true,
    leading: Glyph? = null,
) {
    val c = CluetoothTheme.colors
    val (bg, fg) = if (kind == ButtonKind.Primary) c.blue to c.onBlue else c.fill2 to c.t1
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.rPill)
    val h = if (small) Css.BtnSmall.height else Css.Btn.height
    val pad = if (small) Css.BtnSmall.padding.left else Css.Btn.padding.left
    val base = if (small) CluetoothTheme.type.control else CluetoothTheme.type.body
    Row(
        modifier
            .disabledDim(enabled)
            .height(h)
            .clip(shape)
            .background(bg)
            .plainClickable(source, enabled, onClick = onClick)
            .focusRing(source, shape)
            .padding(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(Css.Btn.gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Icon(leading, null, tint = fg)
        Text(label, style = base.copy(fontWeight = Weights.Medium), color = fg, maxLines = 1)
    }
}

/** `.text-btn`: a `--blue` weight-500 text button ("Search everywhere", "Clear"), 6 × 8 dp padding, `--r-item`. */
@Composable
fun TextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.rItem)
    Box(
        modifier
            .clip(shape)
            .plainClickable(source, onClick = onClick)
            .focusRing(source, shape)
            .padding(horizontal = Css.TextBtn.padding.left, vertical = Css.TextBtn.padding.top),
    ) {
        Text(label, style = CluetoothTheme.type.body.copy(fontWeight = Weights.Medium), color = CluetoothTheme.colors.blue)
    }
}
