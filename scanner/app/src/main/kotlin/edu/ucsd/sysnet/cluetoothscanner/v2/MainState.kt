package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingPermission
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingStep
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchPageState
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.chrome.StorageUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.onboardingSteps
import edu.ucsd.sysnet.cluetoothscanner.map.CluetoothMapState
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetsState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys

/*
 * The main screen's logic (AGENTS.md design principles): one immutable control state, the pure
 * `reduce(state, event) -> Step(state, effects)`, and a sealed MainEffect for everything it wants done. MainViewModel
 * is the runner: it executes the effects and feeds their results back as events. Nothing here calls the platform,
 * the core, the map, the sheets or the clock.
 */

/** The opened core plus the map state built on it. */
class Ready(val core: CoreHandle, val map: CluetoothMapState)

/** The screen's link to the core: waiting until it is open and the map state is built (§1.2.2). */
sealed interface CoreLink {
    data object Waiting : CoreLink
    data class Linked(val ready: Ready) : CoreLink
}

/** Lane D's sheets for the shown scope; they are a Compose holder, so the composition attaches and detaches them. */
sealed interface SheetsLink {
    data object None : SheetsLink
    data class Attached(val sheets: SheetsState) : SheetsLink
}

/** The recorder as the screen mirrors it (V-3, R-4): the panel exists only while a recording is active. */
sealed interface RecorderMirror {
    data object Idle : RecorderMirror
    data class Active(val panelOpen: Boolean) : RecorderMirror
}

/** O-2: the onboarding page and the steps it was opened with. */
data class OnboardingFlow(val ui: OnboardingUi, val steps: List<OnboardingStep>)

/** Counts scope selections (V-7) and V-3's re-read after Stop, so the view is re-read even for the same scope. */
@JvmInline
value class ViewEpoch(val n: Int) {
    fun next() = ViewEpoch(n + 1)
}

/** The map camera's last resting place (D-4, Q-3, Q-6); per-frame values never come here. */
data class MapCamera(val viewport: Bbox, val zoom: Double)

/** What keys the session view's work: the scope and the selection it was made by. */
data class ViewKey(val scope: Scope?, val epoch: ViewEpoch)

/** What keys the search page's query (Q-3, Q-4); its order lives as long as the session view (G-9). */
data class SearchKey(val view: ViewKey, val query: String, val area: Bbox?)

/** The session view's data (the core's), owned by the view job and tagged with the key it was read for. */
data class ViewData(val key: ViewKey, val view: SessionView)

/** The search page's results, owned by the search job and tagged with the key they were read for. */
data class SearchResults(val key: SearchKey, val order: OrderedKeys?, val rows: List<DeviceRow>)

/** What V-6 decides on: the recorder's restored session, the stored setting and the core's session count. */
data class LaunchFacts(val liveSessionId: String?, val recordOnOpen: Boolean, val sessionCount: Int)

/** What the Settings storage section shows (O-1): the core's stats and the map cache, as read. */
data class StorageFacts(val databaseBytes: Long, val rawFilesBytes: Long, val mapCacheBytes: Long?, val pendingCount: Int)

/**
 * §5.4 MainUiState: the screen's control state. Big data (the session view and route, the sessions list, the search
 * order and rows) stays with its owner, keyed by [viewKey] and [searchKey]; only settled map values come in.
 */
data class MainUiState(
    val layout: LayoutMode = LayoutMode.Compact,
    /** null = first run (V-6). */
    val scope: Scope? = null,
    val epoch: ViewEpoch = ViewEpoch(0),
    val sidebarOpen: Boolean = false,
    val page: Page? = null,
    val locationPopoverOpen: Boolean = false,
    val recorder: RecorderMirror = RecorderMirror.Idle,
    /** Search state (Q-5): the picked query, shown in the bar until D's search state ends. */
    val searchQuery: String? = null,
    val storage: StorageUi? = null,
    val onboarding: OnboardingFlow? = null,
    val camera: MapCamera? = null,
    val core: CoreLink = CoreLink.Waiting,
    val sheets: SheetsLink = SheetsLink.None,
) {
    val viewKey: ViewKey get() = ViewKey(scope, epoch)
    val searchPage: Page.SearchPage? get() = page as? Page.SearchPage
    /** The search page's work runs only within a session view (Q-1 searches a scope). */
    val searchKey: SearchKey? get() = searchPage?.takeIf { scope != null }?.let { SearchKey(viewKey, it.query, it.area) }
    val recorderPanelOpen: Boolean get() = (recorder as? RecorderMirror.Active)?.panelOpen == true
    val ready: Ready? get() = (core as? CoreLink.Linked)?.ready
    val attachedSheets: SheetsState? get() = (sheets as? SheetsLink.Attached)?.sheets
}

