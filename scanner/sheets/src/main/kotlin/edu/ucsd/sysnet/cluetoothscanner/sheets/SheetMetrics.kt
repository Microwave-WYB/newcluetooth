package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sheet engine's sizes and timings, in the units of `tokens.css`. These defaults mirror the tokens only until
 * :designsystem (lane C) merges; then the host passes the generated tokens instead (G-1), and this is the one place
 * that changes.
 */
data class SheetMetrics(
    val slop: Dp = 8.dp,                   // --drag-slop (G-13)
    val flickDpPerMs: Float = 0.4f,        // S-14
    val velocitySamples: Int = 5,          // S-14
    val edge: Dp = 12.dp,                  // --edge
    val floatButton: Dp = 44.dp,           // --rb
    val handleZone: Dp = 14.dp,            // --handle-zone (C-15)
    val headerHeight: Dp = 40.dp,          // --sheet-header-h (S-3)
    val halfFraction: Float = 0.67f,       // --discover-sheet-half (S-5)
    val dismissGap: Dp = 8.dp,             // a dismissed sheet rests this far below the screen
    val wheelLockMs: Long = 400,           // S-15
    val pageMs: Int = 320,                 // --t-page (G-7)
    val stdMs: Int = 240,                  // --t-std (T-2 height changes)
    val ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f), // --ease
    val panelWidth: Dp = 400.dp,           // --panel-w (T-1)
    val panelInset: Dp = 8.dp,             // --panel-inset (T-3)
    val panelPartialFraction: Float = 0.45f, // --panel-partial-h (T-2)
    val panelPartialMin: Dp = 300.dp,
    val panelFade: Dp = 48.dp,             // --panel-fade-h
    val panelMoreWidth: Dp = 120.dp,       // --panel-more-w
    val reducedMotion: Boolean = false,    // G-7: every transition instant
) {
    /** --top-zone: the float-button row a sheet never covers (S-5 Full). */
    val topZone: Dp get() = edge + floatButton + edge

    /** --sheet-radius: concentric with the corner buttons (S-2), 34 dp. */
    val sheetRadius: Dp get() = edge + floatButton / 2

    /** --panel-radius (T-1), 30 dp. */
    val panelRadius: Dp get() = panelInset + floatButton / 2
}
