package edu.ucsd.sysnet.cluetoothscanner.gallery

import androidx.compose.runtime.Composable
import edu.ucsd.sysnet.cluetoothscanner.gallery.lanes.AGallery
import edu.ucsd.sysnet.cluetoothscanner.gallery.lanes.BGallery
import edu.ucsd.sysnet.cluetoothscanner.gallery.lanes.CGallery
import edu.ucsd.sysnet.cluetoothscanner.gallery.lanes.DGallery
import edu.ucsd.sysnet.cluetoothscanner.gallery.lanes.EGallery

/** One screen of the gallery: a component or state shown full-screen on fakes. */
class GalleryEntry(val title: String, val content: @Composable () -> Unit)

/** A lane's entries; each lane owns its `lanes/<Lane>Gallery.kt`. */
interface LaneGallery {
    val lane: String
    val entries: List<GalleryEntry>
}

/** Every lane, in lane order. */
val Registry: List<LaneGallery> = listOf(AGallery, BGallery, CGallery, DGallery, EGallery)
