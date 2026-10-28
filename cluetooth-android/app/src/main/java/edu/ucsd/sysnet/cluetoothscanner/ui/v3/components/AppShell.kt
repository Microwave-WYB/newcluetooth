package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState

/**
 * Top bar + content + bottom navigation, plus the record button in whichever of its two
 * places applies. [placement] is Full only on the scan page with no sheet open; the move
 * between the FAB corner and the top bar is a shared-bounds transition.
 *
 * [topBar] receives the slot for the mini record button; put it last in the actions.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppShell(
    tab: AppTab,
    onTab: (AppTab) -> Unit,
    recorder: RecorderUiState,
    callbacks: RecorderCallbacks,
    placement: RecordPlacement,
    modifier: Modifier = Modifier,
    showScrollToTop: Boolean = false,
    onScrollToTop: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    topBar: @Composable (recordSlot: @Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    SharedTransitionLayout(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val scope = this

        @Composable
        fun Shared(visibility: AnimatedVisibilityScope): Modifier = with(scope) {
            Modifier.sharedBounds(rememberSharedContentState("record-control"), visibility)
        }

        val miniSlot: @Composable () -> Unit = {
            AnimatedVisibility(placement == RecordPlacement.Mini, enter = fadeIn(), exit = fadeOut()) {
                RecordControl(recorder, RecordPlacement.Mini, callbacks, Shared(this))
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                topBar(miniSlot)
                Box(Modifier.weight(1f)) { content() }
                V3BottomNav(tab, onTab)
            }
            // Full-size record button, with the scroll-to-top button stacked directly above it.
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 64.dp + 16.dp),
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
        }
    }
}
