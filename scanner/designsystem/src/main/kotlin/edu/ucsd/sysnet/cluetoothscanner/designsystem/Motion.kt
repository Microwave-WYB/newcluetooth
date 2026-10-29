package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * G-7: every transition uses `--ease` and one of the duration tokens. With [reduced] motion every spec is
 * instant (a snap), and the looping animations (the blink, the spinner) stand still.
 */
@Immutable
data class Motion(val reduced: Boolean) {
    /** `--t-fast`: color and opacity. */
    fun <T> fast(): FiniteAnimationSpec<T> = spec(Tokens.tFastMs)
    /** `--t-std`: the sidebar and segmented controls. */
    fun <T> std(): FiniteAnimationSpec<T> = spec(Tokens.tStdMs)
    /** `--t-page`: sheets and pages. */
    fun <T> page(): FiniteAnimationSpec<T> = spec(Tokens.tPageMs)
    /** `--t-morph`: the recorder and button morphs. */
    fun <T> morph(): FiniteAnimationSpec<T> = spec(Tokens.tMorphMs)
    /** `--t-fade`: the search page. */
    fun <T> fade(): FiniteAnimationSpec<T> = spec(Tokens.tFadeMs)
    /** A linear tween of the given token duration, for the spinner (`--t-spin`). */
    fun <T> linear(ms: Int): FiniteAnimationSpec<T> = if (reduced) snap() else tween(ms, easing = LinearEasing)

    /** [ms] with `--ease`, or instant. */
    fun <T> spec(ms: Int, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(ms, delayMillis = delayMs, easing = Tokens.ease)

    /** The duration a timed effect actually takes: 0 under reduced motion. */
    fun duration(ms: Int): Int = if (reduced) 0 else ms

    /** `--t-blink`, the recording dot's period; the dot is steadily on under reduced motion. */
    val blinkMs: Int get() = Tokens.tBlinkMs
    /** `--t-spin`, the GATT spinner's period. */
    val spinMs: Int get() = Tokens.tSpinMs
}
