package edu.ucsd.sysnet.cluetoothscanner.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Note10 SIGSEGV (MapSnapshotter::Impl::onDidFinishLoadingStyle on a freed peer): a MapSnapshotter that is
 * garbage-collected while its URL style is still loading frees its native observer before the style response
 * arrives. The recorder panel's morph resizes the mini map and the panel closes it, so snapshotters were dropped
 * mid-load. With the online style this test churns the size, disposes the mini map and forces finalization while
 * the styles load; before the fix the process died here.
 */
@RunWith(AndroidJUnit4::class)
class MiniMapLifetimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun r5_resizingAndClosingWhileTheStyleLoadsNeverFreesALoadingSnapshotter() {
        val route = listOf(CENTER, GeoPoint(CENTER.lon + 0.01, CENTER.lat + 0.005))
        var width by mutableStateOf(120)
        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) {
                Box(Modifier.size(width.dp, 132.dp)) {
                    RouteMiniMap(route, fallback = CENTER, modifier = Modifier.fillMaxSize(), dark = false, baseStyleJson = null)
                }
            }
        }
        val created = MiniMapStats.created
        val released = MiniMapStats.releasedWhileRunning
        // the panel's morph: the size changes every frame or so, several times, then the panel closes
        repeat(3) { round ->
            for (w in listOf(160, 220, 280, 320, 360)) {
                rule.runOnIdle { width = w + round }
                rule.mainClock.advanceTimeBy(120)
                rule.waitForIdle()
            }
            rule.runOnIdle { shown = false }
            rule.waitForIdle()
            gcHard()
            rule.runOnIdle { shown = true; width = 120 }
            rule.waitForIdle()
        }
        rule.runOnIdle { shown = false }
        // let the in-flight style loads finish while the dropped snapshotters are collected
        repeat(20) {
            gcHard()
            rule.waitForIdle()
            Thread.sleep(250)
        }
        // still alive; no snapshotter was dropped while it ran, the churn built at most one per settled frame, and
        // once every callback came back the keeper holds nothing
        assertEquals("released while running", released, MiniMapStats.releasedWhileRunning)
        assertTrue("created ${MiniMapStats.created - created} snapshotters", MiniMapStats.created - created <= 4)
        val end = System.currentTimeMillis() + 60_000
        while (SnapshotterKeeper.size > 0 && System.currentTimeMillis() < end) { rule.waitForIdle(); Thread.sleep(250) }
        assertEquals("snapshotters still held", 0, SnapshotterKeeper.size)
    }

    private fun gcHard() {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Runtime.getRuntime().gc()
    }
}
