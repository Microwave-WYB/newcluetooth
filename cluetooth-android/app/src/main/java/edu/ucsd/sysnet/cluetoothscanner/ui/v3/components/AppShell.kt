package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState

/** The mini record button sits here on every page: top-right, centred on a 64 dp top bar. */
val MiniRecordSlotWidth = 56.dp

/**
 * The frame of the whole app: pages, bottom navigation and the one record button. There is a
 * single [SharedTransitionLayout] at the top, so the record button moves between the FAB
 * corner (scan page, no sheet) and the top-right corner (everywhere else) with a shared-bounds
 * transition, whichever page is showing.
 *
 * Draw order, bottom to top: [content] and navigation, the full-size record button, [overlay]
 * (sheets and their scrim), the mini record button, the control bar, [snackbar].
 *
 * Pages draw their own top bar and leave [MiniRecordSlotWidth] free at its right end.
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
    /** Mini button on a floating surface, for pages whose top-right is a map. */
    floatingMini: Boolean = false,
    controlBarOpen: Boolean = false,
    onDismissControlBar: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    snackbar: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    SharedTransitionLayout(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val scope = this

        @Composable
        fun Shared(visibility: AnimatedVisibilityScope): Modifier = with(scope) {
            Modifier.sharedBounds(rememberSharedContentState("record-control"), visibility)
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                if (tab != null) V3BottomNav(tab, onTab)
            }
            // Full-size record button, with the scroll-to-top button stacked directly above it.
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .padding(end = 16.dp, bottom = (if (tab != null) 64.dp else 0.dp) + 16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                AnimatedVisibility(showScrollToTop && placement == RecordPlacement.Full, enter = fadeIn(), exit = fadeOut()) {
                    ScrollToTopButton(onScrollToTop, Modifier.padding(end = 10.dp, bottom = 12.dp))
                }
                AnimatedVisibility(placement == RecordPlacement.Full, enter = fadeIn(), exit = fadeOut()) {
                    RecordControl(recorder, RecordPlacement.Full, callbacks, Shared(this))
                }
            }
            overlay()
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 8.dp)) {
                AnimatedVisibility(placement == RecordPlacement.Mini, enter = fadeIn(), exit = fadeOut()) {
                    val mini = Shared(this)
                    if (floatingMini) {
                        Surface(
                            mini,
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) { RecordControl(recorder, RecordPlacement.Mini, callbacks) }
                    } else {
                        RecordControl(recorder, RecordPlacement.Mini, callbacks, mini)
                    }
                }
            }
            if (controlBarOpen) {
                SheetLayer(onDismissControlBar, scrimTopInset = 0.dp) { RecordControlBar(recorder, callbacks) }
            }
            snackbar()
        }
    }
}
