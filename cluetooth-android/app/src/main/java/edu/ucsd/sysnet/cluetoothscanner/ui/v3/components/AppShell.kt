package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import kotlin.math.roundToInt

/**
 * How far the floating pill reaches in from the right edge of the screen, or 0 while it is not
 * shown. Top bars and floating page chrome end this far from the edge so their actions shift left
 * only while the pill is there.
 */
val LocalRecordPillInset = compositionLocalOf { 0.dp }

/** Pill width for a time with or without an hour part, plus a gap. */
private fun pillInset(elapsedSeconds: Int): Dp = if (elapsedSeconds >= 3600) 108.dp else 92.dp

/**
 * A page with its own bottom sheet (the map pages) reports the sheet's height here, so the
 * bottom-right record button rides above it instead of covering its rows.
 */
@Stable
class RecordButtonLift {
    /** Height of whatever the page has pinned to the bottom, in pixels. */
    var px by mutableStateOf(0f)

    /** The page is covered by its sheet; the bottom button steps aside. */
    var hidden by mutableStateOf(false)
}

val LocalRecordButtonLift = compositionLocalOf { RecordButtonLift() }

/**
 * The frame of the whole app: pages, bottom navigation and the one record control. There is a
 * single [SharedTransitionLayout] at the top, so the control moves between the bottom-right
 * corner and a floating pill at the top-right with a shared-bounds transition, whichever page
 * is showing.
 *
 * Draw order, bottom to top: [content] and navigation, the bottom-right record control,
 * [overlay] (sheets and their scrim), the floating pill, the control bar, [snackbar].
 *
 * The pill floats over the top-right corner inside the status-bar inset; pages reserve no room
 * for it. While it shows, top bars end [LocalRecordPillInset] away from the edge.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppShell(
    recorder: RecorderUiState,
    callbacks: RecorderCallbacks,
    placement: RecordPlacement,
    modifier: Modifier = Modifier,
    tab: AppTab? = null,
    onTab: (AppTab) -> Unit = {},
    showScrollToTop: Boolean = false,
    onScrollToTop: () -> Unit = {},
    controlBarOpen: Boolean = false,
    onDismissControlBar: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    snackbar: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val lift = remember { RecordButtonLift() }
    val pillShown = placement == RecordPlacement.Pill
    val target = if (pillShown) pillInset(recorder.elapsedSeconds) else 0.dp
    val inset = if (LocalStaticRender.current) target else animateDpAsState(target, label = "pill-inset").value

    SharedTransitionLayout(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val scope = this

        @Composable
        fun Shared(visibility: AnimatedVisibilityScope): Modifier = with(scope) {
            Modifier.sharedBounds(rememberSharedContentState("record-control"), visibility)
        }

        CompositionLocalProvider(LocalRecordPillInset provides inset, LocalRecordButtonLift provides lift) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { content() }
                    if (tab != null) V3BottomNav(tab, onTab)
                }
                // Bottom-right record control, with the scroll-to-top button stacked directly above it.
                val navBottom = WindowInsets.navigationBars
                val tabHeight = if (tab != null) 64.dp else 0.dp
                Column(
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                        .padding(end = 16.dp, bottom = tabHeight + 16.dp)
                        .offset {
                            // Above a page's own bottom sheet, measured from the screen edge.
                            val base = navBottom.getBottom(this) + (tabHeight + 16.dp).roundToPx()
                            val want = if (lift.px > 0f) lift.px + 16.dp.toPx() else 0f
                            IntOffset(0, -(want - base).coerceAtLeast(0f).roundToInt())
                        },
                    horizontalAlignment = Alignment.End,
                ) {
                    val bottomShown = !pillShown && !lift.hidden
                    AnimatedVisibility(showScrollToTop && bottomShown, enter = fadeIn(), exit = fadeOut()) {
                        ScrollToTopButton(onScrollToTop, Modifier.padding(end = 10.dp, bottom = 12.dp))
                    }
                    AnimatedVisibility(bottomShown, enter = fadeIn(), exit = fadeOut()) {
                        BottomRecordControl(recorder, callbacks, Shared(this))
                    }
                }
                overlay()
                Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 16.dp, end = 8.dp)) {
                    AnimatedVisibility(pillShown, enter = fadeIn(), exit = fadeOut()) {
                        RecordPill(recorder, callbacks, Shared(this))
                    }
                }
                if (controlBarOpen) {
                    // Composed after the sheets, so Back closes the control bar before anything beneath it.
                    BackHandler(onBack = onDismissControlBar)
                    SheetLayer(onDismissControlBar, scrimTopInset = 0.dp) { RecordControlBar(recorder, callbacks) }
                }
                snackbar()
            }
        }
    }
}
