package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.chrome.Cleanup
import edu.ucsd.sysnet.cluetoothscanner.chrome.CleanupFailure
import edu.ucsd.sysnet.cluetoothscanner.chrome.Reclaimable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-10 storage full: the dialog's machine, and the same cleanups in Settings › Storage (O-1 item 11). */
class MainStorageFullTest {
    private val low = Bytes(150_000_000)
    private val reclaim = Reclaim(low, uploadedRaw = Bytes(300_000_000), localDatabase = Bytes(40_000_000))
    private val opened = MainUiState(scope = sessionA).on(MainEvent.StorageFull(low))
    private val busy = CleanupError.Failed("the database is busy")

    private fun freed(vararg freed: Pair<Cleanup, Bytes>, failed: Map<Cleanup, CleanupError> = emptyMap(), after: Reclaim? = null) =
        MainEvent.SpaceFreed(CleanupOutcome(freed.toMap(), failed), after?.let { CleanupResult.Ok(it) })

    private fun freeing(vararg chosen: Cleanup) =
        opened.on(*chosen.map { MainEvent.ChooseCleanup(it, true) }.toTypedArray(), MainEvent.FreeUpSpace)

    private fun dialog(s: MainUiState) = s.storageFull ?: error("the dialog is open")

    @Test fun storageFullOpensTheDialogWithBothSwitchesOff() {
        val step = reduce(MainUiState(), MainEvent.StorageFull(low))
        assertEquals(StorageFullState(low, ReclaimRead.Reading, emptySet(), CleanupStep.Choosing(null)), step.state.storageFull)
        assertEquals(listOf(MainEffect.LoadReclaimable), step.effects)
        val ui = storageFullUi(dialog(opened.on(MainEvent.ReclaimableRead(CleanupResult.Ok(reclaim)))))
        assertEquals(150_000_000L, ui.freeBytes)
        assertEquals(Reclaimable(300_000_000, 40_000_000), ui.reclaimable)
        assertEquals(false, ui.canResume)
        assertEquals(false, ui.checking)
        assertEquals(true, storageFullUi(dialog(opened)).checking)
        assertEquals(false, storageFullUi(dialog(opened.on(MainEvent.ReclaimableRead(CleanupResult.Err(CleanupError.Unavailable))))).checking)
        // a second report while open only updates the free space
        assertEquals(Step(opened.copy(storageFull = opened.storageFull?.copy(free = Bytes(1)))), reduce(opened, MainEvent.StorageFull(Bytes(1))))
    }

    @Test fun freeUpSpaceNeedsASwitch() {
        assertEquals(Step(opened), reduce(opened, MainEvent.FreeUpSpace))
        val chosen = opened.on(MainEvent.ChooseCleanup(Cleanup.UploadedRaw, true))
        val step = reduce(chosen, MainEvent.FreeUpSpace)
        assertEquals(CleanupStep.Freeing, step.state.storageFull?.step)
        assertEquals(listOf(MainEffect.FreeUpSpace(setOf(Cleanup.UploadedRaw))), step.effects)
        // the switches wait while it runs
        assertEquals(step.state, step.state.on(MainEvent.ChooseCleanup(Cleanup.LocalDatabase, true)))
    }

    @Test fun afterFreeingResumeOnlyAboveTheFloor() {
        val stillLow = freeing(Cleanup.UploadedRaw).on(freed(Cleanup.UploadedRaw to Bytes(20_000_000), after = reclaim.copy(free = Bytes(170_000_000))))
        val d = dialog(stillLow)
        assertEquals(20_000_000L, storageFullUi(d).freedBytes)
        assertEquals(emptyList<CleanupFailure>(), storageFullUi(d).failures)
        assertEquals(false, d.canResume)
        assertEquals(emptySet<Cleanup>(), d.chosen)
        // below the floor Resume does nothing: the dialog stays and the recording stays paused
        assertEquals(Step(stillLow), reduce(stillLow, MainEvent.StorageFullResume))
        val enough = freeing(Cleanup.UploadedRaw).on(freed(Cleanup.UploadedRaw to Bytes(300_000_000), after = reclaim.copy(free = Bytes(450_000_000))))
        assertTrue(enough.storageFull?.canResume == true)
        val resume = reduce(enough, MainEvent.StorageFullResume)
        assertNull(resume.state.storageFull)
        assertEquals(listOf(MainEffect.ResumeRecording), resume.effects)
    }

