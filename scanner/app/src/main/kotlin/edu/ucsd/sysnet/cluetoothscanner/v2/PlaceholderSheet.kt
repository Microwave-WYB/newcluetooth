package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Handle
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** The collapsed root sheet's peek (S-5): the handle zone, then the header row centered 48 dp down, plus 10 dp. */
val PlaceholderPeek: Dp = Tokens.handleZone + Tokens.sheetRadius + Tokens.sheetHeaderH / 2 + 10.dp

/**
 * PLACEHOLDER slot for lane D's sheet stack (`PhoneSheetHost` / `TabletPanelHost` with the Discover, product,
 * device and Filter sheets), which :sheets does not expose yet. It draws the collapsed root sheet's frame (phone)
 * or the floating panel (tablet) so the map cover, the FAB lift and the layering can be checked.
 */
@Composable
fun BoxScope.PlaceholderSheet(view: SessionView?, tablet: Boolean, bottomInset: Dp, panelContent: (@Composable () -> Unit)? = null) {
    val c = CluetoothTheme.colors
    val label = view?.let { "${Strings.Discover} · ${Strings.devices(it.deviceCount)}" } ?: Strings.Discover
    if (tablet) {
        val shape = RoundedCornerShape(Concentric.panelRadius)
        Box(
            Modifier.offset(Tokens.edge, Tokens.topZone).width(Tokens.panelW).padding(bottom = Tokens.edge)
                .height(PanelPlaceholderHeight).tokenShadow(CluetoothTheme.shadows.shadowRaised, shape).clip(shape)
                .background(c.panel).testTag("placeholderSheet"),
        ) {
            if (panelContent != null) panelContent() else PlaceholderText(label, Modifier.padding(Tokens.gutter))
        }
    } else {
        val shape = RoundedCornerShape(topStart = Concentric.sheetRadius, topEnd = Concentric.sheetRadius)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(PlaceholderPeek + bottomInset)
                .tokenShadow(CluetoothTheme.shadows.shadowSheet, shape).clip(shape).background(c.panel).testTag("placeholderSheet"),
        ) {
            Handle()
            PlaceholderText(label, Modifier.fillMaxWidth().padding(top = Tokens.sheetRadius - Tokens.sheetHeaderH / 2).height(Tokens.sheetHeaderH))
        }
    }
}

/** The tablet placeholder panel's height (T-2 Partial-sized, so the map stays visible). */
private val PanelPlaceholderHeight = 300.dp

@Composable
private fun PlaceholderText(label: String, modifier: Modifier) {
    Text("$label (lane D sheets)", modifier, style = CluetoothTheme.type.title, color = CluetoothTheme.colors.t2, textAlign = TextAlign.Center)
}
