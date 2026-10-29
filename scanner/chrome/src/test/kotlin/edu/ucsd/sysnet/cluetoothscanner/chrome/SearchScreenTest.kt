package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

val noActions = TopChromeActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val area = Bbox(-117.24, 32.87, -117.22, 32.89)

    /** Q-3 … Q-6 on phone: the button opens the page, typing shows results, Q-4 empty states, the bar and chip. */
    private fun flow(dark: Boolean) {
        val log = mutableListOf<String>()
        var state by mutableStateOf(TopChromeState(hasSession = true))
        var rows by mutableStateOf(emptyList<edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow>())
        val actions = TopChromeActions(
            onMenu = { log += "menu" },
            onSearchOpen = { log += "open"; state = state.copy(page = SearchPageState("", area, null, false)) },
            onSearchBack = { log += "back"; state = state.copy(page = null) },
            onQueryChange = { q -> state = state.copy(page = state.page!!.copy(query = q, results = OrderedKeys(longArrayOf()))) },
            onPick = { log += "pick"; state = state.copy(page = null, searchQuery = state.page!!.query) },
            onSearchEverywhere = { log += "everywhere"; rows = Samples.rows; state = state.copy(page = state.page!!.copy(area = null, searchedEverywhere = true)) },
            onBarEdit = { log += "edit" },
            onBarExit = { log += "exit"; state = state.copy(searchQuery = null, showSearchThisArea = false) },
            onSearchThisArea = { log += "area" },
        )
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                    PhoneTopChrome(state, actions, rows, live = true)
                }
            }
        }
        val s = if (dark) "dark" else "light"
        fun shot(n: String) = compose.onRoot().captureRoboImage("src/test/screenshots/search/412x915-$s-$n.png")
        shot("closed")
        compose.onNodeWithContentDescription("Search devices").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Search devices by name or MAC address").assertIsDisplayed(); shot("open-empty")
        compose.onNodeWithTag("searchInput").performTextInput("qt"); compose.waitForIdle()
        compose.onNodeWithText("No results in this area").assertIsDisplayed(); shot("no-results-area")
        compose.onNodeWithText("Search everywhere").performClick(); compose.waitForIdle()
        compose.onNodeWithText("AirPods Pro").assertIsDisplayed(); shot("results")
        compose.onNodeWithText("AirPods Pro").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("searchBar").assertIsDisplayed()
        state = state.copy(showSearchThisArea = true); compose.waitForIdle(); shot("search-state")
        compose.onNodeWithText("Search this area").performClick()
        compose.onNodeWithContentDescription("Exit search").performClick(); compose.waitForIdle()
        assertEquals(listOf("open", "everywhere", "pick", "area", "exit"), log)
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneLight() = flow(false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneDark() = flow(true)

    /** Q-4 without an area. */
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun noDevicesMatch() {
        compose.setContent {
            CluetoothTheme(reducedMotion = true) {
                SearchResults(SearchPageState("zz", null, OrderedKeys(longArrayOf()), false), emptyList(), false, noActions)
            }
        }
        compose.onNodeWithText("No devices match").assertIsDisplayed()
    }

    /** T-4 / T-5: ☰ and the permanent field; results in a panel-shaped page; the chip over the map area. */
    private fun tablet(dark: Boolean) {
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                    TabletTopChrome(false, "air", true, SearchPageState("air", area, OrderedKeys(longArrayOf()), false),
                        Samples.rows.take(1), false, noActions, showSearchThisArea = true)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/search/1024x768-${if (dark) "dark" else "light"}-typing.png")
        compose.onNodeWithTag("tabletResults").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletLight() = tablet(false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletDark() = tablet(true)
}
