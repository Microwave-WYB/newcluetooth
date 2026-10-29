package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.LocalContentColor
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyph
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** Text in a [CluetoothTheme] style; the color defaults to the content color (`--t1`). */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CluetoothTheme.type.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    val c = color.takeOrElse { LocalContentColor.current.takeOrElse { CluetoothTheme.colors.t1 } }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = c, textAlign = textAlign ?: style.textAlign),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this != Color.Unspecified) this else block()

/** The default stroke of every glyph: `.icon svg { stroke-width: 1.8 }`. */
val GlyphStroke: Float = Css.Icon_Svg.strokeWidth

/** The [ImageVector] of a glyph at a stroke width (in the glyph's viewBox units); solid paths are filled. */
fun Glyph.toImageVector(strokeWidth: Float = GlyphStroke): ImageVector {
    val b = ImageVector.Builder(name, viewportWidth.dp, viewportHeight.dp, viewportWidth, viewportHeight)
    for (p in paths) {
        if (p.solid) {
            b.addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
        } else {
            b.addPath(
                addPathNodes(p.d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return b.build()
}

/** C-17: a stroke glyph, [size] square (`--icon` 17 dp by default), tinted. */
@Composable
fun Icon(
    glyph: Glyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = Tokens.icon,
    tint: Color = Color.Unspecified,
    strokeWidth: Float = GlyphStroke,
) {
    val vector = remember(glyph, strokeWidth) { glyph.toImageVector(strokeWidth) }
    val c = tint.takeOrElse { LocalContentColor.current.takeOrElse { CluetoothTheme.colors.t1 } }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier.size(size * (glyph.viewportWidth / glyph.viewportHeight).coerceAtLeast(1f), size),
        colorFilter = ColorFilter.tint(c),
    )
}

/**
 * A clickable without a ripple: the components draw their own pressed state (`:active` → `--fill2`). Its focus ring
 * comes from [focusRing].
 */
fun Modifier.plainClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    label: String? = null,
    onClick: () -> Unit,
): Modifier = this
    .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = role, onClick = onClick)

/**
 * G-6: the focus ring is inset (2 dp `--focus-color` at offset −2 dp), drawn over the content inside the element's own
 * bounds, so no clipping or scrolling parent can cut it off.
 */
fun Modifier.focusRing(interactionSource: MutableInteractionSource, shape: Shape = RectangleShape): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val color = CluetoothTheme.colors.focusColor
    drawWithContent {
        drawContent()
        if (focused) {
            val w = Tokens.focusW.toPx()
            val inner = Size(size.width - w, size.height - w)
            translate(w / 2, w / 2) {
                drawOutline(shape.createOutline(inner, layoutDirection, this), color, style = Stroke(w))
            }
        }
    }
}

/** The `--fill2` background of a pressed or selected row (`.row:active`, `.row.sel`), faded over `--t-fast`. */
@Composable
fun pressedFill(interactionSource: MutableInteractionSource, selected: Boolean): Color {
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed || selected) CluetoothTheme.colors.fill2 else Color.Transparent
    val c by animateColorAsState(target, CluetoothTheme.motion.fast(), label = "pressedFill")
    return c
}

/** A solid filled [shape] in [color], sized by [modifier]; for dots and marks. */
@Composable
fun ShapeFill(shape: Shape, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val o: Outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(o, color)
    }
}

/**
 * Text clamped to [maxLines] like CSS `-webkit-line-clamp`: lines wrap at words as usual, and when there are more
 * lines the last kept line ends in "…" after its last whole word (Compose's own ellipsis would cut into the next
 * word instead).
 */
@Composable
fun ClampedText(text: String, maxLines: Int, modifier: Modifier = Modifier, style: TextStyle = CluetoothTheme.type.body, color: Color = Color.Unspecified) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val shown = remember(text, style, maxW, maxLines) {
            val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = maxW))
            if (layout.lineCount <= maxLines) text else text.substring(0, layout.getLineEnd(maxLines - 1, visibleEnd = true)).trimEnd() + "\u2026"
        }
        Text(shown, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
