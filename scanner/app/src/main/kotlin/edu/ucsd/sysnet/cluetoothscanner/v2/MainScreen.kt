package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.chrome.LocationPopover
import edu.ucsd.sysnet.cluetoothscanner.chrome.Onboarding
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingPermission
import edu.ucsd.sysnet.cluetoothscanner.chrome.PhoneSidebarLayout
import edu.ucsd.sysnet.cluetoothscanner.chrome.PhoneTopChrome
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderControl
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderGeometry
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsList
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsPage
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.TabletTopChrome
import edu.ucsd.sysnet.cluetoothscanner.chrome.TopChromeActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.TopChromeState
import edu.ucsd.sysnet.cluetoothscanner.chrome.rememberElapsedMs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import edu.ucsd.sysnet.cluetoothscanner.map.CluetoothMap
import edu.ucsd.sysnet.cluetoothscanner.map.LocateControl
import edu.ucsd.sysnet.cluetoothscanner.map.LocatePlacement
import edu.ucsd.sysnet.cluetoothscanner.map.RouteMiniMap
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetsLayer
import edu.ucsd.sysnet.cluetoothscanner.sheets.rememberSheetsState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets

/**
 * P-1 … P-3, T-1 … T-7: the map behind everything; the FAB layer (locate, the record FAB); the sheet slot; the
 * recording island above the sheets; the top chrome; the pages. One layout for phone and tablet (G-2).
 */
