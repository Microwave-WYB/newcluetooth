package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import edu.ucsd.sysnet.cluetoothscanner.sheets.DiscoverView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** D-1b: the Discover view is remembered as UI state (not a setting), across session views and restarts. */
class DiscoverViewRememberedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theUsersSwitchIsRememberedAndSaved_aRepeatSavesNothing() {
        val s = MainUiState()
        assertEquals(DiscoverView.Categories, s.discoverView.view) // first launch
        val chosen = reduce(s, MainEvent.DiscoverViewChosen(DiscoverView.List))
        assertEquals(RememberedView.Known(DiscoverView.List), chosen.state.discoverView)
        assertEquals(listOf(MainEffect.SaveDiscoverView(DiscoverView.List)), chosen.effects)
        assertEquals(Step(chosen.state), reduce(chosen.state, MainEvent.DiscoverViewChosen(DiscoverView.List)))
    }

    @Test
    fun theStoredViewIsReadAtLaunch_andANewSessionViewKeepsIt() {
        val read = reduce(MainUiState(), MainEvent.UiStateRead(DiscoverView.List)).state
        assertEquals(RememberedView.Known(DiscoverView.List), read.discoverView)
        // V-7: another session view resets search and the sidebar, never the remembered view
        assertEquals(DiscoverView.List, reduce(read, MainEvent.SelectScope(edu.ucsd.sysnet.cluetoothscanner.api.core.Scope.All)).state.discoverView.view)
    }

    @Test
    fun aChoiceBeforeTheLaunchReadComesBackWins() {
        // a slow disk: the user switches to List, then the read of an older Categories lands
        val chosen = reduce(MainUiState(), MainEvent.DiscoverViewChosen(DiscoverView.List)).state
        val late = reduce(chosen, MainEvent.UiStateRead(DiscoverView.Categories))
        assertEquals(Step(chosen), late)
        assertEquals(DiscoverView.List, late.state.discoverView.view)
    }

    @Test
    fun aFailedWriteOrReadIsOnlyLogged_noNotice_theViewStays() {
        val s = MainUiState(discoverView = RememberedView.Known(DiscoverView.List))
        for (effect in listOf(MainEffect.SaveDiscoverView(DiscoverView.List), MainEffect.LoadUiState)) {
            val after = reduce(s, MainEvent.Failed(MainTask.Effect(effect), IllegalStateException("disk full")))
            assertEquals(Step(s), after)
            assertNull(after.state.notice)
        }
    }

    /**
     * One owner per file at a time: [block] gets a store on its own scope, which is then cancelled and joined.
     * DataStore releases its file only when the scope's job completes (the write actor closes the connection in a
     * completion handler); a plain `cancel()` returns before that if one of its coroutines is still winding down, and
     * the next owner of the same file then fails with "multiple DataStores active for the same file".
     */
    private suspend fun <T> withStore(file: File, block: suspend (DataStoreUiState) -> T): T {
        val job = SupervisorJob()
        try {
            return block(DataStoreUiState(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun persistenceRoundTrip_acrossARestart() = runBlocking {
        val file = File(tmp.root, "${DataStoreUiState.FILE}.preferences_pb")
        withStore(file) { a ->
            assertEquals(UiStateResult.Ok(DiscoverView.Categories), a.discoverView()) // nothing saved yet: first launch
            assertEquals(UiStateResult.Ok(Unit), a.saveDiscoverView(DiscoverView.List))
        } // the process ends: its store has let go of the file
        assertEquals(UiStateResult.Ok(DiscoverView.List), withStore(file) { it.discoverView() }) // after the restart
    }

    /** The restart, many times over: each owner lets go of the file before the next one opens it. */
    @Test
    fun restartsNeverOverlapTheirOwners() = runBlocking {
        val file = File(tmp.root, "restarts.preferences_pb")
        val views = DiscoverView.entries
        repeat(50) { i ->
            val view = views[i % views.size]
            withStore(file) { assertEquals(UiStateResult.Ok(Unit), it.saveDiscoverView(view)) }
            assertEquals(UiStateResult.Ok(view), withStore(file) { it.discoverView() })
        }
    }

    @Test
    fun aBrokenFileIsATypedError_notAThrow() = runBlocking {
        val file = File(tmp.root, "broken.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7)) }
        assertTrue(withStore(file) { it.discoverView() } is UiStateResult.Err)
    }
}
