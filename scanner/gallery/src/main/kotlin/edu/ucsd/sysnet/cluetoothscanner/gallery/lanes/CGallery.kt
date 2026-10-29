// Lane C owns this file (docs/v2-api.md §6.0); the stub is from B0.
package edu.ucsd.sysnet.cluetoothscanner.gallery.lanes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.chrome.DeleteConfirm
import edu.ucsd.sysnet.cluetoothscanner.chrome.LocationPopover
import edu.ucsd.sysnet.cluetoothscanner.chrome.Onboarding
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingStep
import edu.ucsd.sysnet.cluetoothscanner.chrome.OnboardingUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.PhoneTopChrome
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderControl
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchPageState
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsList
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsPage
import edu.ucsd.sysnet.cluetoothscanner.chrome.SettingsUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.StorageUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.TopChromeActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.TopChromeState
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.DesignSystemGallery
import edu.ucsd.sysnet.cluetoothscanner.gallery.GalleryEntry
import edu.ucsd.sysnet.cluetoothscanner.gallery.LaneGallery
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys

object CGallery : LaneGallery {
    override val lane = "C"
    override val entries: List<GalleryEntry> = listOf(
        GalleryEntry("Design system (C-1 … C-17)") { CluetoothTheme { DesignSystemGallery() } },
        GalleryEntry("Recorder (tap to morph)") { CluetoothTheme { RecorderDemo() } },
        GalleryEntry("Top chrome and search") { CluetoothTheme { SearchDemo() } },
        GalleryEntry("Sessions list") { CluetoothTheme { SessionsList(SessionsListState(null, AllSessionsSummary(5, 1277, null), emptyList(), Scope.All), {}, {}) } },
        GalleryEntry("Settings") { CluetoothTheme { SettingsDemo() } },
        GalleryEntry("Location popover") {
            CluetoothTheme { LocationPopover(LocationStatus(LocationStatus.State.Denied, true, action = LocationStatus.Action.Allow), {}) }
        },
        GalleryEntry("Onboarding") { CluetoothTheme { OnboardingDemo() } },
    )
}


@Composable
private fun RecorderDemo() {
    var ui by remember { mutableStateOf(RecorderUi(RecorderUi.Form.Fab, false, 754_000, 26, null, emptyList())) }
    Box(Modifier.fillMaxSize().height(600.dp).background(CluetoothTheme.colors.fill2)) {
        RecorderControl(
            ui,
            RecorderActions(
                onStart = { ui = ui.copy(form = RecorderUi.Form.Capsule) },
                onExpand = { ui = ui.copy(form = RecorderUi.Form.Panel) },
                onCollapse = { ui = ui.copy(form = RecorderUi.Form.Capsule) },
                onPause = { ui = ui.copy(paused = true) }, onResume = { ui = ui.copy(paused = false) },
                onStop = { ui = ui.copy(form = RecorderUi.Form.Fab, paused = false) }, onMiniMapClick = {},
            ),
            tablet = false,
        )
    }
}

@Composable
private fun SearchDemo() {
    var state by remember { mutableStateOf(TopChromeState(hasSession = true)) }
    Box(Modifier.fillMaxSize().height(600.dp).background(CluetoothTheme.colors.fill2)) {
        PhoneTopChrome(
            state,
            TopChromeActions(
                onMenu = { state = state.copy(sidebarOpen = !state.sidebarOpen) },
                onSearchOpen = { state = state.copy(page = SearchPageState("", null, null, false)) },
                onSearchBack = { state = state.copy(page = null) },
                onQueryChange = { state = state.copy(page = state.page?.copy(query = it, results = OrderedKeys(longArrayOf()))) },
                onPick = {}, onSearchEverywhere = {}, onBarEdit = {}, onBarExit = { state = state.copy(searchQuery = null) },
                onSearchThisArea = {},
            ),
            emptyList(), live = false,
        )
    }
}

@Composable
private fun SettingsDemo() {
    var s by remember { mutableStateOf(Settings("wyb01", true, false, true, true, "0.1.0 (gallery)")) }
    var st by remember { mutableStateOf(StorageUi(12_000_000, 340_000_000, 48_000_000, 3, Scope.All, null, false, null)) }
    SettingsPage(
        true, SettingsUi(s, UploadStatus(3, 4_200_000, 0, null), st),
        SettingsActions(
            onBack = {}, onSettings = { s = it(s) }, onUploadNow = {}, onExportScope = { st = st.copy(exportScope = it) },
            onExportFormat = { st = st.copy(exportFormat = it) }, onShare = {}, onSaveToDownloads = {},
            onDeleteRequest = { st = st.copy(deleteConfirm = DeleteConfirm.Confirm) },
            onDeleteConfirm = { st = st.copy(deleteConfirm = DeleteConfirm.LoseUnuploaded) },
            onDelete = { st = st.copy(deleteConfirm = null) }, onDeleteCancel = { st = st.copy(deleteConfirm = null) },
        ),
        Modifier.height(900.dp),
    )
}

@Composable
private fun OnboardingDemo() {
    var ui by remember { mutableStateOf(OnboardingUi(1, PermissionState(Perm.Granted, Perm.Granted, Perm.Denied, true), "", false, true)) }
    Onboarding(
        ui, OnboardingStep.entries,
        OnboardingActions(
            onRequest = {}, onOpenSettings = {}, onEnableBluetooth = {}, onUserId = { ui = ui.copy(userId = it, userIdValid = Settings.isValidUserId(it)) },
            onAutoRecord = { ui = ui.copy(autoRecord = it) }, onBack = { ui = ui.copy(step = ui.step - 1) },
            onNext = { ui = ui.copy(step = ui.step + 1) }, onDone = {},
        ),
        Modifier.height(800.dp),
    )
}

