package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.SpecValues
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** P-5: the popover's text for a location status (null while the location is usable). */
fun locationMessage(status: LocationStatus): String? = when (status.state) {
    LocationStatus.State.Ok -> null
    LocationStatus.State.Denied -> Strings.LocationPermissionOff
    LocationStatus.State.Off -> Strings.LocationTurnedOff
    LocationStatus.State.Waiting -> Strings.WaitingForLocation
    LocationStatus.State.Stale -> Strings.locationAge(status.ageMin ?: 0)
}

/** The popover's fix: "Allow" for a denied permission, "Turn on" for location off; text only otherwise. */
fun locationActionLabel(action: LocationStatus.Action?): String? = when (action) {
    LocationStatus.Action.Allow -> Strings.Allow
    LocationStatus.Action.TurnOn -> Strings.TurnOn
    null -> null
}

/**
 * P-5: the location popover's content, anchored by :map above the warning button (E places it). It is
 * `min(260 dp, the available width − 2 × --edge)` wide (a fixed width, not the text's), `--panel`, radius 28 (concentric with its small pill button), 12 dp padding, `--shadow-raised`. A tap outside
 * and the location becoming usable close it (E and :app).
 */
@Composable
fun LocationPopover(status: LocationStatus, onAction: (LocationStatus.Action) -> Unit, modifier: Modifier = Modifier) {
    val message = locationMessage(status) ?: return
    val shape = RoundedCornerShape(Concentric.popover)
    BoxWithConstraints(modifier) {
    Column(
        Modifier
            .width(minOf(SpecValues.popoverMaxWidth, maxWidth - Tokens.edge * 2))
            .tokenShadow(CluetoothTheme.shadows.shadowRaised, shape)
            .clip(shape)
            .background(CluetoothTheme.colors.panel)
            .padding(Tokens.popPad)
            .testTag("locationPopover"),
    ) {
        val action = status.action
        val label = locationActionLabel(action)
        Text(
            message,
            Modifier.padding(bottom = if (label != null) Css.LocPop_P.margin.bottom else Dp(0f)),
            style = CluetoothTheme.type.control.copy(fontSize = Css.LocPop_P.fontSize.sp, lineHeight = Css.LocPop_P.lineHeight.sp),
        )
        if (action != null && label != null) Button(label, { onAction(action) }, kind = ButtonKind.Primary, small = true)
    }
    }
}
