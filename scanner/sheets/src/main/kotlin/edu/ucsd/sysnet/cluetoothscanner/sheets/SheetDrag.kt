package edu.ucsd.sysnet.cluetoothscanner.sheets

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The content a sheet scrolls (S-13): a LazyList or a plain scroll, seen only through this. */
internal interface ContentScroll {
    /** True while the content is scrolled away from its top. */
    val canScrollBackward: Boolean

    /** Scrolls by [delta] px (positive: forward, the content moves up). Returns the px consumed. */
    fun scrollBy(delta: Float): Float

    /** Native momentum from [velocity] px/s (positive: forward). */
    suspend fun fling(velocity: Float)
}

/** Where a gesture started: the handle and header row (S-10), or the content below (S-13). */
internal enum class DragRegion { Header, Content }

/**
 * One pointer gesture on the top sheet, in screen px and milliseconds; plain state, no UI.
 *
 * - G-13: under [slop] it is a tap; past it, a mostly horizontal move is not ours ([Mode.Ignored]).
 * - S-10: on the header a vertical move past the slop always drags the sheet.
 * - S-13: on the content the first move past the slop decides: not at Full it drags the sheet; at Full with the
 *   content at its top and the finger moving down it lowers the sheet; otherwise it is native scroll.
 * - S-10a: while dragging, the sheet's top edge moves exactly as far as the finger, slop included. On the content,
 *   the part of an upward move beyond Full scrolls the content and a downward move first scrolls it back, so sheet
 *   movement plus content scroll always equals finger movement.
 * - S-14: on release, a flick (over [flick] px/ms across the last [samples] samples) moves exactly one state from
 *   [from] in its direction; otherwise the sheet snaps to the nearest state.
 */
internal class SheetDrag(
    private val region: DragRegion,
    private val geometry: SheetGeometry,
    private val pushed: Boolean,
    /** The state the sheet rested in, or was snapping to, when the finger went down. */
    val from: SheetHeight,
    startOffset: Float,
    private val content: ContentScroll?,
    private val slop: Float,
    private val flick: Float,
    samples: Int,
    private val x0: Float,
    private val y0: Float,
    t0: Long,
) {
    enum class Mode { Pending, Sheet, Native, Ignored }

    /** What the release asks for. [target] null: nothing to settle. [contentFling] px/s, forward positive. */
    data class Release(val tap: Boolean, val target: SheetHeight?, val contentFling: Float = 0f)

    var mode = Mode.Pending
        private set

    /** The sheet's top edge on screen. */
    var offset = startOffset
        private set

    /** Content px this gesture scrolled (forward positive). */
    var scrolled = 0f
        private set

    private var lastY = y0
    private val sampleT = LongArray(samples)
    private val sampleY = FloatArray(samples)
    private var sampleCount = 0
    private var sampleNext = 0

    init {
        sample(t0, y0)
    }

    /** Feeds one pointer position. Returns true when the move belongs to the sheet (the caller consumes it). */
    fun move(x: Float, y: Float, t: Long): Boolean {
        if (mode == Mode.Ignored || mode == Mode.Native) return false
        sample(t, y)
        if (mode == Mode.Pending) {
            val dx = abs(x - x0)
            val dy = abs(y - y0)
            if (max(dx, dy) < slop) return false
            mode = when {
                dx > dy -> Mode.Ignored
                region == DragRegion.Header -> Mode.Sheet
                !atFull() -> Mode.Sheet
                content?.canScrollBackward != true && y > y0 -> Mode.Sheet
                else -> Mode.Native
            }
            if (mode != Mode.Sheet) return false
        }
        val dy = y - lastY
        lastY = y
        apply(dy)
        return true
    }

    /** A child took the gesture (for example a horizontal card row). Only a pending gesture can be given up. */
    fun yield() {
        if (mode == Mode.Pending) mode = Mode.Ignored
    }

    fun release(): Release = when (mode) {
        Mode.Pending -> Release(tap = true, target = null)
        Mode.Native, Mode.Ignored -> Release(tap = false, target = null)
        Mode.Sheet -> {
            val v = velocity()
            val target =
                if (abs(v) > flick) geometry.step(from, up = v < 0, pushed = pushed)
                else geometry.nearest(offset, pushed)
            val fling =
                if (region == DragRegion.Content && target == SheetHeight.Full && atFull() && v < 0) -v * 1000f
                else 0f
            Release(tap = false, target = target, contentFling = fling)
        }
    }

    /** px/ms over the last samples, positive downward. */
    fun velocity(): Float {
        if (sampleCount < 2) return 0f
        val n = sampleT.size
        val first = (sampleNext - sampleCount + n) % n
        val last = (sampleNext - 1 + n) % n
        val dt = max(1L, sampleT[last] - sampleT[first])
        return (sampleY[last] - sampleY[first]) / dt
    }

    private fun atFull() = abs(offset - geometry.full) < 0.5f

    private fun apply(dy: Float) {
        val top = geometry.full
        val bottom = geometry.maxOffset(pushed)
        if (region == DragRegion.Header || content == null) {
            offset = (offset + dy).coerceIn(top, bottom)
            return
        }
        var d = dy
        if (d < 0) {
            val sheetPart = max(d, top - offset)
            offset += sheetPart
            d -= sheetPart
            if (d < 0) scrolled += content.scrollBy(-d)
        } else if (d > 0) {
            if (offset <= top && content.canScrollBackward) {
                val back = -content.scrollBy(-d)
                scrolled -= back
                d -= back
            }
            offset = min(bottom, offset + d)
        }
    }

    private fun sample(t: Long, y: Float) {
        sampleT[sampleNext] = t
        sampleY[sampleNext] = y
        sampleNext = (sampleNext + 1) % sampleT.size
        sampleCount = min(sampleCount + 1, sampleT.size)
    }
}

/**
 * S-15: a wheel or trackpad over a phone sheet. Down below Full raises it to Full; up with the content at its top
 * lowers it one state (never below Collapsed). Steps are at least [lockMs] apart; events in between are still taken.
 */
internal class WheelGate(private val lockMs: Long) {
    private var unlockAt = Long.MIN_VALUE

    /** Null: not ours, the content scrolls. Otherwise the wheel is consumed and [Step.target] (if any) applied. */
    data class Step(val target: SheetHeight?)

    fun onWheel(deltaY: Float, height: SheetHeight, contentAtTop: Boolean, nowMs: Long): Step? {
        val target = when {
            deltaY > 0 && height != SheetHeight.Full -> SheetHeight.Full
            deltaY < 0 && contentAtTop && height == SheetHeight.Full -> SheetHeight.Half
            deltaY < 0 && contentAtTop && height == SheetHeight.Half -> SheetHeight.Collapsed
            else -> return null
        }
        if (nowMs < unlockAt) return Step(null)
        unlockAt = nowMs + lockMs
        return Step(target)
    }
}
