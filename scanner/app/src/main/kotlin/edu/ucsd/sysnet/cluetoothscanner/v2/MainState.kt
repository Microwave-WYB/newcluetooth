package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.Cleanup
import edu.ucsd.sysnet.cluetoothscanner.chrome.CleanupFailure
import edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm
import edu.ucsd.sysnet.cluetoothscanner.chrome.Reclaimable
import edu.ucsd.sysnet.cluetoothscanner.chrome.StorageFullUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingPermission
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingStep
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchPageState
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.StorageUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.onboardingSteps
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.map.CluetoothMapState
import edu.ucsd.sysnet.cluetoothscanner.sheets.DiscoverView
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

/**
 * The screen's link to the core (§1.2.2): Waiting(n) → Linked(ready, n) → Failed(error, n); Retry → Waiting(n + 1).
 * It fails when linking fails or when anything the screen runs fails unhandled; [CoreState] only means the open.
 */
sealed interface CoreLink {
    /** The runner awaits the core and builds the map state for this attempt. */
    data class Waiting(val attempt: Int) : CoreLink
    /** The map state lives in the attempt's scope until the link fails. */
    data class Linked(val ready: Ready, val attempt: Int) : CoreLink
    data class Failed(val error: Throwable, val attempt: Int) : CoreLink
}

/** What the runner was doing when something threw; [MainEvent.Failed] names it so the logic can recover. */
sealed interface MainTask {
    data class Effect(val effect: MainEffect) : MainTask
    /** Linking to the core: awaiting it and building the map state. */
    data object Link : MainTask
    data class View(val key: ViewKey) : MainTask
    data class Search(val key: SearchKey) : MainTask
    /** The core's event subscription (§2.6). */
    data object CoreEvents : MainTask
    /** R-4a: one read of the recorder panel's discovery rate, its snapshot and its deltas. */
    data class DiscoveryRate(val key: DiscoveryKey) : MainTask
    /** A call into the map or the sheets from a map callback or the cover. */
    data object Platform : MainTask
    /** An Activity launch the user asked for (a permission request, enable Bluetooth, the location settings). */
    data object Launch : MainTask
    /** Something the screen runs threw past every guard (its scope's handler caught it). */
    data object Unhandled : MainTask
}

/** Lane D's sheets for the shown scope; they are a Compose holder, so the composition attaches and detaches them. */
sealed interface SheetsLink {
    data object None : SheetsLink
    data class Attached(val sheets: SheetsState) : SheetsLink
}

/** The recorder as the screen mirrors it (V-3, R-4): the panel exists only while a recording is active. */
sealed interface RecorderMirror {
    data object Idle : RecorderMirror
    /**
     * [pausedForStorage]: R-10's pause (`RecorderState.pause` is StorageFull), which the storage-full dialog follows.
     * [pausedForFailure]: #79's pause (StorageFailed), which its notice follows.
     */
    data class Active(val panelOpen: Boolean, val pausedForStorage: Boolean = false, val pausedForFailure: Boolean = false) : RecorderMirror
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

/** The sessions list as one read returned it, tagged with the number of the request it answered. */
data class SessionsData(val request: Int, val list: SessionsListState)

/** R-4a: what keys one read of the discovery rate: the recording's session and the read's number. */
data class DiscoveryKey(val sessionId: String, val request: Int)

/** What V-6 decides on: the recorder's restored session, the stored setting and the core's session count. */
data class LaunchFacts(val liveSessionId: String?, val recordOnOpen: Boolean, val sessionCount: Int)

/** What the Settings storage section shows (O-1): the core's stats and the map cache, as read. */
data class StorageFacts(val databaseBytes: Long, val rawFilesBytes: Long, val mapCacheBytes: Long?, val pendingCount: Int)

/** R-10: what the dialog knows of what each cleanup frees. */
sealed interface ReclaimRead {
    data object Reading : ReclaimRead
    data class Known(val reclaim: Reclaim) : ReclaimRead
    /** The read failed: no sizes show, and its reason does (the free space shown may be the last one read). */
    data class Failed(val error: CleanupError) : ReclaimRead
}

/** R-10: choosing the cleanups (after one ran, with what it freed), or one running. */
sealed interface CleanupStep {
    /** [last]: what the last run did (null before any). */
    data class Choosing(val last: CleanupOutcome?) : CleanupStep
    data object Freeing : CleanupStep
}

/** R-10: the storage-full dialog; both switches start off. */
data class StorageFullState(val free: Bytes, val reclaim: ReclaimRead, val chosen: Set<Cleanup>, val step: CleanupStep) {
    /** Free space is back above the floor, and nothing is running: "Resume" continues the recording. */
    val canResume: Boolean get() = step is CleanupStep.Choosing && free > STORAGE_FLOOR
}

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
    val core: CoreLink = CoreLink.Waiting(0),
    /** The launch flow (O-2 or V-6) runs once per screen, not again when a failed link is retried. */
    val launched: Boolean = false,
    val sheets: SheetsLink = SheetsLink.None,
    /** R-10: the storage-full dialog, null while closed. */
    val storageFull: StorageFullState? = null,
    /** A failure the user must see, shown as a banner until dismissed or retried; null when there is none. */
    val notice: Notice? = null,
    /** D-1b: the remembered Discover view (UI state, not a setting); every new session view opens on it. */
    val discoverView: RememberedView = RememberedView.Unread,
    /** The session view as last read, for the key it was read for; [shownView] shows it only for the current key. */
    val viewData: ViewData? = null,
    /** The search page's results of the current query as last read; see [shownSearch]. */
    val searchResults: SearchResults? = null,
    /** How many sessions-list reads were asked for; each read answers with its number ([MainEvent.SessionsRead]). */
    val sessionsAsked: Int = 0,
    /** The newest sessions list read so far; see [shownSessions]. */
    val sessionsData: SessionsData? = null,
    /** R-4a: how many discovery-rate reads were asked for, across sessions, so no two reads share a key. */
    val discoveryAsked: Int = 0,
    /** R-4a: the read of the recording's rate the runner works on; null while not recording. */
    val discoveryKey: DiscoveryKey? = null,
    /** R-4a: the recording's rate as shown: empty for a new session until its first read, kept while it is re-read. */
    val discovery: DiscoveryBins = DiscoveryBins.Empty,
) {
    val viewKey: ViewKey get() = ViewKey(scope, epoch)
    val searchPage: Page.SearchPage? get() = page as? Page.SearchPage
    /** The search page's work runs only within a session view (Q-1 searches a scope). */
    val searchKey: SearchKey? get() = searchPage?.takeIf { scope != null }?.let { SearchKey(viewKey, it.query, it.area) }
    val recorderPanelOpen: Boolean get() = (recorder as? RecorderMirror.Active)?.panelOpen == true
    val ready: Ready? get() = (core as? CoreLink.Linked)?.ready
    /** The link attempt the runner works on: its scope lives from Waiting through Linked, and ends when it fails. */
    val linkAttempt: Int? get() = when (core) {
        is CoreLink.Waiting -> core.attempt
        is CoreLink.Linked -> core.attempt
        is CoreLink.Failed -> null
    }
    val attachedSheets: SheetsState? get() = (sheets as? SheetsLink.Attached)?.sheets
}

