package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import kotlin.math.abs
import kotlinx.coroutines.launch

/** The visible height of a [DragSheet] in pixels; readable by the page for scrims and map insets. */
class DragSheetState {
    val height = Animatable(0f)

    /** The lowest rest height, set by the sheet; scrims fade in over it. */
    var lowest by mutableFloatStateOf(1f)
        internal set

    /** 0 when hidden, 1 once the sheet reaches its lowest rest height. */
    val progress: Float get() = (height.value / lowest).coerceIn(0f, 1f)
}

@Composable
fun rememberDragSheetState(): DragSheetState = remember { DragSheetState() }

/**
 * A bottom sheet in the page (not a dialog), so the top bar and the record button stay
 * reachable. It snaps between [levels] (pixel heights, ascending) as the user drags the
 * handle or the sheet, and goes away when dragged below the lowest level if [onDismiss] is set.
 *
 * @param levels maps the container height in pixels to the snap heights
 * @param level index of the level the sheet should rest at; the page owns it
 * @param content receives whether the sheet is at its tallest level
 */
@Composable
fun BoxScope.DragSheet(
    state: DragSheetState,
    levels: (containerPx: Float) -> List<Float>,
    level: Int,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.(expanded: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val containerPx = constraints.maxHeight.toFloat()
        val lv = levels(containerPx)
        val top = lv.last()
        val scope = rememberCoroutineScope()
        val static = LocalStaticRender.current
        SideEffect { state.lowest = lv.first() }

        LaunchedEffect(level, lv) {
            state.height.animateTo(lv[level.coerceIn(lv.indices)], spring(stiffness = Spring.StiffnessMediumLow))
        }

        val drag = rememberDraggableState { delta ->
            scope.launch { state.height.snapTo((state.height.value - delta).coerceIn(0f, top)) }
        }
        val onStopped: suspend (Float) -> Unit = { velocity ->
            // Project where the sheet is heading (velocity > 0 is downwards), then snap to the nearest rest.
            val projected = state.height.value - velocity * 0.18f
            val candidates = buildList { if (onDismiss != null) add(0f); addAll(lv) }
            val nearest = candidates.minBy { abs(it - projected) }
            if (nearest == 0f) {
                state.height.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                onDismiss?.invoke()
            } else {
                val index = lv.indexOf(nearest)
                onLevelChange(index)
                state.height.animateTo(nearest, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }

        // A preview renders one frame: show the rest height at once instead of animating to it.
        val restHeight = lv[level.coerceIn(lv.indices)]
        val currentHeight = { if (static) restHeight else state.height.value }
        // Only the flip to or from the top level recomposes the sheet; the height itself is read at layout time.
        val expanded by remember(top, lv.size, static, restHeight) {
            derivedStateOf { currentHeight() >= top - 2f && lv.size > 1 }
        }
        val corner = if (expanded) 0.dp else 24.dp
        Surface(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = currentHeight().toInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .draggable(drag, Orientation.Vertical, enabled = !expanded, onDragStopped = { onStopped(it) }),
            shape = RoundedCornerShape(topStart = corner, topEnd = corner),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val handle = @Composable { modifier: Modifier ->
                Box(
                    modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { onStopped(it) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.padding(vertical = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().then(if (expanded) Modifier.statusBarsPadding() else Modifier)) {
                    // Low sheets reserve room for the handle; the full-height one overlays it on the top bar.
                    if (!expanded) handle(Modifier)
                    content(expanded)
                }
                if (expanded) handle(Modifier.align(Alignment.TopCenter).statusBarsPadding())
            }
        }
    }
}
