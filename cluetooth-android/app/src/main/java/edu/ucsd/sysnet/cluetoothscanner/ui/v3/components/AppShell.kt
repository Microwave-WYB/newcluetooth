package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.LocalWindowHeight
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender

/**
 * How far the floating pill reaches in from the right edge of the screen, or 0 while it is not
 * shown. [PageTopBar] ends its actions this far from the edge so they shift left only while the
 * pill is there.
 */
val LocalRecordPillInset = compositionLocalOf { 0.dp }

/** Pill width for a time with or without an hour part, plus a gap. */
private fun pillInset(elapsedSeconds: Int): Dp = if (elapsedSeconds >= 3600) 116.dp else 100.dp

/**
 * The frame of the whole app, a stock [Scaffold]: the page's [topBar], the stock navigation bar
 * on the tabs, the snackbar host (errors only) and the record control in the FAB slot. While
 * recording off the Scan page, or with something covering it, the control is a floating pill at
 * the top-right instead; the two cross-fade (plain AnimatedVisibility, no shared-element lookahead:
 * a shared-bounds morph of the FAB slot crashed with "Placement happened before lookahead" when
 * the slot's content changed while it was leaving). The pill floats over the top app bar inside
 * the status-bar inset; the bars reserve no room for it.
 *
 * Recording starts only on the Scan page: elsewhere ([startAllowed] false) there is no record
 * button while idle; a running recording is paused or stopped from the pill and its control sheet.
 *
 * The control sheet opened from the pill is a stock [ModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    recorder: RecorderUiState,
    callbacks: RecorderCallbacks,
    placement: RecordPlacement,
    modifier: Modifier = Modifier,
    tab: AppTab? = null,
    onTab: (AppTab) -> Unit = {},
    topBar: @Composable () -> Unit = {},
    showScrollToTop: Boolean = false,
    onScrollToTop: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    controlBarOpen: Boolean = false,
    onDismissControlBar: () -> Unit = {},
    /** Whether the idle record button shows (the Scan page only). */
    startAllowed: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val pillShown = placement == RecordPlacement.Pill
    val target = if (pillShown) pillInset(recorder.elapsedSeconds) else 0.dp
    val inset = if (LocalStaticRender.current) target else animateDpAsState(target, label = "pill-inset").value

    Box(modifier.fillMaxSize()) {
        var windowHeight by remember { mutableStateOf(800.dp) }
        val density = LocalDensity.current

        CompositionLocalProvider(LocalRecordPillInset provides inset, LocalWindowHeight provides windowHeight) {
            Box(Modifier.fillMaxSize().onSizeChanged { windowHeight = with(density) { it.height.toDp() } }) {
                Scaffold(
                    topBar = topBar,
                    bottomBar = { if (tab != null) V3NavigationBar(tab, onTab) },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    floatingActionButton = {
                        val bottomShown = !pillShown && (startAllowed || recorder.phase != RecorderPhase.Idle)
                        AnimatedVisibility(bottomShown, enter = fadeIn(), exit = fadeOut()) {
                            BottomRecordControl(recorder, callbacks)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize()) {
                        content(padding)
                        // Scroll to top floats over the list, horizontally centered just below the top app bar.
                        // The record pill sits inside the app bar at the right, so they never meet; the record
                        // control at the bottom-right is untouched.
                        AnimatedVisibility(
                            showScrollToTop,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding() + 10.dp),
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ScrollToTopButton(onScrollToTop)
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 8.dp)) {
                    AnimatedVisibility(pillShown, enter = fadeIn(), exit = fadeOut()) {
                        RecordPill(recorder, callbacks)
                    }
                }
                if (controlBarOpen) {
                    ModalBottomSheet(onDismissRequest = onDismissControlBar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        RecordControlSheetContent(recorder, callbacks)
                    }
                }
            }
        }
    }
}
