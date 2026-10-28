package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SearchButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.Sparkline
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.TopBarIconButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.devicesText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.coordinatesText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanLocation
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts

/** "26 devices · 32.88012, -117.23421 ±8 m": the device count and where the phone is. */
@Composable
fun scanSubtitle(deviceCount: Int, location: ScanLocation): String = buildString {
    append(devicesText(deviceCount, LocalTexts.current))
    append(" · ")
    when (location) {
        ScanLocation.Unavailable -> append(stringResource(R.string.scan_location_unavailable))
        is ScanLocation.Stale -> append(stringResource(R.string.scan_location_stale, location.minutes))
        is ScanLocation.Fix -> {
            append(coordinatesText(location.lat, location.lon))
            if (location.accuracyMeters != null) append(" ").append(stringResource(R.string.scan_location_accuracy, location.accuracyMeters))
        }
    }
}

/** The Scan page's stock top app bar: title, count · location (long press copies the coordinates), search and settings. */
@Composable
fun ScanTopBar(
    deviceCount: Int,
    location: ScanLocation,
    @StringRes notice: Int?,
    searching: Boolean,
    onSearchToggle: () -> Unit,
    onSettings: (() -> Unit)?,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.scan_location_copied)
    val fix = location as? ScanLocation.Fix
    PageTopBar(
        title = stringResource(R.string.tab_scan),
        subtitle = scanSubtitle(deviceCount, location),
        notice = notice?.let { stringResource(it) },
        onSubtitleLongClick = fix?.let {
            {
                clipboard.setText(AnnotatedString(coordinatesText(it.lat, it.lon)))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        },
        actions = {
            if (searching) TopBarIconButton(Icons.Filled.Close, stringResource(R.string.action_close_search), onSearchToggle)
            else SearchButton(onSearchToggle)
            if (onSettings != null) TopBarIconButton(Icons.Filled.Settings, stringResource(R.string.action_settings), onSettings)
        },
    )
}

/** One device: a stock [ListItem] with name, "MAC · Legacy · Connectable", and the sparkline and RSSI at the end. Stale rows dim in place. */
@Composable
fun ScanRow(row: DeviceRowState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(
                row.name ?: stringResource(R.string.unnamed),
                color = if (row.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val parts = listOf(row.mac, stringResource(row.advKind.label)) + if (row.connectable) listOf(stringResource(R.string.connectable)) else emptyList()
            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkline(row.history, row.rssi)
                RssiText(row.rssi, Modifier.width(40.dp).padding(start = 4.dp))
            }
        },
        modifier = modifier.clickable(onClick = onClick).alpha(if (row.stale) 0.5f else 1f),
    )
}

/** Remembers whether the current drag began with the list scrolled to the top. */
private class PullStartTracker(private val listState: LazyListState) : NestedScrollConnection {
    var startedAtTop = true
        private set
    private var dragging = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && !dragging) {
            dragging = true
            startedAtTop = !listState.canScrollBackward
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        dragging = false
        return Velocity.Zero
    }
}

/**
 * The Scan page body: the optional search field and the list. The list keeps its order; only the
 * numbers update. Pull to refresh re-sorts, and only from a drag that started at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanContent(
    rows: List<DeviceRowState>,
    listState: LazyListState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRowClick: (DeviceRowState) -> Unit = {},
    searching: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    sortVersion: Int = 0,
) {
    // A sort moves the rows under a keyed list, which then follows the row that was first on screen
    // deep into the list. Scroll back to the top once the sorted rows are here: the version arrives in
    // the same state as those rows. Entering the page again (tab switch) starts from the version it
    // finds, so it does not scroll.
    var handledSort by remember { mutableIntStateOf(sortVersion) }
    LaunchedEffect(sortVersion) {
        if (sortVersion != handledSort) {
            handledSort = sortVersion
            listState.scrollToItem(0)
        }
    }
    Column(modifier.fillMaxSize().padding(padding)) {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_hint_scan)) },
                singleLine = true,
            )
        }
        val gesture = remember(listState) { PullStartTracker(listState) }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (gesture.startedAtTop) onRefresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(gesture),
                state = listState,
                // Room for the record button over the last rows.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(rows, key = { it.id }) { row -> ScanRow(row, onClick = { onRowClick(row) }) }
            }
        }
    }
}
