package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.roundToInt

/** The Compose animator: `--t-page` / `--ease` tweens (G-7); 0 ms is instant. */
internal fun composeOffsetAnimator(metrics: SheetMetrics) = OffsetAnimator { from, to, durationMs, onValue ->
    if (durationMs <= 0 || from == to) {
        onValue(to)
    } else {
        animate(from, to, animationSpec = tween(durationMs, easing = metrics.ease)) { value, _ -> onValue(value) }
    }
}

/**
 * Places a phone sheet at its offset in the placement phase only (no recomposition per frame) and records where it
 * was placed, which is the frame its pointer positions are relative to.
 */
internal fun Modifier.sheetOffset(controller: SheetStackController, entry: SheetEntry): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val y = controller.offsetOf(entry).roundToInt()
        entry.placedTop = y.toFloat()
        placeable.place(0, y)
    }
}

/**
 * The phone sheet's pointer handling, on the whole sheet, in the Initial pass so it sees every move before the
 * controls and the content (S-10, S-10a, S-11, S-13, G-13).
 *
 * - Down: [SheetStackController.beginDrag]; the header region is everything above the measured peek.
 * - Moves the controller takes are consumed, so a control under the finger loses its press and the content never
 *   starts its own scroll.
 * - A pending gesture a child consumed (a horizontal card row) is given up.
 * - Up within the slop on the header, with no control consuming it: [SheetStackController.tapHeader] (S-11).
 *
 * Positions are converted to screen y with the offset the sheet was placed at, so the 1:1 rule holds even while the
 * sheet moves under the finger.
 */
@OptIn(ExperimentalComposeUiApi::class) // PointerInputChange.historical: every batched sample counts (S-14)
internal fun Modifier.sheetGestures(controller: SheetStackController, entry: SheetEntry): Modifier =
    pointerInput(controller, entry) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val region = if (!entry.peek.isNaN() && down.position.y < entry.peek) DragRegion.Header else DragRegion.Content
            if (controller.tablet) {
                if (region == DragRegion.Header) {
                    if (awaitUpConsumed(down.id) == false) controller.tapHeader(entry)
                }
                return@awaitEachGesture
            }
            controller.beginDrag(entry, region, down.position.x, entry.placedTop + down.position.y, down.uptimeMillis)
                ?: return@awaitEachGesture
            var upConsumedByChild = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) break
                val top = entry.placedTop
                var taken = false
                change.historical.forEach { h ->
                    taken = controller.dragMove(entry, h.position.x, top + h.position.y, h.uptimeMillis) || taken
                }
                taken = controller.dragMove(entry, change.position.x, top + change.position.y, change.uptimeMillis) || taken
                val mode = entry.drag?.mode
                if (taken || mode == SheetDrag.Mode.Sheet) change.consume()
                if (!change.pressed) {
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    upConsumedByChild = final.changes.firstOrNull { it.id == down.id }?.isConsumed == true &&
                        mode != SheetDrag.Mode.Sheet
                    break
                }
                if (mode == SheetDrag.Mode.Pending) {
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (final.changes.firstOrNull { it.id == down.id }?.isConsumed == true) controller.dragYield(entry)
                }
                if (mode == SheetDrag.Mode.Native || mode == SheetDrag.Mode.Ignored) break
            }
            val release = controller.endDrag(entry)
            if (release?.tap == true && region == DragRegion.Header && !upConsumedByChild) controller.tapHeader(entry)
        }
    }

/** S-15: the wheel over a phone sheet; consumed only when it changes or would change the height. */
internal fun Modifier.sheetWheel(controller: SheetStackController, entry: SheetEntry): Modifier =
    pointerInput(controller, entry) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (controller.onWheel(entry, change.scrollDelta.y, change.uptimeMillis)) change.consume()
            }
        }
    }

/** Waits for [id] to go up: whether a child consumed the up, or null when the pointer went away. */
private suspend fun AwaitPointerEventScope.awaitUpConsumed(id: PointerId): Boolean? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return null
        if (!change.pressed) {
            val final = awaitPointerEvent(PointerEventPass.Final)
            return final.changes.firstOrNull { it.id == id }?.isConsumed == true
        }
    }
}

/**
 * The content's own touch slop, a hair above the sheet's, so the sheet always decides first (S-13) and the content
 * never scrolls a few px before the sheet takes the gesture.
 */
internal fun ViewConfiguration.withSlopAbove(slopPx: Float): ViewConfiguration {
    val base = this
    return object : ViewConfiguration by base {
        override val touchSlop: Float get() = slopPx + 1f
    }
}

/** Adapts a Compose scrollable to the engine. */
internal class ScrollableContent(private val state: ScrollableState, private val flingBehavior: FlingBehavior) : ContentScroll {
    override val canScrollBackward: Boolean get() = state.canScrollBackward
    override fun scrollBy(delta: Float): Float = state.dispatchRawDelta(delta)
    override suspend fun fling(velocity: Float) {
        state.scroll { with(flingBehavior) { performFling(velocity) } }
    }
}

/**
 * A LazyList state for one list of [entry]'s content, under [key] (the main list by default): it starts at that
 * list's saved scroll (S-8), lets the sheet drive it (S-13), and moves when a restore targets it (Q-7). Leaving the
 * composition saves its position under its own key only, so a second list never overwrites the main one's.
 */
@Composable
fun rememberSheetListState(entry: SheetEntry, key: Any = SheetEntry.MAIN_LIST): LazyListState {
    val state = remember(entry.id, key) { entry.scrollOf(key).let { LazyListState(it.index, it.offset) } }
    val fling = ScrollableDefaults.flingBehavior()
    DisposableEffect(entry, key, state, fling) {
        val content = ScrollableContent(state, fling)
        entry.attachList(key, content, { ScrollSnapshot(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }) {
            state.scrollToItem(it.index, it.offset)
        }
        onDispose { entry.detachList(key, content) }
    }
    return state
}

/** The same for a plain vertically scrolling column. */
@Composable
fun rememberSheetScrollState(entry: SheetEntry, key: Any = SheetEntry.MAIN_LIST): ScrollState {
    val state = remember(entry.id, key) { ScrollState(entry.scrollOf(key).offset) }
    val fling = ScrollableDefaults.flingBehavior()
    DisposableEffect(entry, key, state, fling) {
        val content = ScrollableContent(state, fling)
        entry.attachList(key, content, { ScrollSnapshot(0, state.value) }) { state.scrollTo(it.offset) }
        onDispose { entry.detachList(key, content) }
    }
    return state
}

/** Provides the content slop of [withSlopAbove] below this point. */
@Composable
internal fun SheetContentSlop(slopPx: Float, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val config = remember(base, slopPx) { base.withSlopAbove(slopPx) }
    CompositionLocalProvider(LocalViewConfiguration provides config, content = content)
}
