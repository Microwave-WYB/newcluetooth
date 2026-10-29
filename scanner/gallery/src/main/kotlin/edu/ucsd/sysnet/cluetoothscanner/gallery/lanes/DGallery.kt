// Lane D owns this file (docs/v2-api.md §6.0); the stub is from B0.
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetsLayer
import edu.ucsd.sysnet.cluetoothscanner.sheets.rememberSheetsState

object DGallery : LaneGallery {
    override val lane = "D"
    override val entries: List<GalleryEntry> = listOf(
        GalleryEntry("Sheets, All sessions (phone)") { Sheets(FakeDemo.AllSessions, tablet = false) },
        GalleryEntry("Sheets, recording (phone)") { Sheets(FakeDemo.Recording, tablet = false) },
        GalleryEntry("Sheets, history session (phone)") { Sheets(FakeDemo.History, tablet = false) },
        GalleryEntry("Panel, All sessions (tablet)") { Sheets(FakeDemo.AllSessions, tablet = true) },
    )
}

/** The sheet layer on :core-fake over a plain background (the map is lane E's), with no MapRequests. */
@Composable
private fun Sheets(demo: FakeDemo, tablet: Boolean) {
    val coroutines = rememberCoroutineScope()
    val core = remember(demo) { FakeCore(coroutines, demo = demo) }
    val gatt = remember { FakeGattRunner({ FakeGattDevice(name = null, connectable = true) }) }
    val scope = core.initialScope ?: return
    CluetoothTheme {
        Box(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2)) {
            val state = rememberSheetsState(scope, live = demo == FakeDemo.Recording, routesFaint = false, core = core, gatt = gatt, map = null)
            SheetsLayer(state, tablet)
        }
    }
}
