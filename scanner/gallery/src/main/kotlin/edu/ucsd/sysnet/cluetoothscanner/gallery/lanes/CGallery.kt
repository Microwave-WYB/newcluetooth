// Lane C owns this file (docs/v2-api.md §6.0); the stub is from B0.
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.DesignSystemGallery
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.InteractiveSamples
import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery

object CGallery : LaneGallery {
    override val lane = "C"
    override val entries: List<GalleryEntry> = listOf(
        GalleryEntry("Design system (C-1 … C-17)") { CluetoothTheme { DesignSystemGallery() } },
        GalleryEntry("Design system, interactive") { CluetoothTheme { InteractiveSamples() } },
    )
}