/** Everything that changes [MainUiState]: the user's intents, and the results the runner feeds back. */
sealed interface MainEvent {
    // ---- results and platform state ----
    data class CoreLinked(val ready: Ready) : MainEvent
    data class SettingsLoaded(val settings: Settings, val permissions: PermissionState) : MainEvent
    data class PermissionsChanged(val permissions: PermissionState) : MainEvent
    data class LaunchFactsRead(val facts: LaunchFacts) : MainEvent
    data class RecorderChanged(val phase: RecorderState.Phase) : MainEvent
    data class RecordingStarted(val sessionId: String) : MainEvent
    data class StorageLoaded(val facts: StorageFacts) : MainEvent
    data object ExportFinished : MainEvent
    data class DeleteFinished(val scope: Scope, val deleted: Boolean) : MainEvent
    data object OnboardingSaved : MainEvent
    data class CameraIdle(val camera: MapCamera) : MainEvent
    data class SheetsAttached(val sheets: SheetsState) : MainEvent
    data class SheetsDetached(val sheets: SheetsState) : MainEvent

    // ---- intents ----
    data class SelectScope(val scope: Scope?) : MainEvent
    data object StartRecording : MainEvent
    data object PauseRecording : MainEvent
    data object ResumeRecording : MainEvent
    data object StopRecording : MainEvent
    data class SetRecorderPanel(val open: Boolean) : MainEvent
    /** R-5: the panel collapses, any page closes and the map fits the route. */
    data object FitRoute : MainEvent
    data class SetLayout(val mode: LayoutMode) : MainEvent
    data object ToggleSidebar : MainEvent
    data object CloseSidebar : MainEvent
    data class SetLocationPopover(val open: Boolean) : MainEvent
    data class LocationFix(val action: LocationStatus.Action) : MainEvent
    data object OpenSettings : MainEvent
    data object ClosePage : MainEvent
    /** G-14: System Back, with the sheet stack's own layers as read when it was pressed. */
    data class Back(val pushedSheet: Boolean, val sheetSearching: Boolean) : MainEvent
    data object OpenSearch : MainEvent
    data class SetQuery(val query: String) : MainEvent
    data object SearchEverywhere : MainEvent
    data class Pick(val key: DeviceKey) : MainEvent
    data object SearchThisArea : MainEvent
    data object ExitSearch : MainEvent
    data class UpdateSettings(val transform: (Settings) -> Settings) : MainEvent
    data class EditStorage(val transform: (StorageUi) -> StorageUi) : MainEvent
    data class Export(val toDownloads: Boolean) : MainEvent
    data object DeleteConfirmed : MainEvent
    data class Delete(val includeUnuploaded: Boolean) : MainEvent
    data class EditOnboarding(val transform: (OnboardingUi) -> OnboardingUi) : MainEvent
    data class RequestPermission(val which: OnboardingPermission) : MainEvent
    data object EnableBluetooth : MainEvent
    data object FinishOnboarding : MainEvent
}

/** Everything the logic wants done; the runner executes each and feeds its result back as a [MainEvent]. */
sealed interface MainEffect {
    /** Re-read the sessions list (the core's). */
    data object LoadSessions : MainEffect
    /** V-6: read the [LaunchFacts] → [MainEvent.LaunchFactsRead]. */
    data object ReadLaunchFacts : MainEffect
    /** R-2 → [MainEvent.RecordingStarted]. */
    data object StartRecording : MainEffect
    data object PauseRecording : MainEffect
    data object ResumeRecording : MainEffect
    data object StopRecording : MainEffect
    /** R-5: fit the main map to the shown view's route. */
    data object FrameRoute : MainEffect
    /** G-14: the sheet stack pops its top sheet. */
    data object SheetsBack : MainEffect
    /** Q-5: D's search state, from the map's current camera. */
    data class SheetsEnterSearch(val query: String, val area: Bbox?, val picked: DeviceKey) : MainEffect
    data class SheetsSearchThisArea(val camera: MapCamera) : MainEffect
    data object SheetsExitSearch : MainEffect
    /** O-1 → [MainEvent.StorageLoaded]. */
    data object LoadStorage : MainEffect
    /** O-1 item 10 → [MainEvent.ExportFinished]. */
    data class Export(val scope: Scope, val format: ExportFormat, val toDownloads: Boolean) : MainEffect
    /** O-1 item 11 → [MainEvent.DeleteFinished]. */
    data class Delete(val scope: Scope, val includeUnuploaded: Boolean) : MainEffect
    data class UpdateSettings(val transform: (Settings) -> Settings) : MainEffect
    /** O-2 Done → [MainEvent.OnboardingSaved]. */
    data class SaveOnboarding(val userId: String, val autoRecord: Boolean) : MainEffect
    data class RequestPermissions(val which: OnboardingPermission) : MainEffect
    data object EnableBluetooth : MainEffect
    data object TurnOnLocation : MainEffect
}

