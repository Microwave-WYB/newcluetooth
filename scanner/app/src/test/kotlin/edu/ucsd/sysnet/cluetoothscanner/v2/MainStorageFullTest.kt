package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.chrome.Cleanup
import edu.ucsd.sysnet.cluetoothscanner.chrome.Reclaimable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-10 storage full: the dialog's machine, and the same cleanups in Settings › Storage (O-1 item 11). */
class MainStorageFullTest {
    private val low = Bytes(150_000_000)
    private val reclaim = Reclaim(low, uploadedRaw = Bytes(300_000_000), localDatabase = Bytes(40_000_000))
    private val opened = MainUiState(scope = sessionA).on(MainEvent.StorageFull(low))

    @Test fun storageFullOpensTheDialogWithBothSwitchesOff() {
        val step = reduce(MainUiState(), MainEvent.StorageFull(low))
        assertEquals(StorageFullState(low, ReclaimRead.Reading, emptySet(), CleanupStep.Choosing(null)), step.state.storageFull)
        assertEquals(listOf(MainEffect.LoadReclaimable), step.effects)
        val ui = storageFullUi(opened.on(MainEvent.ReclaimableRead(reclaim)).storageFull ?: error("open"))
        assertEquals(150_000_000L, ui.freeBytes)
        assertEquals(Reclaimable(300_000_000, 40_000_000), ui.reclaimable)
        assertEquals(false, ui.canResume)
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
        val freeing = opened.on(MainEvent.ChooseCleanup(Cleanup.UploadedRaw, true), MainEvent.FreeUpSpace)
        val stillLow = freeing.on(MainEvent.SpaceFreed(setOf(Cleanup.UploadedRaw), Bytes(20_000_000), reclaim.copy(free = Bytes(170_000_000))))
        val d = stillLow.storageFull ?: error("stays open")
        assertEquals(20_000_000L, storageFullUi(d).freedBytes)
        assertEquals(false, d.canResume)
        assertEquals(emptySet<Cleanup>(), d.chosen)
        // below the floor Resume does nothing: the dialog stays and the recording stays paused
        assertEquals(Step(stillLow), reduce(stillLow, MainEvent.StorageFullResume))
        val enough = freeing.on(MainEvent.SpaceFreed(setOf(Cleanup.UploadedRaw), Bytes(300_000_000), reclaim.copy(free = Bytes(450_000_000))))
        assertTrue(enough.storageFull?.canResume == true)
        val resume = reduce(enough, MainEvent.StorageFullResume)
        assertNull(resume.state.storageFull)
        assertEquals(listOf(MainEffect.ResumeRecording), resume.effects)
    }

    @Test fun clearingTheDatabaseReopensTheLaunchView() {
        val freeing = opened.on(MainEvent.ChooseCleanup(Cleanup.LocalDatabase, true), MainEvent.FreeUpSpace)
        assertEquals(listOf(MainEffect.LoadSessions, MainEffect.ReadLaunchFacts),
            reduce(freeing, MainEvent.SpaceFreed(setOf(Cleanup.LocalDatabase), Bytes(1), null)).effects)
    }

    @Test fun notNowAndBackCloseIt() {
        assertNull(opened.on(MainEvent.StorageFullNotNow).storageFull)
        val withPopover = opened.copy(locationPopoverOpen = true)
        val back = reduce(withPopover, MainEvent.Back(pushedSheet = true, sheetSearching = true))
        assertNull(back.state.storageFull)
        assertEquals(true, back.state.locationPopoverOpen)
        assertEquals(emptyList<MainEffect>(), back.effects)
    }

    @Test fun aFailedCleanupGoesBackToChoosing() {
        val freeing = opened.on(MainEvent.ChooseCleanup(Cleanup.UploadedRaw, true), MainEvent.FreeUpSpace)
        val failed = freeing.on(MainEvent.Failed(MainTask.Effect(MainEffect.FreeUpSpace(setOf(Cleanup.UploadedRaw))), IllegalStateException("x")))
        assertEquals(CleanupStep.Choosing(null), failed.storageFull?.step)
        assertEquals(ReclaimRead.Unknown, opened.on(MainEvent.Failed(MainTask.Effect(MainEffect.LoadReclaimable), IllegalStateException("x"))).storageFull?.reclaim)
    }

    @Test fun settingsOffersTheSameCleanupsWithAConfirmation() {
        val settings = MainUiState(scope = sessionA).on(MainEvent.OpenSettings, MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0)), MainEvent.ReclaimableRead(reclaim))
        assertEquals(Reclaimable(300_000_000, 40_000_000), settings.storage?.reclaimable)
        val asked = settings.on(MainEvent.CleanupRequested(Cleanup.UploadedRaw))
        assertEquals(Cleanup.UploadedRaw, asked.storage?.cleanupConfirm)
        assertNull(asked.on(MainEvent.CleanupCancelled).storage?.cleanupConfirm)
        val run = reduce(asked, MainEvent.CleanupConfirmed)
        assertEquals(true, run.state.storage?.cleaning)
        assertEquals(listOf(MainEffect.FreeUpSpace(setOf(Cleanup.UploadedRaw))), run.effects)
        val done = reduce(run.state, MainEvent.SpaceFreed(setOf(Cleanup.UploadedRaw), Bytes(300_000_000), reclaim.copy(uploadedRaw = Bytes(0))))
        assertEquals(false, done.state.storage?.cleaning)
        assertEquals(300_000_000L, done.state.storage?.cleanupFreed)
        assertEquals(0L, done.state.storage?.reclaimable?.uploadedRawBytes)
        assertEquals(listOf(MainEffect.LoadStorage), done.effects)
        // a storage re-read keeps what the cleanup showed
        assertEquals(300_000_000L, done.state.on(MainEvent.StorageLoaded(StorageFacts(1, 1, null, 0))).storage?.cleanupFreed)
        assertEquals(Scope.All, MainUiState().on(MainEvent.StorageLoaded(StorageFacts(0, 0, null, 0))).storage?.exportScope)
    }
}
