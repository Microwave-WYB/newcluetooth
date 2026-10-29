package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RulesTest {
    /** G-2: compact below 600 dp, medium to 840, expanded from 840; medium and expanded are the tablet layout. */
    @Test fun layoutMode() {
        assertEquals(LayoutMode.Compact, LayoutMode.of(412f))
        assertEquals(LayoutMode.Compact, LayoutMode.of(599.9f))
        assertEquals(LayoutMode.Medium, LayoutMode.of(600f))
        assertEquals(LayoutMode.Medium, LayoutMode.of(839f))
        assertEquals(LayoutMode.Expanded, LayoutMode.of(840f))
        assertEquals(LayoutMode.Expanded, LayoutMode.of(1024f))
        assertFalse(LayoutMode.Compact.tablet)
        assertTrue(LayoutMode.Medium.tablet && LayoutMode.Expanded.tablet)
    }

    /** G-14: the popover, a page, the top pushed sheet, the sidebar, the recorder panel, search state; else leave. */
    @Test fun backOrder() {
        val all = BackLayers(true, true, true, true, true, true)
        assertEquals(BackTarget.LocationPopover, backTarget(all))
        assertEquals(BackTarget.Page, backTarget(all.copy(locationPopover = false)))
        assertEquals(BackTarget.PushedSheet, backTarget(all.copy(locationPopover = false, page = false)))
        assertEquals(BackTarget.Sidebar, backTarget(BackLayers(sidebar = true, recorderPanel = true, searchState = true)))
        assertEquals(BackTarget.RecorderPanel, backTarget(BackLayers(recorderPanel = true, searchState = true)))
        assertEquals(BackTarget.SearchState, backTarget(BackLayers(searchState = true)))
        assertEquals(BackTarget.Leave, backTarget(BackLayers()))
    }

    /** V-6: a live recording first, then "Record when the app opens", then All sessions, then the first run. */
    @Test fun launch() {
        assertEquals(LaunchTarget.Session("s1"), launchTarget("s1", recordOnOpen = true, sessionCount = 3))
        assertEquals(LaunchTarget.StartRecording, launchTarget(null, recordOnOpen = true, sessionCount = 0))
        assertEquals(LaunchTarget.AllSessions, launchTarget(null, recordOnOpen = false, sessionCount = 2))
        assertEquals(LaunchTarget.FirstRun, launchTarget(null, recordOnOpen = false, sessionCount = 0))
    }

    /** R-5: the route's box. */
    @Test fun routeBox() {
        assertNull(bboxOf(emptyList()))
        assertEquals(Bbox(-117.24, 32.87, -117.22, 32.89), bboxOf(listOf(GeoPoint(-117.22, 32.87), GeoPoint(-117.24, 32.89), GeoPoint(-117.23, 32.88))))
    }

    /**
     * The temporary seams: only CoreBinding.kt names :core-fake (so the swap to :core-bridge is one line), and only
     * PlatformPlaceholders.kt names :platform-fake. Nothing in v2 :app uses the v3 classes.
     */
    @Test fun seams() {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val fakeCore = files.filter { "cluetoothscanner.fake.core" in it.readText() || "cluetoothscanner.corebridge" in it.readText() }.map { it.name }
        val fakePlatform = files.filter { "cluetoothscanner.platform.fake" in it.readText() }.map { it.name }
        assertEquals(listOf("CoreBinding.kt"), fakeCore)
        assertEquals(listOf("PlatformPlaceholders.kt"), fakePlatform)
        val v3 = Regex("""import edu\.ucsd\.sysnet\.cluetoothscanner\.(core|local|repository|service|ble|gatt|ui(?!\.contract)|data|utils)\.""")
        assertEquals(emptyList<String>(), files.filter { v3.containsMatchIn(it.readText()) }.map { it.name })
    }
}