/**
 * A failure the user must see: the error's own words, and Retry when [retry] can run it again ([retryLabel] names
 * that action when it isn't "Try again"). [untilResumed]: it is about a paused recording (#79's failure pause, or a
 * Resume that was refused); Retry leaves it up, and it closes only when the recording is no longer paused.
 */
data class Notice(val message: String, val retry: MainEffect? = null, val retryLabel: String? = null, val untilResumed: Boolean = false)

/** #79: the recording paused because storing kept failing; Resume makes the core try again. */
fun storageFailedNotice(message: String) =
    Notice(Strings.storageFailedPaused(message), retry = MainEffect.ResumeRecording, retryLabel = Strings.Resume, untilResumed = true)

/** The words a failure shows: a platform call's typed error, else the throwable's message. */
fun Throwable.userMessage(): String = (this as? PlatformFailure)?.error?.message ?: message ?: this::class.simpleName.orEmpty()

/** Everything that changes [MainUiState]: the user's intents, and the results the runner feeds back. */
sealed interface MainEvent {
    // ---- results and platform state ----
    data class CoreLinked(val ready: Ready, val attempt: Int) : MainEvent
    /** Anything the runner ran threw: every effect and every piece of keyed work ends in a result or this. */
    data class Failed(val task: MainTask, val error: Throwable) : MainEvent
    data object RetryLink : MainEvent
    data class SettingsLoaded(val settings: Settings, val permissions: PermissionState) : MainEvent
    data class PermissionsChanged(val permissions: PermissionState) : MainEvent
    data class LaunchFactsRead(val facts: LaunchFacts) : MainEvent
    /** The recorder's phase, while paused why (`RecorderState.pause`), and its session (`RecorderState.sessionId`). */
    data class RecorderChanged(
        val phase: RecorderState.Phase,
        val pause: RecorderState.PauseReason? = null,
        val sessionId: String? = null,
    ) : MainEvent
    data class RecordingStarted(val sessionId: String) : MainEvent
    data class StorageLoaded(val facts: StorageFacts) : MainEvent
    data object ExportFinished : MainEvent
    data class DeleteFinished(val scope: Scope, val deleted: Boolean) : MainEvent
    data object OnboardingSaved : MainEvent
    data class CameraIdle(val camera: MapCamera) : MainEvent
    data class SheetsAttached(val sheets: SheetsState) : MainEvent
    data class SheetsDetached(val sheets: SheetsState) : MainEvent
    /** D-1b: the user switched the Discover view (the sheets' [edu.ucsd.sysnet.cluetoothscanner.sheets.SheetsEvent.ViewChosen]). */
    data class DiscoverViewChosen(val view: DiscoverView) : MainEvent
    /** D-1b: the remembered view, read from the UI-state store at launch. */
    data class UiStateRead(val discoverView: DiscoverView) : MainEvent
    /** V-2: the session view read for [data]'s key; dropped unless that is still the view's key. */
    data class ViewRead(val data: ViewData) : MainEvent
    /** Q-3: the search results read for [results]' key; dropped unless that is still the page's query. */
    data class SearchRead(val results: SearchResults) : MainEvent
    /** The sessions list read for request [request]; dropped if a newer request's list is already shown. */
    data class SessionsRead(val request: Int, val list: SessionsListState) : MainEvent
    /** The core's `SessionsChanged`: the sessions list is read again. */
    data object CoreSessionsChanged : MainEvent
    /** The core's `DataInvalidated` (e.g. the display filter changed which devices count): what it covers is read again. */
    data object CoreDataInvalidated : MainEvent
    /** R-4a: the rate read for [key] (its snapshot, then once per delta); dropped unless [key] is still the read wanted. */
    data class DiscoveryRead(val key: DiscoveryKey, val bins: DiscoveryBins) : MainEvent
    /** R-4a: the notice's Retry: the recording's rate is read again. */
    data object RereadDiscovery : MainEvent

