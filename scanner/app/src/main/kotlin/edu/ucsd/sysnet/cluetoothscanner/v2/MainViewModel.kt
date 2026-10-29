package edu.ucsd.sysnet.cluetoothscanner.v2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.StorageStats
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingPermission
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.map.CluetoothMapState
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * §6.7.1, the runner of the main screen's logic ([reduce] in MainState.kt): one queue of [MainEvent]s with a
 * single consumer that reduces each event and executes the [MainEffect]s it returns; results come back as events.
 * The session view and the search page work in scopes keyed by the state ([ViewKey], [SearchKey]). Each data
 * holder has a single writer: the view job, the search job and the sessions queue. The recording itself lives in
 * the [AppGraph]; this only reads it and asks it to change (§1.2.1).
 */
class MainViewModel(app: Application, val graph: AppGraph) : AndroidViewModel(app) {
    /** Every coroutine of this ViewModel runs here: a failure is logged and moves the core state to Failed. */
    private val vmScope = CoroutineScope(viewModelScope.coroutineContext + graph.core.handler)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val _view = MutableStateFlow<ViewData?>(null)
    /** The session view as last read for its key (the view job is its only writer); see [shownView]. */
    val view: StateFlow<ViewData?> = _view.asStateFlow()

    private val _search = MutableStateFlow<SearchResults?>(null)
    /** The search page's results (the search job is their only writer); see [shownSearch]. */
    val search: StateFlow<SearchResults?> = _search.asStateFlow()

    private val _sessions = MutableStateFlow<SessionsListState?>(null)
    /** The sessions list (the sessions queue is its only writer); see [shownSessions]. */
    val sessions: StateFlow<SessionsListState?> = _sessions.asStateFlow()

    val recorder: StateFlow<RecorderState> get() = graph.recorder.state
    val settings: StateFlow<Settings> get() = graph.settings.settings
    val location: StateFlow<LocationStatus> get() = graph.location.status

    /** §1.2.2: Opening holds the splash; Failed shows the error and a retry. */
    val coreState: StateFlow<CoreState> get() = graph.core.state
    fun retryCore() = graph.core.retry()

    /** What the Activity must launch: a permission request or a system dialog (they need an Activity). */
    sealed interface ActivityRequest {
        class Permissions(val names: Array<String>) : ActivityRequest
        data object EnableBluetooth : ActivityRequest
        data object TurnOnLocation : ActivityRequest
    }

    private val _requests = MutableSharedFlow<ActivityRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<ActivityRequest> = _requests

    private val events = Channel<MainEvent>(Channel.UNLIMITED)
    /** Re-read requests for the sessions list, conflated: one read answers every request made while it runs. */
    private val sessionReads = Channel<Unit>(Channel.CONFLATED)

    /** Every intent and result goes through here; on the main thread the consumer reduces it before this returns. */
    fun dispatch(e: MainEvent) {
        events.trySend(e)
    }