/** One transition: the next state and the effects to run, in order. */
data class Step(val state: MainUiState, val effects: List<MainEffect> = emptyList())

private fun MainUiState.only() = Step(this)
private fun MainUiState.with(vararg effects: MainEffect) = Step(this, effects.toList())

/** The pure transition function of the main screen. */
fun reduce(s: MainUiState, e: MainEvent): Step = when (e) {
    is MainEvent.CoreLinked -> s.copy(core = CoreLink.Linked(e.ready)).only()
    // O-2 on first launch; otherwise V-6 decides on the stored settings, not the defaults
    is MainEvent.SettingsLoaded ->
        if (e.settings.onboardingDone) s.with(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts)
        else {
            val id = e.settings.userId
            val ui = OnboardingUi(1, e.permissions, id.orEmpty(), id?.let(Settings::isValidUserId) ?: false, true)
            s.copy(onboarding = OnboardingFlow(ui, onboardingSteps(true, e.permissions))).with(MainEffect.LoadSessions)
        }
    is MainEvent.PermissionsChanged -> s.copy(onboarding = s.onboarding?.let { it.copy(ui = it.ui.copy(permissions = e.permissions)) }).only()
    // V-6: a live recording first; else record on open; else All sessions if any exist; else the first run
    is MainEvent.LaunchFactsRead -> when (val t = launchTarget(e.facts.liveSessionId, e.facts.recordOnOpen, e.facts.sessionCount)) {
        is LaunchTarget.Session -> reduce(s, MainEvent.SelectScope(Scope.session(t.id)))
        LaunchTarget.StartRecording -> s.with(MainEffect.StartRecording)
        LaunchTarget.AllSessions -> reduce(s, MainEvent.SelectScope(Scope.All))
        LaunchTarget.FirstRun -> reduce(s, MainEvent.SelectScope(null))
    }
    // V-7: a new session view resets search and closes the sidebar; Settings stays open over it
    is MainEvent.SelectScope -> s.copy(
        scope = e.scope, epoch = s.epoch.next(), sidebarOpen = false, searchQuery = null,
        page = when (s.page) {
            Page.Settings -> Page.Settings
            is Page.SearchPage, null -> null
        },
    ).let { if (e.scope != null) it.with(MainEffect.LoadSessions) else it.only() }
    is MainEvent.RecorderChanged -> {
        val idle = e.phase == RecorderState.Phase.Idle
        val next = if (idle) RecorderMirror.Idle else RecorderMirror.Active(s.recorderPanelOpen)
        val base = s.copy(recorder = next)
        // V-3: after Stop the UI stays on the session that just ended, now frozen (re-selected, so re-read)
        val stopped = s.recorder is RecorderMirror.Active && idle
        if (stopped && s.scope != null) reduce(base, MainEvent.SelectScope(s.scope)) else base.only()
    }
    // R-2 / V-2: the new current session
    is MainEvent.RecordingStarted -> reduce(s, MainEvent.SelectScope(Scope.session(e.sessionId)))
    MainEvent.StartRecording -> s.with(MainEffect.StartRecording)
    MainEvent.PauseRecording -> s.with(MainEffect.PauseRecording)
    MainEvent.ResumeRecording -> s.with(MainEffect.ResumeRecording)
    MainEvent.StopRecording -> s.with(MainEffect.StopRecording)
    is MainEvent.SetRecorderPanel -> withPanel(s, e.open).only()
    MainEvent.FitRoute -> withPanel(s, false).copy(page = null).with(MainEffect.FrameRoute)
    // G-2 / T-9: a breakpoint crossing re-lays out and keeps the state
    is MainEvent.SetLayout -> s.copy(layout = e.mode).only()
    MainEvent.ToggleSidebar -> s.copy(sidebarOpen = !s.sidebarOpen).only()
    MainEvent.CloseSidebar -> s.copy(sidebarOpen = false).only()
    is MainEvent.SetLocationPopover -> s.copy(locationPopoverOpen = e.open).only()
    // P-5: Allow asks for location; Turn on opens the location settings
    is MainEvent.LocationFix -> s.copy(locationPopoverOpen = false).with(
        when (e.action) {
            LocationStatus.Action.Allow -> MainEffect.RequestPermissions(OnboardingPermission.Location)
            LocationStatus.Action.TurnOn -> MainEffect.TurnOnLocation
        },
    )
    MainEvent.OpenSettings -> s.copy(page = Page.Settings, sidebarOpen = false).with(MainEffect.LoadStorage)
    // Q-3 / Q-7: Back from the search page returns to search state when it was opened from the bar
    MainEvent.ClosePage -> s.copy(page = null).only()
    is MainEvent.Back -> back(s, e)
    // Q-3: the area is the map viewport when the page opened; the bar's query stays editable (Q-7)
    MainEvent.OpenSearch -> s.copy(page = Page.SearchPage(s.searchQuery.orEmpty(), s.camera?.viewport, false), sidebarOpen = false).only()
    is MainEvent.SetQuery -> s.copy(page = s.searchPage?.copy(query = e.query) ?: s.page).only()
    MainEvent.SearchEverywhere -> s.copy(page = s.searchPage?.copy(area = null, searchedEverywhere = true) ?: s.page).only()
    // Q-5: picking a result leaves the page for D's search state (matches only; the device opens at Half)
    is MainEvent.Pick -> s.searchPage?.let { p ->
        s.copy(page = null, searchQuery = p.query).with(MainEffect.SheetsEnterSearch(p.query, p.area, e.key))
    } ?: s.only()
    // Q-6: D re-runs the query on the settled viewport
    MainEvent.SearchThisArea -> s.camera?.let { s.with(MainEffect.SheetsSearchThisArea(it)) } ?: s.only()
    // Q-7: the bar's X; D restores the Discover view and the map
    MainEvent.ExitSearch -> s.copy(searchQuery = null).with(MainEffect.SheetsExitSearch)
    is MainEvent.CameraIdle -> s.copy(camera = e.camera).only()
    is MainEvent.SheetsAttached -> s.copy(sheets = SheetsLink.Attached(e.sheets)).only()
    // only the sheets that are attached detach (a new scope's sheets may have attached first)
    is MainEvent.SheetsDetached -> (if (s.attachedSheets === e.sheets) s.copy(sheets = SheetsLink.None) else s).only()
    is MainEvent.UpdateSettings -> s.with(MainEffect.UpdateSettings(e.transform))
    // O-1: a re-read keeps the chosen scopes and format; it ends a running export and any open confirmation
    is MainEvent.StorageLoaded -> {
        val shown = s.scope ?: Scope.All
        val prev = s.storage
        val f = e.facts
        s.copy(storage = StorageUi(f.databaseBytes, f.rawFilesBytes, f.mapCacheBytes, f.pendingCount,
            prev?.exportScope ?: shown, prev?.exportFormat, false, null, prev?.deleteScope ?: shown)).only()
    }
    is MainEvent.EditStorage -> s.copy(storage = s.storage?.let(e.transform)).only()
    // O-1 item 10: prepare the scope's export, then hand it to the share sheet or Downloads
    is MainEvent.Export -> s.storage?.let { st ->
        st.exportFormat?.let { format ->
            s.copy(storage = st.copy(exporting = true)).with(MainEffect.Export(st.exportScope, format, e.toDownloads))
        }
    } ?: s.only()
    MainEvent.ExportFinished -> s.copy(storage = s.storage?.copy(exporting = false)).only()
    // O-1 item 11, §2.8: the second step only with pending files; the default keeps them until uploaded
    MainEvent.DeleteConfirmed -> s.storage?.let { st ->
        if (st.pendingCount > 0) s.copy(storage = st.copy(deleteConfirm = DeleteConfirm.LoseUnuploaded)).only()
        else reduce(s, MainEvent.Delete(includeUnuploaded = false))
    } ?: s.only()
    is MainEvent.Delete -> s.storage?.let { st ->
        s.copy(storage = st.copy(deleteConfirm = null)).with(MainEffect.Delete(st.deleteScope, e.includeUnuploaded))
    } ?: s.only()
    // the launch view opens again when the shown data was deleted
    is MainEvent.DeleteFinished ->
        if (!e.deleted) s.only()
        else if (s.scope != null && (e.scope.sessions == SessionScope.All || s.scope == e.scope)) {
            s.with(MainEffect.LoadStorage, MainEffect.LoadSessions, MainEffect.ReadLaunchFacts)
        } else s.with(MainEffect.LoadStorage, MainEffect.LoadSessions)
    is MainEvent.EditOnboarding -> s.copy(onboarding = s.onboarding?.let { it.copy(ui = e.transform(it.ui)) }).only()
    is MainEvent.RequestPermission -> s.with(MainEffect.RequestPermissions(e.which))
    MainEvent.EnableBluetooth -> s.with(MainEffect.EnableBluetooth)
    // O-2 Done: save, then the first run (recording straight away if the switch was left on)
    MainEvent.FinishOnboarding -> s.onboarding?.let { s.with(MainEffect.SaveOnboarding(it.ui.userId, it.ui.autoRecord)) } ?: s.only()
    MainEvent.OnboardingSaved -> s.copy(onboarding = null).with(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts)
}

