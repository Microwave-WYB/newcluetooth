package edu.ucsd.sysnet.cluetoothscanner.v2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.FixSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingStep
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchPageState
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.chrome.StorageUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.onboardingSteps
import edu.ucsd.sysnet.cluetoothscanner.map.CluetoothMapState
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The opened core plus the map state built on it (both exist only once the core is ready). */
class Ready(val core: CoreHandle, val map: CluetoothMapState)

/** §5.4 MainUiState, plus what :app keeps for the chrome and the placeholder sheet slot. */
data class MainUiState(
    val layout: LayoutMode = LayoutMode.Compact,
    /** null = first run (V-6). */
    val scope: Scope? = null,
    val view: SessionView? = null,
    val sidebarOpen: Boolean = false,
    val page: Page? = null,
    val locationPopoverOpen: Boolean = false,
    val recorderPanelOpen: Boolean = false,
    val sessions: SessionsListState? = null,
    val search: SearchPageState? = null,
    val searchRows: List<DeviceRow> = emptyList(),
    /** Search state (Q-5): the picked query, until D's SearchController owns it. */
    val searchQuery: String? = null,
    val route: List<edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint> = emptyList(),
    val storage: StorageUi? = null,
    val onboarding: OnboardingUi? = null,
    val onboardingSteps: List<OnboardingStep> = emptyList(),
    val viewport: Bbox? = null,
)


