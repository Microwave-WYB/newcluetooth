package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.chrome.Cleanup
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingPermission
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Long random event sequences with a fixed seed through [reduce]; the invariants hold after every step. A failure
 * names the run, the step and the event, so it replays exactly.
 */
class MainReducerRandomTest {
    private val scopes = listOf(null, Scope.All, sessionA, sessionB)
    private val boxes = listOf(null, box, Bbox(0.0, 0.0, 1.0, 1.0))

    private val effects = listOf(MainEffect.LoadSettings, MainEffect.LoadSessions, MainEffect.ReadLaunchFacts, MainEffect.LoadStorage,
        MainEffect.Export(Scope.All, ExportFormat.Csv, false), MainEffect.SaveOnboarding("ab1", true), MainEffect.Delete(Scope.All, true),
        MainEffect.StartRecording, MainEffect.SheetsBack, MainEffect.UploadNow)

    private fun Random.event(): MainEvent = when (nextInt(50)) {
        0 -> MainEvent.SettingsLoaded(settings(done = nextBoolean(), userId = listOf(null, "ab1", "BAD").random(this)), perms)
        1 -> MainEvent.PermissionsChanged(perms.copy(bluetoothOn = nextBoolean()))
        2 -> MainEvent.LaunchFactsRead(LaunchFacts(listOf(null, "a", "b").random(this), nextBoolean(), nextInt(3)))
        3, 4, 5 -> MainEvent.RecorderChanged(Phase.entries.random(this))
        6 -> MainEvent.RecordingStarted(listOf("a", "b").random(this))
        7 -> MainEvent.StorageLoaded(StorageFacts(nextLong(100), nextLong(100), null, nextInt(3)))
        8 -> MainEvent.ExportFinished
        9 -> MainEvent.DeleteFinished(scopes.filterNotNull().random(this), nextBoolean())
        10 -> MainEvent.OnboardingSaved
        11 -> boxes.random(this)?.let { MainEvent.CameraIdle(MapCamera(it, nextDouble(1.0, 18.0))) } ?: MainEvent.CloseSidebar
        12, 13, 14 -> MainEvent.SelectScope(scopes.random(this))
        15 -> listOf(MainEvent.StartRecording, MainEvent.PauseRecording, MainEvent.ResumeRecording, MainEvent.StopRecording).random(this)
        16, 17 -> MainEvent.SetRecorderPanel(nextBoolean())
        18 -> MainEvent.FitRoute
        19 -> MainEvent.SetLayout(LayoutMode.entries.random(this))
        20 -> MainEvent.ToggleSidebar
        21 -> MainEvent.SetLocationPopover(nextBoolean())
        22 -> MainEvent.LocationFix(LocationStatus.Action.entries.random(this))
        23 -> MainEvent.OpenSettings
        24 -> MainEvent.ClosePage
        25, 26, 27, 28 -> MainEvent.Back(nextBoolean(), nextBoolean())
        29, 30 -> MainEvent.OpenSearch
        31 -> MainEvent.SetQuery(listOf("", "t", "tile", "tiles").random(this))
        32 -> MainEvent.SearchEverywhere
        33 -> MainEvent.Pick(nextLong(1, 5))
        34 -> MainEvent.SearchThisArea
        35 -> MainEvent.ExitSearch
        36 -> MainEvent.EditStorage { it.copy(exportFormat = ExportFormat.entries.random(this)) }
        37 -> listOf(MainEvent.Export(nextBoolean()), MainEvent.DeleteConfirmed, MainEvent.Delete(nextBoolean())).random(this)
        38 -> MainEvent.EditOnboarding { it.copy(step = it.step + 1) }
        39 -> MainEvent.Failed(
            listOf(MainTask.Effect(effects.random(this)), MainTask.Link, MainTask.Unhandled, MainTask.Sessions, MainTask.Platform,
                MainTask.View(ViewKey(sessionA, ViewEpoch(1)))).random(this),
            IllegalStateException("random failure"),
        )
        40 -> MainEvent.RetryLink
        41 -> listOf(MainEvent.UploadNow, MainEvent.OpenAppSettings, MainEvent.Locate).random(this)
        42 -> MainEvent.StorageFull(Bytes(nextLong(0, 400_000_000)))
        43 -> MainEvent.ReclaimableRead(if (nextBoolean()) null else Reclaim(Bytes(nextLong(0, 400_000_000)), Bytes(nextLong(1_000)), Bytes(nextLong(1_000))))
        44, 45 -> MainEvent.ChooseCleanup(Cleanup.entries.random(this), nextBoolean())
        46 -> MainEvent.FreeUpSpace
        // each cleanup of a run: not run, freed something, or failed with a typed error
        47 -> Cleanup.entries.groupBy { nextInt(3) }.let { split ->
            MainEvent.SpaceFreed(
                CleanupOutcome(
                    split[1].orEmpty().associateWith { Bytes(nextLong(1_000)) },
                    split[2].orEmpty().associateWith { listOf(CleanupError.Unavailable, CleanupError.Failed("busy")).random(this) },
                ),
                if (nextBoolean()) null else Reclaim(Bytes(nextLong(0, 400_000_000)), Bytes(0), Bytes(0)),
            )
        }
        48 -> listOf(MainEvent.StorageFullNotNow, MainEvent.StorageFullResume).random(this)
        49 -> listOf(MainEvent.CleanupRequested(Cleanup.entries.random(this)), MainEvent.CleanupConfirmed, MainEvent.CleanupCancelled).random(this)
        else -> listOf(MainEvent.RequestPermission(OnboardingPermission.entries.random(this)), MainEvent.EnableBluetooth, MainEvent.FinishOnboarding).random(this)
    }

