package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-1b: the Discover view is the user's remembered choice; search's forced list (Q-5) never becomes it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewRememberedTest {
    private val ready = DiscoverState(Liveness.Frozen, phase = DiscoverPhase.Ready(KeyOrder.of(longArrayOf(3, 1, 2))))
    private val back = SearchReturn(DiscoverView.Categories, SheetHeight.Half, PanelHeight.Open, ScrollSnapshot())

    @Test
    fun `D-1 a tap on a view tab switches the view and tells app to remember it, S-12 raises`() {
        val toList = reduceDiscover(ready, DiscoverEvent.ViewSelected(DiscoverView.List))
        assertEquals(DiscoverView.List, toList.state.view)
        assertEquals(listOf(DiscoverEffect.RaiseRoot, DiscoverEffect.ViewChosen(DiscoverView.List)), toList.effects)
        val back = reduceDiscover(toList.state, DiscoverEvent.ViewSelected(DiscoverView.Categories))
        assertEquals(DiscoverEffect.ViewChosen(DiscoverView.Categories), back.effects.last())
        // the current tab: nothing changes or is remembered, but S-12 still raises a Collapsed root
        val same = reduceDiscover(toList.state, DiscoverEvent.ViewSelected(DiscoverView.List))
        assertSame(toList.state, same.state)
        assertEquals(listOf(DiscoverEffect.RaiseRoot), same.effects)
    }

    @Test
    fun `D-1b the remembered view opens a session view, and a later read restores it without being remembered again`() {
        assertEquals(DiscoverView.List, DiscoverState(Liveness.Frozen, view = DiscoverView.List).view)
        val restored = reduceDiscover(ready, DiscoverEvent.ViewRestored(DiscoverView.List))
        assertEquals(ready.copy(view = DiscoverView.List), restored.state)
        assertTrue(restored.effects.isEmpty())
        assertSame(ready, reduceDiscover(ready, DiscoverEvent.ViewRestored(DiscoverView.Categories)).state)
    }

    @Test
    fun `D-1b Q-5 search forces the list but never chooses it, and a restore during search is where search returns`() {
        val searching = reduceDiscover(
            ready.copy(searchVersion = 1),
            DiscoverEvent.SearchLoaded(1, "qt", null, SearchPurpose.Enter(CameraSnapshot(GeoPoint(1.0, 1.0), 15.0), back), DeviceKeys(longArrayOf(1), 0), 0),
        )
        assertEquals(DiscoverView.List, searching.state.view)
        assertTrue(searching.effects.none { it is DiscoverEffect.ViewChosen })
        // no tabs in search: a tap changes nothing, remembers nothing and raises nothing
        for (v in DiscoverView.entries) {
            val tap = reduceDiscover(searching.state, DiscoverEvent.ViewSelected(v))
            assertSame(searching.state, tap.state)
            assertTrue(tap.effects.isEmpty())
        }
        // a restore during search is the view search returns to; the forced list stays
        val restored = reduceDiscover(searching.state, DiscoverEvent.ViewRestored(DiscoverView.List)).state
        assertEquals(DiscoverView.List, restored.searching?.back?.view)
        val exited = reduceDiscover(restored, DiscoverEvent.SearchExited)
        assertEquals(DiscoverView.List, exited.state.view)
        assertTrue(exited.effects.none { it is DiscoverEffect.ViewChosen })
        // and without a restore, search returns to the view it was entered from
        assertEquals(DiscoverView.Categories, reduceDiscover(searching.state, DiscoverEvent.SearchExited).state.view)
    }

    @Test
    fun `D-1b a search session through SheetsState emits no view choice, only the user's tab does`() = runTest {
        val core = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
        val chosen = mutableListOf<SheetsEvent>()
        val s = SheetsState(
            core.initialScope!!, live = false, routesFaint = false, core = core,
            gattRunner = FakeGattRunner({ FakeGattDevice(null, true) }), map = null, coroutines = this,
            nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
            view = DiscoverView.Categories, onEvent = { chosen += it },
        )
        s.stack.setScreen(915f, 1f, 0f)
        s.open()
        s.enterSearch("a", null, CameraSnapshot(GeoPoint(-117.23, 32.88), 15.0), picked = null)
        advanceUntilIdle()
        assertEquals(DiscoverView.List, s.discover.view) // forced (Q-5)
        s.exitSearch()
        advanceUntilIdle()
        assertEquals(DiscoverView.Categories, s.discover.view)
        assertTrue(chosen.isEmpty()) // the saved choice was never touched
        s.discover.selectView(DiscoverView.List)
        assertEquals(listOf<SheetsEvent>(SheetsEvent.ViewChosen(DiscoverView.List)), chosen)
    }
}
