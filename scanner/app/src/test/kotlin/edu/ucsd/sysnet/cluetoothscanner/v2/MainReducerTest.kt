package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal val sessionA = Scope.session("a")
internal val sessionB = Scope.session("b")
internal val box = Bbox(-117.3, 32.8, -117.2, 32.9)
internal val perms = PermissionState(Perm.Granted, Perm.Denied, Perm.NotRequired, bluetoothOn = true)
internal fun settings(done: Boolean, userId: String? = null) =
    Settings(userId, recordOnOpen = false, wifiOnlyUpload = true, scanCodedPhy = false, hideUnnamedApple = true, appVersion = "0.2.0", onboardingDone = done)
internal fun view(scope: Scope, live: Boolean = false) = SessionView(scope, live, listOf("a"), 3, null, emptyList(), routesFaint = false, seq = 1)

/** Applies [events] in order and returns the last state. */
internal fun MainUiState.on(vararg events: MainEvent): MainUiState = events.fold(this) { s, e -> reduce(s, e).state }

/** G-14 back order (backTarget's order through the reducer). */
class MainBackOrderTest {
    private val allOpen = MainUiState(scope = sessionA).on(
        MainEvent.RecorderChanged(Phase.Recording), MainEvent.SetRecorderPanel(true), MainEvent.OpenSettings,
        MainEvent.ToggleSidebar, MainEvent.SetLocationPopover(true),
    ).copy(searchQuery = "tile")

    @Test fun backClosesOneLayerAtATimeTopmostFirst() {
        var s = allOpen
        fun back(pushed: Boolean, searching: Boolean): Step = reduce(s, MainEvent.Back(pushed, searching)).also { s = it.state }

        assertEquals(emptyList<MainEffect>(), back(pushed = true, searching = true).effects)
        assertEquals(false, s.locationPopoverOpen)
        assertEquals(Page.Settings, allOpen.page)
        back(pushed = true, searching = true)
        assertNull(s.page)
        val beforeSheet = s
        // the sheet stack's pushed sheet is D's: the reducer asks it to pop and changes nothing itself
        assertEquals(listOf(MainEffect.SheetsBack), back(pushed = true, searching = true).effects)
        assertEquals(beforeSheet, s)
        back(pushed = false, searching = true)
        assertEquals(false, s.sidebarOpen)
        assertTrue(s.recorderPanelOpen)
        back(pushed = false, searching = true)
        assertEquals(RecorderMirror.Active(panelOpen = false), s.recorder)
        assertEquals(listOf(MainEffect.SheetsExitSearch), back(pushed = false, searching = true).effects)
        assertNull(s.searchQuery)
        val last = s
        val leave = back(pushed = false, searching = false)
        assertEquals(last, leave.state)
        assertEquals(emptyList<MainEffect>(), leave.effects)
    }

    @Test fun backFromTheSearchPageReturnsToSearchState() {
        val s = MainUiState(scope = sessionA, searchQuery = "tile").on(MainEvent.OpenSearch)
        assertEquals(Page.SearchPage("tile", null, false), s.page)
        val back = s.on(MainEvent.Back(pushedSheet = false, sheetSearching = true))
        assertNull(back.page)
        assertEquals("tile", back.searchQuery)
    }

    @Test fun backLayersMirrorTheState() {
        assertEquals(BackLayers(), backLayers(MainUiState(), pushedSheet = false, sheetSearching = false))
        assertEquals(BackLayers(recorderPanel = true, pushedSheet = true),
            backLayers(MainUiState().on(MainEvent.RecorderChanged(Phase.Paused), MainEvent.SetRecorderPanel(true)), pushedSheet = true, sheetSearching = false))
    }
}

/** V-2, V-3, V-6, V-7: scope switches. */
class MainScopeTest {
    @Test fun selectingAScopeStartsAFreshView() {
        val before = MainUiState(scope = sessionA, sidebarOpen = true, searchQuery = "tile", page = Page.SearchPage("t", box, false))
        val step = reduce(before, MainEvent.SelectScope(sessionB))
        assertEquals(sessionB, step.state.scope)
        assertEquals(before.epoch.next(), step.state.epoch)
        assertEquals(false, step.state.sidebarOpen)
        assertNull(step.state.searchQuery)
        assertNull(step.state.page)
        assertEquals(listOf(MainEffect.LoadSessions), step.effects)
    }