    @Test fun invariantsHoldAfterEveryStep() {
        val random = Random(20261029)
        val list = SessionsListState(null, AllSessionsSummary(0, 0, null), emptyList(), selected = null)
        repeat(RUNS) { run ->
            var s = MainUiState()
            val seenKeys = mutableListOf(s.viewKey)
            repeat(STEPS) { stepNo ->
                val e = random.event()
                val step = reduce(s, e)
                val n = step.state
                fun check(what: String, ok: Boolean) { if (!ok) fail("run $run step $stepNo $e: $what\n  before $s\n  after $n\n  effects ${step.effects}") }

                // the view: the epoch never goes back, and a selection always makes a new key
                check("epoch went back", n.epoch.n >= s.epoch.n)
                val selects = e is MainEvent.SelectScope || e is MainEvent.RecordingStarted ||
                    (e is MainEvent.LaunchFactsRead && MainEffect.StartRecording !in step.effects) ||
                    (e is MainEvent.RecorderChanged && s.recorder is RecorderMirror.Active && e.phase == Phase.Idle && s.scope != null)
                if (selects) check("a selection kept the view key", n.viewKey !in seenKeys)
                if (n.viewKey != s.viewKey) seenKeys += n.viewKey
                // only the current key's data shows; an earlier read never does
                seenKeys.takeLast(RECENT_KEYS).forEach { k ->
                    val shown = shownView(n, ViewData(k, view(k.scope ?: Scope.All))) != null
                    check("view of $k shown=$shown", shown == (k == n.viewKey && n.scope != null))
                }
                // the selection is always the shown scope
                check("sessions selection", shownSessions(n, list)?.selected == n.scope)
                // the recorder: the mirror follows the phase; the panel exists only while recording
                if (e is MainEvent.RecorderChanged) check("mirror", (n.recorder is RecorderMirror.Idle) == (e.phase == Phase.Idle))
                if (n.recorderPanelOpen) check("panel while idle", n.recorder is RecorderMirror.Active)
                // search: work only on an open page in a view; the page draws exactly while open
                n.searchKey?.let { check("search key", n.scope != null && n.searchPage != null && it.view == n.viewKey) }
                check("search page drawn", (shownSearch(n, null) != null) == (n.searchPage != null))
                n.searchKey?.let { k ->
                    val r = SearchResults(k, OrderedKeys(longArrayOf()), emptyList())
                    check("own results hidden", shownSearch(n, r)?.first?.results === r.order)
                }
                step.effects.filterIsInstance<MainEffect.SheetsEnterSearch>().forEach { fx ->
                    check("pick without a page", s.searchPage?.query == fx.query && n.searchQuery == fx.query && n.page == null)
                }
                // a failure never leaves the export running; the link fails only while waiting
                if (e is MainEvent.Failed && (e.task as? MainTask.Effect)?.effect is MainEffect.Export) check("export kept running", n.storage?.exporting != true)
                if (n.core is CoreLink.Failed && s.core !is CoreLink.Failed) check("link failed from ${s.core}", s.core !is CoreLink.Failed && e is MainEvent.Failed)
                // R-10: cleanups run only with a switch on (dialog) or a confirmation (Settings); Resume only above the floor
                step.effects.filterIsInstance<MainEffect.FreeUpSpace>().forEach { fx ->
                    val fromDialog = e is MainEvent.FreeUpSpace && s.storageFull?.chosen == fx.cleanups && s.storageFull?.step is CleanupStep.Choosing
                    val fromSettings = e is MainEvent.CleanupConfirmed && s.storage?.cleanupConfirm?.let(::setOf) == fx.cleanups
                    check("cleanup without a choice", fx.cleanups.isNotEmpty() && (fromDialog || fromSettings))
                }
                if (e is MainEvent.StorageFullResume && MainEffect.ResumeRecording in step.effects) check("resume below the floor", (s.storageFull?.free ?: Bytes(0)) > STORAGE_FLOOR)
                n.storageFull?.let { d -> if (s.storageFull == null) check("opened with a switch on", d.chosen.isEmpty()) }
                // a run's outcome: only the cleanups that freed something switch off; a failed one stays on and is shown
                val before = s.storageFull
                if (e is MainEvent.SpaceFreed && before != null && before.step is CleanupStep.Freeing) {
                    val d = n.storageFull
                    check("outcome dropped the dialog", d != null)
                    if (d != null) {
                        val ui = storageFullUi(d)
                        check("switches after the outcome", d.chosen == before.chosen - e.outcome.freed.keys)
                        check("a failure shown as freed", (ui.freedBytes == null) == e.outcome.freed.isEmpty())
                        check("failures not shown", ui.failures.map { it.cleanup }.toSet() == e.outcome.failed.keys)
                    }
                }
                if (e is MainEvent.SpaceFreed && s.storage?.cleaning == true) {
                    check("settings shows a failure as freed", (n.storage?.cleanupFreed == null) == e.outcome.freed.isEmpty())
                    check("settings hides the failure", (n.storage?.cleanupFailure == null) == e.outcome.failed.isEmpty())
                }
                // storage: an export runs only with a format
                n.storage?.let { if (it.exporting) check("export without a format", it.exportFormat != null) }
                if (e is MainEvent.Back) checkBack(s, e, step, ::check)
                s = n
            }
        }
    }

