package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager

/** One runtime permission group as onboarding lists it. */
data class PermissionNeed(@StringRes val title: Int, @StringRes val why: Int, val granted: Boolean, val required: Boolean = true)

enum class OnboardingStep { Permissions, Bluetooth, UserId, AutoRecord }

data class OnboardingActions(
    /** Ask for every permission not granted yet. */
    val onRequestPermissions: () -> Unit = {},
    /** The app's page in system settings, for a permission denied for good. */
    val onOpenSettings: () -> Unit = {},
    /** The system "turn on Bluetooth" request. */
    val onEnableBluetooth: () -> Unit = {},
    /** Last step: the user ID (valid) and the auto-record choice. */
    val onFinish: (userId: String, autoRecord: Boolean) -> Unit = { _, _ -> },
)

/**
 * First launch, one stock full-screen step per page: permissions, Bluetooth on, the user ID, then the
 * optional "record when the app opens". Replaces the old startup user ID dialog.
 */
@Composable
fun OnboardingScreen(
    permissions: List<PermissionNeed>,
    permanentlyDenied: Boolean,
    bluetoothOn: Boolean,
    initialUserId: String,
    actions: OnboardingActions,
    startStep: OnboardingStep = OnboardingStep.Permissions,
) {
    var step by rememberSaveable { mutableStateOf(startStep) }
    var userId by rememberSaveable { mutableStateOf(initialUserId) }
    var autoRecord by rememberSaveable { mutableStateOf(false) }
    val steps = OnboardingStep.entries
    BackHandler(enabled = step != steps.first()) { step = steps[step.ordinal - 1] }
    fun next() { step = steps[step.ordinal + 1] }
    val requiredGranted = permissions.filter { it.required }.all { it.granted }

    Scaffold(
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step != steps.first()) TextButton({ step = steps[step.ordinal - 1] }) { Text(stringResource(R.string.action_back)) }
                Spacer(Modifier.weight(1f))
                when (step) {
                    OnboardingStep.Permissions -> when {
                        requiredGranted -> Button(::next) { Text(stringResource(R.string.action_next)) }
                        permanentlyDenied -> Button(actions.onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                        else -> Button(actions.onRequestPermissions) { Text(stringResource(R.string.action_allow)) }
                    }
                    OnboardingStep.Bluetooth -> if (bluetoothOn) {
                        Button(::next) { Text(stringResource(R.string.action_next)) }
                    } else {
                        TextButton(::next) { Text(stringResource(R.string.action_skip)) }
                        Button(actions.onEnableBluetooth) { Text(stringResource(R.string.action_turn_on_bluetooth)) }
                    }
                    OnboardingStep.UserId -> Button(::next, enabled = DeviceIdManager.isValidUserId(userId)) { Text(stringResource(R.string.action_next)) }
                    OnboardingStep.AutoRecord -> Button({ actions.onFinish(userId, autoRecord) }) { Text(stringResource(R.string.action_done)) }
                }
            }
        },
        modifier = Modifier.safeDrawingPadding(),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                OnboardingStep.Permissions -> {
                    StepTitle(R.string.onboarding_permissions_title, R.string.onboarding_permissions_text)
                    Column {
                        permissions.forEach { p ->
                            ListItem(
                                headlineContent = { Text(stringResource(p.title)) },
                                supportingContent = { Text(stringResource(p.why)) },
                                trailingContent = if (p.granted) {
                                    { Icon(Icons.Filled.CheckCircle, stringResource(R.string.permission_granted)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    if (permanentlyDenied && !requiredGranted) {
                        Text(stringResource(R.string.onboarding_permissions_denied), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OnboardingStep.Bluetooth -> StepTitle(
                    R.string.onboarding_bluetooth_title,
                    if (bluetoothOn) R.string.onboarding_bluetooth_on else R.string.onboarding_bluetooth_off,
                )
                OnboardingStep.UserId -> {
                    StepTitle(R.string.onboarding_user_id_title, R.string.onboarding_user_id_text)
                    UserIdField(userId, { userId = it })
                }
                OnboardingStep.AutoRecord -> {
                    StepTitle(R.string.onboarding_auto_record_title, R.string.onboarding_auto_record_text)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_auto_record)) },
                        supportingContent = { Text(stringResource(R.string.onboarding_auto_record_detail)) },
                        trailingContent = { Switch(checked = autoRecord, onCheckedChange = { autoRecord = it }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepTitle(@StringRes title: Int, @StringRes text: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * After onboarding, a required permission that was taken away blocks the app with just that piece and
 * its fix (ask again, or the system settings when it was denied for good); onboarding does not rerun.
 */
@Composable
fun MissingPermissionsScreen(missing: List<PermissionNeed>, permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(modifier = Modifier.safeDrawingPadding()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.missing_permissions_title), style = MaterialTheme.typography.headlineSmall)
            missing.forEach { p ->
                ListItem(headlineContent = { Text(stringResource(p.title)) }, supportingContent = { Text(stringResource(p.why)) })
            }
            if (permanentlyDenied) {
                Button(onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
            } else {
                Button(onRequest) { Text(stringResource(R.string.action_allow)) }
            }
        }
    }
}

/** Bluetooth turned off after onboarding: one stock card under the page's app bar, with the fix. */
@Composable
fun BluetoothOffBanner(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bluetooth_off_banner), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onTurnOn) { Text(stringResource(R.string.action_turn_on)) }
        }
    }
}
