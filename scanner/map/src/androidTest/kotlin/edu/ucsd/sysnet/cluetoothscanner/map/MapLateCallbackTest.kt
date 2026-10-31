package edu.ucsd.sysnet.cluetoothscanner.map

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.map.model.SurfaceState
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * A main-map callback that arrives after the map left the composition is a no-op: a late getMapAsync attaches no
 * surface to the destroyed MapView, and a late setStyle installs nothing and reports no style load. The map is shown
 * and removed again at random moments, so the removal lands before, between and after those callbacks.
 */
@RunWith(AndroidJUnit4::class)
class MapLateCallbackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    @Test fun callbacksAfterDisposeAreNoOps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val center = GeoPoint(-117.2340, 32.8801)
        var state: CluetoothMapState? = null
        rule.runOnUiThread { state = CluetoothMapState(GridQueries(emptyList()), StaticLocation(center), MapAssets.style(context), scope) }
        val s = state ?: throw AssertionError("no state")
        val shown = mutableStateOf(false)
        rule.setContent {
            if (shown.value) CluetoothMap(s, RecordingEvents(), Modifier.fillMaxSize(), dark = false, baseStyleJson = BLANK_STYLE)
        }
        val rnd = Random(7)
        repeat(80) {
            rule.runOnUiThread { shown.value = true }
            rule.waitForIdle()
            // remove it at once, while the map or its style is still loading, or after it loaded
            SystemClock.sleep(rnd.nextLong(0, 250))
            rule.runOnUiThread { shown.value = false }
            rule.waitForIdle()
        }
        var loads = -1
        rule.runOnUiThread { loads = s.styleLoads }
        // the callbacks still in flight arrive now
        SystemClock.sleep(3_000)
        rule.waitForIdle()
        rule.runOnUiThread {
            assertEquals("no surface may attach after dispose", null, s.attachedSurface)
            assertTrue("the state is detached: ${s.state.surface}", s.state.surface is SurfaceState.Detached)
            assertEquals("no style load may be reported after dispose", loads, s.styleLoads)
            assertEquals("no MapLibre call failed: ${s.state.lastFailure}", null, s.state.lastFailure)
        }
        // and the map still works when shown again
        rule.runOnUiThread { shown.value = true }
        rule.waitUntil(30_000) {
            var ok = false
            rule.runOnUiThread { ok = s.attachedSurface != null && s.styleLoads > loads }
            ok.also { if (!it) SystemClock.sleep(50) }
        }
    }
}
