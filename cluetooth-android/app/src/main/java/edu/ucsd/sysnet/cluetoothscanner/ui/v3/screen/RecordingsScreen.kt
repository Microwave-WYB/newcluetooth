package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordSavedSnackbar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetLayer
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.V3TopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatClock
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDistance
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingSummaryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra
import java.util.Locale

@Composable
private fun UploadLabel(status: UploadStatus) {
    Text(
        status.label,
        style = MaterialTheme.typography.labelMedium,
        color = when (status) {
            UploadStatus.Uploaded, UploadStatus.Unknown, UploadStatus.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
            UploadStatus.Uploading -> MaterialTheme.colorScheme.primary
            UploadStatus.Failed -> MaterialTheme.colorScheme.error
        },
    )
}

@Composable
private fun DayLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Start time, duration, upload status; distance and device count; an optional note. */
@Composable
fun RecordingRow(rec: RecordingSummaryState, onClick: () -> Unit, modifier: Modifier = Modifier, live: Boolean = false, liveClock: Int = 0) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.extra.record))
                Text("录制中", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(" · ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatClock(liveClock), fontFamily = MonoFamily, fontSize = 15.sp)
            } else {
                Text(
                    String.format(Locale.US, "%02d:%02d", rec.startedAt.hour, rec.startedAt.minute),
                    fontFamily = MonoFamily, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
                Text(" · ${formatDuration(rec.durationSeconds)}", style = MaterialTheme.typography.bodyLarge)
            }
            Box(Modifier.weight(1f))
            UploadLabel(rec.upload)
        }
        Text(
            "${formatDistance(rec.distanceMeters)} · ${rec.deviceCount} 台设备",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rec.note != null) {
            Text(
                rec.note,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RecordingsPage(
    state: RecordingsUiState,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onOpenRecording: (RecordingSummaryState) -> Unit = {},
) {
    val total = state.days.sumOf { it.recordings.size } + if (state.current != null) 1 else 0
    Column(modifier.fillMaxSize()) {
        V3TopBar(
            title = "录制",
            status = {
                RecorderStatus(state.recorder, "共 $total 次")
                HeaderNotice(notice)
            },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.current != null) {
                item(key = "current") {
                    RecordingRow(state.current, { onOpenRecording(state.current) }, live = true, liveClock = state.recorder.elapsedSeconds)
                }
            }
            state.days.forEach { day ->
                item(key = "day-${day.label}") { DayLabel(day.label) }
                items(day.recordings, key = { it.id }) { RecordingRow(it, { onOpenRecording(it) }) }
            }
            if (state.current == null && state.days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有录制。点右下角的红色按钮开始。",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
