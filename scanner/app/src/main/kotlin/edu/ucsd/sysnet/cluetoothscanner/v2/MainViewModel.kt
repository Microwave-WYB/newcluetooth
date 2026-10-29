package edu.ucsd.sysnet.cluetoothscanner.v2

import android.app.Application
import android.util.Log
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
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
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
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * Runs [block]; anything it throws (except cancellation, which belongs to the caller's scope) goes to [failed] with
 * [task] and yields null. The runner runs every effect, every piece of keyed work and every platform call through
 * this, so a failure always ends as a typed [MainEvent.Failed] and never reaches the main thread.
 */
internal inline fun <T> guarded(task: MainTask, failed: (MainTask, Throwable) -> Unit, block: () -> T): T? =
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        failed(task, t)
        null
    }

/**
 * §6.7.1, the runner of the main screen's logic ([reduce] in MainState.kt): one queue of [MainEvent]s with a
 * single consumer that reduces each event and executes the [MainEffect]s it returns; results come back as events,
 * failures as [MainEvent.Failed] (see [guarded]). The link, the session view and the search page work in scopes
 * keyed by the state ([MainUiState.linkAttempt], [ViewKey], [SearchKey]). Each data holder has a single writer: the
 * view job, the search job and the sessions queue. The recording itself lives in the [AppGraph]; this only reads
 * it and asks it to change (§1.2.1).
 */
class MainViewModel(app: Application, val graph: AppGraph) : AndroidViewModel(app) {
    /**
     * Every coroutine of this ViewModel runs here. [guarded] catches first; what still escapes is the screen's own
     * failure ([MainTask.Unhandled] → CoreLink.Failed), never the core's ([CoreState] means only the open).
     */
    private val vmScope = CoroutineScope(viewModelScope.coroutineContext + CoroutineExceptionHandler { _, e -> fail(MainTask.Unhandled, e) })

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

    /** The one failure path: log, then the logic decides ([MainEvent.Failed]). */
    private fun fail(task: MainTask, error: Throwable) {
        Log.w(TAG, "$task failed", error)
        dispatch(MainEvent.Failed(task, error))
    }

    private inline fun <T> guard(task: MainTask, block: () -> T): T? = guarded(task, ::fail, block)

