package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import kotlin.math.roundToInt

/**
 * How far the floating pill reaches in from the right edge of the screen, or 0 while it is not
 * shown. [PageTopBar] ends its actions this far from the edge so they shift left only while the
 * pill is there.
 */
val LocalRecordPillInset = compositionLocalOf { 0.dp }

/** Pill width for a time with or without an hour part, plus a gap. */
private fun pillInset(elapsedSeconds: Int): Dp = if (elapsedSeconds >= 3600) 116.dp else 100.dp

/**
 * A page with its own persistent bottom sheet (the map pages) reports how tall the sheet is on
 * screen, so the bottom-right record button rides above it.
 */
@Stable
class RecordButtonLift {
    /** Visible height of the page's sheet, in pixels. */
    var px by mutableStateOf(0f)

    /** The sheet is up; the bottom button steps aside. */
    var hidden by mutableStateOf(false)
}

val LocalRecordButtonLift = compositionLocalOf { RecordButtonLift() }

/**
 * The frame of the whole app, a stock [Scaffold]: the page's [topBar], the stock navigation bar
 * on the tabs, the snackbar host (errors only) and the record control in the FAB slot. The
 * control moves between that slot and a floating pill at the top-right with a shared-bounds
 * transition (one [SharedTransitionLayout] around everything). The pill floats over the top app
 * bar inside the status-bar inset; the bars reserve no room for it.
 *
 * The control sheet opened from the pill is a stock [ModalBottomSheet].
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
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
    content: @Composable (PaddingValues) -> Unit,
) {
    val lift = remember { RecordButtonLift() }
    val pillShown = placement == RecordPlacement.Pill
    val target = if (pillShown) pillInset(recorder.elapsedSeconds) else 0.dp
    val inset = if (LocalStaticRender.current) target else animateDpAsState(target, label = "pill-inset").value

    SharedTransitionLayout(modifier.fillMaxSize()) {
        val scope = this
        var windowHeight by remember { mutableStateOf(800.dp) }
        val density = LocalDensity.current

        @Composable
        fun Shared(visibility: AnimatedVisibilityScope): Modifier = with(scope) {
            Modifier.sharedBounds(rememberSharedContentState("record-control"), visibility)
        }

        CompositionLocalProvider(LocalRecordPillInset provides inset, LocalRecordButtonLift provides lift, LocalWindowHeight provides windowHeight) {
            Box(Modifier.fillMaxSize().onSizeChanged { windowHeight = with(density) { it.height.toDp() } }) {
                Scaffold(
                    topBar = topBar,
                    bottomBar = { if (tab != null) V3NavigationBar(tab, onTab) },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    floatingActionButton = {
                        val bottomShown = !pillShown && !lift.hidden
                        val navBottom = WindowInsets.navigationBars
                        Column(
                            // Above a page's own bottom sheet (map pages), measured from the screen edge.
                            Modifier.offset {
                                val base = navBottom.getBottom(this) + 16.dp.roundToPx()
                                // Never higher than mid-screen, so it cannot reach the top app bar while a sheet is dragged up.
                                val maxLift = windowHeight.toPx() * 0.5f
                                val want = if (lift.px > 0f) lift.px.coerceAtMost(maxLift) + 16.dp.toPx() else 0f
                                IntOffset(0, -(want - base).coerceAtLeast(0f).roundToInt())
                            },
                            horizontalAlignment = Alignment.End,
                        ) {
                            AnimatedVisibility(bottomShown, enter = fadeIn(), exit = fadeOut()) {
                                BottomRecordControl(recorder, callbacks, Shared(this))
                            }
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
                        RecordPill(recorder, callbacks, Shared(this))
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
