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
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BottomRecordControl
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPill
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.recordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordSavedSnackbar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.ScrollToTopButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.FakeData
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.CategoryListPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.NearbyPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.PlaceholderMapFor
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.RecordingsPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanDeviceSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.ScanPage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanUiState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.DeviceDetailFull
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SavedRecording
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

@Composable
private fun ScanPreview(
    state: ScanUiState,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    detail: DeviceDetailUiState? = null,
    controlBar: Boolean = false,
    saved: SavedRecording? = null,
) {
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    AppShell(
        recorder = state.recorder,
        callbacks = cb,
        placement = recordPlacement(state.recorder.phase, roomForExpanded = detail == null && !controlBar),
        tab = AppTab.Scan,
        showScrollToTop = showTop,
        controlBarOpen = controlBar,
        overlay = { if (detail != null) ScanDeviceSheet(detail, DeviceDetailActions(), {}) },
        snackbar = { if (saved != null) SavedSnackbarHost(saved) },
    ) {
        ScanPage(state.rows, listState, refreshing = refreshing)
    }
}

@Composable
private fun BoxScope.SavedSnackbarHost(saved: SavedRecording, hasNav: Boolean = true) {
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = (if (hasNav) 64.dp else 0.dp) + 12.dp)) {
        RecordSavedSnackbar(saved.durationSeconds, saved.sightings)
    }
}

@PreviewTest @PhonePreview @Composable fun ScanList() = Frame { ScanPreview(FakeData.scan) }

@PreviewTest @PhonePreview @Composable
fun ScanRecording() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.recording)) }

@PreviewTest @PhonePreview @Composable
fun ScanPaused() = Frame { ScanPreview(FakeData.scan.copy(recorder = FakeData.paused)) }

@PreviewTest @PhonePreview @Composable
fun ScanPullToRefresh() = Frame { ScanPreview(FakeData.scan, refreshing = true) }

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDown() = Frame {
    ScanPreview(FakeData.scan, listState = rememberLazyListState(initialFirstVisibleItemIndex = 6))
}

@PreviewTest @PhonePreview @Composable
fun ScanScrolledDownRecording() = Frame {
    ScanPreview(
        FakeData.scan.copy(recorder = FakeData.recording),
        listState = rememberLazyListState(initialFirstVisibleItemIndex = 6),
    )
}

@PreviewTest @PhonePreview @Composable
fun ScanDeviceHalfSheet() = Frame { ScanPreview(FakeData.scan, detail = FakeData.detailHalf) }

@PreviewTest @PhonePreview @Composable
fun ScanDeviceHalfSheetNotConnectable() = Frame {
    ScanPreview(FakeData.scan.copy(recorder = FakeData.recording), detail = FakeData.detailNotConnectable)
}

@PreviewTest @PhonePreview @Composable
fun ScanRecordControlBar() = Frame {
    ScanPreview(FakeData.scan.copy(recorder = FakeData.recording), controlBar = true)
}

@PreviewTest @PhonePreview @Composable
fun ScanSavedSnackbar() = Frame { ScanPreview(FakeData.scan, saved = SavedRecording(23 * 60, 1_400)) }

// ---- Device detail, expanded ---------------------------------------------------------------

@Composable
private fun FullPreview(detail: DeviceDetailUiState, tab: DetailTab, expandedVariant: String? = null) =
    DeviceDetailFull(detail, tab, expandedVariant, {}, {}, {}, {}, {})

@PreviewTest @PhonePreview @Composable
fun DeviceFullBroadcast() = Frame { FullPreview(FakeData.detailHalf, DetailTab.Advertising, "v1") }

@PreviewTest @PhonePreview @Composable
fun DeviceFullGattInProgress() = Frame { FullPreview(FakeData.detailGattProgress, DetailTab.Gatt) }

@PreviewTest @PhonePreview @Composable
fun DeviceFullGattDone() = Frame { FullPreview(FakeData.detailGattDone, DetailTab.Gatt) }

@PreviewTest @PhonePreview @Composable
fun DeviceFullWiki() = Frame { FullPreview(FakeData.detailHalf, DetailTab.Wiki) }

@PreviewTest @PhonePreview @Composable
fun DeviceFullNotConnectable() = Frame { FullPreview(FakeData.detailNotConnectable, DetailTab.Gatt) }

// ---- Nearby --------------------------------------------------------------------------------

