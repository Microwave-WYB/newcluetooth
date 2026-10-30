package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.SpecValues
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * R-4a's data: a recording's new devices per bin and its runs, wall-clock ms, as the core keeps them (each device's
 * first-scanned time in the session, so it survives an app restart). [bins] may be sparse (a bin without new devices
 * may be left out) and in any order; a bin belongs to the run its start falls in, or counts in the next run's first
 * bin if it starts in a pause (or before the first run). [runs] are the stretches from a start or resume to a pause or stop, in order.
 */
data class DiscoveryBins(val binMs: Long, val bins: List<Bin>, val runs: List<Run>) {
    data class Bin(val startMs: Long, val newDevices: Int)

    /** [endMs] is null while the run goes on. */
    data class Run(val startMs: Long, val endMs: Long?)

    companion object {
        /** R-4a: devices first scanned in each 30 s bin. */
        const val BIN_MS = 30_000L
        val Empty = DiscoveryBins(BIN_MS, emptyList(), emptyList())
    }
}

/** R-4a, pure: the discovery rate as a series, and the series as lines in a box of pixels. */
object DiscoveryChart {
    /** [x]: the recording's elapsed time (paused time left out), ms; [perMin]: new devices per minute. */
    data class Point(val x: Long, val perMin: Float)

    /** One list of points per run that has any; the line breaks between two. */
    data class Series(val runs: List<List<Point>>) {
        val last: Point? get() = runs.lastOrNull()?.lastOrNull()

        companion object {
            val Empty = Series(emptyList())
        }
    }

    /** The series in pixels: one polyline per run, the 0 baseline's y, and the line's end (for the label). */
    data class Shape(val lines: List<List<Offset>>, val baseline: Float, val end: Offset?)

    /**
     * The series at [elapsedMs] of recording. Each run is cut into bins from its own start; a bin's devices × 2 is
     * its rate (a run's last, shorter bin is scaled by its length, at least half a bin), lightly smoothed with
     * 1-2-1 weights. The bin still filling in the running run is left out until it is complete, so the line never
     * dips at its end. A run's line starts at the run's start at its first bin's rate, then a point per bin end.
     */
    fun series(data: DiscoveryBins, elapsedMs: Long): Series {
        val binMs = data.binMs
        if (binMs <= 0) return Series.Empty
        val bins = data.bins.sortedBy { it.startMs }
        var next = 0
        var activeStart = 0L
        val out = ArrayList<List<Point>>()
        for (run in data.runs) {
            val end = run.endMs
            val len = (if (end == null) elapsedMs - activeStart else end - run.startMs).coerceAtLeast(0)
            val slots = (if (end == null) len / binMs else (len + binMs - 1) / binMs).toInt()
            val counts = IntArray(slots)
            // the bins up to this run's end; one that starts before the run counts in its first bin
            while (next < bins.size && (end == null || bins[next].startMs < end)) {
                val b = bins[next++]
                val slot = ((b.startMs - run.startMs).coerceAtLeast(0) / binMs).toInt()
                if (slot < slots) counts[slot] += b.newDevices.coerceAtLeast(0)
            }
            if (slots > 0) {
                val rates = FloatArray(slots) { k ->
                    val dur = minOf(binMs, len - k * binMs).coerceAtLeast(binMs / 2)
                    counts[k] * MINUTE_MS / dur
                }
                val smooth = smooth(rates)
                val pts = ArrayList<Point>(slots + 1)
                pts += Point(activeStart, smooth[0])
                for (k in 0 until slots) pts += Point(activeStart + minOf((k + 1) * binMs, len), smooth[k])
                out += pts
            }
            activeStart += len
        }
        return Series(out)
    }

    /** 1-2-1 weights, renormalized at the ends. */
    internal fun smooth(v: FloatArray): FloatArray = FloatArray(v.size) { i ->
        var sum = v[i] * 2
        var w = 2f
        if (i > 0) { sum += v[i - 1]; w += 1 }
        if (i < v.size - 1) { sum += v[i + 1]; w += 1 }
        sum / w
    }

    /**
     * The series in a [width] × [height] px box: x spans the series' elapsed time with a [gap] wide break per pause,
     * y runs from 0 at the bottom to the highest rate (at least [MIN_SCALE_PER_MIN], with a little headroom) at [top],
     * the room kept free above for the label; the line stays [stroke]/2 inside the box. Each run is thinned to at most four points per pixel column (first,
     * lowest, highest, last), so a long session costs the box's width, not its length.
     */
    fun layout(series: Series, width: Float, height: Float, stroke: Float, gap: Float, top: Float = 0f): Shape {
        val half = stroke / 2
        val baseline = height - half
        val last = series.last
        val plotTop = maxOf(half, top)
        if (last == null || width <= stroke || baseline <= plotTop) return Shape(emptyList(), baseline, null)
        val breaks = gap * (series.runs.size - 1)
        val plotW = (width - stroke - breaks).coerceAtLeast(1f)
        val xMax = last.x.coerceAtLeast(1).toFloat()
        val yMax = maxOf(series.runs.maxOf { r -> r.maxOf { it.perMin } } * HEADROOM, MIN_SCALE_PER_MIN)
        val plotH = baseline - plotTop
        val lines = series.runs.mapIndexed { i, run ->
            thin(run.map { Offset(half + it.x / xMax * plotW + gap * i, baseline - it.perMin / yMax * plotH) })
        }
        return Shape(lines, baseline, lines.lastOrNull()?.lastOrNull())
    }