    // ---- intents ----
    data class SelectScope(val scope: Scope?) : MainEvent
    data object StartRecording : MainEvent
    data object PauseRecording : MainEvent
    data object ResumeRecording : MainEvent
    data object StopRecording : MainEvent
    data class SetRecorderPanel(val open: Boolean) : MainEvent
    data class SetLayout(val mode: LayoutMode) : MainEvent
    data object ToggleSidebar : MainEvent
    /** P-4: the locate button. */
    data object Locate : MainEvent
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
    data object OpenAppSettings : MainEvent
    data object UploadNow : MainEvent
    data object DismissNotice : MainEvent
    /** The notice's Retry: it closes and runs what failed again. */
    data object RetryNotice : MainEvent

    // ---- R-10 storage full ----
    /** `CoreEvent.StorageFull { freeBytes }`: the core stopped writing (and the recorder paused, B). */
    data class StorageFull(val free: Bytes) : MainEvent
    /** `CoreEvent.StorageFailed { message }` (#79): storing kept failing for 60 s; the core stopped writing and paused. */
    data class StorageFailed(val message: String) : MainEvent
    /** What each cleanup frees, or null when the core could not say. */
    /** The free space and what each cleanup frees, or why they couldn't be read. */
    data class ReclaimableRead(val result: CleanupResult<Reclaim>) : MainEvent
    data class ChooseCleanup(val cleanup: Cleanup, val on: Boolean) : MainEvent
    data object FreeUpSpace : MainEvent
    /**
     * The cleanups ran: what each freed or why it failed, and the free space and reclaimable sizes read after them
     * ([after] null when nothing could be read, as when the run threw).
     */
    data class SpaceFreed(val outcome: CleanupOutcome, val after: CleanupResult<Reclaim>?) : MainEvent
    data object StorageFullNotNow : MainEvent
    data object StorageFullResume : MainEvent
    /** Settings › Storage (O-1 item 11): a cleanup's button, its confirmation, its cancel. */
    data class CleanupRequested(val cleanup: Cleanup) : MainEvent
    data object CleanupConfirmed : MainEvent
    data object CleanupCancelled : MainEvent
    data object FinishOnboarding : MainEvent
}

/** Everything the logic wants done; the runner executes each and feeds its result back as a [MainEvent]. */
sealed interface MainEffect {
    /** V-6 / O-2: read the stored settings and the grants → [MainEvent.SettingsLoaded]. */
    data object LoadSettings : MainEffect
    /** Read the sessions list (the core's) → [MainEvent.SessionsRead] with this [request]'s number. */
    data class LoadSessions(val request: Int) : MainEffect
    /** V-6: read the [LaunchFacts] → [MainEvent.LaunchFactsRead]. */
    data object ReadLaunchFacts : MainEffect
    /** R-2 → [MainEvent.RecordingStarted]. */
    data object StartRecording : MainEffect
    data object PauseRecording : MainEffect
    data object ResumeRecording : MainEffect
    data object StopRecording : MainEffect
    /** P-4: the main map follows the user's location. */
    data object Locate : MainEffect
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
    data object OpenAppSettings : MainEffect
    /** O-1: the uploader runs now (B). */
    data object UploadNow : MainEffect
    /** R-10 → [MainEvent.ReclaimableRead]. */
    data object LoadReclaimable : MainEffect
    /** R-10: run the chosen cleanups, then read the free space → [MainEvent.SpaceFreed]. */
    data class FreeUpSpace(val cleanups: Set<Cleanup>) : MainEffect
    /** D-1b: read the UI-state store → [MainEvent.UiStateRead]; a failure keeps Categories (logged). */
    data object LoadUiState : MainEffect
    /** D-1b: remember the Discover view; a failure is logged only (UI state, no notice). */
    data class SaveDiscoverView(val view: DiscoverView) : MainEffect
    /** R-4a: read the recording's discovery rate again (its notice's Retry). */
    data object ReloadDiscoveryRate : MainEffect
}

/**
 * D-1b: the remembered Discover view: [Unread] (Categories, the first-launch default) until the store answers or the
 * user chooses, then [Known]. A late store read never overrides a choice.
 */
