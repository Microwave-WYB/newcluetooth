package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.recordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.filterScanRows
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DiscoverContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DiscoverTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.HistoryContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.HistoryTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapLibreMap
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapOverlay
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ProductContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ProductTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.RecordingMenu
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanDeviceSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SettingsActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SettingsContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SettingsTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme
import kotlinx.coroutines.launch

/**
 * The v3 app: one [AppShell] (a stock Scaffold, so the record button can move between pages) with the
 * current route's top app bar and content in it, the device sheet, the error snackbar and the note dialog.
 */
@Composable
fun V3App(vm: V3ViewModel) {
    V3Theme {
        val stack by vm.stack.collectAsStateWithLifecycle()
        val route = stack.last()
        val context = LocalContext.current
        LaunchedEffect(vm) { vm.toastFlow.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

        // The live list and its one-second loop run only while the UI is on screen. A recording lives in
        // its own service and is not affected by this.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, vm) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> vm.setUiActive(true)
                    Lifecycle.Event.ON_STOP -> vm.setUiActive(false)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                vm.setUiActive(false)
            }
        }

        // A recording's export: the core prepares the file, the stock document picker says where it goes.
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            vm.finishExport(uri)
        }
        LaunchedEffect(vm) { vm.exportFileNames.collect { exportLauncher.launch(it) } }

        val recorder by vm.recorder.collectAsStateWithLifecycle()
        val controlBarOpen by vm.controlBarOpen.collectAsStateWithLifecycle()
        val selected by vm.selectedAddr.collectAsStateWithLifecycle()
        val detail by vm.detail.collectAsStateWithLifecycle()
        val scan by vm.scanState.collectAsStateWithLifecycle()
        val notice by vm.headerNotice.collectAsStateWithLifecycle()
        val problem by vm.problem.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

        val onScan = route == Route.Tab(AppTab.Scan)
        // The device sheet opens over the Scan list and over a product page's devices.
        val sheetOpen = (onScan || route is Route.Product) && selected != null && detail != null
        val tab = when (route) {
            is Route.Tab -> route.tab
            is Route.Product -> AppTab.Discover
            else -> null
        }

        // Back: the control bar (closed in AppShell, composed last), the sheet, the selection, then pages;
        // on the Discover and History tabs it goes to the Scan tab before leaving the app.
        BackHandler(enabled = stack.size > 1) { vm.back() }
        BackHandler(enabled = stack.size == 1 && tab != null && tab != AppTab.Scan) { vm.selectTab(AppTab.Scan) }

        val detailActions = DeviceDetailActions(
            onDiscoverGatt = vm::discoverGatt,
            onCancelGatt = vm::cancelGatt,
            onShowOnMap = vm::showSelectedOnMap,
        )

        val snackbarHost = remember { SnackbarHostState() }
        // Only a recording that could not be saved says anything; a saved one is simply in History.
        val retryLabel = stringResource(R.string.action_retry)
        LaunchedEffect(problem) {
            val message = problem ?: return@LaunchedEffect
            val result = snackbarHost.showSnackbar(message, actionLabel = retryLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) vm.retrySave() else vm.dismissProblem()
        }

        // Search on the Scan page: it only hides rows, the list keeps its order.
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        val mapState by vm.mapState.collectAsStateWithLifecycle()
        val discover by vm.discover.collectAsStateWithLifecycle()
        val discoverMode by vm.discoverMode.collectAsStateWithLifecycle()
        val rec by vm.recordings.collectAsStateWithLifecycle()
        val mapActions = MapActions(
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
        val product = (route as? Route.Product)?.let { vm.productHeader(it.pageId) }

        AppShell(
            recorder = recorder,
            callbacks = vm.recorderCallbacks,
            placement = recordPlacement(recorder.phase, roomForExpanded = onScan && !sheetOpen && !controlBarOpen),
            tab = tab,
            onTab = vm::selectTab,
            topBar = {
                when (route) {
                    is Route.Tab -> when (route.tab) {
                        AppTab.Scan -> ScanTopBar(
                            deviceCount = scan.rows.size,
                            location = scan.location,
                            notice = notice,
                            searching = searching,
                            onSearchToggle = { searching = !searching; if (!searching) query = "" },
                            onSettings = { vm.open(Route.Settings) },
                        )
                        AppTab.Discover -> DiscoverTopBar(discover, discoverMode, notice)
                        AppTab.History -> HistoryTopBar(rec, notice)
                    }
                    is Route.Product -> ProductTopBar(product?.title.orEmpty(), onBack = { vm.back() })
                    is Route.RecordingMap -> mapState?.let { state ->
                        val summary = vm.recordingSummary(route.id)
                        MapTopBar(state, mapActions) {
                            RecordingMenu(
                                status = summary?.upload,
                                inProgress = rec.current?.id == route.id,
                                onRetryUpload = vm::uploadNow,
                                onExportJsonl = { vm.exportRecording(route.id, GatewayExportFormat.JSONL) },
                                onExportParquet = { vm.exportRecording(route.id, GatewayExportFormat.PARQUET) },
                                onDelete = { vm.deleteRecordingData(route.id) },
                            )
                        }
                    }
                    is Route.ProductMap, is Route.DeviceMap -> mapState?.let { MapTopBar(it, mapActions) }
                    Route.Settings -> SettingsTopBar { vm.back() }
                }
            },
            showScrollToTop = onScan && showTop,
            onScrollToTop = { scope.launch { listState.animateScrollToItem(0) } },
            snackbarHostState = snackbarHost,
            controlBarOpen = controlBarOpen,
            onDismissControlBar = { vm.setControlBar(false) },
            startAllowed = onScan,
        ) { padding ->
            when (route) {
                is Route.Tab -> when (route.tab) {
                    AppTab.Scan -> ScanContent(
                        rows = filterScanRows(scan.rows, searching, query),
                        sortVersion = scan.sortVersion,
                        listState = listState,
                        padding = padding,
                        refreshing = vm.refreshing.collectAsStateWithLifecycle().value,
                        onRefresh = vm::refreshScan,
                        onRowClick = { vm.select(it.id) },
                        searching = searching,
                        query = query,
                        onQueryChange = { query = it },
                    )
                    AppTab.Discover -> DiscoverContent(
                        discover, discoverMode, padding,
                        onMode = vm::setDiscoverMode,
                        onOpenProduct = { vm.openProduct(it.id) },
                    )
                    AppTab.History -> HistoryContent(
                        rec, padding,
                        onOpenRecording = { vm.open(Route.RecordingMap(it.id)) },
                        onEditNote = { vm.addNote(it.id) },
                    )
                }
                is Route.Product -> if (product != null) {
                    // The map state of a product page has no selection of its own: the sheet shows the device.
                    val state = mapState
                    ProductContent(
                        product, state, padding,
                        onOpenMap = { vm.openProductMap(product.id) },
                        onDevice = { vm.select(it.id) },
                    ) {
                        if (state != null) ProductMapPreview(state, route)
                    }
                } else {
                    Box(Modifier.fillMaxSize())
                }
                is Route.RecordingMap, is Route.ProductMap, is Route.DeviceMap ->
                    mapState?.let { MapRoute(it, vm, mapActions, padding) } ?: Box(Modifier.fillMaxSize())
                Route.Settings -> SettingsContent(
                    vm.settings.collectAsStateWithLifecycle().value,
                    SettingsActions(
                        onUserId = vm::setUserId,
                        onAutoRecord = vm::setAutoRecord,
                        onWifiOnly = vm::setWifiOnly,
                        onCodedPhy = vm::setCodedPhy,
                        onUploadNow = vm::uploadNow,
                    ),
                    padding,
                )
            }
        }
        val d = detail
        if (sheetOpen && d != null) ScanDeviceSheet(d, detailActions, onDismiss = { vm.select(null) })

        val noteTarget by vm.noteTarget.collectAsStateWithLifecycle()
        if (noteTarget != null) NoteDialog(vm)
    }
}

