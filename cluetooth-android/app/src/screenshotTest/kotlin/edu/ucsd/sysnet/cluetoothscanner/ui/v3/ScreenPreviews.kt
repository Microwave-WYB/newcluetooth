package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControl
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordSavedSnackbar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.ScrollToTopButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.FakeData
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.CategoryListScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailFull
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.NearbyScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.RecordingsScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SavedRecording
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Renders the same composable in light and dark at phone size. */
@Preview(name = "light", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "dark", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class PhonePreview

/** Static frame: follows the preview's light/dark and freezes infinite animations. */
@Composable
private fun Frame(content: @Composable () -> Unit) {
    V3Theme {
        CompositionLocalProvider(LocalStaticRender provides true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

private val cb = RecorderCallbacks()

// ---- Scan ----------------------------------------------------------------------------------

@PreviewTest @PhonePreview @Composable fun ScanList() = Frame { ScanScreen(FakeData.scan, cb) }

@PreviewTest @PhonePreview @Composable
fun ScanRecording() = Frame { ScanScreen(FakeData.scan.copy(recorder = FakeData.recording), cb) }

@PreviewTest @PhonePreview @Composable
fun ScanPaused() = Frame { ScanScreen(FakeData.scan.copy(recorder = FakeData.paused), cb) }

@PreviewTest @PhonePreview @Composable
fun ScanPullToRefresh() = Frame { ScanScreen(FakeData.scan, cb, refreshing = true) }

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDown() = Frame {
    ScanScreen(FakeData.scan, cb, listState = rememberLazyListState(initialFirstVisibleItemIndex = 6))
}

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDownRecording() = Frame {
    ScanScreen(
        FakeData.scan.copy(recorder = FakeData.recording), cb,
        listState = rememberLazyListState(initialFirstVisibleItemIndex = 6),
    )
}

@PreviewTest @PhonePreview @Composable
fun ScanDeviceHalfSheet() = Frame { ScanScreen(FakeData.scan, cb, detail = FakeData.detailHalf) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceHalfSheetNotConnectable() = Frame {
    ScanScreen(FakeData.scan.copy(recorder = FakeData.recording), cb, detail = FakeData.detailNotConnectable)
}

@PreviewTest @PhonePreview @Composable
fun ScanRecordControlBar() = Frame {
    ScanScreen(FakeData.scan.copy(recorder = FakeData.recording), cb, controlBarOpen = true)
}

@PreviewTest @PhonePreview @Composable
fun ScanSavedSnackbar() = Frame { ScanScreen(FakeData.scan, cb, saved = SavedRecording(23 * 60, 1_400)) }

// ---- Device detail, expanded ---------------------------------------------------------------

@PreviewTest @PhonePreview @Composable
fun DeviceFullBroadcast() = Frame {
    DeviceDetailFull(FakeData.detailHalf, DetailTab.Advertising, "v1", {}, {}, {}, {})
}

@PreviewTest @PhonePreview @Composable
fun DeviceFullGattInProgress() = Frame {
    DeviceDetailFull(FakeData.detailGattProgress, DetailTab.Gatt, null, {}, {}, {}, {})
}

@PreviewTest @PhonePreview @Composable
fun DeviceFullGattDone() = Frame {
    DeviceDetailFull(FakeData.detailGattDone, DetailTab.Gatt, null, {}, {}, {}, {})
}

@PreviewTest @PhonePreview @Composable
fun DeviceFullWiki() = Frame {
    DeviceDetailFull(FakeData.detailHalf, DetailTab.Wiki, null, {}, {}, {}, {})
}

@PreviewTest @PhonePreview @Composable
fun DeviceFullNotConnectable() = Frame {
    DeviceDetailFull(FakeData.detailNotConnectable, DetailTab.Gatt, null, {}, {}, {}, {})
}

// ---- Nearby --------------------------------------------------------------------------------

@PreviewTest @PhonePreview @Composable fun NearbyHome() = Frame { NearbyScreen(FakeData.nearby, cb) }

@PreviewTest @PhonePreview @Composable
fun NearbyRecording() = Frame { NearbyScreen(FakeData.nearbyRecording, cb) }

@PreviewTest @PhonePreview @Composable
fun NearbyPaused() = Frame { NearbyScreen(FakeData.nearbyPaused, cb) }

@PreviewTest @PhonePreview @Composable
fun NearbyControlBar() = Frame { NearbyScreen(FakeData.nearbyRecording, cb, controlBarOpen = true) }

@PreviewTest @PhonePreview @Composable
fun NearbyCategoryList() = Frame { CategoryListScreen(FakeData.nearbyAudio, FakeData.idle, cb) }

// ---- Recordings ----------------------------------------------------------------------------

@PreviewTest @PhonePreview @Composable
fun RecordingsList() = Frame { RecordingsScreen(FakeData.recordings, cb) }

@PreviewTest @PhonePreview @Composable
fun RecordingsListIdle() = Frame { RecordingsScreen(FakeData.recordingsIdle, cb) }

@PreviewTest @PhonePreview @Composable
fun RecordingsSavedSnackbar() = Frame {
    RecordingsScreen(FakeData.recordingsIdle, cb, saved = SavedRecording(23 * 60, 1_400))
}

@PreviewTest @PhonePreview @Composable
fun RecordingMapRoute() = Frame { MapScreen(FakeData.recordingMap, cb) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapDeviceSelected() = Frame { MapScreen(FakeData.recordingMapSelected, cb) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapPannedHeatmap() = Frame { MapScreen(FakeData.recordingMapPanned, cb) }

@PreviewTest @PhonePreview @Composable
fun ProductMap() = Frame { MapScreen(FakeData.productMap, cb) }

// ---- Record control, every state -----------------------------------------------------------

@Composable
private fun StateRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun Control(state: RecorderUiState, placement: RecordPlacement) = RecordControl(state, placement, cb)

@PreviewTest @PhonePreview @Composable
fun RecordControlStates() = Frame {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        StateRow("空闲 · 完整") { Control(FakeData.idle, RecordPlacement.Full) }
        StateRow("录制中 · 完整") { Control(FakeData.recording, RecordPlacement.Full) }
        StateRow("已暂停 · 完整") { Control(FakeData.paused, RecordPlacement.Full) }
        StateRow("空闲 · 迷你") { Control(FakeData.idle, RecordPlacement.Mini) }
        StateRow("录制中 · 迷你") { Control(FakeData.recording, RecordPlacement.Mini) }
        StateRow("已暂停 · 迷你") { Control(FakeData.paused, RecordPlacement.Mini) }
        StateRow("回到顶部（叠在完整按钮上方）") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollToTopButton({})
                Control(FakeData.idle, RecordPlacement.Full)
            }
        }
        StateRow("停止后的提示") { RecordSavedSnackbar(23 * 60, 1_400, Modifier.width(380.dp)) }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            RecordControlBar(FakeData.recording, cb)
        }
    }
}

@PreviewTest @PhonePreview @Composable
fun RecordControlBarPaused() = Frame {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        RecordControlBar(FakeData.paused, cb)
    }
}

