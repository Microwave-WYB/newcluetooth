package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D-1 header redesign: the scope moved into the Filter sheet (D-3a item 1), its badge and Clear, and search. */
class DiscoverHeaderTest {
    private val order = DiscoverPhase.Ready(KeyOrder.of(longArrayOf(3, 1, 2)))
    private val live = DiscoverState(Liveness.Live, phase = order)
    private val frozen = DiscoverState(Liveness.Frozen, phase = order)

    private fun DiscoverState.on(vararg events: DiscoverEvent) = events.fold(this) { s, e -> reduceDiscover(s, e).state }

    @Test
    fun `D-3a the scope defaults per session kind - Nearby while recording, All otherwise and for every new session view`() {
        assertEquals(Segment.Nearby, live.segment.segment)
        assertEquals(Segment.All, frozen.segment.segment)
        // not read yet: the frozen default, until the read says live
        val pending = DiscoverState(Liveness.Pending, phase = order)
        assertEquals(Segment.All, pending.segment.segment)
        assertEquals(Segment.Nearby, pending.on(DiscoverEvent.LiveRead(true)).segment.segment)
        // V-7: a new session view is a new state, on its default whatever the last one chose
        assertEquals(Segment.ThisArea, frozen.on(DiscoverEvent.SegmentSelected(Segment.ThisArea)).segment.segment)
        assertEquals(Segment.All, DiscoverState(Liveness.Frozen).segment.segment)
    }

    @Test
    fun `D-1 a scope other than the default counts as one active filter for the badge`() {
        assertEquals(0, live.activeFilters)
        assertEquals(1, live.on(DiscoverEvent.SegmentSelected(Segment.All)).activeFilters)
        assertEquals(0, frozen.activeFilters)
        val area = frozen.on(DiscoverEvent.SegmentSelected(Segment.ThisArea))
        assertEquals(1, area.activeFilters)
        // with the sheet's own filters: one each, and the scope one more
        val more = area.on(DiscoverEvent.FilterSet(DeviceFilter(readable = true, identification = Identification.Identified, categories = setOf("a", "b"))))
        assertEquals(1 + 1 + 2 + 1, more.activeFilters)
        // back to the default scope: it no longer counts
        assertEquals(4, more.on(DiscoverEvent.SegmentSelected(Segment.All)).activeFilters)
        // R-7: Stop turns Nearby into All, the frozen default: still nothing counted
        assertEquals(0, live.on(DiscoverEvent.LiveRead(false)).activeFilters)
    }

    @Test
    fun `D-3a Clear resets the scope with the sheet's filters, and asks the rows again`() {
        val filtered = frozen.on(
            DiscoverEvent.SegmentSelected(Segment.ThisArea),
            DiscoverEvent.FilterSet(DeviceFilter(readable = true, vulnerable = true, categories = setOf("a"))),
        )
        val cleared = reduceDiscover(filtered, DiscoverEvent.FiltersCleared)
        assertEquals(Segment.All, cleared.state.segment.segment)
        assertEquals(DeviceFilter(), cleared.state.filter)
        assertEquals(0, cleared.state.activeFilters)
        assertEquals(listOf(DiscoverEffect.Refresh), cleared.effects)
        // while recording, Clear goes back to Nearby
        assertEquals(SegmentState.Nearby, live.on(DiscoverEvent.SegmentSelected(Segment.All), DiscoverEvent.FiltersCleared).segment)
        // on the default scope only the filters clear
        val onDefault = reduceDiscover(live.on(DiscoverEvent.FilterSet(DeviceFilter(trackers = true))), DiscoverEvent.FiltersCleared)
        assertEquals(live, onDefault.state)
        assertEquals(listOf(DiscoverEffect.Refresh), onDefault.effects)
    }

    @Test
    fun `D-3a the scope is no header control any more - choosing it raises nothing (S-12 is for the header)`() {
        val step = reduceDiscover(frozen, DiscoverEvent.SegmentSelected(Segment.ThisArea))
        assertTrue(step.effects.none { it == DiscoverEffect.RaiseRoot })
        assertTrue(reduceDiscover(step.state, DiscoverEvent.SegmentSelected(Segment.ThisArea)).effects.isEmpty())
    }

    @Test
    fun `S-12 the re-sort acts and raises, and raises even before the first sort`() {
        assertEquals(listOf(DiscoverEffect.RaiseRoot, DiscoverEffect.LoadResort(1)), reduceDiscover(frozen, DiscoverEvent.ResortRequested).effects)
        val loading = DiscoverState(Liveness.Frozen)
        val early = reduceDiscover(loading, DiscoverEvent.ResortRequested)
        assertSame(loading, early.state)
        assertEquals(listOf(DiscoverEffect.RaiseRoot), early.effects)
    }

