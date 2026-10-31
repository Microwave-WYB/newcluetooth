package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The few values that neither tokens.css nor components.css carries as a resolvable declaration: SPEC values whose
 * prototype element was removed, `min()`/runtime-variable expressions the generator skips, and app.js layout
 * arithmetic. This is the only hand-written file allowed dp literals (TokensTest scans :designsystem and :chrome);
 * each cites its source.
 */
object SpecValues {
    /** C-3: ChipRow's gap (the Discover chip row that carried it in components.css is gone, D-3). */
    val chipRowGap = 6.dp

    /** P-5: the location popover is at most 260 dp wide (`.loc-pop { width: min(260px, …) }`). */
    val popoverMaxWidth = 260.dp

    /** R-4a: the discovery-rate chart is about 72 dp tall. */
    val recorderChartHeight = 72.dp

    /** R-4a: the chart's line width. */
    val recorderChartStroke = 2.dp

    /** R-4a: the line breaks across a pause; the break is this wide (paused time itself is left out of x). */
    val recorderChartPauseGap = 6.dp

    /** R-4a: the "N new/min" label is 12 (`--t2`, tabular). */
    val recorderChartLabelSize = 12.sp

    /** R-4a: the label sits this far above (or below) the line's end. */
    val recorderChartLabelGap = 4.dp

    /** R-3: app.js `setRecorder` adds 4 px between the dot-and-time group and the buttons in the capsule width. */
    val recorderCapsuleSlack = 4.dp

    /** L-1: ☰ rides the open sidebar's edge 4 dp in (`.menu-btn.open { left: calc(var(--side-w) - var(--rb) - 4px) }`). */
    val menuRide = 4.dp

    /** T-8: the gap between the tablet recorder's top and the locate button or warning (the `+ 12px` in `.phone.tablet .locate-btn`). */
    val locateAboveRecorder = 12.dp
}