    @Test fun settingsStaysOpenOverAScopeSwitch() {
        assertEquals(Page.Settings, MainUiState(page = Page.Settings).on(MainEvent.SelectScope(Scope.All)).page)
    }

    @Test fun reselectingTheSameScopeChangesTheViewKey() {
        val s = MainUiState().on(MainEvent.SelectScope(sessionA))
        assertNotEquals(s.viewKey, s.on(MainEvent.SelectScope(sessionA)).viewKey)
    }

    @Test fun theFirstRunHasNoViewAndReadsNothing() {
        val step = reduce(MainUiState(scope = sessionA), MainEvent.SelectScope(null))
        assertNull(step.state.scope)
        assertEquals(emptyList<MainEffect>(), step.effects)
        assertNull(step.state.searchKey)
    }

    @Test fun stopKeepsTheEndedSessionAndReReadsIt() {
        val recording = MainUiState().on(MainEvent.RecordingStarted("a"), MainEvent.RecorderChanged(Phase.Recording), MainEvent.SetRecorderPanel(true))
        assertEquals(sessionA, recording.scope)
        val stopped = reduce(recording, MainEvent.RecorderChanged(Phase.Idle))
        assertEquals(sessionA, stopped.state.scope)
        assertEquals(recording.epoch.next(), stopped.state.epoch)
        assertEquals(RecorderMirror.Idle, stopped.state.recorder)
        assertEquals(listOf(MainEffect.LoadSessions), stopped.effects)
        // a phase change that is not a stop re-reads nothing
        assertEquals(recording.epoch, recording.on(MainEvent.RecorderChanged(Phase.Paused)).epoch)
        assertTrue(recording.on(MainEvent.RecorderChanged(Phase.Paused)).recorderPanelOpen)
    }

    @Test fun thePanelOpensOnlyWhileRecording() {
        assertEquals(RecorderMirror.Idle, MainUiState().on(MainEvent.SetRecorderPanel(true)).recorder)
        assertEquals(false, MainUiState().on(MainEvent.RecorderChanged(Phase.Starting)).recorderPanelOpen)
    }

    @Test fun launchFactsPickTheLaunchView() {
        fun launch(live: String?, recordOnOpen: Boolean, count: Int) = reduce(MainUiState(), MainEvent.LaunchFactsRead(LaunchFacts(live, recordOnOpen, count)))
        assertEquals(sessionA, launch("a", recordOnOpen = true, count = 3).state.scope)
        assertEquals(listOf(MainEffect.StartRecording), launch(null, recordOnOpen = true, count = 3).effects)
        assertEquals(Scope.All, launch(null, recordOnOpen = false, count = 3).state.scope)
        val first = launch(null, recordOnOpen = false, count = 0).state
        assertNull(first.scope)
        assertEquals(ViewEpoch(1), first.epoch)
    }

    @Test fun onlyTheCurrentKeysViewIsShown() {
        val s = MainUiState().on(MainEvent.SelectScope(sessionA))
        val read = ViewData(s.viewKey, view(sessionA))
        assertEquals(read.view, shownView(s, read))
        assertNull(shownView(s.on(MainEvent.SelectScope(sessionA)), read))
        assertNull(shownView(s.on(MainEvent.SelectScope(null)), ViewData(ViewKey(null, s.epoch.next()), view(sessionA))))
    }

    @Test fun theSessionsListSelectsTheShownScope() {
        val list = SessionsListState(null, AllSessionsSummary(1, 2, null), emptyList(), selected = Scope.All)
        assertEquals(sessionB, shownSessions(MainUiState(scope = sessionB), list)?.selected)
    }

    @Test fun deletingTheShownDataOpensTheLaunchViewAgain() {
        val s = MainUiState(scope = sessionA)
        assertEquals(listOf(MainEffect.LoadStorage, MainEffect.LoadSessions, MainEffect.ReadLaunchFacts),
            reduce(s, MainEvent.DeleteFinished(Scope.All, deleted = true)).effects)
        assertEquals(listOf(MainEffect.LoadStorage, MainEffect.LoadSessions), reduce(s, MainEvent.DeleteFinished(sessionB, deleted = true)).effects)
        assertEquals(emptyList<MainEffect>(), reduce(s, MainEvent.DeleteFinished(sessionA, deleted = false)).effects)
    }
}