    @Test
    fun `Q-5 search hides the view tabs, the re-sort and the scope - their events change nothing and do nothing`() {
        val back = SearchReturn(DiscoverView.Categories, SheetHeight.Half, PanelHeight.Open, ScrollSnapshot())
        val searching = frozen.copy(
            searching = Searching("qt", null, CameraSnapshot(GeoPoint(1.0, 1.0), 15.0), false, KeyOrder.of(longArrayOf(3)), DeviceFilter(), back),
            view = DiscoverView.List,
        )
        val hidden = listOf(
            DiscoverEvent.ViewSelected(DiscoverView.Categories), DiscoverEvent.ViewSelected(DiscoverView.List),
            DiscoverEvent.ResortRequested, DiscoverEvent.SegmentSelected(Segment.ThisArea), DiscoverEvent.FiltersCleared,
        )
        for (e in hidden) {
            val step = reduceDiscover(searching, e)
            assertSame("$e", searching, step.state)
            assertTrue("$e", step.effects.isEmpty())
        }
    }
}

/** D-1 in the composed header and Filter sheet (frozen All sessions demo: [This area | All], default All). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class DiscoverHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var sheets: SheetsState

    private fun show() {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            val coroutines = rememberCoroutineScope()
            val fake = remember { FakeCore(coroutines, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.AllSessions, simulate = false) }
            val scope = fake.initialScope ?: return@setContent
            CluetoothTheme(darkTheme = false, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                    val state = rememberSheetsState(
                        scope, live = false, routesFaint = false, core = fake,
                        gatt = FakeGattRunner({ FakeGattDevice(name = null, connectable = true) }), map = null,
                        nowMs = { FakeCore.DEMO_NOW_MS }, reducedMotion = true,
                    )
                    sheets = state
                    SheetsLayer(state, tablet = false)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { sheets.stack.setHeight(sheets.stack.root, SheetHeight.Half) }
        compose.waitForIdle()
    }

    private val inHeader = hasAnyAncestor(hasTestTag(DiscoverTags.HEADER))

    @Test
    fun d1_reSortAtLeft_viewTabsInTheCenter_noScopeInTheHeader() {
        show()
        // S-2: the re-sort's center on the left corner button's center, 34 dp from the edge
        val resort = compose.onNodeWithTag(DiscoverTags.RESORT).fetchSemanticsNode().boundsInRoot
        val corner = with(compose.density) { 34.dp.toPx() }
        assertEquals(corner, resort.center.x, 1f)
        compose.onNodeWithTag(DiscoverTags.VIEW_TABS).assertExists()
        compose.onAllNodes(hasText(Strings.ListView) and inHeader).assertCountEquals(1)
        compose.onAllNodes(hasText(Strings.Categories) and inHeader).assertCountEquals(1)
        compose.onAllNodes(hasText(Strings.ThisArea) and inHeader).assertCountEquals(0)
        compose.onAllNodes(hasText(Strings.All) and inHeader).assertCountEquals(0)
        // the tabs show the current view, and a tap switches it
        assertEquals(DiscoverView.Categories, sheets.discover.view)
        compose.onAllNodes(hasText(Strings.ListView) and inHeader)[0].performClick()
        compose.waitForIdle()
        assertEquals(DiscoverView.List, sheets.discover.view)
    }

    @Test
    fun d3a_theScopeIsTheFilterSheetsFirstItem_itsBadgeAndClear() {
        show()
        compose.onNodeWithTag(DiscoverTags.FILTER).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PushedTags.SCOPE).assertExists()
        // "Show" comes before the switches
        val show = compose.onAllNodesWithText(Strings.Show)[0].fetchSemanticsNode().boundsInRoot.top
        val readable = compose.onNodeWithTag(PushedTags.preset(Strings.Readable)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Show at $show, Readable at $readable", show < readable)
        compose.onAllNodes(hasText(Strings.ThisArea) and hasAnyAncestor(hasTestTag(PushedTags.SCOPE)))[0].performClick()
        compose.waitForIdle()
        assertEquals(Segment.ThisArea, sheets.discover.segment)
        assertEquals(1, sheets.discover.activeFilters)
        compose.onNodeWithTag(PushedTags.CLEAR).performClick()
        compose.waitForIdle()
        assertEquals(Segment.All, sheets.discover.segment)
        assertEquals(0, sheets.discover.activeFilters)
    }

    @Test
    fun d1_theFilterButtonCountsANonDefaultScope() {
        show()
        compose.runOnIdle { sheets.selectSegment(Segment.ThisArea) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(Strings.filterActive(1)).assertExists() // the filter button, with its badge
        compose.onNodeWithContentDescription(Strings.Filter).assertDoesNotExist()
    }

    @Test
    fun q5_searchShowsNoTabsReSortOrFilter() {
        show()
        compose.runOnIdle { sheets.enterSearch("a", null, CameraSnapshot(GeoPoint(-117.23, 32.88), 15.0), picked = null) }
        compose.waitForIdle()
        compose.onNodeWithTag(DiscoverTags.VIEW_TABS).assertDoesNotExist()
        compose.onNodeWithTag(DiscoverTags.RESORT).assertDoesNotExist()
        compose.onNodeWithTag(DiscoverTags.FILTER).assertDoesNotExist()
    }
}
