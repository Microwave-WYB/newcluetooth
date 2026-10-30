package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import edu.ucsd.sysnet.cluetoothscanner.sheets.DiscoverView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        assertEquals(DiscoverView.Categories, s.discoverView) // first launch
        val chosen = reduce(s, MainEvent.DiscoverViewChosen(DiscoverView.List))
        assertEquals(DiscoverView.List, chosen.state.discoverView)
        assertEquals(listOf(MainEffect.SaveDiscoverView(DiscoverView.List)), chosen.effects)
        assertEquals(Step(chosen.state), reduce(chosen.state, MainEvent.DiscoverViewChosen(DiscoverView.List)))
    }

    @Test
    fun theStoredViewIsReadAtLaunch_andANewSessionViewKeepsIt() {
        val read = reduce(MainUiState(), MainEvent.UiStateRead(DiscoverView.List)).state
        assertEquals(DiscoverView.List, read.discoverView)
        // V-7: another session view resets search and the sidebar, never the remembered view
        assertEquals(DiscoverView.List, reduce(read, MainEvent.SelectScope(edu.ucsd.sysnet.cluetoothscanner.api.core.Scope.All)).state.discoverView)
    }

    @Test
    fun aFailedWriteOrReadIsOnlyLogged_noNotice_theViewStays() {
        val s = MainUiState(discoverView = DiscoverView.List)
        for (effect in listOf(MainEffect.SaveDiscoverView(DiscoverView.List), MainEffect.LoadUiState)) {
            val after = reduce(s, MainEvent.Failed(MainTask.Effect(effect), IllegalStateException("disk full")))
            assertEquals(Step(s), after)
            assertNull(after.state.notice)
        }
    }

    private fun store(file: File, scope: CoroutineScope) =
        DataStoreUiState(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun persistenceRoundTrip_acrossARestart() = runBlocking {
        val file = File(tmp.root, "${DataStoreUiState.FILE}.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val a = store(file, first)
        assertEquals(UiStateResult.Ok(DiscoverView.Categories), a.discoverView()) // nothing saved yet: first launch
        assertEquals(UiStateResult.Ok(Unit), a.saveDiscoverView(DiscoverView.List))
        first.cancel() // the process ends
        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        assertEquals(UiStateResult.Ok(DiscoverView.List), store(file, second).discoverView()) // after the restart
        second.cancel()
    }

    @Test
    fun aBrokenFileIsATypedError_notAThrow() = runBlocking {
        val file = File(tmp.root, "broken.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7)) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        assertTrue(store(file, scope).discoverView() is UiStateResult.Err)
        scope.cancel()
    }
}