/**
 * §6.7.1: scope selection, the G-2 layout mode, the G-14 back dispatcher, P-1 to P-3, V-2 to V-7, T-9, the map's
 * events to D (the sheet slot) and C (the chrome), and the R-5 fit action. The recording itself lives in the
 * [AppGraph]; this ViewModel only reads it and asks it to change (§1.2.1).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val graph: AppGraph = (app as CluetoothApplication).graph

    private val _ready = MutableStateFlow<Ready?>(null)
    /** Null until the core is open; the splash screen is held until then (§1.2.2). */
    val ready: StateFlow<Ready?> = _ready.asStateFlow()

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val recorder: StateFlow<RecorderState> get() = graph.recorder.state
    val settings: StateFlow<Settings> get() = graph.settings.settings
    val location: StateFlow<LocationStatus> get() = graph.location.status

    private var viewJob: Job? = null

    init {
        viewModelScope.launch {
            val core = graph.core.await()
            val map = CluetoothMapState(core.queries, graph.location, MapAssets.style(app), viewModelScope)
            _ready.value = Ready(core, map)
            launch { core.queries.events.collect { if (it is CoreEvent.SessionsChanged || it is CoreEvent.DataInvalidated) refreshSessions() } }
            launch { graph.recorder.state.collect { onRecorder(it) } }
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { launchView() }
        }
    }

    private suspend fun ready(): Ready = _ready.value ?: graph.core.await().let { _ready.value!! }

    // ---- V-6 launch ----

    private suspend fun launchView() {
        val s = settings.value
        if (!s.onboardingDone) {
            val steps = onboardingSteps(true, graph.permissions.state.value)
            _ui.update {
                it.copy(onboarding = OnboardingUi(1, graph.permissions.state.value, s.userId.orEmpty(), s.userId?.let(Settings::isValidUserId) ?: false, true), onboardingSteps = steps)
            }
            refreshSessions()
            return
        }
        openLaunchView()
    }

    /** V-6: record on open, else All sessions when any session exists, else the first run (no scope). */
    private suspend fun openLaunchView() {
        refreshSessions()
        val restored = recorder.value.sessionId?.takeIf { recorder.value.phase != RecorderState.Phase.Idle }
        when {
            restored != null -> selectScope(Scope.session(restored))
            settings.value.recordOnOpen -> startRecording()
            ready().core.queries.allSessionsSummary().sessionCount > 0 -> selectScope(Scope.All)
            else -> selectScope(null)
        }
    }

    // ---- scope (L-3, V-2 … V-5, V-7) ----

    /** V-7: a new session view resets search, closes the sidebar and refits the map (E frames on show). */
    fun selectScope(scope: Scope?) {
        _ui.update { it.copy(scope = scope, sidebarOpen = false, searchQuery = null, search = null, searchRows = emptyList(), page = it.page.takeIf { p -> p == Page.Settings }) }
        viewJob?.cancel()
        viewJob = viewModelScope.launch {
            val r = ready()
            if (scope == null) { _ui.update { it.copy(view = null, route = emptyList()) }; return@launch }
            val view = r.core.queries.sessionView(scope)
            _ui.update { it.copy(view = view, route = view.route) }
            r.map.show(scope, DeviceFilter(), view.routesFaint)
            refreshSessions()
            // the live route and device count while this view is live (V-2); frozen views are read once
            while (view.live) {
                delay(LIVE_REFRESH_MS)
                val v = r.core.queries.sessionView(scope)
                _ui.update { it.copy(view = v, route = v.route) }
                if (!v.live) break
            }
        }
    }

    private suspend fun refreshSessions() {
        val q = ready().core.queries
        val current = q.currentSession()
        val list = SessionsListState.build(current, runCatching { q.allSessionsSummary() }.getOrElse { AllSessionsSummary(0, 0, null) },
            q.sessions(), _ui.value.scope, System.currentTimeMillis())
        _ui.update { it.copy(sessions = list) }
    }

    // ---- recorder (R-2, R-4, R-6, R-7, V-2, V-3) ----

    private var lastPhase = RecorderState.Phase.Idle

    private fun onRecorder(s: RecorderState) {
        val was = lastPhase
        lastPhase = s.phase
        if (s.phase == RecorderState.Phase.Idle) _ui.update { it.copy(recorderPanelOpen = false) }
        // V-3: after Stop the UI stays on the session that just ended, now frozen
        if (was != RecorderState.Phase.Idle && s.phase == RecorderState.Phase.Idle) _ui.value.scope?.let { selectScope(it) }
    }

    fun recorderForm(s: RecorderState, panelOpen: Boolean): RecorderUi.Form = when (s.phase) {
        RecorderState.Phase.Idle, RecorderState.Phase.Stopping -> RecorderUi.Form.Fab
        else -> if (panelOpen) RecorderUi.Form.Panel else RecorderUi.Form.Capsule
    }

    /** R-2 / V-2: start, then switch to the new current session. */
    fun startRecording() = viewModelScope.launch {
        graph.recorder.start().onSuccess { id -> selectScope(Scope.session(id)) }
    }
    fun pause() = viewModelScope.launch { graph.recorder.pause() }
    fun resume() = viewModelScope.launch { graph.recorder.resume() }
    fun stop() = viewModelScope.launch { graph.recorder.stop() }
    fun setRecorderPanel(open: Boolean) = _ui.update { it.copy(recorderPanelOpen = open) }

    /** R-5: collapse the panel, close any page and fit the main map to the route. */
    fun fitRoute() {
        _ui.update { it.copy(recorderPanelOpen = false, page = null) }
        val box = bboxOf(_ui.value.route) ?: return
        _ready.value?.map?.frame(box)
    }

    fun fix(): FixSummary? = graph.location.myLocation.value?.let { FixSummary(it.at.lat, it.at.lon, it.accuracyM, it.ageMs) }

    // ---- layout, sidebar, pages, popover ----

    /** G-2 and T-9: a breakpoint crossing re-lays out and keeps the state. */
    fun setLayout(mode: LayoutMode) { if (_ui.value.layout != mode) _ui.update { it.copy(layout = mode) } }
    fun toggleSidebar() = _ui.update { it.copy(sidebarOpen = !it.sidebarOpen) }
    fun closeSidebar() = _ui.update { it.copy(sidebarOpen = false) }
    fun setLocationPopover(open: Boolean) = _ui.update { it.copy(locationPopoverOpen = open) }

    fun openSettings() {
        _ui.update { it.copy(page = Page.Settings, sidebarOpen = false) }
        refreshStorage()
    }

    // ---- G-14 back ----

    /** The sheet stack's back step (D's `DiscoverModel.handleBack()`); a placeholder slot until :sheets exposes it. */
    var sheetsBack: suspend () -> Boolean = { false }
    var sheetsHasPushed: () -> Boolean = { false }

    fun backLayers(): BackLayers = _ui.value.let {
        BackLayers(it.locationPopoverOpen, it.page != null, sheetsHasPushed(), it.sidebarOpen, it.recorderPanelOpen, it.searchQuery != null)
    }

    /** G-14: closes the topmost layer; false when nothing is open (Back leaves the app). */
    fun onBack(): Boolean = when (backTarget(backLayers())) {
        BackTarget.LocationPopover -> { setLocationPopover(false); true }
        BackTarget.Page -> { closePage(); true }
        BackTarget.PushedSheet -> { viewModelScope.launch { sheetsBack() }; true }
        BackTarget.Sidebar -> { closeSidebar(); true }
        BackTarget.RecorderPanel -> { setRecorderPanel(false); true }
        BackTarget.SearchState -> { exitSearch(); true }
        BackTarget.Leave -> false
    }

    /** Q-3 / Q-7: Back from the search page returns to search state when it was opened from the bar. */
    fun closePage() = _ui.update { it.copy(page = null, search = null) }

    // ---- search (Q-3, Q-4, Q-5 entry, Q-7) ----

    private var searchOrder: OrderedKeys? = null
    private var searchJob: Job? = null

    /** Q-3: the scope is the map viewport at the moment the page opened. */
    fun openSearch() {
        val q = _ui.value.searchQuery.orEmpty()
        _ui.update { it.copy(page = Page.SearchPage, search = SearchPageState(q, it.viewport, null, false), sidebarOpen = false) }
        if (q.isNotBlank()) runSearch()
    }

    fun setQuery(q: String) {
        _ui.update { it.copy(search = it.search?.copy(query = q)) }
        runSearch()
    }

    fun searchEverywhere() {
        _ui.update { it.copy(search = it.search?.copy(area = null, searchedEverywhere = true)) }
        runSearch()
    }

    /** Q-1/Q-2 through the core; results in the session's fixed order (G-9). */
    private fun runSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val page = _ui.value.search ?: return@launch
            val scope = _ui.value.scope ?: return@launch
            val q = ready().core.queries
            val hits = q.search(scope, page.query, page.area)
            if (hits == null) { _ui.update { it.copy(search = it.search?.copy(results = null), searchRows = emptyList()) }; return@launch }
            val order = searchOrder ?: OrderedKeys(q.deviceOrder(scope).keys).also { searchOrder = it }
            order.append(hits.keys)
            val keys = order.visible(hits).take(MAX_ROWS).toLongArray()
            val rows = q.deviceRows(scope, keys)
            _ui.update { it.copy(search = it.search?.copy(results = order), searchRows = rows) }
        }
    }

    /** Q-5: picking a result leaves the page for search state; the map shows only the matches. */
    fun pick(key: DeviceKey) {
        val page = _ui.value.search ?: return
        _ui.update { it.copy(page = null, searchQuery = page.query, search = null) }
        val scope = _ui.value.scope ?: return
        _ready.value?.map?.let { m ->
            m.show(scope, DeviceFilter(query = page.query, area = page.area), _ui.value.view?.routesFaint ?: false)
            m.setSelection(key)
            m.focusDevice(key)
        }
    }

    /** Q-7: the bar's X or Back leaves search state. */
    fun exitSearch() {
        _ui.update { it.copy(searchQuery = null) }
        val scope = _ui.value.scope ?: return
        _ready.value?.map?.let { it.setSelection(null); it.show(scope, DeviceFilter(), _ui.value.view?.routesFaint ?: false) }
    }

    // ---- map events (M-7, P-6, D-4, Q-6) ----

    /** The sheet stack's side of [MapEvents] (D's `SheetMapEvents`); a placeholder slot until :sheets exposes it. */
    var sheetEvents: MapEvents? = null

    val mapEvents: MapEvents = object : MapEvents {
        override fun onUserGestureStart() { sheetEvents?.onUserGestureStart() }
        override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) {
            _ui.update { it.copy(viewport = viewport) }
            sheetEvents?.onCameraIdle(byUser, viewport, zoom)
        }
        override fun onDeviceTap(key: DeviceKey) { sheetEvents?.onDeviceTap(key) ?: _ready.value?.map?.setSelection(key) }
        override fun onEmptyTap() { sheetEvents?.onEmptyTap() ?: _ready.value?.map?.setSelection(null) }
    }

    /** What the sheets, the panel and the chrome cover, for the map's padding (M-10, D-14). */
    fun setCover(insets: CoverInsets) { _ready.value?.map?.setCover(insets) }

    // ---- Settings (O-1) ----

    fun updateSettings(t: (Settings) -> Settings) = viewModelScope.launch { graph.settings.update(t) }

    fun refreshStorage() = viewModelScope.launch {
        val r = ready()
        val stats = r.core.storage.storageStats()
        val cache = runCatching { graph.mapCache.sizeBytes() }.getOrNull()
        val scope = _ui.value.scope ?: Scope.All
        _ui.update {
            val prev = it.storage
            it.copy(storage = StorageUi(stats.databaseBytes, stats.rawFilesBytes, cache, stats.pendingCount,
                prev?.exportScope ?: scope, prev?.exportFormat, false, null, prev?.deleteScope ?: scope))
        }
    }

    fun editStorage(t: (StorageUi) -> StorageUi) = _ui.update { it.copy(storage = it.storage?.let(t)) }

    /** O-1 item 10: prepare the scope's export, then hand it to the system share sheet or Downloads (B). */
    fun export(toDownloads: Boolean) = viewModelScope.launch {
        val st = _ui.value.storage ?: return@launch
        val format: ExportFormat = st.exportFormat ?: return@launch
        editStorage { it.copy(exporting = true) }
        val prepared = runCatching { ready().core.storage.prepareExport(st.exportScope, format) }.getOrNull()
        if (prepared != null) { if (toDownloads) graph.exportShare.saveToDownloads(prepared) else graph.exportShare.share(prepared) }
        editStorage { it.copy(exporting = false) }
    }

    /** O-1 item 11, §2.8: Confirm, then the second step only with pending files; false keeps them until uploaded. */
    fun deleteConfirmed() {
        val st = _ui.value.storage ?: return
        if (st.pendingCount > 0) editStorage { it.copy(deleteConfirm = DeleteConfirm.LoseUnuploaded) } else delete(false)
    }

    fun delete(includeUnuploaded: Boolean) = viewModelScope.launch {
        val st = _ui.value.storage ?: return@launch
        editStorage { it.copy(deleteConfirm = null) }
        val outcome = ready().core.storage.deleteLocalData(st.deleteScope, includeUnuploaded)
        if (outcome !is DeleteOutcome.Refused) {
            refreshStorage(); refreshSessions()
            val shown = _ui.value.scope
            if (shown != null && (st.deleteScope.sessions == SessionScope.All || shown == st.deleteScope)) openLaunchView()
        }
    }

    // ---- Onboarding (O-2) ----

    fun editOnboarding(t: (OnboardingUi) -> OnboardingUi) = _ui.update { it.copy(onboarding = it.onboarding?.let(t)) }

    /** O-2 Done: save, then open the first run, recording straight away if the switch was left on. */
    fun finishOnboarding() = viewModelScope.launch {
        val ob = _ui.value.onboarding ?: return@launch
        graph.settings.update { it.copy(userId = ob.userId, recordOnOpen = ob.autoRecord, onboardingDone = true) }
        _ui.update { it.copy(onboarding = null) }
        openLaunchView()
    }

    private companion object {
        const val LIVE_REFRESH_MS = 5_000L
        const val MAX_ROWS = 200
    }
}

/** A session's label for the one-session scope in Settings ("6:05 PM"). */
fun SessionSummary.label(): String = edu.ucsd.sysnet.cluetoothscanner.designsystem.Format.time(startedMs)