    @Test fun aTypedErrIsAFailureNotAFreedZero() {
        val afterErr = freeing(Cleanup.LocalDatabase).on(freed(failed = mapOf(Cleanup.LocalDatabase to busy)))
        val failed = dialog(afterErr)
        val ui = storageFullUi(failed)
        // no "Freed 0 B": nothing succeeded
        assertNull(ui.freedBytes)
        assertEquals(listOf(CleanupFailure(Cleanup.LocalDatabase, "the database is busy")), ui.failures)
        // its switch stays on, so Free up space retries it
        assertEquals(setOf(Cleanup.LocalDatabase), failed.chosen)
        assertEquals(CleanupStep.Choosing(CleanupOutcome(emptyMap(), mapOf(Cleanup.LocalDatabase to busy))), failed.step)
        assertEquals(listOf(MainEffect.FreeUpSpace(setOf(Cleanup.LocalDatabase))), reduce(afterErr, MainEvent.FreeUpSpace).effects)
    }

    @Test fun oneSucceedsAndOneFailsShowsBoth() {
        val step = reduce(
            freeing(Cleanup.UploadedRaw, Cleanup.LocalDatabase),
            freed(Cleanup.UploadedRaw to Bytes(300_000_000), failed = mapOf(Cleanup.LocalDatabase to CleanupError.Unavailable)),
        )
        val d = dialog(step.state)
        val ui = storageFullUi(d)
        assertEquals(300_000_000L, ui.freedBytes)
        assertEquals(listOf(CleanupFailure(Cleanup.LocalDatabase, "not available in this version")), ui.failures)
        assertEquals(setOf(Cleanup.LocalDatabase), d.chosen)
        // the database was not cleared, so the launch view does not reopen
        assertEquals(emptyList<MainEffect>(), step.effects)
    }

    @Test fun clearingTheDatabaseReopensTheLaunchView() {
        assertEquals(listOf(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts),
            reduce(freeing(Cleanup.LocalDatabase), freed(Cleanup.LocalDatabase to Bytes(1))).effects)
    }

    @Test fun notNowAndBackCloseIt() {
        assertNull(opened.on(MainEvent.StorageFullNotNow).storageFull)
        val withPopover = opened.copy(locationPopoverOpen = true)
        val back = reduce(withPopover, MainEvent.Back(pushedSheet = true, sheetSearching = true))
        assertNull(back.state.storageFull)
        assertEquals(true, back.state.locationPopoverOpen)
        assertEquals(emptyList<MainEffect>(), back.effects)
    }

    @Test fun aThrowFailsEveryCleanupOfTheRun() {
        val run = freeing(Cleanup.UploadedRaw, Cleanup.LocalDatabase)
        val effect = MainEffect.FreeUpSpace(setOf(Cleanup.UploadedRaw, Cleanup.LocalDatabase))
        val d = dialog(run.on(MainEvent.Failed(MainTask.Effect(effect), IllegalStateException("x"))))
        assertEquals(setOf(Cleanup.UploadedRaw, Cleanup.LocalDatabase), d.chosen)
        assertEquals(listOf(CleanupFailure(Cleanup.UploadedRaw, "x"), CleanupFailure(Cleanup.LocalDatabase, "x")), storageFullUi(d).failures)
        assertNull(storageFullUi(d).freedBytes)
        assertEquals(ReclaimRead.Failed(CleanupError.Failed("x")), opened.on(MainEvent.Failed(MainTask.Effect(MainEffect.LoadReclaimable), IllegalStateException("x"))).storageFull?.reclaim)
    }

