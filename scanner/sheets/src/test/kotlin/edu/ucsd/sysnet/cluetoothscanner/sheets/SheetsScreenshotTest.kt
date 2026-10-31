package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet screens on :core-fake's All sessions demo (the prototype's `?state=1`), phone 412 × 915 and tablet
 * 1024 × 768 (`?layout=tablet`), light and dark, over a plain background (the map and the top row are other lanes').
 * References under src/test/screenshots/sheets; `-Pcluetooth.record=true` rewrites them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shots(tablet: Boolean, size: String, dark: Boolean) {
        var sheets: SheetsState? = null
        var core: FakeCore? = null
        compose.mainClock.autoAdvance = true
        compose.setContent {
            val coroutines = rememberCoroutineScope()
            val fake = androidx.compose.runtime.remember {
                FakeCore(coroutines, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.AllSessions, simulate = false).also { core = it }
            }
            val scope = fake.initialScope ?: return@setContent
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
                    val state = rememberSheetsState(
                        scope, live = false, routesFaint = false, core = fake,
                        gatt = FakeGattRunner({ FakeGattDevice(name = null, connectable = true) }), map = null,
                        nowMs = { FakeCore.DEMO_NOW_MS }, reducedMotion = true,
                    )
                    sheets = state
                    SheetsLayer(state, tablet)
                }
            }
        }
        compose.waitForIdle()
        val s = sheets ?: return
        val fake = core ?: return
        val scheme = if (dark) "dark" else "light"
        fun shot(name: String) {
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/sheets/$size-$scheme-$name.png")
        }
        if (!tablet) compose.runOnIdle { s.stack.setHeight(s.stack.root, SheetHeight.Half) }
        shot("discover")
        // D-1: the header row on its own: re-sort, the view tabs, filter
        compose.onNodeWithTag(DiscoverTags.HEADER).captureRoboImage("src/test/screenshots/sheets/$size-$scheme-header.png")
        compose.runOnIdle { s.discover.selectView(DiscoverView.List) }
        shot("discover-list")
        compose.runOnIdle { s.discover.selectView(DiscoverView.Categories) }
        val products = runBlocking { fake.products(s.scope, DeviceFilter()) }
        val page = products.flatMap { it.products }.first { it.id != UNIDENTIFIED }.id
        compose.runOnIdle { s.openProduct(page) }
        shot("product")
        val device = runBlocking { fake.deviceKeys(s.scope, DeviceFilter(page = page)) }.keys.first()
        compose.runOnIdle { s.openDevice(device, fromList = true) }
        shot("device")
        compose.runOnIdle { s.stack.reset() }
        compose.runOnIdle { s.openFilter() }
        shot("filter")
        if (tablet) {
            compose.runOnIdle {
                s.stack.reset()
                s.mapEvents.onUserGestureStart() // T-2: a user pan moves Open to Partial
            }
            shot("partial")
        }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneLight() = shots(false, "412x915", false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneDark() = shots(false, "412x915", true)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletLight() = shots(true, "1024x768", false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletDark() = shots(true, "1024x768", true)
}