/** Q-3 to Q-7: search enter and exit. */
class MainSearchTest {
    private val camera = MapCamera(box, 15.0)
    private val base = MainUiState(scope = sessionA, camera = camera, sidebarOpen = true)

    @Test fun theSearchPageSearchesTheViewportItOpenedOn() {
        val s = base.on(MainEvent.OpenSearch)
        assertEquals(Page.SearchPage("", box, false), s.page)
        assertEquals(false, s.sidebarOpen)
        assertEquals(SearchKey(s.viewKey, "", box), s.searchKey)
        // a later camera move does not move the page's area (Q-3)
        assertEquals(box, s.on(MainEvent.CameraIdle(MapCamera(Bbox(0.0, 0.0, 1.0, 1.0), 3.0))).searchPage?.area)
    }

    @Test fun queryAndEverywhereEditThePage() {
        val s = base.on(MainEvent.OpenSearch, MainEvent.SetQuery("tile"), MainEvent.SearchEverywhere)
        assertEquals(Page.SearchPage("tile", null, true), s.page)
        // without the page, a query edit changes nothing
        assertEquals(base, base.on(MainEvent.SetQuery("x"), MainEvent.SearchEverywhere))
    }

    @Test fun pickingAResultEntersSearchState() {
        val page = base.on(MainEvent.OpenSearch, MainEvent.SetQuery("tile"))
        val step = reduce(page, MainEvent.Pick(42L))
        assertNull(step.state.page)
        assertEquals("tile", step.state.searchQuery)
        assertEquals(listOf(MainEffect.SheetsEnterSearch("tile", box, 42L)), step.effects)
        // no page, no pick
        assertEquals(Step(base), reduce(base, MainEvent.Pick(42L)))
    }

    @Test fun theBarReopensThePageWithItsQueryAndItsXExits() {
        val searching = base.on(MainEvent.OpenSearch, MainEvent.SetQuery("tile"), MainEvent.Pick(1L))
        assertEquals(Page.SearchPage("tile", box, false), searching.on(MainEvent.OpenSearch).page)
        val exit = reduce(searching, MainEvent.ExitSearch)
        assertNull(exit.state.searchQuery)
        assertEquals(listOf(MainEffect.SheetsExitSearch), exit.effects)
    }

    @Test fun searchThisAreaUsesTheSettledCamera() {
        assertEquals(listOf(MainEffect.SheetsSearchThisArea(camera)), reduce(base, MainEvent.SearchThisArea).effects)
        assertEquals(emptyList<MainEffect>(), reduce(base.copy(camera = null), MainEvent.SearchThisArea).effects)
    }

    @Test fun resultsShowOnlyWithinTheirView() {
        val s = base.on(MainEvent.OpenSearch, MainEvent.SetQuery("tile"))
        val key = s.searchKey ?: error("the page is open")
        val results = SearchResults(key, OrderedKeys(longArrayOf(1, 2)), emptyList())
        assertSame(results.order, shownSearch(s, results)?.first?.results)
        // the next query's results replace these when they arrive; until then these stay
        assertSame(results.order, shownSearch(s.on(MainEvent.SetQuery("tiles")), results)?.first?.results)
        // a new view's page never shows the old view's results
        val other = s.on(MainEvent.SelectScope(sessionB), MainEvent.OpenSearch)
        assertNull(shownSearch(other, results)?.first?.results)
        assertNull(shownSearch(s.on(MainEvent.ClosePage), results))
    }
}

/** O-1 / O-2: the Settings storage flow and onboarding. */
class MainSettingsTest {
    @Test fun firstLaunchOpensOnboardingAndLaterLaunchesTheLaunchView() {
        val first = reduce(MainUiState(), MainEvent.SettingsLoaded(settings(done = false, userId = "ab1"), perms))
        assertEquals("ab1", first.state.onboarding?.ui?.userId)
        assertEquals(true, first.state.onboarding?.ui?.userIdValid)
        assertEquals(listOf(MainEffect.LoadSessions), first.effects)
        assertEquals(listOf(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts),
            reduce(MainUiState(), MainEvent.SettingsLoaded(settings(done = true), perms)).effects)
    }

