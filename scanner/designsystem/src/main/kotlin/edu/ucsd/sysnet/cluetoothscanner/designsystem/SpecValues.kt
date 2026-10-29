package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.unit.dp

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

    /** R-4: app.js `setRecorder` adds 2 px to the panel's measured content height. */
    val recorderPanelSlack = 2.dp

    /** R-3: app.js `setRecorder` adds 4 px between the dot-and-time group and the buttons in the capsule width. */
    val recorderCapsuleSlack = 4.dp

    /** L-1: ☰ rides the open sidebar's edge 4 dp in (`.menu-btn.open { left: calc(var(--side-w) - var(--rb) - 4px) }`). */
    val menuRide = 4.dp
}