    init {
        vmScope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (e in events) {
                val step = reduce(_ui.value, e)
                _ui.value = step.state
                step.effects.forEach(::execute)
            }
        }
        vmScope.launch { linkWork() }
        vmScope.launch { linkedWork() }
    }

    private suspend fun ready(): Ready = ui.mapNotNull { it.ready }.first()

    // ---- the work keyed by the state ----

    /**
     * §1.2.2: each attempt awaits the core and builds the map state in the attempt's own scope → CoreLinked, or
     * Failed(Link). The scope lives while the attempt is Waiting or Linked, so a failed screen's map state ends with
     * it and Retry builds a fresh one.
     */
    private suspend fun linkWork() = ui.map { it.linkAttempt }.distinctUntilChanged().collectLatest { attempt ->
        if (attempt == null) return@collectLatest
        guard(MainTask.Link) {
            coroutineScope {
                val core = graph.core.ready()
                val map = CluetoothMapState(core.queries, graph.location, MapAssets.style(getApplication<Application>()), this)
                dispatch(MainEvent.CoreLinked(Ready(core, map), attempt))
                awaitCancellation()
            }
        }
    }

    /** What runs while the screen is linked: the core's events, the sessions queue, the platform state, the view. */
    private suspend fun linkedWork() = ui.map { it.ready }.distinctUntilChanged().collectLatest { ready ->
        val q = ready?.core?.queries ?: return@collectLatest
        coroutineScope {
            // §2.6: subscribe before querying
            launch(start = CoroutineStart.UNDISPATCHED) {
                guard(MainTask.CoreEvents) {
                    q.events.collect { if (it is CoreEvent.SessionsChanged || it is CoreEvent.DataInvalidated) sessionReads.trySend(Unit) }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                for (r in sessionReads) guard(MainTask.Sessions) { _sessions.value = readSessions(q) }
            }
            launch { graph.recorder.state.map { it.phase }.distinctUntilChanged().collect { dispatch(MainEvent.RecorderChanged(it)) } }
            launch { graph.permissions.state.collect { dispatch(MainEvent.PermissionsChanged(it)) } }
            // R-10: the core stopped writing for lack of space
            launch { guard(MainTask.CoreEvents) { graph.storageCleanup.storageFull.collect { dispatch(MainEvent.StorageFull(it)) } } }
            launch { viewWork(q) }
        }
    }

    /** V-2 / V-7: the shown scope's view, re-read while it is live; the search page's work lives inside it. */
    private suspend fun viewWork(q: CoreQueries) = ui.map { it.viewKey }.distinctUntilChanged().collectLatest { key ->
        val scope = key.scope
        if (scope == null) { _view.value = null; return@collectLatest }
        coroutineScope {
            launch { searchWork(q, key) }
            guard(MainTask.View(key)) { followView(q, key, scope) }
        }
    }

    /**
     * V-2: the view read once; while live, the live deltas' route points are appended to it (§2.6: subscribed
     * before the read, deltas up to the read's `seq` ignored). It is never re-read whole; V-3 re-selects after Stop.
     */
    private suspend fun followView(q: CoreQueries, key: ViewKey, scope: Scope) = coroutineScope {
        val deltas = Channel<LiveDelta>(Channel.UNLIMITED)
        val subscription = launch(start = CoroutineStart.UNDISPATCHED) { q.live.collect { deltas.send(it) } }
        var v = q.sessionView(scope)
        _view.value = ViewData(key, v)
        val session = v.sessionIds.singleOrNull()
        if (!v.live || session == null) { subscription.cancel(); return@coroutineScope }
        val route = LiveRoute(v.route)
        for (d in deltas) {
            if (d.sessionId != session || d.seq <= v.seq || d.routeAppended.isEmpty()) continue
            v = v.copy(route = route.append(d.routeAppended), seq = d.seq)
            _view.value = ViewData(key, v)
        }
    }

    /**
     * Q-1 / Q-2 through the core. The core returns the hits in the session's fixed order (G-9), so only the shown
     * ones are kept, in a fresh value per result that is never changed once published.
     */
    private suspend fun searchWork(q: CoreQueries, view: ViewKey) =
        ui.map { it.searchKey }.distinctUntilChanged().collectLatest { key ->
            val scope = view.scope
            if (key == null || key.view != view || scope == null) { _search.value = null; return@collectLatest }
            guard(MainTask.Search(key)) {
                val hits = q.search(scope, key.query, key.area)
                _search.value = if (hits == null) SearchResults(key, null, emptyList()) else {
                    val keys = hits.keys.copyOf(minOf(hits.keys.size, MAX_ROWS))
                    SearchResults(key, OrderedKeys(keys), q.deviceRows(scope, keys))
                }
            }
        }

    private suspend fun readSessions(q: CoreQueries): SessionsListState {
        val current = q.currentSession()
        // the summary is optional for the list: an unreadable one shows as empty
        val all = runCatching { q.allSessionsSummary() }.getOrElse { AllSessionsSummary(0, 0, null) }
        return SessionsListState.build(current, all, q.sessions(), _ui.value.scope, System.currentTimeMillis())
    }

    // ---- effects ----

    /** Every effect runs in its own coroutine, through [guard]: it ends in its result event or in Failed(effect). */
    private fun execute(effect: MainEffect) {
        vmScope.launch { guard(MainTask.Effect(effect)) { run(effect) } }
    }

    private suspend fun run(effect: MainEffect) {
        val s = _ui.value
        when (effect) {
            MainEffect.LoadSettings -> {
                val loaded = graph.settings.loaded() // V-6 decides on the stored settings, not the defaults
                dispatch(MainEvent.SettingsLoaded(loaded, graph.permissions.state.value))
            }
            MainEffect.LoadSessions -> sessionReads.trySend(Unit)
            MainEffect.ReadLaunchFacts -> dispatch(MainEvent.LaunchFactsRead(launchFacts()))
            MainEffect.StartRecording -> graph.recorder.start().onSuccess { dispatch(MainEvent.RecordingStarted(it)) }
            MainEffect.PauseRecording -> graph.recorder.pause()
            MainEffect.ResumeRecording -> graph.recorder.resume()
            MainEffect.StopRecording -> graph.recorder.stop()
            MainEffect.FrameRoute -> bboxOf(shownRoute(s, _view.value))?.let { box -> s.ready?.map?.frame(box) }
            MainEffect.Locate -> s.ready?.map?.locate()
            MainEffect.SheetsBack -> s.attachedSheets?.handleBack()
            is MainEffect.SheetsEnterSearch -> s.ready?.let { r -> s.attachedSheets?.enterSearch(effect.query, effect.area, r.map.camera(), effect.picked) }
            is MainEffect.SheetsSearchThisArea -> s.attachedSheets?.searchThisArea(effect.camera.viewport, effect.camera.zoom)
            MainEffect.SheetsExitSearch -> s.attachedSheets?.exitSearch()
            MainEffect.LoadStorage -> dispatch(MainEvent.StorageLoaded(storageFacts()))
            is MainEffect.Export -> {
                val prepared = ready().core.storage.prepareExport(effect.scope, effect.format)
                if (effect.toDownloads) graph.exportShare.saveToDownloads(prepared) else graph.exportShare.share(prepared)
                dispatch(MainEvent.ExportFinished)
            }
            is MainEffect.Delete -> {
                val outcome = ready().core.storage.deleteLocalData(effect.scope, effect.includeUnuploaded)
                dispatch(MainEvent.DeleteFinished(effect.scope, outcome !is DeleteOutcome.Refused))
            }
            is MainEffect.UpdateSettings -> graph.settings.update(effect.transform)
            is MainEffect.SaveOnboarding -> {
                graph.settings.update { it.copy(userId = effect.userId, recordOnOpen = effect.autoRecord, onboardingDone = true) }
                dispatch(MainEvent.OnboardingSaved)
            }
            is MainEffect.RequestPermissions -> _requests.emit(
                ActivityRequest.Permissions(
                    when (effect.which) {
                        OnboardingPermission.Nearby -> Permissions.nearby()
                        OnboardingPermission.Location -> Permissions.location()
                        OnboardingPermission.Notifications -> Permissions.notifications()
                    },
                ),
            )
            MainEffect.EnableBluetooth -> _requests.emit(ActivityRequest.EnableBluetooth)
            MainEffect.TurnOnLocation -> _requests.emit(ActivityRequest.TurnOnLocation)
            MainEffect.OpenAppSettings -> graph.permissions.openAppSettings()
            MainEffect.UploadNow -> graph.uploads.uploadNow()
            MainEffect.LoadReclaimable -> dispatch(MainEvent.ReclaimableRead(readReclaim()))
            is MainEffect.FreeUpSpace -> {
                val outcome = graph.storageCleanup.run(effect.cleanups)
                outcome.failed.forEach { (c, error) -> Log.w(TAG, "$c: $error") }
                dispatch(MainEvent.SpaceFreed(outcome, readReclaim()))
            }
        }
    }

    /** R-10: the free space and what each cleanup frees, or null when the core cannot say. */
    private suspend fun readReclaim(): Reclaim? = when (val r = graph.storageCleanup.reclaimable()) {
        is CleanupResult.Ok -> r.value
        is CleanupResult.Err -> null.also { Log.w(TAG, "reclaimable: ${r.error}") }
    }

    /** V-6's facts: a recording the recorder restored, the stored setting and how many sessions exist. */
    private suspend fun launchFacts(): LaunchFacts {
        val rec = graph.recorder.state.value
        val restored = rec.sessionId?.takeIf { rec.phase != RecorderState.Phase.Idle }
        // an unreadable count opens as if there were none (V-6 then picks record-on-open or the first run)
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

    // ---- what the composition and the map call: platform calls, each through [guard] ----

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
        override fun onUserGestureStart() { guard(MainTask.Platform) { _ui.value.attachedSheets?.mapEvents?.onUserGestureStart() } }
        override fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) {
            dispatch(MainEvent.CameraIdle(MapCamera(viewport, zoom)))
            guard(MainTask.Platform) { _ui.value.attachedSheets?.mapEvents?.onCameraIdle(byUser, viewport, zoom) }
        }
        override fun onDeviceTap(key: DeviceKey) { guard(MainTask.Platform) { _ui.value.attachedSheets?.mapEvents?.onDeviceTap(key) } }
        override fun onEmptyTap() {
            guard(MainTask.Platform) { _ui.value.attachedSheets?.mapEvents?.onEmptyTap() ?: _ui.value.ready?.map?.setSelection(null) }
        }
    }

    /** What the sheets, the panel and the chrome cover, for the map's padding (M-10, D-14); per frame, so no event. */
    fun setCover(insets: CoverInsets) { guard(MainTask.Platform) { _ui.value.ready?.map?.setCover(insets) } }

    /** The Activity's launches fail here (e.g. no settings activity on the device). */
    fun launchFailed(error: Throwable) = fail(MainTask.Platform, error)

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_ROWS = 200

        /** The Activity hands in the process graph, so nothing here looks it up or casts for it. */
        fun factory(app: Application, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app, graph) }
        }
    }
}

/** A session's label for the one-session scope in Settings ("6:05 PM"). */
fun SessionSummary.label(): String = Format.time(startedMs)
