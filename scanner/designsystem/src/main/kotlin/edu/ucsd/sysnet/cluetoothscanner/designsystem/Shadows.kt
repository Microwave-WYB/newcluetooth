package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.TokenShadow
import kotlin.math.max

/**
 * G-5: a tokens.css box-shadow, drawn with Compose's blur-radius shadow API (no elevation, no light source).
 *
 * CSS blur radius B is a Gaussian with σ = B / 2. Compose hands its radius r to Android's BlurMaskFilter, whose
 * σ = 0.57735 · r + 0.5 (in px). So r = (B / 2 − 0.5) / 0.57735, computed in px for the current density.
 */
fun Modifier.tokenShadow(shadow: TokenShadow, shape: Shape): Modifier = dropShadow(shape) {
    radius = cssBlurToBlurMaskRadius(shadow.blur.toPx())
    spread = shadow.spread.toPx()
    offset = Offset(shadow.x.toPx(), shadow.y.toPx())
    color = shadow.color
}

/** The BlurMaskFilter radius (px) that blurs like a CSS box-shadow blur of [cssBlurPx]. */
fun cssBlurToBlurMaskRadius(cssBlurPx: Float): Float = max(0f, (cssBlurPx / 2f - 0.5f) / 0.57735f)
