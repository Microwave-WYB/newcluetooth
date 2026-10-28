package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStartResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattUiState

/**
 * Debug-builds-only trigger for one manual GATT discovery of the device in the sheet.
 * The real device-page UI is a separate piece of work; this only drives start, cancel
 * and shows progress and the result.
 */
@Composable
fun GattDebugPanel(
    state: GattUiState,
    onStart: () -> GattStartResult,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    startMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("GATT discovery (debug)", style = MaterialTheme.typography.titleMedium)
        when (state) {
            GattUiState.Idle -> Button(onClick = { onStart() }) { Text("Connect and read GATT") }
            is GattUiState.Running -> {
                val progress = state.progress
                val reads = progress.readsTotal?.let { " ${progress.readsDone}/$it" }.orEmpty()
                Text(
                    "Running: ${progress.stage.wire}$reads (attempt ${progress.attempt}) · ${state.request.target.addr}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            is GattUiState.Finished -> {
                val result = state.result
                val characteristics = result.services.sumOf { it.characteristics.size }
                // Buttons first: the sheet is short and the status lines may push them off-screen.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onStart() }) { Text("Connect again") }
                    OutlinedButton(onClick = onDismissResult) { Text("Done") }
                }
                Text(
                    "${result.outcome.wire} at ${result.stageReached.wire}: " +
                        "${result.services.size} services, $characteristics characteristics",
                    style = MaterialTheme.typography.bodyMedium,
                )
                result.error?.let {
                    Text("Error: ${it.name} (${it.status}) at ${it.stage.wire}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (state.saved) "Saved for upload" else "Not saved: ${state.saveError}; retried at next start",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        startMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
