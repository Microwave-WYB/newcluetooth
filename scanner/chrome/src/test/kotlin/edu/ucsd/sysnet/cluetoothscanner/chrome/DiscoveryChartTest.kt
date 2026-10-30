package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.ui.geometry.Offset
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryChart.Point
import edu.ucsd.sysnet.cluetoothscanner.chrome.gallery.DiscoverySamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-4a: the discovery-rate series and its layout, as pure functions. */
class DiscoveryChartTest {
    private val bin = DiscoveryBins.BIN_MS
    private fun data(bins: List<Bin>, vararg runs: Run) = DiscoveryBins(bin, bins, runs.toList())
    private fun rates(s: DiscoveryChart.Series) = s.runs.map { r -> r.map { it.perMin } }

    private fun series(bins: List<Bin>, elapsedMs: Long, vararg runs: Run = arrayOf(Run(0, null))) =
        DiscoveryChart.series(data(bins, *runs), elapsedMs)
    private fun at(s: DiscoveryChart.Series, x: Long) = s.runs.flatten().last { it.x == x }.perMin
    /** The label's value after [elapsedMs] of a recording with [bins]. */
    private fun label(bins: List<Bin>, elapsedMs: Long) = DiscoveryChart.current(series(bins, elapsedMs))

    /** 0 points: no run or no bin size; nothing is drawn and there is no label. */
    @Test fun emptyDrawsNothing() {
        assertEquals(DiscoveryChart.Series.Empty, DiscoveryChart.series(DiscoveryBins.Empty, 600_000))
        assertEquals(DiscoveryChart.Series.Empty, DiscoveryChart.series(DiscoveryBins(0, listOf(Bin(0, 3)), listOf(Run(0, null))), 600_000))
        assertEquals(DiscoveryChart.Series.Empty, series(emptyList(), 0))
        assertNull(DiscoveryChart.current(DiscoveryChart.Series.Empty))
        val shape = DiscoveryChart.layout(DiscoveryChart.Series.Empty, 300f, 180f, 4f, 12f)
        assertEquals(emptyList<List<Offset>>(), shape.lines); assertNull(shape.end); assertEquals(178f, shape.baseline)
    }

    /** Just started, nothing scanned: a flat 0 from the start to now, "0 new/min". */
    @Test fun justStartedIsAFlatZero() {
        val s = DiscoveryChart.series(DiscoverySamples.empty, DiscoverySamples.EMPTY_ELAPSED_MS)
        assertEquals(listOf(listOf(Point(0, 0f), Point(3_000, 0f))), s.runs)
        assertEquals(0, DiscoveryChart.current(s))
    }

    /** y at t is the devices first scanned in the trailing 30 s of recording × 2, a point per 5 s bin end. */
    @Test fun theTrailing30sTimesTwo() {
        // one device in every 5 s bin for two minutes: 6 in any 30 s, 12 a minute
        val steady = List(24) { k -> Bin(k * bin, 1) }
        val s = series(steady, 120_000)
        assertEquals((0..24).map { it * bin }, s.runs.single().map { it.x })
        (30_000L..120_000L step bin).forEach { assertEquals("at $it", 12f, at(s, it), 1e-4f) }
        // only the last 30 s count: four devices just now, none before
        assertEquals(8, label(listOf(Bin(0, 0), Bin(60_000, 4)), 65_000))
    }

    /** In the first 30 s, over the time recorded, at least 10 s. */
    @Test fun theFirst30sScaleByTheTimeRecorded() {
        val five = listOf(Bin(0, 5))
        val s = series(five, 30_000)
        assertEquals(30f, at(s, 5_000), 1e-4f) // 5 in 5 s, over the 10 s floor
        assertEquals(30f, at(s, 10_000), 1e-4f) // 5 in 10 s
        assertEquals(15f, at(s, 20_000), 1e-4f) // 5 in 20 s
        assertEquals(10f, at(s, 30_000), 1e-4f) // 5 in 30 s
        assertEquals(0f, at(s, 0), 0f) // nothing recorded yet
        assertEquals(0, label(five, 40_000)) // (10 s, 40 s] holds none of them
    }

    /** The bin still filling counts, its point at now: the end follows the live deltas within seconds. */
    @Test fun theFillingBinCounts() {
        val before = List(12) { k -> Bin(k * bin, 0) }
        val s = series(before + Bin(60_000, 3), 62_000)
        assertEquals(62_000L, s.last?.x)
        assertEquals(6, DiscoveryChart.current(s))
        // a live delta raises the filling bin's total: the end moves at once, nothing waits for the bin to end
        assertEquals(10, label(before + Bin(60_000, 5), 62_000))
        assertEquals(10, label(before + Bin(60_000, 5), 63_000))
    }

