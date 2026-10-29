// Lane D owns this file (docs/v2-api.md §6.0); the stub is from B0.
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetsDemo

object DGallery : LaneGallery {
    override val lane = "D"
    override val entries: List<GalleryEntry> = listOf(
        GalleryEntry("Sheet stack (phone): drag, flick, push, pop, scrim") { SheetsDemo.PhoneStack() },
        GalleryEntry("Panel (tablet): Open and Partial") { SheetsDemo.TabletPanel() },
    )
}
