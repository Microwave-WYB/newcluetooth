package edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** A CSS box-shadow (one layer, not inset), as in tokens.css. */
@Immutable
data class TokenShadow(val x: Dp, val y: Dp, val blur: Dp, val spread: Dp, val color: Color)

/** A CSS padding/margin/inset shorthand, expanded. */
@Immutable
data class CssBox(val top: Dp, val right: Dp, val bottom: Dp, val left: Dp)

/**
 * What an expression token (`82cqw`, `calc(100% - 68px)`, `env(safe-area-inset-top, 0px)`) is evaluated against,
 * in dp: the prototype's frame (container) size, the parent's size for `%`, and the safe-area insets.
 */
@Immutable
data class CssEnv(
    val containerWidth: Float = 0f,
    val containerHeight: Float = 0f,
    val parent: Float = 0f,
    val safeTop: Float = 0f,
    val safeBottom: Float = 0f,
)

/** One path of a [Glyph]: stroked, or filled when the prototype marks it `class="solid"`. */
@Immutable
data class GlyphPath(val d: String, val solid: Boolean)

/** A stroke glyph of the prototype (C-17), in its SVG viewBox units. */
@Immutable
data class Glyph(val name: String, val viewportWidth: Float, val viewportHeight: Float, val paths: List<GlyphPath>)