@Composable
private fun MapRoute(state: MapUiState, vm: V3ViewModel, actions: MapActions, padding: PaddingValues) {
    val route = vm.stack.collectAsStateWithLifecycle().value.last()
    var offline by remember { mutableStateOf(false) }
    // Frame the camera again when the content first arrives: points load after the selection is made.
    // Only what the overlay draws counts: with a selection that is its points or estimate, not the route.
    val hasContent = if (state.selected != null) {
        state.selectedPoints.isNotEmpty() || state.estimate != null
    } else {
        state.route.size >= 2 || state.productPoints.isNotEmpty()
    }
    MapContent(state, actions, padding, notice = if (offline) R.string.notice_offline_map else null) { covered ->
        MapLibreMap(
            overlay = MapOverlay(
                route = if (state.selected != null) emptyList() else state.route,
                points = if (state.selected != null) state.selectedPoints else state.productPoints,
                estimate = state.estimate,
                heat = state.heat,
                fitKey = Triple(route, state.selected?.device?.id, hasContent),
            ),
            coveredFraction = covered,
            onGesture = vm::onMapPanned,
            onViewport = vm::onViewport,
            onOffline = { offline = it },
        )
    }
}

/** The product page's map: the product's points, framed once; it takes no gestures (the card above it opens the full map). */
@Composable
private fun ProductMapPreview(state: MapUiState, route: Route) {
    MapLibreMap(
        overlay = MapOverlay(route = emptyList(), points = state.productPoints, estimate = null, heat = emptyList(), fitKey = route to state.productPoints.isNotEmpty()),
        coveredFraction = { 0f },
        onGesture = {},
        onViewport = {},
    )
}

@Composable
private fun NoteDialog(vm: V3ViewModel) {
    // null until the store has answered: the editor starts from its note, never from a placeholder.
    val stored by vm.noteText.collectAsStateWithLifecycle()
    val editable by vm.noteEditable.collectAsStateWithLifecycle()
    val canEdit = stored != null && editable
    var text by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!edited) stored?.let { text = it } }
    AlertDialog(
        onDismissRequest = vm::dismissNote,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; edited = true },
                enabled = canEdit,
                placeholder = {
                    Text(
                        stringResource(
                            when {
                                !editable -> R.string.note_unavailable
                                stored == null -> R.string.note_loading
                                else -> R.string.note_hint
                            },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton({ vm.saveNote(text) }, enabled = canEdit) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(vm::dismissNote) { Text(stringResource(R.string.action_cancel)) } },
    )
}
