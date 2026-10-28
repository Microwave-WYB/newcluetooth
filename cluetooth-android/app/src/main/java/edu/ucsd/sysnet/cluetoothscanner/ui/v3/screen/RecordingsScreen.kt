package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatClock
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDistance
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingSummaryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.TabularNumbers

@Composable
private fun UploadLabel(status: UploadStatus) {
    Text(
        stringResource(status.label),
        style = MaterialTheme.typography.labelMedium,
        color = when (status) {
            UploadStatus.Uploaded, UploadStatus.Unknown, UploadStatus.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
            UploadStatus.Uploading -> MaterialTheme.colorScheme.primary
            UploadStatus.Failed -> MaterialTheme.colorScheme.error
        },
    )
}

/**
 * One recording as a stock [ListItem]: start time and duration (or "In progress" and the running
 * time), distance and device count, an optional note, the upload state at the end. Long press edits the note.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingRow(
    rec: RecordingSummaryState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    liveClock: Int = 0,
    onLongClick: (() -> Unit)? = null,
) {
    val texts = LocalTexts.current
    ListItem(
        leadingContent = if (live) {
            { Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)) }
        } else {
            null
        },
        headlineContent = {
            Text(
                if (live) "${stringResource(R.string.history_in_progress)} · ${formatClock(liveClock)}"
                else "${texts.timeOfDay(rec.startedAt)} · ${formatDuration(rec.durationSeconds, texts)}",
                style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
            )
        },
        supportingContent = {
            Column {
                // No 0 m distance; a recording that has saved no minute yet says when devices appear.
                val devices = if (live && rec.deviceCount == 0) stringResource(R.string.recording_devices_pending)
                else texts.plural(R.plurals.devices, rec.deviceCount, rec.deviceCount)
                Text(if (rec.distanceMeters > 0) stringResource(R.string.recording_summary, formatDistance(rec.distanceMeters), devices) else devices)
                if (rec.note != null) Text(rec.note, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        // Nothing is uploading while the recording runs; its status shows once it has stopped.
        trailingContent = if (live) null else { { UploadLabel(rec.upload) } },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
fun HistoryTopBar(state: RecordingsUiState, @StringRes notice: Int?) {
    val total = state.days.sumOf { it.recordings.size } + if (state.current != null) 1 else 0
    PageTopBar(
        title = stringResource(R.string.tab_history),
        subtitle = LocalTexts.current.plural(R.plurals.recordings_count, total, total),
        notice = notice?.let { stringResource(it) },
    )
}

@Composable
fun HistoryContent(
    state: RecordingsUiState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenRecording: (RecordingSummaryState) -> Unit = {},
    onEditNote: (RecordingSummaryState) -> Unit = {},
    /** Hoisted by the caller so a recording and back keeps the scroll position. */
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        if (state.current != null) {
            item(key = "current") {
                RecordingRow(state.current, { onOpenRecording(state.current) }, live = true, liveClock = state.recorder.elapsedSeconds, onLongClick = { onEditNote(state.current) })
            }
        }
        state.days.forEach { day ->
            item(key = "day-${day.label}") { SectionLabel(day.label) }
            items(day.recordings, key = { it.id }) { RecordingRow(it, { onOpenRecording(it) }, onLongClick = { onEditNote(it) }) }
        }
        if (state.current == null && state.days.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.history_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
