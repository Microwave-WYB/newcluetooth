package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * R-7: Stop does nothing to the sheets; only the live-only parts end. The sheets see a recording through their live
 * flag; :core-fake's history session gives the devices (its Recording demo has none without the simulation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopKeepsSheetsTest {
    private suspend fun TestScope.recording(): Pair<SheetsState, FakeCore> {
        val core = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
        val s = SheetsState(
            core.initialScope!!, live = true, routesFaint = false, core = core,
            gattRunner = FakeGattRunner({ FakeGattDevice(null, true) }), map = null, coroutines = this,
            nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
        )
        s.stack.setScreen(915f, 1f, 0f)
        s.open()
        return s to core
    }

    /** The session order (G-9). */
    private fun SheetsState.order(): LongArray = (discover.phase as? DiscoverPhase.Ready)?.order?.toLongArray() ?: LongArray(0)

    @Test
    fun `R-7 Stop with a device sheet at Full and the list scrolled changes only the live-only parts`() = runTest {
        val (s, _) = recording()
        assertTrue(s.live)
        assertEquals(Segment.Nearby, s.discover.segment)
        s.discover.toggleView() // the list
        s.discover.setFilter(DeviceFilter(readable = true))
        advanceUntilIdle()
        s.stack.root.scroll = ScrollSnapshot(37, 12)
        val key = s.order().first() // Nearby has no rows without the simulation
        val device = s.stack.openDevice(key)
        s.stack.setHeight(device, SheetHeight.Full)
        device.scroll = ScrollSnapshot(3, 40)
        advanceUntilIdle()
        val stack = s.stack.state
        val order = s.order()
        val view = s.discover.view
        val filter = s.discover.filter

        s.setLive(false) // Stop: the same session, now frozen
        advanceUntilIdle()

        // the sheets as they were: the same stack value (levels, heights, panels, cameras), scrolls, view, filters, order
        assertSame(stack, s.stack.state)
        assertEquals(SheetHeight.Full, s.stack.top.height)
        assertSame(device, s.stack.top)
        assertEquals(ScrollSnapshot(37, 12), s.stack.root.scroll)
        assertEquals(ScrollSnapshot(3, 40), device.scroll)
        assertEquals(view, s.discover.view)
        assertEquals(filter, s.discover.filter)
        assertArrayEquals(order, s.order())
        // only the live-only parts: not live (no sparklines, no chart), Nearby gone (D-1), no more appends
        assertFalse(s.live)
        assertEquals(Segment.All, s.discover.segment)
        val appends = s.discover.liveAppends
        s.discover.appendLive(longArrayOf(9_999_999))
        assertEquals(appends, s.discover.liveAppends)
        assertArrayEquals(order, s.order())
    }

    @Test
    fun `R-7 random stacks - Stop never changes the stack or the heights`() = runTest {
        for (seed in 1..12) {
            val rnd = Random(seed)
            val (s, core) = recording()
            val keys = s.order().toList()
            val pages = core.products(s.scope, DeviceFilter()).flatMap { it.products }.map { it.id }
            val stopAt = rnd.nextInt(5, 30)
            repeat(40) { step ->
                when (rnd.nextInt(7)) {
                    0, 1 -> s.stack.openDevice(keys.random(rnd))
                    2 -> s.openProduct(pages.random(rnd))
                    3 -> if (s.stack.top === s.stack.root) s.openFilter()
                    4 -> s.stack.setHeight(s.stack.top, listOf(SheetHeight.Collapsed, SheetHeight.Half, SheetHeight.Full).random(rnd))
                    5 -> s.stack.pop(PopReason.Back)
                    else -> s.stack.setLayout(rnd.nextInt(6) == 0)
                }
                advanceUntilIdle()
                if (step == stopAt) {
                    val stack = s.stack.state
                    val heights = s.stack.entries.map { it.id to it.height }
                    s.setLive(false)
                    advanceUntilIdle()
                    assertSame("seed $seed", stack, s.stack.state)
                    assertEquals("seed $seed", heights, s.stack.entries.map { it.id to it.height })
                }
            }
            assertFalse(s.live)
        }
    }
}

/** R-7 through rememberSheetsState: live → frozen keeps the same sheets, and the device sheet's open tab with them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class StopKeepsSheetsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun v7_aDifferentScopeBuildsFreshSheets_liveReadsAndStopKeepThem() {
        var scope by mutableStateOf<Scope?>(null)
        var live by mutableStateOf<Boolean?>(null) // not read yet
        val made = mutableListOf<SheetsState>()
        compose.setContent {
            val coroutines = rememberCoroutineScope()
            val core = androidx.compose.runtime.remember {
                FakeCore(coroutines, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
            }
            val shown = scope ?: core.initialScope ?: return@setContent
            CluetoothTheme(darkTheme = false, reducedMotion = true) {
                val state = rememberSheetsState(
                    shown, live, routesFaint = false, core = core, gatt = FakeGattRunner({ FakeGattDevice(null, true) }),
                    map = null, nowMs = { FakeCore.DEMO_NOW_MS }, reducedMotion = true,
                )
                if (made.lastOrNull() !== state) made += state
                SheetsLayer(state, tablet = false)
            }
        }
        compose.waitForIdle()
        val first = made.single()
        assertEquals(Liveness.Pending, first.discover.liveness)
        live = true // the view read: live
        compose.waitForIdle()
        live = false // Stop
        compose.waitForIdle()
        assertSame(first, made.single())
        assertEquals(Liveness.Frozen, first.discover.liveness)
        scope = Scope.All // V-7: another session view
        live = null
        compose.waitForIdle()
        assertEquals(2, made.size)
        assertTrue(made[1] !== first)
        assertEquals(Liveness.Pending, made[1].discover.liveness)
    }

    @Test
    fun r7_stopKeepsTheSameSheetsAndTheOpenTab() {
        var live by mutableStateOf(true)
        val made = mutableListOf<SheetsState>()
        compose.setContent {
            val coroutines = rememberCoroutineScope()
            val core = androidx.compose.runtime.remember {
                FakeCore(coroutines, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
            }
            val scope = core.initialScope ?: return@setContent
            CluetoothTheme(darkTheme = false, reducedMotion = true) {
                val state = rememberSheetsState(
                    scope, live, routesFaint = false, core = core, gatt = FakeGattRunner({ FakeGattDevice(null, true) }),
                    map = null, nowMs = { FakeCore.DEMO_NOW_MS }, reducedMotion = true,
                )
                if (made.lastOrNull() !== state) made += state
                SheetsLayer(state, tablet = false)
            }
        }
        compose.waitForIdle()
        val s = made.single()
        compose.runOnIdle { s.stack.openDevice((s.discover.phase as DiscoverPhase.Ready).order.toLongArray().first()) }
        compose.waitForIdle()
        compose.runOnIdle { s.stack.setHeight(s.stack.top, SheetHeight.Full) }
        compose.onNodeWithText(Strings.Gatt).performClick() // the GATT tab
        compose.waitForIdle()
        compose.onNodeWithText(Strings.DiscoverGatt).assertExists()
        val stack = s.stack.state

        live = false // Stop
        compose.waitForIdle()

        assertSame(s, made.single()) // the same sheets, not new ones
        assertSame(stack, s.stack.state)
        assertFalse(s.live)
        compose.onNodeWithText(Strings.DiscoverGatt).assertExists() // still on the GATT tab
    }
}
