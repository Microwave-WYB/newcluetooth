package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SearchField
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Switch
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** docs/v2-api.md §5.4: [step] is 1-based within [OnboardingStep.entries] (or the missing-only subset). */
data class OnboardingUi(val step: Int, val permissions: PermissionState, val userId: String, val userIdValid: Boolean, val autoRecord: Boolean)

/** O-2's four steps. */
enum class OnboardingStep { Permissions, Bluetooth, UserId, AutoRecord }

/** The permission rows of step 1. */
enum class OnboardingPermission { Nearby, Location, Notifications }

class OnboardingActions(
    val onRequest: (OnboardingPermission) -> Unit,
    /** A permanently denied permission offers "Open settings". */
    val onOpenSettings: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onUserId: (String) -> Unit,
    val onAutoRecord: (Boolean) -> Unit,
    val onBack: () -> Unit,
    val onNext: () -> Unit,
    /** Done opens the first run, recording straight away if the switch was left on (V-6). */
    val onDone: () -> Unit,
)

/**
 * The steps to show (O-2): all four on first launch; later, a revoked permission or Bluetooth turned off shows only
 * the missing piece.
 */
fun onboardingSteps(firstLaunch: Boolean, permissions: PermissionState): List<OnboardingStep> {
    if (firstLaunch) return OnboardingStep.entries
    return buildList {
        val required = listOf(permissions.nearby, permissions.location)
        if (required.any { it == Perm.Denied || it == Perm.PermanentlyDenied }) add(OnboardingStep.Permissions)
        if (!permissions.bluetoothOn) add(OnboardingStep.Bluetooth)
    }
}

/** Whether Next may leave a step: the required permissions, Bluetooth on, a valid user ID. */
fun onboardingCanContinue(step: OnboardingStep, ui: OnboardingUi): Boolean = when (step) {
    OnboardingStep.Permissions -> ui.permissions.nearby.ok() && ui.permissions.location.ok()
    OnboardingStep.Bluetooth -> ui.permissions.bluetoothOn
    OnboardingStep.UserId -> ui.userIdValid
    OnboardingStep.AutoRecord -> true
}

private fun Perm.ok() = this == Perm.Granted || this == Perm.NotRequired

/**
 * O-2: a full-screen step: "N of M" (12.5 `--t3`), a 24/30 title, a body in `--t2`, and Back (tonal, from step 2) on
 * the left with Next/Done (primary) on the right.
 */
@Composable
fun Onboarding(
    ui: OnboardingUi,
    steps: List<OnboardingStep>,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    statusInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val c = CluetoothTheme.colors
    val i = (ui.step - 1).coerceIn(0, steps.lastIndex)
    val step = steps[i]
    val last = i == steps.lastIndex
    Column(
        modifier.fillMaxSize().background(c.bg)
            .padding(start = Tokens.gutter, end = Tokens.gutter, top = statusInset + Tokens.topZone + Css.ObBody.marginTop, bottom = Tokens.gutter + bottomInset)
            .testTag("onboarding"),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(Strings.stepOf(i + 1, steps.size), Modifier.padding(bottom = Css.ObStep.marginBottom), style = CluetoothTheme.type.small, color = c.t3)
            Text(
                when (step) {
                    OnboardingStep.Permissions -> Strings.Permissions
                    OnboardingStep.Bluetooth -> Strings.Bluetooth
                    OnboardingStep.UserId -> Strings.YourUserId
                    OnboardingStep.AutoRecord -> Strings.RecordAutomatically
                },
                style = CluetoothTheme.type.head,
            )
            Column(Modifier.padding(top = Css.ObBody.marginTop)) { StepBody(step, ui, actions) }
        }
        Row(Modifier.fillMaxWidth().padding(top = Css.ObActions.paddingTop), horizontalArrangement = Arrangement.SpaceBetween) {
            if (i > 0) Button(Strings.Back, actions.onBack, kind = ButtonKind.Tonal) else Spacer(Modifier)
            Button(
                if (last) Strings.Done else Strings.Next,
                if (last) actions.onDone else actions.onNext,
                kind = ButtonKind.Primary,
                enabled = onboardingCanContinue(step, ui),
            )
        }
    }
}

@Composable
private fun Para(text: String) = Text(
    text, Modifier.padding(bottom = Css.ObBody_P.margin.bottom), color = CluetoothTheme.colors.t2,
)

@Composable
private fun StepBody(step: OnboardingStep, ui: OnboardingUi, a: OnboardingActions) {
    val c = CluetoothTheme.colors
    val side = Css.ObBody_Item.paddingLeft
    when (step) {
        OnboardingStep.Permissions -> {
            Para(Strings.PermissionsBody)
            val p = ui.permissions
            listOf(
                Triple(OnboardingPermission.Nearby, Strings.NearbyDevices to Strings.NearbyDevicesWhy, p.nearby),
                Triple(OnboardingPermission.Location, Strings.PreciseLocation to Strings.PreciseLocationWhy, p.location),
                Triple(OnboardingPermission.Notifications, Strings.Notifications to Strings.NotificationsWhy, p.notifications),
            ).forEach { (which, text, perm) ->
                ListItem(
                    text.first, sub = text.second, horizontalPadding = side,
                    end = if (perm.ok()) Strings.Allowed else null,
                    trailing = when (perm) {
                        Perm.Denied -> ({ Button(Strings.Allow, { a.onRequest(which) }, kind = ButtonKind.Tonal, small = true) })
                        Perm.PermanentlyDenied -> ({ Button(Strings.OpenSettings, a.onOpenSettings, kind = ButtonKind.Tonal, small = true) })
                        else -> null
                    },
                )
            }
        }
        OnboardingStep.Bluetooth -> {
            Para(if (ui.permissions.bluetoothOn) Strings.BluetoothOn else Strings.BluetoothOff)
            if (!ui.permissions.bluetoothOn) Button(Strings.TurnOnBluetooth, a.onEnableBluetooth, kind = ButtonKind.Tonal, small = true)
        }
        OnboardingStep.UserId -> {
            Para(Strings.UserIdBody)
            SearchField(ui.userId, a.onUserId, Strings.UserId, Modifier.fillMaxWidth().testTag("userId"), leading = {})
            Text(
                Strings.UserIdHint, Modifier.padding(top = Css.ObHint.marginTop),
                style = CluetoothTheme.type.sub.copy(fontSize = Css.ObHint.fontSize.sp),
                color = if (ui.userId.isNotEmpty() && !ui.userIdValid) c.rssiWeak else c.t3,
            )
        }
        OnboardingStep.AutoRecord -> {
            Para(Strings.RecordAutomaticallyBody)
            ListItem(
                Strings.RecordAutomaticallySwitch, sub = Strings.RecordAutomaticallySub, horizontalPadding = side,
                trailing = { Switch(ui.autoRecord, a.onAutoRecord, label = Strings.RecordAutomaticallySwitch) },
            )
        }
    }
}