/** R-4: the panel opens and closes only while a recording is active. */
private fun withPanel(s: MainUiState, open: Boolean): MainUiState = when (s.recorder) {
    RecorderMirror.Idle -> s
    is RecorderMirror.Active -> s.copy(recorder = RecorderMirror.Active(open))
}

/** G-14: closes the topmost layer; Leave changes nothing (the BackHandler is disabled then). */
private fun back(s: MainUiState, e: MainEvent.Back): Step = when (backTarget(backLayers(s, e.pushedSheet, e.sheetSearching))) {
    BackTarget.LocationPopover -> s.copy(locationPopoverOpen = false).only()
    BackTarget.Page -> s.copy(page = null).only()
    BackTarget.PushedSheet -> s.with(MainEffect.SheetsBack)
    BackTarget.Sidebar -> s.copy(sidebarOpen = false).only()
    BackTarget.RecorderPanel -> withPanel(s, false).only()
    BackTarget.SearchState -> reduce(s, MainEvent.ExitSearch)
    BackTarget.Leave -> s.only()
}

/** G-14: the open layers of [s]; the sheet stack reports its own pushed sheets and search state. */
fun backLayers(s: MainUiState, pushedSheet: Boolean, sheetSearching: Boolean): BackLayers =
    BackLayers(s.locationPopoverOpen, s.page != null, pushedSheet, s.sidebarOpen, s.recorderPanelOpen, sheetSearching)