    @Test fun doneSavesThenOpensTheLaunchView() {
        val ob = MainUiState().on(MainEvent.SettingsLoaded(settings(done = false), perms), MainEvent.EditOnboarding { it.copy(userId = "x7", autoRecord = false) })
        assertEquals(listOf(MainEffect.SaveOnboarding("x7", false)), reduce(ob, MainEvent.FinishOnboarding).effects)
        val saved = reduce(ob, MainEvent.OnboardingSaved)
        assertNull(saved.state.onboarding)
        assertEquals(listOf(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts), saved.effects)
    }

    @Test fun storageKeepsTheChosenScopesAndFormat() {
        val facts = StorageFacts(10, 20, null, 0)
        val loaded = MainUiState(scope = sessionA).on(MainEvent.StorageLoaded(facts))
        assertEquals(sessionA, loaded.storage?.exportScope)
        val chosen = loaded.on(MainEvent.EditStorage { it.copy(exportScope = Scope.All, exportFormat = ExportFormat.Csv) }, MainEvent.Export(toDownloads = false))
        assertEquals(true, chosen.storage?.exporting)
        val reread = chosen.on(MainEvent.StorageLoaded(facts.copy(pendingCount = 2)))
        assertEquals(Scope.All, reread.storage?.exportScope)
        assertEquals(ExportFormat.Csv, reread.storage?.exportFormat)
        assertEquals(false, reread.storage?.exporting)
    }

    @Test fun exportNeedsAFormat() {
        val loaded = MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)))
        assertEquals(Step(loaded), reduce(loaded, MainEvent.Export(toDownloads = true)))
        val step = reduce(loaded.on(MainEvent.EditStorage { it.copy(exportFormat = ExportFormat.ParquetRaw) }), MainEvent.Export(toDownloads = true))
        assertEquals(listOf(MainEffect.Export(Scope.All, ExportFormat.ParquetRaw, toDownloads = true)), step.effects)
    }

    @Test fun deleteAsksAgainOnlyWithPendingFiles() {
        val clean = MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)))
        assertEquals(listOf(MainEffect.Delete(Scope.All, includeUnuploaded = false)), reduce(clean, MainEvent.DeleteConfirmed).effects)
        val pending = MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 3)), MainEvent.DeleteConfirmed)
        assertEquals(DeleteConfirm.LoseUnuploaded, pending.storage?.deleteConfirm)
        val step = reduce(pending, MainEvent.Delete(includeUnuploaded = true))
        assertNull(step.state.storage?.deleteConfirm)
        assertEquals(listOf(MainEffect.Delete(Scope.All, includeUnuploaded = true)), step.effects)
    }
}

/** Every effect and every piece of keyed work ends in a result or a typed Failed the logic recovers from. */
class MainFailureTest {
    private val boom = IllegalStateException("boom")
    private fun failed(s: MainUiState, task: MainTask) = reduce(s, MainEvent.Failed(task, boom))

    @Test fun theLinkFailsAndRetriesAsAStateMachine() {
        val failed = failed(MainUiState(), MainTask.Link).state
        assertEquals(CoreLink.Failed(boom, 0), failed.core)
        assertNull(failed.linkAttempt)
        val retry = failed.on(MainEvent.RetryLink)
        assertEquals(CoreLink.Waiting(1), retry.core)
        assertEquals(1, retry.linkAttempt)
        // Retry while waiting changes nothing
        assertEquals(retry, retry.on(MainEvent.RetryLink))
    }

    @Test fun anUnhandledFailureFailsTheScreenNotTheCore() {
        val failed = failed(MainUiState(), MainTask.Unhandled).state
        assertEquals(CoreLink.Failed(boom, 0), failed.core)
        assertEquals(CoreLink.Waiting(1), failed.on(MainEvent.RetryLink).core)
        // a failure after the screen failed changes nothing
        assertEquals(Step(failed), failed(failed, MainTask.Link))
    }

    @Test fun locateIsAnEffect() {
        assertEquals(Step(MainUiState(), listOf(MainEffect.Locate)), reduce(MainUiState(), MainEvent.Locate))
    }

    @Test fun whatTheScreenWaitsForFallsBack() {
        // no settings: the launch view still opens; no launch facts: the first run
        assertEquals(listOf(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts), failed(MainUiState(), MainTask.Effect(MainEffect.LoadSettings)).effects)
        val first = failed(MainUiState(scope = sessionA), MainTask.Effect(MainEffect.ReadLaunchFacts)).state
        assertNull(first.scope)
        // no storage stats: the section shows zeros
        assertEquals(0L, failed(MainUiState(), MainTask.Effect(MainEffect.LoadStorage)).state.storage?.databaseBytes)
    }