    @Test fun aFailedReadShowsItsReasonAndNoSizes() {
        val failed = dialog(opened.on(MainEvent.ReclaimableRead(CleanupResult.Err(busy))))
        val ui = storageFullUi(failed)
        assertEquals("the database is busy", ui.readFailure)
        assertNull(ui.reclaimable)
        assertEquals(false, ui.checking)
        // the next good read clears it
        assertNull(storageFullUi(dialog(opened.on(MainEvent.ReclaimableRead(CleanupResult.Err(busy)), MainEvent.ReclaimableRead(CleanupResult.Ok(reclaim))))).readFailure)
    }

    /** A cleanup that worked, then a follow-up read that failed: the old free space stays, and why is on screen. */
    @Test fun aFailedFollowUpReadExplainsTheFreeSpace() {
        val run = freeing(Cleanup.UploadedRaw)
        val after = dialog(run.on(MainEvent.SpaceFreed(CleanupOutcome(mapOf(Cleanup.UploadedRaw to Bytes(300_000_000)), emptyMap()), CleanupResult.Err(busy))))
        val ui = storageFullUi(after)
        assertEquals(low, after.free)
        assertEquals(false, ui.canResume)
        assertEquals(300_000_000L, ui.freedBytes)
        assertEquals("the database is busy", ui.readFailure)
    }

    @Test fun settingsShowsAFailedRead() {
        val st = MainUiState(scope = sessionA).on(MainEvent.OpenSettings, MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)),
            MainEvent.ReclaimableRead(CleanupResult.Ok(reclaim)), MainEvent.ReclaimableRead(CleanupResult.Err(CleanupError.Unavailable)))
        assertNull(st.storage?.reclaimable)
        assertEquals("not available in this version", st.storage?.reclaimFailure)
        // a re-read of the storage figures keeps it; a good read clears it
        assertEquals("not available in this version", st.on(MainEvent.StorageLoaded(StorageFacts(1, 1, null, 0))).storage?.reclaimFailure)
        assertNull(st.on(MainEvent.ReclaimableRead(CleanupResult.Ok(reclaim))).storage?.reclaimFailure)
    }

    private val settings = MainUiState(scope = sessionA).on(MainEvent.OpenSettings, MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)), MainEvent.ReclaimableRead(CleanupResult.Ok(reclaim)))

    @Test fun settingsOffersTheSameCleanupsWithAConfirmation() {
        assertEquals(Reclaimable(300_000_000, 40_000_000), settings.storage?.reclaimable)
        val asked = settings.on(MainEvent.CleanupRequested(Cleanup.UploadedRaw))
        assertEquals(Cleanup.UploadedRaw, asked.storage?.cleanupConfirm)
        assertNull(asked.on(MainEvent.CleanupCancelled).storage?.cleanupConfirm)
        val run = reduce(asked, MainEvent.CleanupConfirmed)
        assertEquals(true, run.state.storage?.cleaning)
        assertEquals(listOf(MainEffect.FreeUpSpace(setOf(Cleanup.UploadedRaw))), run.effects)
        val done = reduce(run.state, freed(Cleanup.UploadedRaw to Bytes(300_000_000), after = reclaim.copy(uploadedRaw = Bytes(0))))
        assertEquals(false, done.state.storage?.cleaning)
        assertEquals(300_000_000L, done.state.storage?.cleanupFreed)
        assertNull(done.state.storage?.cleanupFailure)
        assertEquals(0L, done.state.storage?.reclaimable?.uploadedRawBytes)
        assertEquals(listOf(MainEffect.LoadStorage), done.effects)
        // a storage re-read keeps what the cleanup showed
        assertEquals(300_000_000L, done.state.on(MainEvent.StorageLoaded(StorageFacts(1, 1, null, 0))).storage?.cleanupFreed)
        assertEquals(Scope.All, MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0))).storage?.exportScope)
    }

    @Test fun settingsShowsATypedErrAsAFailure() {
        val run = settings.on(MainEvent.CleanupRequested(Cleanup.LocalDatabase), MainEvent.CleanupConfirmed)
        val done = run.on(freed(failed = mapOf(Cleanup.LocalDatabase to busy)))
        assertEquals(false, done.storage?.cleaning)
        assertNull(done.storage?.cleanupFreed)
        assertEquals(CleanupFailure(Cleanup.LocalDatabase, "the database is busy"), done.storage?.cleanupFailure)
        // the failure survives a storage re-read, and the next run starts clean
        assertEquals(CleanupFailure(Cleanup.LocalDatabase, "the database is busy"), done.on(MainEvent.StorageLoaded(StorageFacts(1, 1, null, 0))).storage?.cleanupFailure)
        assertNull(done.on(MainEvent.CleanupRequested(Cleanup.LocalDatabase), MainEvent.CleanupConfirmed).storage?.cleanupFailure)
    }

    /** The runner's loop: each Err stays with its cleanup, is never counted as freed, and does not stop the next. */
    @Test fun runKeepsEachErrWithItsCleanup() = runTest {
        val calls = mutableListOf<Cleanup>()
        val port = object : StorageCleanup {
            override val storageFull: Flow<Bytes> = emptyFlow()
            override val storageFailed: Flow<String> = emptyFlow()
            override suspend fun reclaimable(): CleanupResult<Reclaim> = CleanupResult.Ok(reclaim)
            override suspend fun deleteUploadedRaw(): CleanupResult<Bytes> = CleanupResult.Err(busy).also { calls += Cleanup.UploadedRaw }
            override suspend fun clearLocalDatabase(): CleanupResult<Bytes> = CleanupResult.Ok(Bytes(40)).also { calls += Cleanup.LocalDatabase }
        }
        val outcome = port.run(setOf(Cleanup.LocalDatabase, Cleanup.UploadedRaw))
        assertEquals(listOf(Cleanup.UploadedRaw, Cleanup.LocalDatabase), calls)
        assertEquals(CleanupOutcome(mapOf(Cleanup.LocalDatabase to Bytes(40)), mapOf(Cleanup.UploadedRaw to busy)), outcome)
        assertEquals(Bytes(40), outcome.freedTotal)
        assertNull(port.run(setOf(Cleanup.UploadedRaw)).freedTotal)
    }
}