@Composable
fun MainScreen(vm: MainViewModel) {
    val ready by vm.ready.collectAsState()
    val ui by vm.ui.collectAsState()
    val rec by vm.recorder.collectAsState()
    val settings by vm.settings.collectAsState()
    val location by vm.location.collectAsState()
    val r = ready ?: return
    val density = LocalDensity.current
    val nav = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val status = with(density) { WindowInsets.statusBars.getTop(this).toDp() }

    // read `ui` here, in this scope, so the handler's enabled state recomposes with it (G-14)
    BackHandler(enabled = backTarget(vm.backLayers(ui)) != BackTarget.Leave) { vm.onBack() }

    BoxWithConstraints(Modifier.fillMaxSize().background(CluetoothTheme.colors.bg)) {
        val layout = LayoutMode.of(maxWidth.value)
        SideEffect { vm.setLayout(layout) }
        val tablet = layout.tablet
        val hasSession = ui.scope != null
        val live = ui.view?.live == true
        val form = vm.recorderForm(rec, ui.recorderPanelOpen)
        val recorderUi = RecorderUi(form, rec.phase == RecorderState.Phase.Paused, rememberElapsedMs(rec.activeBeforeMs, rec.runningSinceMs),
            rec.deviceCount, vm.fix(), ui.route)
        val recorderActions = remember(vm) {
            RecorderActions(vm::startRecording.let { f -> { f(); Unit } }, { vm.setRecorderPanel(true) }, { vm.setRecorderPanel(false) },
                { vm.pause(); Unit }, { vm.resume(); Unit }, { vm.stop(); Unit }, vm::fitRoute)
        }
        // lane D's sheets for the shown scope (V-7: a new scope starts them fresh)
        val sheets = ui.scope?.let { scope ->
            rememberSheetsState(scope, live, ui.view?.routesFaint ?: (scope.sessions == SessionScope.All), r.core.queries,
                vm.graph.gattRunner, r.map, reducedMotion = CluetoothTheme.motion.reduced)
        }
        DisposableEffect(sheets) { vm.sheets = sheets; onDispose { if (vm.sheets === sheets) vm.sheets = null } }
        // P-3: the FAB sits above the root's collapsed peek (and the navigation bar); a rising sheet covers it
        val rootPeekPx = sheets?.stack?.root?.peek?.takeUnless { it.isNaN() }
        val sheetCover = if (sheets != null && !tablet) with(density) { (rootPeekPx ?: PlaceholderPeek.toPx()).toDp() } + nav else 0.dp
        // M-10 / D-14: the map's padding is what the top sheet (phone) or the panel (tablet) covers
        val heightPx = with(density) { maxHeight.toPx() }
        val topOffsetPx = sheets?.let { it.stack.offsetOf(it.stack.top) }
        LaunchedEffect(tablet, topOffsetPx, heightPx) {
            vm.setCover(
                if (tablet) CoverInsets(top = Tokens.topZone.value.toInt(), bottom = 0, left = (Tokens.edge + Tokens.panelW).value.toInt(), right = 0)
                else CoverInsets(top = Tokens.topZone.value.toInt(), bottom = with(density) { ((heightPx - (topOffsetPx ?: heightPx)).coerceAtLeast(0f)).toDp() }.value.toInt(), left = 0, right = 0),
            )
        }
        val miniMap: @Composable (Modifier) -> Unit = { m ->
            RouteMiniMap(ui.route, vm.graph.location.myLocation.value?.at, m, dark = CluetoothTheme.isDark)
        }
        val sessions = @Composable { m: Modifier ->
            ui.sessions?.let { SessionsList(it, vm::selectScope, vm::openSettings, m, tablet = tablet, bottomInset = if (tablet) 0.dp else nav) }
        }

        val mainLayers = @Composable {
            // the FAB layer: above the map, below every sheet (P-3, P-4, P-5)
            val mapUi by r.map.ui.collectAsState()
            val recH = if (form == RecorderUi.Form.Panel) RecorderGeometry.panelHeight(true) else Tokens.fab
            LocateControl(
                mapUi, if (tablet) LocatePlacement.Tablet(recH) else LocatePlacement.Phone(sheetCover),
                onLocate = r.map::locate, onWarning = { vm.setLocationPopover(!ui.locationPopoverOpen) },
            )
            if (form == RecorderUi.Form.Fab) RecorderControl(recorderUi, recorderActions, tablet, bottomCover = sheetCover, miniMap = miniMap)
            // the sheet stack (lane D); on tablet ☰ swaps the panel's content for the sessions list (T-6)
            if (sheets != null && !(tablet && ui.sidebarOpen)) SheetsLayer(sheets, tablet)
            if (tablet && ui.sidebarOpen) SessionsPanel { sessions(Modifier) }
            if (form != RecorderUi.Form.Fab) RecorderControl(recorderUi, recorderActions, tablet, bottomCover = sheetCover, searching = sheets?.searching == true, miniMap = miniMap)
            if (ui.locationPopoverOpen) LocationPopoverLayer(location, tablet, sheetCover, recH, vm::locationFix) { vm.setLocationPopover(false) }
        }
        // tablet: the floating pieces stay inside the system bars; the map runs full-bleed behind them (P-1)
        val safe = if (tablet) Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars) else Modifier.fillMaxSize()
        val main = @Composable {
            Box(Modifier.fillMaxSize()) {
                CluetoothMap(r.map, vm.mapEvents, Modifier.fillMaxSize(), dark = CluetoothTheme.isDark)
                Box(safe) { mainLayers() }
            }
        }

        val topState = TopChromeState(
            hasSession = hasSession, sidebarOpen = ui.sidebarOpen && !tablet, recorderPanelOpen = form == RecorderUi.Form.Panel && !tablet,
            recording = rec.phase != RecorderState.Phase.Idle, page = ui.search.takeIf { ui.page == Page.SearchPage },
            searchQuery = sheets?.discover?.search?.query?.takeIf { sheets.searching } ?: ui.searchQuery?.takeIf { sheets?.searching != false },
            showSearchThisArea = sheets?.searchMovedAway == true,
        )
        val topActions = remember(vm) {
            TopChromeActions(vm::toggleSidebar, vm::openSearch, vm::closePage, vm::setQuery, vm::pick, vm::searchEverywhere,
                vm::openSearch, vm::exitSearch, vm::searchThisArea)
        }
        if (tablet) {
            main()
            TabletTopChrome(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars), sessionsOpen = ui.sidebarOpen, query = ui.search?.query ?: ui.searchQuery.orEmpty(),
                resultsOpen = ui.page == Page.SearchPage, page = ui.search, rows = ui.searchRows, live = live,
                actions = topActions.forTablet(vm), showSearchThisArea = sheets?.searchMovedAway == true)
        } else {
            PhoneSidebarLayout(ui.sidebarOpen, vm::closeSidebar, sidebar = { m -> sessions(m) }) { main() }
            PhoneTopChrome(topState, topActions, ui.searchRows, live, statusInset = status)
        }

        // O-1: Settings over everything
        ui.storage?.let { st ->
            val shown = ui.scope?.takeIf { it.sessions is SessionScope.Sessions }
            val label = (shown?.sessions as? SessionScope.Sessions)?.ids?.firstOrNull()?.let { id ->
                ui.sessions?.let { s -> (listOfNotNull(s.current) + s.days.flatMap { it.sessions }).firstOrNull { it.id == id }?.label() }
            }
            SettingsPage(
                ui.page == Page.Settings, SettingsUi(settings, vm.graph.uploads.status.collectAsState().value, st),
                SettingsActions(
                    onBack = vm::closePage, onSettings = { vm.updateSettings(it) }, onUploadNow = vm.graph.uploads::uploadNow,
                    onExportScope = { s -> vm.editStorage { it.copy(exportScope = s) } }, onExportFormat = { f -> vm.editStorage { it.copy(exportFormat = f) } },
                    onShare = { vm.export(false) }, onSaveToDownloads = { vm.export(true) },
                    onDeleteScope = { s -> vm.editStorage { it.copy(deleteScope = s) } },
                    onDeleteRequest = { vm.editStorage { it.copy(deleteConfirm = edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm.Confirm) } },
                    onDeleteConfirm = vm::deleteConfirmed, onDelete = { vm.delete(it) }, onDeleteCancel = { vm.editStorage { it.copy(deleteConfirm = null) } },
                ),
                sessionLabel = label, sessionScope = if (label != null) shown else null, statusInset = status, bottomInset = nav,
            )
        }

        // O-2: onboarding on first launch
        ui.onboarding?.let { ob ->
            Onboarding(
                ob, ui.onboardingSteps,
                OnboardingActions(
                    onRequest = { which: OnboardingPermission -> vm.request(which) }, onOpenSettings = vm.graph.permissions::openAppSettings,
                    onEnableBluetooth = vm::enableBluetooth, onUserId = { id -> vm.editOnboarding { it.copy(userId = id, userIdValid = edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings.isValidUserId(id)) } },
                    onAutoRecord = { v -> vm.editOnboarding { it.copy(autoRecord = v) } },
                    onBack = { vm.editOnboarding { it.copy(step = it.step - 1) } }, onNext = { vm.editOnboarding { it.copy(step = it.step + 1) } },
                    onDone = { vm.finishOnboarding() },
                ),
                statusInset = status, bottomInset = nav,
            )
        }
    }
}