    @Test fun aFailedExportStopsExportingAndSaysSo() {
        val exporting = MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)),
            MainEvent.EditStorage { it.copy(exportFormat = ExportFormat.Csv) }, MainEvent.Export(toDownloads = true))
        assertEquals(true, exporting.storage?.exporting)
        val failed = reduce(exporting, MainEvent.Failed(MainTask.Effect(MainEffect.Export(Scope.All, ExportFormat.Csv, true)), PlatformFailure(PlatformError.Io("disk full"))))
        assertEquals(false, failed.state.storage?.exporting)
        assertEquals("not ExportFinished: it says it failed", Notice("Export failed: disk full"), failed.state.notice)
        assertTrue(failed.effects.isEmpty())
    }

    @Test fun aFailedOnboardingSaveKeepsTheOnboardingAndOffersRetry() {
        val ob = MainUiState().on(MainEvent.SettingsLoaded(settings(done = false), perms), MainEvent.EditOnboarding { it.copy(userId = "x7") })
        val save = MainEffect.SaveOnboarding("x7", ob.onboarding?.ui?.autoRecord ?: true)
        val failed = reduce(ob, MainEvent.Failed(MainTask.Effect(save), PlatformFailure(PlatformError.Io("settings not written")))).state
        assertEquals("the onboarding stays open", ob.onboarding, failed.onboarding)
        assertEquals(Notice("Could not save the settings: settings not written", retry = save), failed.notice)
        val retried = reduce(failed, MainEvent.RetryNotice)
        assertNull(retried.state.notice)
        assertEquals(listOf<MainEffect>(save), retried.effects)
    }

    @Test fun failedLaunchesAndPlatformCallsTheUserAskedForShowTheirError() {
        val error = PlatformFailure(PlatformError.Unavailable("No activity found"))
        for (task in listOf(MainTask.Effect(MainEffect.OpenAppSettings), MainTask.Effect(MainEffect.UploadNow), MainTask.Launch)) {
            val failed = reduce(MainUiState(), MainEvent.Failed(task, error)).state
            assertEquals("$task", Notice("No activity found"), failed.notice)
            assertNull("$task: a plain notice has no retry", failed.notice?.retry)
            assertNull("$task: Dismiss closes it", failed.on(MainEvent.DismissNotice).notice)
            assertEquals("$task: Retry without a retry only closes it", Step(failed.copy(notice = null)), reduce(failed, MainEvent.RetryNotice))
        }
        assertEquals("a thrown failure shows its own message", Notice("boom"), failed(MainUiState(), MainTask.Launch).state.notice)
    }

    @Test fun otherFailuresLeaveTheStateAlone() {
        val s = MainUiState(scope = sessionA, searchQuery = "tile").on(MainEvent.OpenSearch)
        listOf(MainTask.View(s.viewKey), MainTask.Sessions, MainTask.CoreEvents, MainTask.Platform,
            MainTask.Effect(MainEffect.SheetsBack),
            MainTask.Effect(MainEffect.Delete(Scope.All, false)), MainTask.Effect(MainEffect.StartRecording),
        ).forEach { assertEquals(Step(s), failed(s, it)) }
        s.searchKey?.let { assertEquals(Step(s), failed(s, MainTask.Search(it))) }
    }
}

/** The runner's one wrapper: a throw becomes the task's failure; cancellation stays the caller's. */
class GuardedTest {
    @Test fun aThrowIsReportedWithItsTask() {
        val reported = mutableListOf<Pair<MainTask, Throwable>>()
        val e = IllegalArgumentException("x")
        assertNull(guarded(MainTask.Sessions, { t, err -> reported += t to err }) { throw e })
        assertEquals(listOf<Pair<MainTask, Throwable>>(MainTask.Sessions to e), reported)
        assertEquals(3, guarded(MainTask.Platform, { _, _ -> reported.clear() }) { 3 })
        assertEquals(1, reported.size)
    }

    @Test fun cancellationIsNotAFailure() {
        var reported = false
        val thrown = runCatching { guarded(MainTask.Link, { _, _ -> reported = true }) { throw kotlinx.coroutines.CancellationException("c") } }
        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(false, reported)
    }
}