    /**
     * The owner's case: 43 devices in the first 30 s, then about nothing. The label drops below 10 new/min within
     * 35 s of the burst ending and stays there.
     */
    @Test fun aBurstLeavesTheWindowWithin35s() {
        val burst = listOf(8, 8, 7, 7, 7, 6).mapIndexed { k, n -> Bin(k * bin, n) }
        val bins = burst + Bin(100_000, 1)
        assertEquals(43, burst.sumOf { it.newDevices })
        assertEquals(86, label(bins, 30_000))
        val labels = (30_000L..180_000L step 1_000).associateWith { label(bins, it) ?: -1 }
        val below = labels.entries.first { it.value < 10 }.key
        assertTrue("below 10 at $below ms", below <= 30_000 + 35_000)
        assertTrue(labels.filterKeys { it >= below }.values.all { it < 10 })
    }

    /** No centered smoothing: a burst raises nothing before it, and nothing 30 s after it ends. */
    @Test fun nothingSmearsBackwardOrForward() {
        val quiet = List(12) { k -> Bin(k * bin, 0) } // a quiet minute
        val burst = (12 until 18).map { k -> Bin(k * bin, 9) } // 60 s to 90 s
        val s = series(quiet + burst, 180_000)
        val pts = s.runs.single()
        assertTrue(pts.filter { it.x <= 60_000 }.all { it.perMin == 0f })
        assertTrue(pts.first { it.x > 60_000 }.perMin > 0f)
        assertTrue(pts.filter { it.x >= 90_000 + DiscoveryChart.WINDOW_MS }.all { it.perMin == 0f })
        assertEquals(108f, at(s, 90_000), 1e-4f) // the whole burst in the window: 54 × 2
    }

    /** Standing still the line falls towards 0; walking somewhere new raises it. */
    @Test fun stationaryDecaysAndWalkingRises() {
        val still = DiscoveryChart.series(DiscoverySamples.stationary, DiscoverySamples.ELAPSED_MS).runs.single()
        assertTrue(still.first { it.x == 30_000L }.perMin > 30f)
        assertTrue(still.filter { it.x >= 8 * 60_000 }.all { it.perMin < 1f })
        assertEquals(0, DiscoveryChart.current(DiscoveryChart.series(DiscoverySamples.stationary, DiscoverySamples.ELAPSED_MS)))
        val walk = DiscoveryChart.series(DiscoverySamples.rising, DiscoverySamples.ELAPSED_MS).runs.single()
        assertTrue(walk.last().perMin > walk.first { it.x == 30_000L }.perMin * 3)
    }

    /**
     * x is recording time: a pause is left out and the line breaks there by a fixed gap. The window runs over
     * recording time, so the first point after the resume carries on from the last before the pause.
     */
    @Test fun aPauseBreaksTheLine() {
        val s = DiscoveryChart.series(DiscoverySamples.pauseGap, DiscoverySamples.ELAPSED_MS)
        assertEquals(2, s.runs.size)
        val (before, after) = s.runs
        assertEquals(5 * 60_000L, before.last().x)
        assertEquals(5 * 60_000L, after.first().x) // the two minutes paused are not on the axis
        assertEquals(before.last().perMin, after.first().perMin, 0f)
        assertEquals(DiscoverySamples.ELAPSED_MS, after.last().x) // now: recording time, the filling bin's end
        val shape = DiscoveryChart.layout(s, 388f, 72f, 2f, 6f)
        assertEquals(6f, shape.lines[1].first().x - shape.lines[0].last().x, 1e-3f)
        assertEquals(shape.lines[1].last(), shape.end)
    }

    /** A bin that starts in a pause (or before the first run) counts in the next run's first bin. */
    @Test fun binsInAPauseGoToTheNextRun() {
        val s = series(listOf(Bin(-bin, 9), Bin(0, 1), Bin(15_000, 2)), 15_000, Run(0, 10_000), Run(20_000, null))
        // run 1: 10 in its first bin; run 2 (recording time 10 s to 15 s): the bin of the pause, 2
        assertEquals(listOf(listOf(0f, 60f, 60f), listOf(60f, 48f)), rates(s))
        assertEquals(listOf(listOf(0L, 5_000L, 10_000L), listOf(10_000L, 15_000L)), s.runs.map { r -> r.map { it.x } })
    }

