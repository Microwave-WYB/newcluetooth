// Lane B owns this file (docs/v2-api.md §6.0).
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeLocationStatus
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeRecorder
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.LocationDemo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object BGallery : LaneGallery {
    override val lane = "B"
    override val entries: List<GalleryEntry> = listOf(
        GalleryEntry("Platform fakes: recorder, location status, GATT") { PlatformFakes() },
    )
}

private val gattDevices = mapOf(
    1L to FakeGattDevice("JBL Flip 6", connectable = true, companyName = "Harman International"),
    2L to FakeGattDevice(null, connectable = false),
    3L to FakeGattDevice("ELK-BLEDOM", connectable = true),
)

/** Plain controls over the :platform-fake objects, to see their state machines without any UI lane. */
@Composable
private fun PlatformFakes() {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(0) }
    val recorder = remember { FakeRecorder(onStart = { "session-${++sessions}" }, onStop = { "session-$sessions" }) }
    val location = remember { FakeLocationStatus() }
    val gatt = remember { FakeGattRunner(gattDevices::get) }
    val state by recorder.state.collectAsState()
    val status by location.status.collectAsState()
    var gattView by remember { mutableStateOf<GattView?>(null) }
    var gattJob by remember { mutableStateOf<Job?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recorder: ${state.phase} ${state.sessionId ?: ""} before=${state.activeBeforeMs} ms")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { recorder.start() } }) { Text("Start") }
            Button(onClick = { scope.launch { recorder.pause() } }) { Text("Pause") }
            Button(onClick = { scope.launch { recorder.resume() } }) { Text("Resume") }
            Button(onClick = { scope.launch { recorder.stop() } }) { Text("Stop") }
        }
        Text("Location: ${status.state} warn=${status.warn} age=${status.ageMin} action=${status.action}")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (demo in LocationDemo.entries) {
                FilterChip(selected = status == FakeLocationStatus.statusFor(demo), onClick = { location.set(demo) }, label = { Text(demo.name) })
            }
        }
        Text("GATT")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, device) in gattDevices) {
                Button(onClick = {
                    gattJob?.cancel()
                    gattJob = scope.launch { gatt.discover(key).collect { gattView = it } }
                }) { Text(device.name ?: "No name") }
            }
        }
        gattView?.let { view ->
            for (step in view.steps) Text("${step.kind} ${step.state} ${step.read ?: ""}/${step.readable ?: ""} ${step.failure ?: ""}")
            if (view.ok) Text("${view.services.size} services")
        }
    }
}
