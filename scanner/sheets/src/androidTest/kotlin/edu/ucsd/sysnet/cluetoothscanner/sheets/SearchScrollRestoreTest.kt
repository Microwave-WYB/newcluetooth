package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.viewportCamera
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Q-7 with composed lists: the Discover list leaves while search shows its results list, and comes back at the
 * scroll it had before search, even though the results list (scrolled too) leaves the composition after the restore.
 */
@RunWith(AndroidJUnit4::class)
class SearchScrollRestoreTest {
    @get:Rule
    val rule = createComposeRule()

    private val keys = LongArray(200) { it.toLong() }

    private val queries = object : DiscoverQueries {
        override suspend fun deviceOrder() = DeviceKeys(keys, 0)
        override suspend fun deviceKeys(filter: DeviceFilter) = DeviceKeys(keys, 0)
        override suspend fun search(query: String, area: Bbox?) = DeviceKeys(keys.filter { it % 2 == 0L }.toLongArray(), 0)
    }

    private lateinit var model: DiscoverModel
    private lateinit var stack: SheetStackController
    private val lists = mutableMapOf<String, LazyListState>()

    @Composable
    private fun Rows(key: String, state: LazyListState, count: Int) {
        DisposableEffect(state) { lists[key] = state; onDispose { lists.remove(key) } }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag(key)) {
            items(count) { Box(Modifier.fillMaxWidth().height(50.dp)) }
        }
    }

    private fun start(separateResults: Boolean) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val s = androidx.compose.runtime.remember { SheetStackController(scope, { _, to, _, onValue -> onValue(to) }) }
            stack = s
            model = androidx.compose.runtime.remember { DiscoverModel(queries, false, s) }
            val root = s.root
            if (model.search == null || !separateResults) Rows(MAIN, rememberSheetListState(root), model.rows.size)
            else Rows(RESULTS, rememberSheetListState(root, RESULTS), model.rows.size)
        }
        rule.runOnUiThread { runBlocking { model.open() } }
        rule.waitForIdle()
        rule.runOnUiThread { runBlocking { lists.getValue(MAIN).scrollToItem(37, 12) } }
        rule.waitForIdle()
        rule.runOnUiThread { runBlocking { model.enterSearch("q", null, viewportCamera(Bbox(0.0, 0.0, 1.0, 1.0), 15.0)) } }
        rule.waitForIdle()
    }

    private fun scrollDuringSearch(key: String) {
        rule.runOnUiThread { runBlocking { lists.getValue(key).scrollToItem(5, 0) } }
        rule.waitForIdle()
        rule.runOnUiThread { runBlocking { model.exitSearch() } }
        rule.waitForIdle()
    }

    private fun assertMainAt(index: Int, offset: Int) {
        val main = lists.getValue(MAIN)
        assertEquals(index, rule.runOnIdle { main.firstVisibleItemIndex })
        assertEquals(offset, rule.runOnIdle { main.firstVisibleItemScrollOffset })
    }

    @Test
    fun q7_oneListShowingBothModes_movesBackToItsScrollOnExit() {
        start(separateResults = false)
        rule.onNodeWithTag(MAIN).assertExists() // the same list shows the results
        scrollDuringSearch(MAIN)
        assertMainAt(37, 12)
    }

    @Test
    fun q7_aSeparateResultsList_leavesTheMainListRestoredAndDrivenBySheet() {
        start(separateResults = true)
        rule.onNodeWithTag(MAIN).assertDoesNotExist()
        scrollDuringSearch(RESULTS)
        rule.onNodeWithTag(RESULTS).assertDoesNotExist()
        assertMainAt(37, 12)
        val root = stack.root
        // the outgoing results list unregistered only itself: the sheet still reads and drives the main list
        assertEquals(ScrollSnapshot(37, 12), rule.runOnIdle { root.scrollReader?.invoke() })
        val content = rule.runOnIdle { root.content }
        val rowPx = with(rule.density) { 50.dp.roundToPx() }
        val main = lists.getValue(MAIN)
        val px = { rule.runOnIdle { main.firstVisibleItemIndex * rowPx + main.firstVisibleItemScrollOffset } }
        val before = px()
        rule.runOnUiThread { content!!.scrollBy(-1000f) } // the sheet's drag target is the main list
        rule.waitForIdle()
        assertEquals(before - 1000, px())
        assertEquals(ScrollSnapshot(5, 0), root.scrollOf(RESULTS))
    }

    private companion object {
        const val MAIN = SheetEntry.MAIN_LIST
        const val RESULTS = "search"
    }
}