sealed interface RememberedView {
    val view: DiscoverView

    data object Unread : RememberedView {
        override val view: DiscoverView get() = DiscoverView.Categories
    }

    data class Known(override val view: DiscoverView) : RememberedView
}

/** One transition: the next state and the effects to run, in order. */
data class Step(val state: MainUiState, val effects: List<MainEffect> = emptyList())

private fun MainUiState.only() = Step(this)
private fun MainUiState.with(vararg effects: MainEffect) = Step(this, effects.toList())

/** [next] after this: its state, and both steps' effects in order. */
private operator fun Step.plus(next: Step) = Step(next.state, effects + next.effects)

/** The pure transition function of the main screen. */
fun reduce(s: MainUiState, e: MainEvent): Step = when (e) {
    // §1.2.2 then V-6: the screen is linked (a stale attempt's link is dropped); the launch flow starts once
    is MainEvent.CoreLinked -> when (val c = s.core) {
        is CoreLink.Waiting ->
            if (c.attempt != e.attempt) s.only()
            else {
                val linked = s.copy(core = CoreLink.Linked(e.ready, e.attempt), launched = true)
                if (s.launched) linked.only() else linked.with(MainEffect.LoadSettings, MainEffect.LoadUiState)
            }
        is CoreLink.Linked, is CoreLink.Failed -> s.only()
    }
    is MainEvent.Failed -> failed(s, e.task, e.error)
    MainEvent.RetryLink -> when (val c = s.core) {
        is CoreLink.Failed -> s.copy(core = CoreLink.Waiting(c.attempt + 1)).only()
        is CoreLink.Waiting, is CoreLink.Linked -> s.only()
    }
    // O-2 on first launch; otherwise V-6 decides on the stored settings, not the defaults
    is MainEvent.SettingsLoaded ->
        if (e.settings.onboardingDone) s.loadingSessions { listOf(it, MainEffect.ReadLaunchFacts) }
        else {
            val id = e.settings.userId
            val ui = OnboardingUi(1, e.permissions, id.orEmpty(), id?.let(Settings::isValidUserId) ?: false, true)
            s.copy(onboarding = OnboardingFlow(ui, onboardingSteps(true, e.permissions))).loadingSessions { listOf(it) }
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
    ).let { if (e.scope != null) it.loadingSessions { load -> listOf(load) } else it.only() }
    is MainEvent.RecorderChanged -> {
        val idle = e.phase == RecorderState.Phase.Idle
        val storagePause = e.pause as? RecorderState.PauseReason.StorageFull
        val wasStoragePause = (s.recorder as? RecorderMirror.Active)?.pausedForStorage == true
        val failurePause = e.pause as? RecorderState.PauseReason.StorageFailed
        val wasFailurePause = (s.recorder as? RecorderMirror.Active)?.pausedForFailure == true
        val next = if (idle) RecorderMirror.Idle else RecorderMirror.Active(s.recorderPanelOpen, storagePause != null, failurePause != null)
        // #79: the failure pause shows its notice; a paused recording's notice goes only when the recording is no
        // longer paused (Resume accepted, here or from the notification, or Stop); another notice that came in stays
        val mirrored = s.discoveryFor(e.sessionId.takeIf { !idle }).copy(
            recorder = next,
            notice = when {
                failurePause != null && !wasFailurePause -> storageFailedNotice(failurePause.message)
                e.pause == null && s.notice?.untilResumed == true -> null
                else -> s.notice
            },
        )
        val base = when {
            // R-10: the storage pause ended (a Resume the core accepted, from the app or the notification, or a Stop)
            wasStoragePause && storagePause == null -> Step(mirrored.copy(storageFull = null))
            // R-10: the recorder paused for storage; the dialog opens on its free space unless the core's event did
            storagePause != null && !wasStoragePause && s.storageFull == null -> Step(
                mirrored.copy(storageFull = StorageFullState(Bytes(storagePause.freeBytes), ReclaimRead.Reading, emptySet(), CleanupStep.Choosing(null))),
                listOf(MainEffect.LoadReclaimable),
            )
            else -> mirrored.only()
        }
        // V-3: after Stop the UI stays on the session that just ended, now frozen (re-selected, so re-read)
        val stopped = s.recorder is RecorderMirror.Active && idle
        if (stopped && s.scope != null) base + reduce(base.state, MainEvent.SelectScope(s.scope)) else base
    }
    // R-2 / V-2: the new current session
    is MainEvent.RecordingStarted -> reduce(s, MainEvent.SelectScope(Scope.session(e.sessionId)))
    MainEvent.StartRecording -> s.with(MainEffect.StartRecording)
    MainEvent.PauseRecording -> s.with(MainEffect.PauseRecording)
    MainEvent.ResumeRecording -> s.with(MainEffect.ResumeRecording)
    MainEvent.StopRecording -> s.with(MainEffect.StopRecording)
    is MainEvent.SetRecorderPanel -> withPanel(s, e.open).only()
    // G-2 / T-9: a breakpoint crossing re-lays out and keeps the state
    is MainEvent.SetLayout -> s.copy(layout = e.mode).only()
    MainEvent.ToggleSidebar -> s.copy(sidebarOpen = !s.sidebarOpen).only()
    MainEvent.Locate -> s.with(MainEffect.Locate)
    MainEvent.CloseSidebar -> s.copy(sidebarOpen = false).only()
    is MainEvent.SetLocationPopover -> s.copy(locationPopoverOpen = e.open).only()
    // P-5: Allow asks for location; Turn on opens the location settings
    is MainEvent.LocationFix -> s.copy(locationPopoverOpen = false).with(
        when (e.action) {
            LocationStatus.Action.Allow -> MainEffect.RequestPermissions(OnboardingPermission.Location)
            LocationStatus.Action.TurnOn -> MainEffect.TurnOnLocation
        },
    )
    MainEvent.OpenSettings -> s.copy(page = Page.Settings, sidebarOpen = false).with(MainEffect.LoadStorage, MainEffect.LoadReclaimable)
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
    // the reads: each result counts only for the request it answers, so a stale one (an earlier view, an old query,
    // an older sessions read, a previous read of the rate) is dropped whatever order they arrive in
    is MainEvent.ViewRead -> (if (e.data.key == s.viewKey) s.copy(viewData = e.data) else s).only()
    is MainEvent.SearchRead -> (if (e.results.key == s.searchKey) s.copy(searchResults = e.results) else s).only()
    is MainEvent.SessionsRead ->
        (if (e.request > (s.sessionsData?.request ?: 0)) s.copy(sessionsData = SessionsData(e.request, e.list)) else s).only()
    MainEvent.CoreSessionsChanged -> s.loadingSessions { listOf(it) }
    MainEvent.CoreDataInvalidated -> s.rereadDiscovery().loadingSessions { listOf(it) }
    is MainEvent.DiscoveryRead -> (if (e.key == s.discoveryKey) s.copy(discovery = e.bins) else s).only()
    MainEvent.RereadDiscovery -> s.rereadDiscovery().only()
    // D-1b: the user's switch is remembered (search's forced list never comes here: the sheets don't emit it)
    is MainEvent.DiscoverViewChosen ->
        if (s.discoverView == RememberedView.Known(e.view)) s.only()
        else s.copy(discoverView = RememberedView.Known(e.view)).with(MainEffect.SaveDiscoverView(e.view))
    // the store read applies only while nothing is known: a choice made before it came back (a slow disk) wins
    is MainEvent.UiStateRead -> when (s.discoverView) {
        RememberedView.Unread -> s.copy(discoverView = RememberedView.Known(e.discoverView)).only()
        is RememberedView.Known -> s.only()
    }
    // only the sheets that are attached detach (a new scope's sheets may have attached first)
    is MainEvent.SheetsDetached -> (if (s.attachedSheets === e.sheets) s.copy(sheets = SheetsLink.None) else s).only()
    is MainEvent.UpdateSettings -> s.with(MainEffect.UpdateSettings(e.transform))
    // O-1: a re-read keeps the chosen scopes and format; it ends a running export and any open confirmation
    is MainEvent.StorageLoaded -> {
        val shown = s.scope ?: Scope.All
        val prev = s.storage
        val f = e.facts
        s.copy(storage = StorageUi(f.databaseBytes, f.rawFilesBytes, f.mapCacheBytes, f.pendingCount,
            prev?.exportScope ?: shown, prev?.exportFormat, false, null, prev?.deleteScope ?: shown,
            reclaimable = prev?.reclaimable, cleaning = prev?.cleaning ?: false, cleanupFreed = prev?.cleanupFreed,
            cleanupFailure = prev?.cleanupFailure, reclaimFailure = prev?.reclaimFailure)).only()
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
            s.loadingSessions { listOf(MainEffect.LoadStorage, it, MainEffect.ReadLaunchFacts) }
        } else s.loadingSessions { listOf(MainEffect.LoadStorage, it) }
    is MainEvent.EditOnboarding -> s.copy(onboarding = s.onboarding?.let { it.copy(ui = e.transform(it.ui)) }).only()
    is MainEvent.RequestPermission -> s.with(MainEffect.RequestPermissions(e.which))
    MainEvent.EnableBluetooth -> s.with(MainEffect.EnableBluetooth)
    MainEvent.OpenAppSettings -> s.with(MainEffect.OpenAppSettings)
    MainEvent.UploadNow -> s.with(MainEffect.UploadNow)
    // #79: shown at once; the recorder's own pause (lane B) follows and keeps it until the pause ends
    is MainEvent.StorageFailed -> s.copy(notice = storageFailedNotice(e.message)).only()
    is MainEvent.StorageFull -> s.storageFull?.let { s.copy(storageFull = it.copy(free = e.free)).only() }
        ?: s.copy(storageFull = StorageFullState(e.free, ReclaimRead.Reading, emptySet(), CleanupStep.Choosing(null))).with(MainEffect.LoadReclaimable)
    is MainEvent.ReclaimableRead -> s.copy(
        storageFull = s.storageFull?.let { d -> withRead(d, e.result) },
        storage = s.storage?.let { st -> withRead(st, e.result) },
    ).only()
    is MainEvent.ChooseCleanup -> s.storageFull?.takeIf { it.step is CleanupStep.Choosing }?.let { d ->
        s.copy(storageFull = d.copy(chosen = if (e.on) d.chosen + e.cleanup else d.chosen - e.cleanup)).only()
    } ?: s.only()
    // "Free up space" is enabled when at least one switch is on
    MainEvent.FreeUpSpace -> s.storageFull?.takeIf { it.step is CleanupStep.Choosing && it.chosen.isNotEmpty() }?.let { d ->
        s.copy(storageFull = d.copy(step = CleanupStep.Freeing)).with(MainEffect.FreeUpSpace(d.chosen))
    } ?: s.only()
    is MainEvent.SpaceFreed -> spaceFreed(s, e)
    MainEvent.StorageFullNotNow -> s.copy(storageFull = null).only()
    // Resume only once free space is back above the floor; otherwise the dialog stays and the recording stays paused
    MainEvent.StorageFullResume -> s.storageFull?.takeIf { it.canResume }?.let { s.copy(storageFull = null).with(MainEffect.ResumeRecording) } ?: s.only()
    is MainEvent.CleanupRequested -> s.storage?.takeIf { !it.cleaning }?.let { s.copy(storage = it.copy(cleanupConfirm = e.cleanup)).only() } ?: s.only()
    MainEvent.CleanupCancelled -> s.copy(storage = s.storage?.copy(cleanupConfirm = null)).only()
    MainEvent.CleanupConfirmed -> s.storage?.let { st ->
        st.cleanupConfirm?.let { c ->
            s.copy(storage = st.copy(cleanupConfirm = null, cleaning = true, cleanupFreed = null, cleanupFailure = null)).with(MainEffect.FreeUpSpace(setOf(c)))
        }
    } ?: s.only()
    // O-2 Done: save, then the first run (recording straight away if the switch was left on)
    MainEvent.FinishOnboarding -> s.onboarding?.let { s.with(MainEffect.SaveOnboarding(it.ui.userId, it.ui.autoRecord)) } ?: s.only()
    MainEvent.OnboardingSaved -> s.copy(onboarding = null).loadingSessions { listOf(it, MainEffect.ReadLaunchFacts) }
    MainEvent.DismissNotice -> s.copy(notice = null).only()
    MainEvent.RetryNotice -> {
        val notice = s.notice
        val retry = notice?.retry
        when {
            retry == null -> s.copy(notice = null).only()
            // a paused recording's Resume: the notice stays until the pause really ends, or says why the Resume failed
            notice?.untilResumed == true -> s.with(retry)
            // the same export again, with its spinner, showing the scope and format it exports
            retry is MainEffect.Export -> s.copy(
                notice = null,
                storage = s.storage?.copy(exporting = true, exportScope = retry.scope, exportFormat = retry.format),
            ).with(retry)
            else -> s.copy(notice = null).with(retry)
        }
    }
}