    init {
        vmScope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (e in events) {
                val step = reduce(_ui.value, e)
                _ui.value = step.state
                step.effects.forEach(::execute)
            }
        }
        vmScope.launch {
            val core = graph.core.ready()
            val map = CluetoothMapState(core.queries, graph.location, MapAssets.style(app), vmScope)
            // §2.6: subscribe before querying
            launch(start = CoroutineStart.UNDISPATCHED) {
                core.queries.events.collect { if (it is CoreEvent.SessionsChanged || it is CoreEvent.DataInvalidated) sessionReads.trySend(Unit) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { for (r in sessionReads) _sessions.value = readSessions(core.queries) }
            dispatch(MainEvent.CoreLinked(Ready(core, map)))
            launch { graph.recorder.state.map { it.phase }.distinctUntilChanged().collect { dispatch(MainEvent.RecorderChanged(it)) } }
            launch { graph.permissions.state.collect { dispatch(MainEvent.PermissionsChanged(it)) } }
            launch { viewWork(core.queries) }
            // V-6 decides on the stored settings, not the defaults
            launch(start = CoroutineStart.UNDISPATCHED) {
                val s = graph.settings.loaded()
                dispatch(MainEvent.SettingsLoaded(s, graph.permissions.state.value))
            }
        }
    }

    private suspend fun ready(): Ready = ui.mapNotNull { it.ready }.first()

    // ---- the work keyed by the state ----

    /** V-2 / V-7: the shown scope's view, re-read while it is live; the search page's work lives inside it. */
    private suspend fun viewWork(q: CoreQueries) = ui.map { it.viewKey }.distinctUntilChanged().collectLatest { key ->
        val scope = key.scope
        if (scope == null) { _view.value = null; return@collectLatest }
        coroutineScope {
            launch { searchWork(q, key) }
            var v = q.sessionView(scope)
            _view.value = ViewData(key, v)
            // the live route and device count while this view is live; frozen views are read once
            while (v.live) {
                delay(LIVE_REFRESH_MS)
                v = q.sessionView(scope)
                _view.value = ViewData(key, v)
            }
        }
    }

    /** Q-1 / Q-2 through the core, in the session's fixed order (G-9), which lives as long as [view]'s work. */
    private suspend fun searchWork(q: CoreQueries, view: ViewKey) {
        var order: OrderedKeys? = null
        ui.map { it.searchKey }.distinctUntilChanged().collectLatest { key ->
            val scope = view.scope
            if (key == null || key.view != view || scope == null) { _search.value = null; return@collectLatest }
            val hits = q.search(scope, key.query, key.area)
            if (hits == null) { _search.value = SearchResults(key, null, emptyList()); return@collectLatest }
            val o = order ?: OrderedKeys(q.deviceOrder(scope).keys).also { order = it }
            o.append(hits.keys)
            val keys = o.visible(hits).take(MAX_ROWS).toLongArray()
            _search.value = SearchResults(key, o, q.deviceRows(scope, keys))
        }
    }

    private suspend fun readSessions(q: CoreQueries): SessionsListState {
        val current = q.currentSession()
        val all = runCatching { q.allSessionsSummary() }.getOrElse { AllSessionsSummary(0, 0, null) }
        return SessionsListState.build(current, all, q.sessions(), _ui.value.scope, System.currentTimeMillis())
    }

    // ---- effects ----

    private fun execute(effect: MainEffect) {
        val s = _ui.value
        when (effect) {
            MainEffect.LoadSessions -> sessionReads.trySend(Unit)
            MainEffect.ReadLaunchFacts -> vmScope.launch { dispatch(MainEvent.LaunchFactsRead(launchFacts())) }
            MainEffect.StartRecording -> vmScope.launch { graph.recorder.start().onSuccess { dispatch(MainEvent.RecordingStarted(it)) } }
            MainEffect.PauseRecording -> vmScope.launch { graph.recorder.pause() }
            MainEffect.ResumeRecording -> vmScope.launch { graph.recorder.resume() }
            MainEffect.StopRecording -> vmScope.launch { graph.recorder.stop() }
            MainEffect.FrameRoute -> bboxOf(shownRoute(s, _view.value))?.let { box -> s.ready?.map?.frame(box) }
            MainEffect.SheetsBack -> s.attachedSheets?.handleBack()
            is MainEffect.SheetsEnterSearch -> s.ready?.let { r -> s.attachedSheets?.enterSearch(effect.query, effect.area, r.map.camera(), effect.picked) }
            is MainEffect.SheetsSearchThisArea -> s.attachedSheets?.searchThisArea(effect.camera.viewport, effect.camera.zoom)
            MainEffect.SheetsExitSearch -> s.attachedSheets?.exitSearch()
            MainEffect.LoadStorage -> vmScope.launch { dispatch(MainEvent.StorageLoaded(storageFacts())) }
            is MainEffect.Export -> vmScope.launch {
                val core = ready().core
                val prepared = runCatching { core.storage.prepareExport(effect.scope, effect.format) }.getOrNull()
                if (prepared != null) {
                    runCatching { if (effect.toDownloads) graph.exportShare.saveToDownloads(prepared) else graph.exportShare.share(prepared) }
                }
                dispatch(MainEvent.ExportFinished)
            }
            is MainEffect.Delete -> vmScope.launch {
                val core = ready().core
                val outcome = runCatching { core.storage.deleteLocalData(effect.scope, effect.includeUnuploaded) }.getOrNull()
                dispatch(MainEvent.DeleteFinished(effect.scope, outcome != null && outcome !is DeleteOutcome.Refused))
            }
            is MainEffect.UpdateSettings -> vmScope.launch { graph.settings.update(effect.transform) }
            is MainEffect.SaveOnboarding -> vmScope.launch {
                graph.settings.update { it.copy(userId = effect.userId, recordOnOpen = effect.autoRecord, onboardingDone = true) }
                dispatch(MainEvent.OnboardingSaved)
            }
            is MainEffect.RequestPermissions -> _requests.tryEmit(
                ActivityRequest.Permissions(
                    when (effect.which) {
                        OnboardingPermission.Nearby -> Permissions.nearby()
                        OnboardingPermission.Location -> Permissions.location()
                        OnboardingPermission.Notifications -> Permissions.notifications()
                    },
                ),
            )
            MainEffect.EnableBluetooth -> _requests.tryEmit(ActivityRequest.EnableBluetooth)
            MainEffect.TurnOnLocation -> _requests.tryEmit(ActivityRequest.TurnOnLocation)
        }
    }

    /** V-6's facts: a recording the recorder restored, the stored setting and how many sessions exist. */
    private suspend fun launchFacts(): LaunchFacts {
        val rec = graph.recorder.state.value
        val restored = rec.sessionId?.takeIf { rec.phase != RecorderState.Phase.Idle }
        val sessions = runCatching { ready().core.queries.allSessionsSummary().sessionCount }.getOrDefault(0)
        return LaunchFacts(restored, graph.settings.loaded().recordOnOpen, sessions)
    }

    private suspend fun storageFacts(): StorageFacts {
        val core = ready().core
        // the real core does not implement storage stats yet (CoreException.Db): show zeros rather than fail
        val stats = runCatching { core.storage.storageStats() }.getOrNull() ?: StorageStats(0, 0, 0, 0, 0)
        val cache = runCatching { graph.mapCache.sizeBytes() }.getOrNull()
        return StorageFacts(stats.databaseBytes, stats.rawFilesBytes, cache, stats.pendingCount)
    }

    // ---- what the composition reads from the platform side ----

    private fun pushedSheet(s: MainUiState) = s.attachedSheets?.let { it.stack.entries.size > 1 } == true
    private fun sheetSearching(s: MainUiState) = s.attachedSheets?.searching == true

    /** G-14: the open layers, with the sheet stack's own. */
    fun backLayers(s: MainUiState): BackLayers = backLayers(s, pushedSheet(s), sheetSearching(s))

    /** G-14: System Back closes the topmost layer (the handler is disabled when there is none). */
    fun onBack() {
        val s = _ui.value
        dispatch(MainEvent.Back(pushedSheet(s), sheetSearching(s)))
    }

    /** M-7, P-6, D-4, Q-6: the map's events go to D's sheets; the settled camera also comes here (Q-3, Q-6). */
    val mapEvents: MapEvents = object : MapEvents {
        override fun onUserGestureStart() { _ui.value.attachedSheets?.mapEvents?.onUserGestureStart() }
        override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) {
            dispatch(MainEvent.CameraIdle(MapCamera(viewport, zoom)))
            _ui.value.attachedSheets?.mapEvents?.onCameraIdle(byUser, viewport, zoom)
        }
        override fun onDeviceTap(key: DeviceKey) { _ui.value.attachedSheets?.mapEvents?.onDeviceTap(key) }
        override fun onEmptyTap() { _ui.value.attachedSheets?.mapEvents?.onEmptyTap() ?: _ui.value.ready?.map?.setSelection(null) }
    }

    /** What the sheets, the panel and the chrome cover, for the map's padding (M-10, D-14). */
    fun setCover(insets: CoverInsets) { _ui.value.ready?.map?.setCover(insets) }

    companion object {
        private const val LIVE_REFRESH_MS = 5_000L
        private const val MAX_ROWS = 200

        /** The Activity hands in the process graph, so nothing here looks it up or casts for it. */
        fun factory(app: Application, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app, graph) }
        }
    }
}

/** A session's label for the one-session scope in Settings ("6:05 PM"). */
fun SessionSummary.label(): String = Format.time(startedMs)