    /** G-14: Back closes exactly the topmost open layer, which really was open; Leave changes nothing. */
    private fun checkBack(s: MainUiState, e: MainEvent.Back, step: Step, check: (String, Boolean) -> Unit) {
        val n = step.state
        // R-10's dialog is over everything: Back is "Not now" and nothing else changes
        if (s.storageFull != null) {
            check("back with the dialog open", n == s.copy(storageFull = null) && step.effects.isEmpty())
            return
        }
        val before = backLayers(s, e.pushedSheet, e.sheetSearching)
        val target = backTarget(before)
        val wasOpen = when (target) {
            BackTarget.LocationPopover -> before.locationPopover
            BackTarget.Page -> before.page
            BackTarget.PushedSheet -> before.pushedSheet
            BackTarget.Sidebar -> before.sidebar
            BackTarget.RecorderPanel -> before.recorderPanel
            BackTarget.SearchState -> before.searchState
            BackTarget.Leave -> before == BackLayers()
        }
        check("back to $target, which was not open", wasOpen)
        val expected = when (target) {
            BackTarget.LocationPopover -> s.copy(locationPopoverOpen = false)
            BackTarget.Page -> s.copy(page = null)
            BackTarget.PushedSheet, BackTarget.Leave -> s
            BackTarget.Sidebar -> s.copy(sidebarOpen = false)
            BackTarget.RecorderPanel -> s.copy(recorder = RecorderMirror.Active(panelOpen = false))
            BackTarget.SearchState -> s.copy(searchQuery = null)
        }
        check("back to $target changed more than that layer", n == expected)
        val effects = when (target) {
            BackTarget.PushedSheet -> listOf(MainEffect.SheetsBack)
            BackTarget.SearchState -> listOf(MainEffect.SheetsExitSearch)
            BackTarget.LocationPopover, BackTarget.Page, BackTarget.Sidebar, BackTarget.RecorderPanel, BackTarget.Leave -> emptyList()
        }
        check("back to $target effects", step.effects == effects)
        // the layers the reducer owns only ever close
        val after = backLayers(n, e.pushedSheet, e.sheetSearching)
        check("back opened a layer", !(after.locationPopover && !before.locationPopover) && !(after.page && !before.page) &&
            !(after.sidebar && !before.sidebar) && !(after.recorderPanel && !before.recorderPanel))
    }

    @Test fun theSequencesReachEveryBackTarget() {
        val random = Random(20261029)
        val reached = mutableSetOf<BackTarget>()
        var s = MainUiState()
        repeat(RUNS * STEPS / 10) {
            val e = random.event()
            if (e is MainEvent.Back) reached += backTarget(backLayers(s, e.pushedSheet, e.sheetSearching))
            s = reduce(s, e).state
        }
        assertEquals(BackTarget.entries.toSet(), reached)
        assertTrue(s.epoch.n > 0)
    }

    private companion object {
        const val RUNS = 200
        const val STEPS = 500
        const val RECENT_KEYS = 16
    }
}