    /**
     * #129: a device first seen exactly at a closed run's end is in a bin that starts at the end (start == end). It
     * counts in that run's last bin: not drawn after the pause, and not dropped when the run is the last one.
     */
    @Test fun aBinAtARunsEndCountsInItsLastBin() {
        // run 1 is 10 s (two whole bins); its end bin holds 3; run 2 starts after a pause
        val s = series(listOf(Bin(0, 0), Bin(10_000, 3), Bin(20_000, 1)), 15_000, Run(0, 10_000), Run(20_000, null))
        assertEquals(listOf(listOf(0f, 0f, 18f), listOf(18f, 16f)), rates(s))
        // 3 then 4 devices in all: none moved into the next run
        assertEquals(4 * 60f / 15f, s.last?.perMin ?: 0f, 1e-4f)
        // paused at the end: the last closed run keeps its end bin, so the label still counts it
        val paused = series(listOf(Bin(0, 0), Bin(10_000, 3)), 10_000, Run(0, 10_000))
        assertEquals(listOf(0f, 0f, 18f), rates(paused).single())
        assertEquals(18, DiscoveryChart.current(paused))
        // only a closed run's end bin: a running run's bin later than now is not pulled into its last bin
        assertEquals(0, label(listOf(Bin(0, 0), Bin(20_000, 5)), 10_000))
    }

    /** A window that starts inside a bin counts that bin's share; a short last bin counts by its own length. */
    @Test fun partialBinsCountByTheirShare() {
        // 6 devices in 0..5 s, then nothing: at 32 s the window (2 s, 32 s] holds 3 s of that bin, so 3.6 devices
        val s = series(listOf(Bin(0, 6)), 32_000)
        assertEquals(3.6f * 2, at(s, 32_000), 1e-4f)
        // a paused run's last, 2 s bin: its 2 devices count whole at its end
        val paused = series(listOf(Bin(0, 0), Bin(bin, 2)), 7_000, Run(0, 7_000))
        assertEquals(listOf(0L, 5_000L, 7_000L), paused.runs.single().map { it.x })
        assertEquals(2 * 60f / 10f, at(paused, 7_000), 1e-4f)
    }

    /** Baseline 0 at the bottom, the highest rate near the top; a near-empty recording does not fill the height. */
    @Test fun yScale() {
        fun line(vararg perMin: Float) = DiscoveryChart.Series(listOf(perMin.mapIndexed { k, v -> Point(k * bin, v) }))
        val y = DiscoveryChart.layout(line(20f, 20f), 100f, 100f, 2f, 6f).lines.single().first().y
        assertTrue(y > 1f && y < 30f)
        assertTrue(DiscoveryChart.layout(line(0f, 0f), 100f, 100f, 2f, 6f).lines.single().all { it.y == 99f })
        // 2/min of the 4/min floor
        assertEquals(51f, DiscoveryChart.layout(line(2f, 2f), 100f, 102f, 2f, 6f).lines.single().first().y, 1e-3f)
        // the label's room: the top rate sits at `top`, so the label above the end always fits
        val full = line(0f, 20f)
        val shape = DiscoveryChart.layout(full, 100f, 100f, 2f, 6f, top = 20f)
        assertTrue(shape.lines.single().all { it.y >= 20f && it.y <= 99f })
        assertEquals(DiscoveryChart.Shape(emptyList(), 99f, null), DiscoveryChart.layout(full, 100f, 100f, 2f, 6f, top = 99f))
        // two points span the box, the stroke's half inside it
        assertEquals(listOf(1f, 99f), DiscoveryChart.layout(full, 100f, 100f, 2f, 6f).lines.single().map { it.x })
    }

    /**
     * A long session costs the panel's width: at most four points per pixel column, the peaks and the end kept. The
     * box is narrow enough (about 58 bins per column) that an unthinned line breaks the bound.
     */
    @Test fun longSessionsThinToThePixelWidth() {
        val hours = 8
        val n = hours * 720
        val counts = List(n) { k -> if (k == 3_000) 40 else k % 3 }
        val s = DiscoveryChart.series(data(counts.mapIndexed { k, c -> Bin(k * bin, c) }, Run(0, null)), n * bin)
        assertEquals(n + 1, s.runs.single().size)
        val width = 100f
        val shape = DiscoveryChart.layout(s, width, 72f, 2f, 6f)
        val line = shape.lines.single()
        val perColumn = line.groupingBy { kotlin.math.floor(it.x).toInt() }.eachCount()
        assertTrue("$perColumn", perColumn.values.all { it <= 4 })
        assertTrue("${line.size}", line.size <= 4 * width.toInt())
        assertTrue(line.zipWithNext().all { (a, b) -> a.x <= b.x })
        val unthinned = s.runs.single().maxOf { it.perMin }
        val top = 72f - 1f - unthinned / (unthinned * 1.15f) * 70f
        assertEquals(top, line.minOf { it.y }, 1e-3f)
        assertEquals(width - 1f, shape.end?.x ?: 0f, 1e-3f)
    }