/**
 * R-10 with the recorder's own pause (`RecorderState.pause`): the dialog closes as soon as the storage pause ends,
 * whether by Resume in the app, Resume from the notification or Stop, and opens on it if the core's event hasn't.
 * The events are the ones the runner derives from [FakeRecorder.state].
 */
class MainStoragePauseTest {
    private val fake = edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeRecorder(onStart = { "a" }, onStop = { "a" }, nowMs = { 0L })

    /** What the runner dispatches for the recorder's current state. */
    private fun MainUiState.follow(): MainUiState = fake.state.value.let { on(MainEvent.RecorderChanged(it.phase, it.pause)) }

    private suspend fun recording(): MainUiState {
        fake.start()
        return MainUiState(scope = sessionA).follow()
    }

    @Test fun aStoragePauseOpensTheDialogAndAResumeClosesIt() = runTest {
        val rec = recording()
        fake.storageFull(150_000_000)
        val step = reduce(rec, fake.state.value.let { MainEvent.RecorderChanged(it.phase, it.pause) })
        assertEquals(Bytes(150_000_000), step.state.storageFull?.free)
        assertEquals(RecorderMirror.Active(panelOpen = false, pausedForStorage = true), step.state.recorder)
        assertEquals(listOf(MainEffect.LoadReclaimable), step.effects)
        // a Resume from the notification: the app did nothing, the recorder's state is all it sees
        fake.resume()
        val resumed = step.state.follow()
        assertNull(resumed.storageFull)
        assertEquals(RecorderMirror.Active(panelOpen = false, pausedForStorage = false), resumed.recorder)
    }

    @Test fun aStopEndsTheStoragePauseAndClosesTheDialog() = runTest {
        val rec = recording()
        fake.storageFull(150_000_000)
        val paused = rec.follow()
        fake.stop()
        val stopped = reduce(paused, fake.state.value.let { MainEvent.RecorderChanged(it.phase, it.pause) })
        assertNull(stopped.state.storageFull)
        assertEquals(RecorderMirror.Idle, stopped.state.recorder)
        // V-3 still re-reads the ended session
        assertEquals(paused.epoch.next(), stopped.state.epoch)
    }