/** Tablet: the field types into the search; clearing it leaves (T-5). */
private fun TopChromeActions.forTablet(vm: MainViewModel) = TopChromeActions(
    onMenu, onSearchOpen = {}, onSearchBack = vm::closePage,
    onQueryChange = { q -> if (vm.ui.value.page != Page.SearchPage) vm.openSearch(); if (q.isEmpty()) vm.closePage() else vm.setQuery(q) },
    onPick, onSearchEverywhere, onBarEdit, onBarExit, onSearchThisArea,
)

/** P-5: the popover above the warning button (E places the button; the anchor follows LocateControl's spot). */
@Composable
private fun LocationPopoverLayer(status: LocationStatus, tablet: Boolean, sheetCover: Dp, recorderHeight: Dp, onFix: (LocationStatus.Action) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose)) {
        val gap = 8.dp
        val bottom = if (tablet) Tokens.edge + 12.dp + recorderHeight + 12.dp + Tokens.rb + gap
        else sheetCover + Tokens.edge + 12.dp + (Tokens.fab - Tokens.rb) / 2 + Tokens.rb + gap
        Box(
            Modifier.align(if (tablet) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(start = Tokens.edge, end = Tokens.edge, bottom = bottom).width(300.dp),
            contentAlignment = if (tablet) Alignment.BottomEnd else Alignment.BottomStart,
        ) { LocationPopover(status, onAction = onFix) }
    }
}

