package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordSavedSnackbar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.CategoryListPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapLibreMap
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapOverlay
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.NearbyPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.RecordingsPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanDeviceSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme
import kotlinx.coroutines.launch

/**
 * The v3 app: one [AppShell] (so the record button can move between pages), the current route's
 * page inside it, and the sheets, snackbar and dialogs the routes need.
 *
 * @param legacyContent the pre-v3 sessions/upload screen, shown for [Route.Legacy]
 */
@Composable
fun V3App(vm: V3ViewModel, legacyContent: @Composable (onBack: () -> Unit) -> Unit) {
    V3Theme {
        val stack by vm.stack.collectAsState()
        val route = stack.last()
        val context = LocalContext.current
        LaunchedEffect(vm) { vm.toastFlow.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

        if (route == Route.Legacy) {
            BackHandler { vm.back() }
            legacyContent { vm.back() }
            return@V3Theme
        }

        val recorder by vm.recorder.collectAsState()
        val controlBarOpen by vm.controlBarOpen.collectAsState()
        val saved by vm.saved.collectAsState()
        val selected by vm.selectedAddr.collectAsState()
        val detail by vm.detail.collectAsState()
        val scan by vm.scanState.collectAsState()
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

        val onScan = route == Route.Tab(AppTab.Scan)
        val isMap = route is Route.RecordingMap || route is Route.ProductMap || route is Route.DeviceMap || route is Route.DemoMap
        val sheetOpen = onScan && selected != null && detail != null
        val tab = when (route) {
            is Route.Tab -> route.tab
            is Route.Category -> AppTab.Nearby
            else -> null
        }

        val sortVersion by vm.sortVersion.collectAsState()
        // A re-sort moves the rows under a keyed list; start again from the top.
        LaunchedEffect(sortVersion) { if (sortVersion > 0) listState.scrollToItem(0) }

        BackHandler(enabled = stack.size > 1) { vm.back() }
        BackHandler(enabled = controlBarOpen) { vm.setControlBar(false) }

        val detailActions = DeviceDetailActions(
            onWindowChange = vm::setWindow,
            onDiscoverGatt = vm::discoverGatt,
            onCancelGatt = vm::cancelGatt,
            onShowOnMap = vm::showSelectedOnMap,
        )

        AppShell(
            recorder = recorder,
            callbacks = vm.recorderCallbacks,
            placement = if (onScan && !sheetOpen && !controlBarOpen) RecordPlacement.Full else RecordPlacement.Mini,
            tab = tab,
            onTab = vm::selectTab,
            showScrollToTop = showTop,
            onScrollToTop = { scope.launch { listState.animateScrollToItem(0) } },
            floatingMini = isMap,
            controlBarOpen = controlBarOpen,
            onDismissControlBar = { vm.setControlBar(false) },
            overlay = {
                val d = detail
                if (sheetOpen && d != null) ScanDeviceSheet(d, detailActions, onDismiss = { vm.select(null) })
            },
            snackbar = {
                val info = saved
                if (info != null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = (if (tab != null) 64.dp else 0.dp) + 12.dp)) {
                        RecordSavedSnackbar(
                            info.summary.durationSeconds, info.summary.sightings,
                            onAddNote = { vm.addNote(info.recordingId); vm.dismissSaved() },
                            onView = vm::viewSaved,
                        )
                    }
                }
            },
        ) {
            when (route) {
                is Route.Tab -> when (route.tab) {
                    AppTab.Scan -> ScanPage(
                        rows = scan.rows,
                        recorder = scan.recorder,
                        listState = listState,
                        refreshing = vm.refreshing.collectAsState().value,
                        onRefresh = vm::refreshScan,
                        onRowClick = { vm.select(it.id) },
                        onSettings = { vm.open(Route.Legacy) },
                        notice = vm.scanNotice.collectAsState().value,
                    )
                    AppTab.Nearby -> {
                        val nearby by vm.nearby.collectAsState()
                        NearbyPage(
                            nearby,
                            onOpenCategory = { vm.open(Route.Category(it.id)) },
                            onOpenProduct = { vm.openProduct(it.id) },
                        )
                    }
                    AppTab.Recordings -> {
                        val rec by vm.recordings.collectAsState()
                        RecordingsPage(rec, onOpenRecording = { vm.open(Route.RecordingMap(it.id)) })
                    }
                }
                is Route.Category -> {
                    val nearby by vm.nearby.collectAsState()
                    val category = (nearby.categories + nearby.unrecognized).firstOrNull { it.id == route.id }
                    if (category == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        CategoryListPage(category, onBack = { vm.back() }, onOpenProduct = { vm.openProduct(it.id) })
                    }
                }
                is Route.RecordingMap, is Route.ProductMap, is Route.DeviceMap, is Route.DemoMap -> {
                    val state by vm.mapState.collectAsState()
                    state?.let { MapRoute(it, vm, detailActions) } ?: Box(Modifier.fillMaxSize())
                }
                Route.Legacy -> Unit
            }
        }

        val noteTarget by vm.noteTarget.collectAsState()
        if (noteTarget != null) NoteDialog(vm)
    }
}

@Composable
private fun MapRoute(state: MapUiState, vm: V3ViewModel, detailActions: DeviceDetailActions) {
    val actions = MapActions(
        onBack = { vm.back() },
        onRowClick = { vm.select(it.id) },
        onBackToList = { vm.select(null) },
        onSortClick = vm::cycleSort,
        onSearchToggle = vm::toggleSearch,
        onQueryChange = vm::setQuery,
        onAreaChip = vm::toggleArea,
        onLayerToggle = vm::toggleHeat,
        detail = detailActions,
    )
    val route = vm.stack.collectAsState().value.last()
    MapPage(state, actions) { covered ->
        MapLibreMap(
            overlay = MapOverlay(
                route = if (state.selected != null) emptyList() else state.route,
                points = if (state.selected != null) state.selectedPoints else state.productPoints,
                estimate = state.estimate,
                heat = state.heat,
                fitKey = route to state.selected?.device?.id,
            ),
            coveredFraction = covered,
            onGesture = vm::onMapPanned,
            onViewport = vm::onViewport,
        )
    }
}

@Composable
private fun NoteDialog(vm: V3ViewModel) {
    val initial by vm.noteText.collectAsState()
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = vm::dismissNote,
        title = { Text("备注") },
        text = {
            Box {
                if (text.isEmpty()) Text("这次录制是在哪里、做什么", color = MaterialTheme.colorScheme.outline)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = { TextButton({ vm.saveNote(text) }) { Text("保存") } },
        dismissButton = { TextButton(vm::dismissNote) { Text("取消") } },
    )
}
