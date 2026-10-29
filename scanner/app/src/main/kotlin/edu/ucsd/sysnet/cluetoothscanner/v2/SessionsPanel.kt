package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** The collapsed root sheet's peek (S-5), until the sheet has laid out its header: the handle zone, then the header
 *  row centered 48 dp down, plus 10 dp. */
val PlaceholderPeek: Dp = Tokens.handleZone + Tokens.sheetRadius + Tokens.sheetHeaderH / 2 + 10.dp

/**
 * T-6: on tablet, ☰ swaps the floating panel's content for the sessions list (no drawer, no dim). The panel is the
 * same floating shape as D's (T-1): 400 dp wide, `--edge` from the left and bottom, its top at `--top-zone`.
 */
@Composable
fun SessionsPanel(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val shape = RoundedCornerShape(Concentric.panelRadius)
        Box(
            Modifier.offset(Tokens.edge, Tokens.topZone).width(Tokens.panelW).height(maxHeight - Tokens.topZone - Tokens.edge)
                .tokenShadow(CluetoothTheme.shadows.shadowRaised, shape).clip(shape).background(CluetoothTheme.colors.panel)
                .testTag("sessionsPanel"),
        ) { content() }
    }
}