    @Test fun theCoresEventOpensItFirstAndThePauseKeepsIt() = runTest {
        val rec = recording().on(MainEvent.StorageFull(Bytes(120_000_000)))
        fake.storageFull(150_000_000)
        val paused = rec.follow()
        // already open: the pause doesn't reopen it or reset its state
        assertEquals(rec.storageFull, paused.storageFull)
        fake.resume()
        assertNull(paused.follow().storageFull)
    }

    @Test fun aUserPauseNeitherOpensNorClosesIt() = runTest {
        val rec = recording()
        fake.pause()
        assertNull(rec.follow().storageFull)
        // the dialog the core's event opened stays through a pause that is not the storage one
        val open = rec.on(MainEvent.StorageFull(Bytes(1)))
        fake.resume()
        assertEquals(open.storageFull, open.follow().storageFull)
    }

    @Test fun thePanelKeepsTheStoragePause() = runTest {
        val rec = recording()
        fake.storageFull(150_000_000)
        val paused = rec.follow().on(MainEvent.SetRecorderPanel(true), MainEvent.SetRecorderPanel(false))
        assertEquals(RecorderMirror.Active(panelOpen = false, pausedForStorage = true), paused.recorder)
    }
}

/**
 * #79: storing kept failing (not for space), so the core stopped writing and the recording paused. It shows as a
 * paused recording with the failure's words and Resume, on the R-10 notice path; the events come from
 * [FakeRecorder.storageFailed] as the runner derives them.
 */
class MainStorageFailedTest {
    private val fake = edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeRecorder(onStart = { "a" }, onStop = { "a" }, nowMs = { 0L })
    private val failure = "local database failed: disk I/O error"

    private fun MainUiState.follow(): MainUiState = fake.state.value.let { on(MainEvent.RecorderChanged(it.phase, it.pause)) }

    private suspend fun recording(): MainUiState {
        fake.start()
        return MainUiState(scope = sessionA).follow()
    }

    @Test fun theFailurePauseShowsItsWordsAndResume() = runTest {
        val rec = recording()
        fake.storageFailed(failure)
        val paused = rec.follow()
        val n = paused.notice ?: error("the notice shows")
        assertEquals("Recording paused: saving keeps failing ($failure)", n.message)
        assertEquals(MainEffect.ResumeRecording, n.retry)
        assertEquals("Resume", n.retryLabel)
        assertEquals(RecorderMirror.Active(panelOpen = false, pausedForFailure = true), paused.recorder)
        // Resume on the notice asks the recorder to resume, which makes the core try again
        val resume = reduce(paused, MainEvent.RetryNotice)
        assertNull(resume.state.notice)
        assertEquals(listOf(MainEffect.ResumeRecording), resume.effects)
        // and the storage-full dialog is not involved
        assertNull(paused.storageFull)
    }

    @Test fun aResumeFromTheNotificationOrAStopClosesIt() = runTest {
        val rec = recording()
        fake.storageFailed(failure)
        val paused = rec.follow()
        fake.resume()
        assertNull(paused.follow().notice)
        fake.storageFailed(failure)
        val again = paused.follow().follow()
        assertEquals(true, again.notice?.storagePause)
        fake.stop()
        assertNull(again.follow().notice)
    }

    @Test fun theCoresEventShowsItAndThePauseKeepsIt() = runTest {
        val rec = recording().on(MainEvent.StorageFailed(failure))
        assertEquals(storageFailedNotice(failure), rec.notice)
        fake.storageFailed(failure)
        val paused = rec.follow()
        assertEquals(storageFailedNotice(failure), paused.notice)
        fake.resume()
        assertNull(paused.follow().notice)
    }

    @Test fun anotherNoticeThatCameMeanwhileStays() = runTest {
        val rec = recording()
        fake.storageFailed(failure)
        val other = Notice("Export failed: disk full")
        val paused = rec.follow().copy(notice = other)
        fake.resume()
        assertEquals(other, paused.follow().notice)
    }

    @Test fun aUserPauseShowsNoNotice() = runTest {
        val rec = recording()
        fake.pause()
        assertNull(rec.follow().notice)
    }
}