    /** Keeps, per pixel column, the first, lowest, highest and last point, in order. */
    internal fun thin(pts: List<Offset>): List<Offset> {
        if (pts.size <= 4) return pts
        val out = ArrayList<Offset>()
        var i = 0
        while (i < pts.size) {
            val col = floor(pts[i].x)
            var j = i
            var lo = i
            var hi = i
            while (j < pts.size && floor(pts[j].x) == col) {
                if (pts[j].y < pts[lo].y) lo = j
                if (pts[j].y > pts[hi].y) hi = j
                j++
            }
            sortedSetOf(i, lo, hi, j - 1).forEach { out += pts[it] }
            i = j
        }
        return out
    }

    /**
     * Where the [w] × [h] label goes: right-aligned to the line's end, [gap] above the highest point of the line
     * within the label's own x span (the stroke's half included), so a line that falls or rises at its end never
     * runs through it; below the lowest point there if there is no room above; at the top if neither fits.
     * [DiscoveryRateChart] keeps the room above free ([layout]'s `top`), so it is always above there.
     */
    fun labelAt(shape: Shape, w: Float, h: Float, gap: Float, stroke: Float, height: Float): Offset? {
        val end = shape.end ?: return null
        val x = (end.x - w).coerceAtLeast(0f)
        val (highest, lowest) = ySpan(shape.lines, x, x + w) ?: (end.y to end.y)
        val above = highest - stroke / 2 - gap - h
        val below = lowest + stroke / 2 + gap
        return Offset(x, if (above >= 0f) above else if (below + h <= height) below else 0f)
    }

    /** The smallest and largest y of [lines] over x in [x0]..[x1], interpolated at the edges; null where none runs. */
    internal fun ySpan(lines: List<List<Offset>>, x0: Float, x1: Float): Pair<Float, Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        fun take(y: Float) { lo = minOf(lo, y); hi = maxOf(hi, y) }
        for (pts in lines) {
            pts.singleOrNull()?.let { if (it.x in x0..x1) take(it.y) }
            pts.zipWithNext { a, b ->
                val from = maxOf(a.x, x0)
                val to = minOf(b.x, x1)
                if (from <= to) {
                    if (b.x == a.x) { take(a.y); take(b.y) } else {
                        take(a.y + (b.y - a.y) * (from - a.x) / (b.x - a.x))
                        take(a.y + (b.y - a.y) * (to - a.x) / (b.x - a.x))
                    }
                }
            }
        }
        return if (lo <= hi) lo to hi else null
    }

    /** The label's value: the line's end, rounded. */
    fun current(series: Series): Int? = series.last?.perMin?.roundToInt()

    private const val MINUTE_MS = 60_000f
    private const val HEADROOM = 1.15f
    /** A near-empty recording's 0–1 devices per minute does not fill the chart's height. */
    const val MIN_SCALE_PER_MIN = 4f
}

/**
 * R-4a: the discovery-rate line (`--blue`) with a soft fill under it, baseline 0 and no grid; the one label,
 * "N new/min" (12 `--t2`, tabular), right-aligned to the line's end, sits above the line under it ([DiscoveryChart.labelAt])
 * in room the plot keeps free.
 * An empty series (no bin complete yet) draws nothing.
 */
@Composable
fun DiscoveryRateChart(series: DiscoveryChart.Series, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val style = CluetoothTheme.type.small.copy(fontSize = SpecValues.recorderChartLabelSize, lineHeight = SpecValues.recorderChartLabelSize).tabular()
    val measurer = rememberTextMeasurer()
    val label = DiscoveryChart.current(series)?.let(Strings::newPerMinute)
    Spacer(
        modifier
            .testTag("discoveryChart")
            .semantics { if (label != null) contentDescription = label }
            .drawWithCache {
                val stroke = SpecValues.recorderChartStroke.toPx()
                val labelGap = SpecValues.recorderChartLabelGap.toPx()
                val text = label?.let { measurer.measure(it, style) }
                val top = text?.let { it.size.height + labelGap + stroke / 2 } ?: 0f
                val shape = DiscoveryChart.layout(series, size.width, size.height, stroke, SpecValues.recorderChartPauseGap.toPx(), top)
                val lines = shape.lines.map { pts -> Path().apply { pts.forEachIndexed { k, p -> if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } } }
                val fills = shape.lines.map { pts ->
                    Path().apply {
                        pts.forEachIndexed { k, p -> if (k == 0) moveTo(p.x, shape.baseline); lineTo(p.x, p.y) }
                        pts.lastOrNull()?.let { lineTo(it.x, shape.baseline) }
                        close()
                    }
                }
                val fill = Brush.verticalGradient(listOf(c.blue.copy(alpha = FILL_TOP_ALPHA), c.blue.copy(alpha = FILL_BOTTOM_ALPHA)))
                val textAt = text?.let { DiscoveryChart.labelAt(shape, it.size.width.toFloat(), it.size.height.toFloat(), labelGap, stroke, size.height) }
                onDrawBehind {
                    fills.forEach { drawPath(it, fill) }
                    lines.forEach { drawPath(it, c.blue, style = Stroke(stroke, join = StrokeJoin.Round)) }
                    if (text != null && textAt != null) drawText(text, c.t2, textAt)
                }
            },
    )
}

private const val FILL_TOP_ALPHA = 0.18f
private const val FILL_BOTTOM_ALPHA = 0.02f
