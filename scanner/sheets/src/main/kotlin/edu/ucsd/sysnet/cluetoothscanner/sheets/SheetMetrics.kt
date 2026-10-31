package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.CssEnv
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * The sheet engine's sizes and timings, all from the generated `tokens.css` values (G-1). Only [flickDpPerMs],
 * [velocitySamples] and [wheelLockMs] are SPEC numbers (S-14, S-15) that no token carries, and [dismissGap] is where
 * a dismissed sheet rests off screen (never seen).
 */
data class SheetMetrics(
    val slop: Dp = Tokens.dragSlop,                     // G-13
    val flickDpPerMs: Float = 0.4f,                     // S-14
    val velocitySamples: Int = 5,                       // S-14
    val edge: Dp = Tokens.edge,
    val floatButton: Dp = Tokens.rb,
    val handleZone: Dp = Tokens.handleZone,             // C-15
    val headerHeight: Dp = Tokens.sheetHeaderH,         // S-3
    val halfFraction: Float = Tokens.discoverSheetHalf(CssEnv(containerHeight = 1f)).value, // S-5: 67 %
    val dismissGap: Dp = Tokens.dragSlop,
    val wheelLockMs: Long = 400,                        // S-15
    val pageMs: Int = Tokens.tPageMs,                   // G-7
    val stdMs: Int = Tokens.tStdMs,                     // T-2
    val ease: Easing = Tokens.ease,
    val panelWidth: Dp = Tokens.panelW,                 // T-1
    val panelInset: Dp = Tokens.panelInset,             // T-3
    val panelFade: Dp = Tokens.panelFadeH,              // T-2
    val panelMoreWidth: Dp = Tokens.panelMoreW,         // T-2
    val reducedMotion: Boolean = false,                 // G-7: every transition instant
) {
    /** --top-zone: the float-button row a sheet never covers (S-5 Full). */
    val topZone: Dp get() = Tokens.topZone

    /** --sheet-radius: concentric with the corner buttons (S-2), 34 dp. */
    val sheetRadius: Dp get() = Tokens.sheetRadius

    /** --panel-radius (T-1), 30 dp. */
    val panelRadius: Dp get() = Tokens.panelRadius

    /** --panel-partial-h (T-2): 45 % of the column, at least header plus about three rows. */
    fun panelPartial(containerHeight: Dp): Dp = Tokens.panelPartialH(CssEnv(containerHeight = containerHeight.value))
}
