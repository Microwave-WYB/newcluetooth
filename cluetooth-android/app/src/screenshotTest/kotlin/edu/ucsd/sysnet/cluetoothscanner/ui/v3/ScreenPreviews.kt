package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.LocalWindowHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BottomRecordControl
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlSheetContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPill
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.ScrollToTopButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.recordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DiscoverMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DiscoverUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.FakeData
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanLocation
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ProductContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ProductTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.HistoryContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.HistoryTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DiscoverContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DiscoverTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.PlaceholderMapFor
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.sheetContentMaxHeight
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"
private const val NARROW_PHONE = "spec:width=360dp,height=740dp,dpi=420"

/** Renders the same composable in light and dark at 411 dp, and in light at 360 dp ("narrow"). */
@Preview(name = "narrow", device = NARROW_PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "light", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "dark", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class PhonePreview

/** Static frame: follows the preview's light/dark and freezes infinite animations. */
@Composable
private fun Frame(content: @Composable () -> Unit) {
    V3Theme {
        CompositionLocalProvider(LocalStaticRender provides true) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}

private val cb = RecorderCallbacks()
private val fix = ScanLocation.Fix(32.88012, -117.23421, 8)

/**
 * Previews cannot open a window, so a modal sheet is drawn in place with the stock sheet pieces
 * (scrim, shape, container color, drag handle) to check its content's layout and cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetMock(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalWindowHeight provides maxHeight) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = BottomSheetDefaults.ExpandedShape, color = BottomSheetDefaults.ContainerColor, tonalElevation = BottomSheetDefaults.Elevation) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                content(Modifier.heightIn(max = sheetContentMaxHeight()))
            }
        }
      }
    }
}

// ---- Scan ----------------------------------------------------------------------------------

@Composable
private fun ScanPreview(
    state: ScanUiState,
    listState: LazyListState = rememberLazyListState(),
    location: ScanLocation = fix,
    detail: DeviceDetailUiState? = null,
    tab: DetailTab = DetailTab.Advertising,
    controlSheet: Boolean = false,
) {
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    Box(Modifier.fillMaxSize()) {
        AppShell(
            recorder = state.recorder,
            callbacks = cb,
            placement = recordPlacement(state.recorder.phase, roomForExpanded = detail == null && !controlSheet),
            tab = AppTab.Scan,
            topBar = { ScanTopBar(state.rows.size, location, null, searching = false, onSearchToggle = {}, onSettings = {}) },
            showScrollToTop = showTop,
        ) { padding -> ScanContent(state.rows, listState, padding) }
        if (detail != null) SheetMock { DeviceDetailContent(detail, DeviceDetailActions(), it, tabState = remember { mutableStateOf(tab) }) }
        if (controlSheet) SheetMock { RecordControlSheetContent(state.recorder, cb, it) }
    }
}

@PreviewTest @PhonePreview @Composable fun ScanList() = Frame { ScanPreview(FakeData.scan) }

@PreviewTest @PhonePreview @Composable
fun ScanNoLocation() = Frame { ScanPreview(FakeData.scan, location = ScanLocation.Unavailable) }

@PreviewTest @PhonePreview @Composable
fun ScanRecording() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.recording)) }

@PreviewTest @PhonePreview @Composable
fun ScanPaused() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.paused), location = ScanLocation.Stale(2)) }

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDown() = Frame { ScanPreview(FakeData.scan, listState = rememberLazyListState(initialFirstVisibleItemIndex = 6)) }

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDownRecording() = Frame {
    ScanPreview(FakeData.scan.copy(recorder = FakeData.recording), listState = rememberLazyListState(initialFirstVisibleItemIndex = 6))
}

@PreviewTest @PhonePreview @Composable
fun ScanDeviceSheet() = Frame { ScanPreview(FakeData.scan, detail = FakeData.detailHalf) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceSheetLongName() = Frame { ScanPreview(FakeData.scan, detail = FakeData.detailLongName) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceSheetShortLived() = Frame { ScanPreview(FakeData.scan, detail = FakeData.detailShortLived) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceSheetGattRecording() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.recording), detail = FakeData.detailGattDone, tab = DetailTab.Gatt) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceSheetNotConnectable() = Frame { ScanPreview(FakeData.scan, detail = FakeData.detailNotConnectable, tab = DetailTab.Gatt) }

@PreviewTest @PhonePreview @Composable
fun ScanControlSheet() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.recording), controlSheet = true) }

// ---- Discover and History ------------------------------------------------------------------

@Composable
private fun DiscoverPreview(state: DiscoverUiState, mode: DiscoverMode) =
    AppShell(state.recorder, cb, recordPlacement(state.recorder.phase, false), tab = AppTab.Discover, startAllowed = false, topBar = { DiscoverTopBar(state, mode, null) }) {
        DiscoverContent(state, mode, it)
    }

@PreviewTest @PhonePreview @Composable
fun DiscoverNearby() = Frame { DiscoverPreview(FakeData.discover, DiscoverMode.Nearby) }

@PreviewTest @PhonePreview @Composable
fun DiscoverAll() = Frame { DiscoverPreview(FakeData.discover, DiscoverMode.All) }

@PreviewTest @PhonePreview @Composable
fun DiscoverRecording() = Frame { DiscoverPreview(FakeData.discoverRecording, DiscoverMode.Nearby) }

@Composable
private fun ProductPreview(state: MapUiState) =
    AppShell(FakeData.idle, cb, recordPlacement(FakeData.idle.phase, false), tab = AppTab.Discover, startAllowed = false, topBar = { ProductTopBar(FakeData.productHeader.title) {} }) {
        ProductContent(FakeData.productHeader, state, it, nowMs = 1_761_600_000_000L) { PlaceholderMapFor(state) { 0f } }
    }

@PreviewTest @PhonePreview @Composable
fun ProductPage() = Frame { ProductPreview(FakeData.productMap.copy(rows = FakeData.productMap.rows.map { it.copy(lastSeenMs = 1_761_600_000_000L - 2 * 3_600_000L) })) }

@PreviewTest @PhonePreview @Composable
fun ProductPageNoMap() = Frame { ProductPreview(FakeData.productMap.copy(productPoints = emptyList())) }

@PreviewTest @PhonePreview @Composable
fun HistoryList() = Frame {
    AppShell(FakeData.recordings.recorder, cb, recordPlacement(FakeData.recordings.recorder.phase, false), tab = AppTab.History, startAllowed = false, topBar = { HistoryTopBar(FakeData.recordings, null) }) {
        HistoryContent(FakeData.recordings, it)
    }
}

@PreviewTest @PhonePreview @Composable
fun HistoryIdle() = Frame {
    AppShell(FakeData.idle, cb, recordPlacement(FakeData.idle.phase, false), tab = AppTab.History, startAllowed = false, topBar = { HistoryTopBar(FakeData.recordingsIdle, null) }) {
        HistoryContent(FakeData.recordingsIdle, it)
    }
}

// ---- Maps ----------------------------------------------------------------------------------

@Composable
private fun MapPreview(state: MapUiState, recorder: RecorderUiState = FakeData.idle, expanded: Boolean = false, tab: DetailTab = DetailTab.Advertising) =
    AppShell(recorder, cb, recordPlacement(recorder.phase, roomForExpanded = false), startAllowed = false, topBar = { MapTopBar(state, MapActions()) }) { padding ->
        MapContent(state, MapActions(), padding, initiallyExpanded = expanded, initialTab = tab) { covered -> PlaceholderMapFor(state, covered) }
    }

@PreviewTest @PhonePreview @Composable fun RecordingMapRoute() = Frame { MapPreview(FakeData.recordingMap) }

@PreviewTest @PhonePreview @Composable fun RecordingMapListExpanded() = Frame { MapPreview(FakeData.recordingMap, expanded = true) }

@PreviewTest @PhonePreview @Composable fun RecordingMapDeviceSelected() = Frame { MapPreview(FakeData.recordingMapSelected) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapDeviceExpanded() = Frame { MapPreview(FakeData.recordingMapSelected, expanded = true, tab = DetailTab.Gatt) }

@PreviewTest @PhonePreview @Composable fun RecordingMapPannedHeatmap() = Frame { MapPreview(FakeData.recordingMapPanned) }

@PreviewTest @PhonePreview @Composable fun ProductMap() = Frame { MapPreview(FakeData.productMap) }

@PreviewTest @PhonePreview @Composable fun RecordingMapWithPill() = Frame { MapPreview(FakeData.recordingMap, FakeData.recording) }

@PreviewTest @PhonePreview @Composable fun ProductMapWithPillPaused() = Frame { MapPreview(FakeData.productMap, FakeData.paused) }

// ---- Record control, every state -----------------------------------------------------------

@Composable
private fun StateRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@PreviewTest @PhonePreview @Composable
fun RecordControlStates() = Frame {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        StateRow("Idle · round button") { BottomRecordControl(FakeData.idle, cb) }
        StateRow("Active · expanded (Scan page)") { BottomRecordControl(FakeData.recording, cb) }
        StateRow("Paused · expanded (Scan page)") { BottomRecordControl(FakeData.paused, cb) }
        StateRow("Active · floating pill") { RecordPill(FakeData.recording, cb) }
        StateRow("Paused · floating pill") { RecordPill(FakeData.paused, cb) }
        StateRow("Scroll to top") { ScrollToTopButton({}) }
        StateRow("Control sheet, paused") { RecordControlSheetContent(FakeData.paused, cb) }
    }
}
