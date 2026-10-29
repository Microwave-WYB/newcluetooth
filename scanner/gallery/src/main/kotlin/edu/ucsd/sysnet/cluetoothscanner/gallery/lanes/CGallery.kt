// Lane C owns this file (docs/v2-api.md §6.0); the stub is from B0.
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery

object CGallery : LaneGallery {
    override val lane = "C"
    override val entries: List<GalleryEntry> = emptyList()
}
