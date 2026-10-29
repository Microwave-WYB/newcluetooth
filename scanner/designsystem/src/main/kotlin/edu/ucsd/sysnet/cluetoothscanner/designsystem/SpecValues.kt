package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.unit.dp

/**
 * The few values SPEC.md states that neither tokens.css nor components.css carries (their prototype element was
 * removed). This is the only hand-written file allowed dp literals; each cites its rule.
 */
object SpecValues {
    /** C-3: ChipRow's gap (the Discover chip row that carried it in components.css is gone, D-3). */
    val chipRowGap = 6.dp
}