/**
 * A failure leaves the state as consistent as a success would: what was running stops, and what the screen waits
 * for falls back (the launch view to the first run, the storage section to zeros). The runner has logged it.
 */
private fun failed(s: MainUiState, task: MainTask, error: Throwable): Step = when (task) {
    // the screen fails as a whole: its link scope ends (the map state with it) and Retry links again
    MainTask.Link, MainTask.Unhandled -> when (val c = s.core) {
        is CoreLink.Waiting -> s.copy(core = CoreLink.Failed(error, c.attempt)).only()
        is CoreLink.Linked -> s.copy(core = CoreLink.Failed(error, c.attempt)).only()
        is CoreLink.Failed -> s.only()
    }
    is MainTask.Effect -> when (task.effect) {
        // without stored settings the launch view still opens (its own facts fall back to the first run)
        MainEffect.LoadSettings -> s.loadingSessions { listOf(it, MainEffect.ReadLaunchFacts) }
        MainEffect.ReadLaunchFacts -> reduce(s, MainEvent.LaunchFactsRead(LaunchFacts(null, recordOnOpen = false, sessionCount = 0)))
        // O-1 item 8: unknown use is not "0 B" silently; the page opens and the notice says why, with Retry
        MainEffect.LoadStorage -> reduce(s, MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0))).let { step ->
            step.copy(state = step.state.copy(notice = Notice("$STORAGE_FAILED: ${error.userMessage()}", retry = task.effect)))
        }
        MainEffect.LoadReclaimable -> reduce(s, MainEvent.ReclaimableRead(CleanupResult.Err(CleanupError.Failed(error.message ?: error::class.simpleName.orEmpty()))))
        // a throw fails every cleanup of the run, each with the error's message, as a typed Err would
        is MainEffect.FreeUpSpace -> reduce(s, MainEvent.SpaceFreed(
            CleanupOutcome(emptyMap(), task.effect.cleanups.associateWith { CleanupError.Failed(error.message ?: error::class.simpleName.orEmpty()) }), null,
        ))
        // the export says it failed: it stops, and nothing looks done
        is MainEffect.Export ->
            s.copy(storage = s.storage?.copy(exporting = false), notice = Notice("$EXPORT_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        // the onboarding stays open; the notice retries the save
        is MainEffect.SaveOnboarding -> s.copy(notice = Notice("$SAVE_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        MainEffect.OpenAppSettings, MainEffect.UploadNow -> s.copy(notice = Notice(error.userMessage())).only()
        // the recorder refused: say why, and offer the same action again
        MainEffect.StartRecording -> s.copy(notice = Notice("$START_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        MainEffect.PauseRecording -> s.copy(notice = Notice("$PAUSE_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        MainEffect.StopRecording -> s.copy(notice = Notice("$STOP_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        // the recording stays paused: the notice says why and stays, with Resume, until the pause really ends
        MainEffect.ResumeRecording -> s.copy(
            notice = Notice("$RESUME_FAILED: ${error.userMessage()}", retry = task.effect, retryLabel = Strings.Resume, untilResumed = true),
        ).only()
        // the user asked for these too: say why they didn't happen, and offer them again
        is MainEffect.Delete -> s.copy(notice = Notice("$DELETE_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        is MainEffect.UpdateSettings -> s.copy(notice = Notice("$SAVE_FAILED: ${error.userMessage()}", retry = task.effect)).only()
        is MainEffect.LoadSessions, MainEffect.ReloadDiscoveryRate, MainEffect.Locate, MainEffect.SheetsBack, is MainEffect.SheetsEnterSearch,
        is MainEffect.SheetsSearchThisArea, MainEffect.SheetsExitSearch,
        is MainEffect.RequestPermissions, MainEffect.EnableBluetooth, MainEffect.TurnOnLocation -> s.only()
        // D-1b: UI state, so no notice: a failed read keeps Categories (the runner logged it); a failed write never
        // comes here (the runner only logs it, a deliberate exception), and the choice holds for this run
        MainEffect.LoadUiState, is MainEffect.SaveDiscoverView -> s.only()
    }
    // the user asked for it and nothing happened: say why
    MainTask.Launch -> s.copy(notice = Notice(error.userMessage())).only()
    // R-4a: the panel's chart has no data; say why, and offer to read it again (a superseded read's failure is moot)
    is MainTask.DiscoveryRate ->
        if (task.key != s.discoveryKey) s.only()
        else s.copy(notice = Notice("$RATE_FAILED: ${error.userMessage()}", retry = MainEffect.ReloadDiscoveryRate)).only()
    // the keyed work keeps its last data, which the selectors show only for its own key
    is MainTask.View, is MainTask.Search, MainTask.CoreEvents, MainTask.Platform -> s.only()
}

private const val EXPORT_FAILED = "Export failed"
private const val STORAGE_FAILED = "Could not read the storage use"
private const val SAVE_FAILED = "Could not save the settings"
private const val START_FAILED = "Could not start recording"
private const val PAUSE_FAILED = "Could not pause the recording"
private const val RESUME_FAILED = "Could not resume the recording"
private const val STOP_FAILED = "Could not stop the recording"
private const val DELETE_FAILED = "Could not delete local data"
private const val RATE_FAILED = "Could not read the discovery rate"

/** R-4: the panel opens and closes only while a recording is active. */
private fun withPanel(s: MainUiState, open: Boolean): MainUiState = when (s.recorder) {
    RecorderMirror.Idle -> s
    is RecorderMirror.Active -> s.copy(recorder = s.recorder.copy(panelOpen = open))
}

/**
 * R-10: after the cleanups the dialog shows what each did: what the ones that succeeded freed, and why each one that
 * failed did not (its switch stays on, so "Free up space" retries it). Resume shows if free space is back above the
 * floor. Settings shows the same, and the storage figures are re-read. Clearing the database may have removed the
 * shown sessions, so the launch view opens again (a paused recording keeps its session, V-6).
 */
private fun spaceFreed(s: MainUiState, e: MainEvent.SpaceFreed): Step {
    val outcome = e.outcome
    val dialog = s.storageFull?.let { d ->
        if (d.step !is CleanupStep.Freeing) d
        else (e.after?.let { withRead(d, it) } ?: d).copy(chosen = d.chosen - outcome.freed.keys, step = CleanupStep.Choosing(outcome))
    }
    val storage = s.storage?.let { st ->
        val read = e.after?.let { withRead(st, it) } ?: st
        if (!st.cleaning) read
        else read.copy(
            cleaning = false, cleanupFreed = outcome.freedTotal?.n,
            cleanupFailure = outcome.failed.entries.firstOrNull()?.let { (c, err) -> CleanupFailure(c, reason(err)) },
        )
    }
    val next = s.copy(storageFull = dialog, storage = storage)
    val storageRead = listOfNotNull(MainEffect.LoadStorage.takeIf { s.storage != null })
    // the history is gone: the sessions list and the launch view are read again
    return if (Cleanup.LocalDatabase !in outcome.freed) Step(next, storageRead)
    else next.loadingSessions { storageRead + it + listOfNotNull(MainEffect.ReadLaunchFacts.takeIf { s.scope != null }) }
}

/** The dialog after a read: its sizes and free space, or the read's failure (the last free space stays, explained). */
private fun withRead(d: StorageFullState, r: CleanupResult<Reclaim>): StorageFullState = when (r) {
    is CleanupResult.Ok -> d.copy(reclaim = ReclaimRead.Known(r.value), free = r.value.free)
    is CleanupResult.Err -> d.copy(reclaim = ReclaimRead.Failed(r.error))
}

/** Settings › Storage after a read: the sizes, or no sizes and the read's failure. */
private fun withRead(st: StorageUi, r: CleanupResult<Reclaim>): StorageUi = when (r) {
    is CleanupResult.Ok -> st.copy(reclaimable = reclaimable(r.value), reclaimFailure = null)
    is CleanupResult.Err -> st.copy(reclaimable = null, reclaimFailure = reason(r.error))
}

/** The text after "Couldn't …:" for a typed failure. */
fun reason(e: CleanupError): String = when (e) {
    CleanupError.Unavailable -> Strings.CleanupUnavailable
    is CleanupError.Failed -> e.message
}

private fun reclaimable(r: Reclaim) = Reclaimable(r.uploadedRaw.n, r.localDatabase.n)

/** R-10: the dialog as chrome draws it. */
fun storageFullUi(d: StorageFullState): StorageFullUi = StorageFullUi(
    freeBytes = d.free.n,
    reclaimable = (d.reclaim as? ReclaimRead.Known)?.reclaim?.let(::reclaimable),
    chosen = d.chosen,
    freeing = d.step is CleanupStep.Freeing,
    freedBytes = (d.step as? CleanupStep.Choosing)?.last?.freedTotal?.n,
    failures = (d.step as? CleanupStep.Choosing)?.last?.failed?.map { (c, err) -> CleanupFailure(c, reason(err)) }.orEmpty(),
    canResume = d.canResume,
    checking = d.reclaim is ReclaimRead.Reading,
    readFailure = (d.reclaim as? ReclaimRead.Failed)?.let { reason(it.error) },
)

/** G-14, with R-10's dialog over everything: Back there is "Not now". */
private fun back(s: MainUiState, e: MainEvent.Back): Step =
    if (s.storageFull != null) s.copy(storageFull = null).only() else backLayer(s, e)

/** G-14: closes the topmost layer; Leave changes nothing (the BackHandler is disabled then). */
private fun backLayer(s: MainUiState, e: MainEvent.Back): Step = when (backTarget(backLayers(s, e.pushedSheet, e.sheetSearching))) {
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
fun shownView(s: MainUiState): SessionView? = s.viewData?.takeIf { s.scope != null && it.key == s.viewKey }?.view

/** A scope's live flag, as last read from its view. */
data class ViewLive(val scope: Scope, val live: Boolean)

/**
 * R-7: the sheets' live flag for [scope]: the view's [live] once read; while the same scope's view is re-read (V-3
 * re-selects it after Stop) the [last] one read, so Stop reaches the same sheets as live → frozen; null before the
 * first read of a scope (the sheets start with liveness unknown).
 */
fun viewLive(scope: Scope?, live: Boolean?, last: ViewLive?): ViewLive? = when {
    scope == null -> null
    live != null -> ViewLive(scope, live)
    last?.scope == scope -> last
    else -> null
}

/**
 * The search page as chrome draws it: the page's query and area, with the latest results of this session view
 * (the previous query's stay until the new query's arrive, so typing does not blank the list).
 */
fun shownSearch(s: MainUiState): Pair<SearchPageState, List<DeviceRow>>? {
    val page = s.searchPage ?: return null
    val r = s.searchResults?.takeIf { it.key.view == s.viewKey }
    return SearchPageState(page.query, page.area, r?.order, page.searchedEverywhere) to r?.rows.orEmpty()
}

/** The sessions list with the shown scope selected (the list is the core's; the selection is always the scope). */
fun shownSessions(s: MainUiState): SessionsListState? = s.sessionsData?.list?.copy(selected = s.scope)

/** Asks for the sessions list: the read answers with this request's number ([MainEvent.SessionsRead]). */
private fun MainUiState.loadingSessions(effects: (MainEffect) -> List<MainEffect>): Step {
    val n = sessionsAsked + 1
    return Step(copy(sessionsAsked = n), effects(MainEffect.LoadSessions(n)))
}

/** R-4a: the recording's session: a new one (or none) shows empty, and a new one asks for its rate. */
private fun MainUiState.discoveryFor(sessionId: String?): MainUiState = when {
    sessionId == discoveryKey?.sessionId -> this
    sessionId == null -> copy(discoveryKey = null, discovery = DiscoveryBins.Empty)
    else -> copy(discoveryAsked = discoveryAsked + 1, discoveryKey = DiscoveryKey(sessionId, discoveryAsked + 1), discovery = DiscoveryBins.Empty)
}

/** R-4a: the same session's rate read again (Retry, `DataInvalidated`); the shown rate stays until the new one comes. */
private fun MainUiState.rereadDiscovery(): MainUiState =
    discoveryKey?.let { copy(discoveryAsked = discoveryAsked + 1, discoveryKey = it.copy(request = discoveryAsked + 1)) } ?: this
