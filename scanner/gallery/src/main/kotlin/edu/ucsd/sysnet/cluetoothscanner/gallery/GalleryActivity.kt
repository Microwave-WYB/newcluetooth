package edu.ucsd.sysnet.cluetoothscanner.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The gallery shell: the registry as a list; an entry opens full-screen and Back returns to the list. */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Gallery(Registry) }
            }
        }
    }
}

@Composable
fun Gallery(lanes: List<LaneGallery>) {
    // "<lane>/<index>" of the open entry, so the selection survives a configuration change.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = open?.let { key ->
        val (lane, index) = key.split('/')
        lanes.firstOrNull { it.lane == lane }?.entries?.getOrNull(index.toInt())
    }
    if (entry != null) {
        BackHandler { open = null }
        Box(Modifier.fillMaxSize()) { entry.content() }
        return
    }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
        for (lane in lanes) {
            item(key = "lane-${lane.lane}") {
                Text(
                    "Lane ${lane.lane}" + if (lane.entries.isEmpty()) " (no entries yet)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                )
            }
            items(lane.entries.size, key = { "${lane.lane}/$it" }) { index ->
                Text(
                    lane.entries[index].title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = "${lane.lane}/$index" }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}
