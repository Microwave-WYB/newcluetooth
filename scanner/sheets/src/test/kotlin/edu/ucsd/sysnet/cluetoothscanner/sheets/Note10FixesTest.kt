package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bugs from the Note10 run: Full under the top row, the product title's label, the raw table's Len header. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h869dp-420dpi")
class Note10FixesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun s5_fullSitsBelowTheStatusBarAndTheTopRow() = runTest {
        val density = 2.625f
        val status = 63f // the Note10's status bar, px
        val s = SheetStackController(this, { _, to, _, onValue -> onValue(to) }).also { it.setScreen(2280f, density, 0f, status) }
        // --top-zone (12 + 44 + 12 dp) below the status bar: ☰ and search stay visible and tappable at Full
        assertEquals(status + 68f * density, s.geometry(s.root).full, 0.01f)
        s.setHeight(s.root, SheetHeight.Full)
        assertEquals(status + 68f * density, s.offsetOf(s.root), 0.01f)
        val noInset = SheetStackController(this, { _, to, _, onValue -> onValue(to) }).also { it.setScreen(2280f, density, 0f) }
        assertEquals(68f * density, noInset.geometry(noInset.root).full, 0.01f) // the tablet panel's host has none
    }

    @Test
    fun d6_theProductTitleIsTheCategoryLabel_evenWhenTheFirstLabelReadWasEmpty() = runTest {
        val core = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)
        // a live session that has not seen a device yet: the read at open finds no categories
        val empty = object : CoreQueries by core {
            var first = true
            override suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount> =
                if (first && filter == DeviceFilter()) emptyList<CategoryCount>().also { first = false } else core.categoryCounts(scope, filter)
        }
        val s = SheetsState(
            core.initialScope!!, live = false, routesFaint = false, core = empty,
            gattRunner = FakeGattRunner({ FakeGattDevice(null, true) }), map = null, coroutines = this,
            nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
        )
        s.open()
        assertEquals(emptyMap<String, String>(), s.categoryLabels)
        val group = core.products(core.initialScope!!, DeviceFilter()).first { it.products.any { p -> p.id != UNIDENTIFIED } }
        val page = group.products.first { it.id != UNIDENTIFIED }.id
        assertNotEquals(group.category, group.label) // e.g. "lighting-control" vs "Lighting control"
        assertEquals(group.label, s.labelOf(group.category, page))
        // and learned once, from any answer carrying labels
        val known = s.categoryLabels
        s.learnLabels(listOf(group.category to group.label))
        assertSame(known, s.categoryLabels)
    }

    private val detail = DeviceDetail(
        key = 1, addr = "AA:BB:CC:DD:EE:FF", name = null, page = null, categories = emptyList(), traits = emptyList(),
        readable = false, vulnerable = false, vulnerabilities = emptyList(), wikiUrl = null, icon = null, rssi = -60,
        inRange = true, live = false, connectable = true, format = AdvFormat.Legacy, companyId = null, companyName = null,
        serviceUuid = null, txPower = null,
        structures = listOf(AdStructure(2, 1, byteArrayOf(6)), AdStructure(12, 9, ByteArray(11) { 0x41 })),
        tail = null, decoded = null, lastSeenMs = 0,
    )

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun d12_theLenHeaderNeverWraps_andTheColumnsLineUp() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                CluetoothTheme(darkTheme = false, reducedMotion = true) { RawTable(detail) }
            }
        }
        for (s in listOf(1f, 1.3f, 2f)) {
            scale = s
            compose.waitForIdle()
            // one line, and all of it inside the column (not wrapped, and not clipped either)
            val len = layout(DeviceTags.rawLen(header = true))
            assertEquals("font scale $s", 1, len.lineCount)
            assertFalse("font scale $s: clipped", len.hasVisualOverflow)
            val head = compose.onNodeWithTag(DeviceTags.rawLen(header = true)).fetchSemanticsNode().boundsInRoot.width
            val cell = compose.onAllNodesWithTag(DeviceTags.rawLen(header = false))[0].fetchSemanticsNode().boundsInRoot.width
            assertEquals("font scale $s", head, cell, 0.5f)
        }
    }
}
