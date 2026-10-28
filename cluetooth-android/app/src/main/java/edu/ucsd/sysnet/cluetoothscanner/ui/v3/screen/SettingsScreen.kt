package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SettingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager

data class SettingsActions(
    /** False when the ID breaks the rule (and nothing was saved). */
    val onUserId: (String) -> Boolean = { true },
    val onAutoRecord: (Boolean) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onCodedPhy: (Boolean) -> Unit = {},
    val onUploadNow: () -> Unit = {},
)

@Composable
fun SettingsTopBar(onBack: () -> Unit) {
    PageTopBar(title = stringResource(R.string.settings_title), navigation = { BackButton(onBack) })
}

/** Stock [ListItem]s: the user ID (tap to edit), three switches, pending uploads with "Upload now", the version. */
@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions, padding: PaddingValues, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = padding) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_user_id)) },
                supportingContent = { Text(state.userId ?: stringResource(R.string.settings_user_id_unset)) },
                modifier = Modifier.clickable { editing = true },
            )
        }
        item { SwitchItem(stringResource(R.string.settings_auto_record), stringResource(R.string.settings_auto_record_detail), state.autoRecord, actions.onAutoRecord) }
        item { SwitchItem(stringResource(R.string.settings_wifi_only), null, state.wifiOnly, actions.onWifiOnly) }
        item { SwitchItem(stringResource(R.string.settings_coded_phy), stringResource(R.string.settings_coded_phy_detail), state.codedPhy, actions.onCodedPhy) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_pending)) },
                supportingContent = { Text(LocalTexts.current.plural(R.plurals.pending_recordings, state.pendingUploads, state.pendingUploads)) },
                trailingContent = {
                    TextButton(onClick = actions.onUploadNow, enabled = state.canUploadNow) { Text(stringResource(R.string.settings_upload_now)) }
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(state.version) },
            )
        }
    }
    if (editing) {
        UserIdDialog(state.userId.orEmpty(), onDismiss = { editing = false }) { id -> if (actions.onUserId(id)) editing = false }
    }
}

@Composable
private fun SwitchItem(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** The user ID text field with its rule (lowercase letters and digits, at most 9), shared with onboarding. */
@Composable
fun UserIdField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val invalid = value.isNotEmpty() && !DeviceIdManager.isValidUserId(value)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.trim()) },
        label = { Text(stringResource(R.string.settings_user_id)) },
        supportingText = { Text(stringResource(R.string.user_id_rule)) },
        isError = invalid,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun UserIdDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_user_id)) },
        text = { UserIdField(text, { text = it }) },
        confirmButton = { TextButton({ onSave(text) }, enabled = DeviceIdManager.isValidUserId(text)) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The History recording detail's overflow menu: its upload status, retry, export (JSONL, Parquet) and
 * deleting its data on the phone, behind a stock confirmation dialog. The recording in progress can
 * only be looked at.
 */
@Composable
fun RecordingMenu(
    status: UploadStatus?,
    inProgress: Boolean,
    onRetryUpload: () -> Unit,
    onExportJsonl: () -> Unit,
    onExportParquet: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.menu_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (status != null && !inProgress) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_upload_status, stringResource(status.label))) }, onClick = {}, enabled = false)
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_retry_upload)) },
                onClick = { open = false; onRetryUpload() },
                enabled = !inProgress && status != UploadStatus.Uploaded,
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_export_jsonl)) }, onClick = { open = false; onExportJsonl() }, enabled = !inProgress)
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_export_parquet)) }, onClick = { open = false; onExportParquet() }, enabled = !inProgress)
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete_local)) }, onClick = { open = false; confirm = true }, enabled = !inProgress)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(if (status == UploadStatus.Uploaded) R.string.delete_text_uploaded else R.string.delete_text_not_uploaded)) },
            confirmButton = { TextButton({ confirm = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
