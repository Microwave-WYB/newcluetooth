package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A pushed sheet is never blank: Pending shows a loading line (after a short delay), Missing and Failed say so. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class LoadStatesUiTest {
    @get:Rule val compose = createComposeRule()

    private enum class Mode { Missing, Slow, Fail, ChartLast }

    /** :core-fake's history session, whose device and product reads return none, never return, or throw. */
    private class Reads(val core: FakeCore, val mode: Mode) : CoreQueries by core {
        private suspend fun <T> read(real: suspend () -> T?): T? = when (mode) {
            Mode.Missing -> null
            Mode.Slow -> awaitCancellation()
            Mode.Fail -> throw IllegalStateException("core closed")
            Mode.ChartLast -> real()
        }
        override suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail? = read { core.device(scope, key) }
        override suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail? = read { core.product(scope, pageId, filter) }
        /** ChartLast: the live chart's read lands after the detail's, as on the reader while recording. */
        override suspend fun deviceChart(key: DeviceKey): LiveChart? {
            if (mode == Mode.ChartLast) delay(1_000)
            return core.deviceChart(key)
        }
    }

    private fun sheets(mode: Mode, live: Boolean = false): Pair<SheetsState, FakeCore> {
        var made: Pair<SheetsState, FakeCore>? = null
        compose.setContent {
            val coroutines = rememberCoroutineScope()
            val core = androidx.compose.runtime.remember { FakeCore(coroutines, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false) }
            val reads = androidx.compose.runtime.remember { Reads(core, mode) }
            val scope = core.initialScope ?: return@setContent
            CluetoothTheme(darkTheme = false, reducedMotion = true) {
                val state = rememberSheetsState(
                    scope, live = live, routesFaint = false, core = reads, gatt = FakeGattRunner({ FakeGattDevice(null, true) }),
                    map = null, nowMs = { FakeCore.DEMO_NOW_MS }, reducedMotion = true,
                )
                made = state to core
                SheetsLayer(state, tablet = false)
            }
        }
        compose.waitForIdle()
        return made ?: error("no sheets")
    }

    /** The note's column carries [tag]; its text is the child. */
    private fun shows(tag: String, text: String) {
        compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun SheetsState.firstDevice(): Long = (discover.phase as DiscoverPhase.Ready).order.toLongArray().first()

    private fun SheetsState.firstPage(core: FakeCore): String =
        runBlocking { core.products(scope, DeviceFilter()) }.flatMap { it.products }.first { it.id != UNIDENTIFIED }.id

    @Test
    fun aMissingDeviceSaysSo() {
        val (s, _) = sheets(Mode.Missing)
        compose.runOnIdle { s.openDevice(s.firstDevice(), fromList = false) }
        shows(LoadTags.MISSING, LoadTexts.DeviceMissing)
    }

    @Test
    fun aMissingProductSaysSo() {
        val (s, core) = sheets(Mode.Missing)
        compose.runOnIdle { s.openProduct(s.firstPage(core)) }
        shows(LoadTags.MISSING, LoadTexts.ProductMissing)
    }

    @Test
    fun aSlowDeviceShowsLoadingOnlyAfterTheDelay() {
        val (s, _) = sheets(Mode.Slow)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { s.openDevice(s.firstDevice(), fromList = false) }
        compose.mainClock.advanceTimeBy(PENDING_DELAY_MS / 2)
        compose.onNodeWithTag(LoadTags.PENDING).assertDoesNotExist() // a fast read doesn't flash it
        compose.mainClock.advanceTimeBy(PENDING_DELAY_MS)
        shows(LoadTags.PENDING, LoadTexts.Loading)
    }

    @Test
    fun aSlowProductShowsLoading() {
        val (s, core) = sheets(Mode.Slow)
        compose.runOnIdle { s.openProduct(s.firstPage(core)) }
        compose.mainClock.advanceTimeBy(PENDING_DELAY_MS * 2)
        shows(LoadTags.PENDING, LoadTexts.Loading)
    }

    @Test
    fun aFailedReadKeepsItsMessage() {
        val (s, core) = sheets(Mode.Fail)
        compose.runOnIdle { s.openDevice(s.firstDevice(), fromList = false) }
        shows(LoadTags.FAILED, "core closed")
        compose.runOnIdle { s.stack.pop(PopReason.Back) }
        compose.runOnIdle { s.openProduct(s.firstPage(core)) }
        shows(LoadTags.FAILED, "core closed")
    }

    @Test
    fun aLiveChartLandingAfterTheDetailKeepsTheDetail() {
        // the owner's blank live device sheet: the chart's write used to put the detail back to Pending
        val (s, _) = sheets(Mode.ChartLast, live = true)
        compose.runOnIdle { s.openDevice(s.firstDevice(), fromList = false) }
        compose.mainClock.advanceTimeBy(2_000) // the chart has landed
        compose.waitForIdle()
        compose.onNodeWithTag(DeviceTags.HEAD_RSSI).assertExists() // the header
        compose.onNodeWithText(edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings.Advertising).assertExists() // the tabs
        compose.onNodeWithTag(LoadTags.PENDING).assertDoesNotExist()
    }
}
