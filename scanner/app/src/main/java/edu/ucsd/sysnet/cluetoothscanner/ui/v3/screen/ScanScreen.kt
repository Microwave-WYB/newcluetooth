package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SearchField
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.TopBarIconButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.appendMac
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordSavedSnackbar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SearchButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetLayer
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.Sparkline
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.StatusLine
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.V3TopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatCount
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetSurface
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra
import kotlinx.coroutines.launch

/** What the stop snackbar reports. */
data class SavedRecording(val durationSeconds: Int, val sightings: Int)

/** Status line shared by every page that has a recorder: "未录制 · 132 台" or "● 录制中 · 1.2k 条". */
@Composable
fun RecorderStatus(recorder: RecorderUiState, idleText: String) {
    when (recorder.phase) {
        RecorderPhase.Idle -> StatusLine(idleText)
        RecorderPhase.Recording -> StatusLine("录制中 · ${formatCount(recorder.sightings)} 条", dot = MaterialTheme.extra.record)
        RecorderPhase.Paused -> StatusLine("已暂停 · ${formatCount(recorder.sightings)} 条", dot = MaterialTheme.colorScheme.outline)
    }
}

/** A one-line problem under a page header (Bluetooth off, no location fix, ...); nothing when null. */
@Composable
fun HeaderNotice(text: String?) {
    if (text != null) Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
}

/** Two text lines, then a sparkline and the RSSI number on the right. */
@Composable
fun ScanRow(row: DeviceRowState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (row.name != null) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("未命名", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                text = buildAnnotatedString {
                    appendMac(row.mac)
                    append(" · ${row.advKind.label}")
                    if (row.connectable) append(" · 可连接")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Sparkline(row.history, Modifier.alpha(if (row.stale) 0.4f else 1f))
        RssiText(row.rssi, Modifier.width(38.dp).alpha(if (row.stale) 0.4f else 1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun StaticRefreshIndicator(modifier: Modifier) {
    Surface(
        modifier.padding(top = 16.dp).size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { 0.7f }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        }
    }
}

/** The list keeps its order; only the numbers update. Pull-to-refresh re-sorts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanList(
    rows: List<DeviceRowState>,
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRowClick: (DeviceRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val static = LocalStaticRender.current
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        // Previews render one frame, before the default indicator has animated in.
        indicator = if (static && refreshing) {
            { StaticRefreshIndicator(Modifier.align(Alignment.TopCenter)) }
        } else {
            { PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter)) }
        },
        state = pullState,
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(top = if (static && refreshing) 64.dp else 0.dp),
            state = listState,
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            items(rows, key = { it.id }) { row -> ScanRow(row, onClick = { onRowClick(row) }) }
        }
    }
}

/** Scan page content: top bar, optional search, and the list. The shell around it draws the record button and nav. */
@Composable
fun ScanPage(
    rows: List<DeviceRowState>,
    recorder: RecorderUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRowClick: (DeviceRowState) -> Unit = {},
    onSettings: (() -> Unit)? = null,
    /** A scanner problem (Bluetooth off, no permission), shown under the status line. */
    notice: String? = null,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (!searching || q.isEmpty()) rows else rows.filter { (it.name.orEmpty() + " " + it.mac).lowercase().contains(q) }
    Column(modifier.fillMaxSize()) {
        V3TopBar(
            title = "扫描",
            status = {
                RecorderStatus(recorder, "未录制 · ${rows.size} 台")
                HeaderNotice(notice)
            },
            actions = {
                if (searching) {
                    TopBarIconButton(Icons.Filled.Close, "关闭搜索", { searching = false; query = "" })
                } else {
                    SearchButton { searching = true }
                }
                if (onSettings != null) TopBarIconButton(Icons.Filled.Settings, "设置", onSettings)
            },
        )
        if (searching) {
            SearchField(query, { query = it }, "搜索名称或 MAC", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        ScanList(shown, listState, refreshing, onRefresh, onRowClick, Modifier.weight(1f))
    }
}