    /** Samples every stroke of [lines] at 0.25 px steps. */
    private fun samples(lines: List<List<Offset>>): List<Offset> = lines.flatMap { pts ->
        pts.zipWithNext().flatMap { (a, b) ->
            val steps = maxOf(1, (kotlin.math.hypot(b.x - a.x, b.y - a.y) * 4).toInt())
            (0..steps).map { k -> Offset(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps) }
        }
    }

    /**
     * The label sits over the line under its own x span, not over the end point alone: a rate that drops at the end
     * (20, 20, 20, 20, 0, 0 on a phone panel, 2.625 px/dp) never runs through it, the stroke's width included.
     */
    @Test fun theLabelClearsALineThatDropsAtItsEnd() {
        val d = 2.625f
        val (w, h, gap, stroke) = listOf(150f, 32f, 4 * d, 2 * d)
        val width = (412 - 24 - 32) * d
        val height = 72 * d
        val s = DiscoveryChart.series(DiscoverySamples.dropAtEnd, DiscoverySamples.DROP_ELAPSED_MS)
        assertEquals(0, DiscoveryChart.current(s))
        val shape = DiscoveryChart.layout(s, width, height, stroke, 6 * d, top = h + gap + stroke / 2)
        val at = DiscoveryChart.labelAt(shape, w, h, gap, stroke, height) ?: Offset.Unspecified
        val pts = samples(shape.lines)
        fun inside(o: Offset, p: Offset) = p.x >= o.x - stroke / 2 && p.x <= o.x + w + stroke / 2 && p.y >= o.y - stroke / 2 && p.y <= o.y + h + stroke / 2
        assertEquals(emptyList<Offset>(), pts.filter { inside(at, it) })
        assertTrue("inside the chart", at.y >= 0f && at.y + h <= height && at.x + w <= width)
        // the end point alone would have put it on the falling stroke
        val end = shape.end ?: Offset.Unspecified
        assertTrue(pts.any { inside(Offset(end.x - w, end.y - gap - h), it) })
    }

    /** No room above: below the lowest point under the label; no room either way: at the top. */
    @Test fun theLabelGoesBelowWithoutRoomAbove() {
        val shape = DiscoveryChart.Shape(listOf(listOf(Offset(0f, 1f), Offset(60f, 3f), Offset(100f, 1f))), 99f, Offset(100f, 1f))
        assertEquals(Offset(70f, 2.5f + 1f + 2f), DiscoveryChart.labelAt(shape, 30f, 10f, 2f, 2f, 100f))
        assertEquals(Offset(70f, 0f), DiscoveryChart.labelAt(shape, 30f, 10f, 2f, 2f, 12f))
        assertNull(DiscoveryChart.labelAt(DiscoveryChart.Shape(emptyList(), 99f, null), 30f, 10f, 2f, 2f, 100f))
        // a single point counts where it is, and the edges are interpolated
        assertEquals(1f to 2.5f, DiscoveryChart.ySpan(shape.lines, 70f, 100f))
        assertNull(DiscoveryChart.ySpan(shape.lines, 101f, 120f))
        assertEquals(5f to 5f, DiscoveryChart.ySpan(listOf(listOf(Offset(10f, 5f))), 0f, 20f))
    }

    @Test fun thinKeepsShortLinesAndOrder() {
        val short = listOf(Offset(0f, 1f), Offset(0.2f, 5f), Offset(0.4f, 2f))
        assertEquals(short, DiscoveryChart.thin(short))
        val col = listOf(Offset(0.1f, 3f), Offset(0.2f, 9f), Offset(0.3f, 1f), Offset(0.4f, 5f), Offset(0.5f, 4f), Offset(1.5f, 2f))
        assertEquals(listOf(Offset(0.1f, 3f), Offset(0.2f, 9f), Offset(0.3f, 1f), Offset(0.5f, 4f), Offset(1.5f, 2f)), DiscoveryChart.thin(col))
    }
}