@Composable
private fun NearbyPreview(state: NearbyUiState, controlBar: Boolean = false) = AppShell(
    recorder = state.recorder,
    callbacks = cb,
    placement = recordPlacement(state.recorder.phase, roomForExpanded = false),
    tab = AppTab.Nearby,
    controlBarOpen = controlBar,
) { NearbyPage(state) }

@PreviewTest @PhonePreview @Composable fun NearbyHome() = Frame { NearbyPreview(FakeData.nearby) }

@PreviewTest @PhonePreview @Composable
fun NearbyRecording() = Frame { NearbyPreview(FakeData.nearbyRecording) }

@PreviewTest @PhonePreview @Composable
fun NearbyPaused() = Frame { NearbyPreview(FakeData.nearbyPaused) }

@PreviewTest @PhonePreview @Composable
fun NearbyControlBar() = Frame { NearbyPreview(FakeData.nearbyRecording, controlBar = true) }

@PreviewTest @PhonePreview @Composable
fun NearbyCategoryList() = Frame {
    AppShell(recorder = FakeData.idle, callbacks = cb, placement = recordPlacement(FakeData.idle.phase, roomForExpanded = false), tab = AppTab.Nearby) {
        CategoryListPage(FakeData.nearbyAudio)
    }
}

// ---- Recordings ----------------------------------------------------------------------------

@Composable
private fun RecordingsPreview(state: RecordingsUiState, saved: SavedRecording? = null) = AppShell(
    recorder = state.recorder,
    callbacks = cb,
    placement = recordPlacement(state.recorder.phase, roomForExpanded = false),
    tab = AppTab.History,
    snackbar = { if (saved != null) SavedSnackbarHost(saved) },
) { RecordingsPage(state) }

@PreviewTest @PhonePreview @Composable
fun RecordingsList() = Frame { RecordingsPreview(FakeData.recordings) }

@PreviewTest @PhonePreview @Composable
fun RecordingsListIdle() = Frame { RecordingsPreview(FakeData.recordingsIdle) }

@PreviewTest @PhonePreview @Composable
fun RecordingsSavedSnackbar() = Frame { RecordingsPreview(FakeData.recordingsIdle, SavedRecording(23 * 60, 1_400)) }

@Composable
private fun MapPreview(state: MapUiState, recorder: RecorderUiState = FakeData.idle) = AppShell(
    recorder = recorder,
    callbacks = cb,
    placement = recordPlacement(recorder.phase, roomForExpanded = false),
) { MapPage(state, MapActions()) { covered -> PlaceholderMapFor(state, covered) } }

@PreviewTest @PhonePreview @Composable
fun RecordingMapRoute() = Frame { MapPreview(FakeData.recordingMap) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapDeviceSelected() = Frame { MapPreview(FakeData.recordingMapSelected) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapPannedHeatmap() = Frame { MapPreview(FakeData.recordingMapPanned) }

@PreviewTest @PhonePreview @Composable
fun ProductMap() = Frame { MapPreview(FakeData.productMap) }

/** The same floating pill as on every other page, with the map's own controls ending before it. */
@PreviewTest @PhonePreview @Composable
fun RecordingMapWithPill() = Frame { MapPreview(FakeData.recordingMap, FakeData.recording) }

@PreviewTest @PhonePreview @Composable
fun RecordingMapWithPillPaused() = Frame { MapPreview(FakeData.recordingMapSelected, FakeData.paused) }

@PreviewTest @PhonePreview @Composable
fun ProductMapWithPill() = Frame { MapPreview(FakeData.productMap, FakeData.recording) }

// ---- Record control, every state -----------------------------------------------------------

@Composable
private fun StateRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun Bottom(state: RecorderUiState) = BottomRecordControl(state, cb)

@Composable
private fun Pill(state: RecorderUiState) = RecordPill(state, cb)

@PreviewTest @PhonePreview @Composable
fun RecordControlStates() = Frame {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        StateRow("Idle · round button") { Bottom(FakeData.idle) }
        StateRow("Active · expanded (Scan page)") { Bottom(FakeData.recording) }
        StateRow("Paused · expanded (Scan page)") { Bottom(FakeData.paused) }
        StateRow("Active · floating pill") { Pill(FakeData.recording) }
        StateRow("Paused · floating pill") { Pill(FakeData.paused) }
        StateRow("Scroll to top, stacked above the round button") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollToTopButton({})
                Bottom(FakeData.idle)
            }
        }
        StateRow("After stop") { RecordSavedSnackbar(23 * 60, 1_400, Modifier.width(380.dp)) }
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

