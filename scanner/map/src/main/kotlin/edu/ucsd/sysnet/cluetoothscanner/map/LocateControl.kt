package edu.ucsd.sysnet.cluetoothscanner.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.FloatButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** Where the locate button and the location warning sit (P-4, P-5, T-8). */
sealed interface LocatePlacement {
    /** Phone: bottom-left, centre level with the FAB's. recLift: the Discover sheet's collapsed peek (or 0). */
    data class Phone(val recLift: Dp) : LocatePlacement
    /** Tablet: bottom-right, 12 dp above the recorder, moving up with its panel. recorderHeight: FAB or panel. */
    data class Tablet(val recorderHeight: Dp) : LocatePlacement
}

/**
 * P-4 / P-5 / T-8: the locate button, or the location warning in its spot, as C-1 FloatButtons. Both live in the
 * FAB's layer (above the map, below every sheet). [MapUiState.showWarning] overrides [MapUiState.showLocate]; the
 * warning opens lane C's LocationPopover through [onWarning].
 */
@Composable
fun BoxScope.LocateControl(
    ui: MapUiState,
    placement: LocatePlacement,
    onLocate: () -> Unit,
    onWarning: () -> Unit,
) {
    val position = when (placement) {
        // components.css .locate-btn: left --edge, bottom --rec-lift + --edge + 12 + (--fab − --rb) / 2
        is LocatePlacement.Phone -> Modifier.align(Alignment.BottomStart)
            .padding(start = Tokens.edge, bottom = placement.recLift + Tokens.edge + 12.dp + (Tokens.fab - Tokens.rb) / 2)
        // .phone.tablet .locate-btn: right --edge + (--fab − --rb) / 2, bottom --edge + 12 + --rec-h + 12
        is LocatePlacement.Tablet -> Modifier.align(Alignment.BottomEnd)
            .padding(end = Tokens.edge + (Tokens.fab - Tokens.rb) / 2, bottom = Tokens.edge + 12.dp + placement.recorderHeight + 12.dp)
    }
    val motion = CluetoothTheme.motion
    // .locate-btn fades with opacity var(--t-std)
    AnimatedVisibility(
        visible = ui.showLocate && !ui.showWarning,
        modifier = position,
        enter = fadeIn(motion.std()),
        exit = fadeOut(motion.std()),
    ) {
        FloatButton(Glyphs.locate, Strings.ShowMyLocation, onLocate)
    }
    if (ui.showWarning) {
        FloatButton(Glyphs.locateOff, Strings.LocationProblem, onWarning, position, tint = CluetoothTheme.colors.warn)
    }
}