/** R-1 / R-4: the FAB while idle or stopping; else the island, expanded to the panel when it is open. */
fun recorderForm(phase: RecorderState.Phase, panelOpen: Boolean): RecorderUi.Form = when (phase) {
    RecorderState.Phase.Idle, RecorderState.Phase.Stopping -> RecorderUi.Form.Fab
    RecorderState.Phase.Starting, RecorderState.Phase.Recording, RecorderState.Phase.Paused ->
        if (panelOpen) RecorderUi.Form.Panel else RecorderUi.Form.Capsule
}

/** The shown session view: only data read for the current key, so a stale read never shows (V-7). */
fun shownView(s: MainUiState, data: ViewData?): SessionView? = data?.takeIf { s.scope != null && it.key == s.viewKey }?.view

/** The shown view's route (R-5, the mini-map), empty without a view. */
fun shownRoute(s: MainUiState, data: ViewData?): List<GeoPoint> = shownView(s, data)?.route.orEmpty()

/**
 * The search page as chrome draws it: the page's query and area, with the latest results of this session view
 * (the previous query's stay until the new query's arrive, so typing does not blank the list).
 */
fun shownSearch(s: MainUiState, results: SearchResults?): Pair<SearchPageState, List<DeviceRow>>? {
    val page = s.searchPage ?: return null
    val r = results?.takeIf { it.key.view == s.viewKey }
    return SearchPageState(page.query, page.area, r?.order, page.searchedEverywhere) to r?.rows.orEmpty()
}

/** The sessions list with the shown scope selected (the list is the core's; the selection is always the scope). */
fun shownSessions(s: MainUiState, list: SessionsListState?): SessionsListState? = list?.copy(selected = s.scope)
