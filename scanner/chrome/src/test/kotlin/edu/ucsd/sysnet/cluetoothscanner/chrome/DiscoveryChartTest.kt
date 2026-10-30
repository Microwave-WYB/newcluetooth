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

    /** 0 points: no run, no bin size, or no bin complete yet; nothing is drawn and there is no label. */
    @Test fun emptyDrawsNothing() {
        assertEquals(DiscoveryChart.Series.Empty, DiscoveryChart.series(DiscoveryBins.Empty, 600_000))
        assertEquals(DiscoveryChart.Series.Empty, DiscoveryChart.series(DiscoveryBins(0, listOf(Bin(0, 3)), listOf(Run(0, null))), 600_000))
        val early = DiscoveryChart.series(DiscoverySamples.empty, DiscoverySamples.EMPTY_ELAPSED_MS)
        assertEquals(DiscoveryChart.Series.Empty, early)
        assertNull(DiscoveryChart.current(early))
        val shape = DiscoveryChart.layout(early, 300f, 180f, 4f, 12f)
        assertEquals(emptyList<List<Offset>>(), shape.lines); assertNull(shape.end); assertEquals(178f, shape.baseline)
    }

    /** 1 point: one complete bin is a flat line across its bin, at devices × 2 per minute. */
    @Test fun onePointIsAFlatLine() {
        val s = DiscoveryChart.series(data(listOf(Bin(0, 3)), Run(0, null)), bin)
        assertEquals(listOf(listOf(Point(0, 6f), Point(bin, 6f))), s.runs)
        assertEquals(6, DiscoveryChart.current(s))
        val line = DiscoveryChart.layout(s, 300f, 180f, 4f, 12f).lines.single()
        assertEquals(2, line.size); assertEquals(line[0].y, line[1].y); assertEquals(2f, line[0].x); assertEquals(298f, line[1].x)
    }

    /** y is the bin's devices × 2, smoothed 1-2-1 (renormalized at the ends); the filling bin is left out. */
    @Test fun ratesAreBinsTimesTwoLightlySmoothed() {
        val s = DiscoveryChart.series(data(listOf(Bin(0, 2), Bin(bin, 4), Bin(2 * bin, 6), Bin(3 * bin, 9)), Run(0, null)), 3 * bin + 20_000)
        val r = rates(s).single()
        assertEquals(3, r.size - 1) // three bins, plus the run's start
        assertEquals(16f / 3, r[1], 1e-4f); assertEquals(8f, r[2], 1e-4f); assertEquals(32f / 3, r[3], 1e-4f)
        assertEquals(r[1], r[0], 0f)
        assertEquals(listOf(0L, bin, 2 * bin, 3 * bin), s.runs.single().map { it.x })
    }

    /** The core may leave bins without new devices out: they count as 0, and bins come in any order. */
    @Test fun sparseBinsAreZeros() {
        val s = DiscoveryChart.series(data(listOf(Bin(3 * bin, 6), Bin(0, 6)), Run(0, null)), 4 * bin)
        val raw = listOf(12f, 0f, 0f, 12f)
        assertEquals(DiscoveryChart.smooth(raw.toFloatArray()).toList(), rates(s).single().drop(1))
    }

    /** Standing still the line falls towards 0; walking somewhere new raises it. */
    @Test fun stationaryDecaysAndWalkingRises() {
        val still = DiscoveryChart.series(DiscoverySamples.stationary, DiscoverySamples.ELAPSED_MS).runs.single()
        assertEquals(26, still.size)
        assertTrue(still.first().perMin > 20f)
        assertTrue(still.takeLast(6).all { it.perMin < 1f })
        assertEquals(0, DiscoveryChart.current(DiscoveryChart.series(DiscoverySamples.stationary, DiscoverySamples.ELAPSED_MS)))
        val walk = DiscoveryChart.series(DiscoverySamples.rising, DiscoverySamples.ELAPSED_MS).runs.single()
        assertTrue(walk.last().perMin > walk.first().perMin * 3)
    }

    /** x is elapsed recording time: a pause is left out, and the line breaks there by a fixed gap. */
    @Test fun aPauseBreaksTheLine() {
        val s = DiscoveryChart.series(DiscoverySamples.pauseGap, DiscoverySamples.ELAPSED_MS)
        assertEquals(2, s.runs.size)
        val (before, after) = s.runs
        assertEquals(5 * 60_000L, before.last().x)
        assertEquals(5 * 60_000L, after.first().x) // the two minutes paused are not on the axis
        assertEquals(5 * 60_000L + 15 * bin, after.last().x) // 7:34 since the resume: 15 bins complete
        val shape = DiscoveryChart.layout(s, 388f, 72f, 2f, 6f)
        assertEquals(6f, shape.lines[1].first().x - shape.lines[0].last().x, 1e-3f)
        assertEquals(shape.lines[1].last(), shape.end)
    }

    /** A bin that starts in a pause (or before the first run) counts in the next run's first bin. */
    @Test fun binsInAPauseGoToTheNextRun() {
        val s = DiscoveryChart.series(data(listOf(Bin(-bin, 9), Bin(0, 1), Bin(3 * bin, 2)), Run(0, 2 * bin), Run(4 * bin, null)), 3 * bin)
        assertEquals(listOf(DiscoveryChart.smooth(floatArrayOf(20f, 0f)).toList(), listOf(4f)), rates(s).map { it.drop(1) })
    }

    /** A paused run's last, shorter bin is scaled by its length, at least half a bin. */
    @Test fun aRunsShortLastBinIsScaled() {
        val s = DiscoveryChart.series(data(listOf(Bin(0, 0), Bin(bin, 3)), Run(0, bin + 15_000)), bin + 15_000)
        assertEquals(DiscoveryChart.smooth(floatArrayOf(0f, 12f)).toList(), rates(s).single().drop(1))
        assertEquals(bin + 15_000, s.last?.x)
        val tiny = DiscoveryChart.series(data(listOf(Bin(bin, 1)), Run(0, bin + 1_000)), bin + 1_000)
        assertEquals(DiscoveryChart.smooth(floatArrayOf(0f, 4f)).toList(), rates(tiny).single().drop(1))
    }

    /** Baseline 0 at the bottom, the highest rate near the top; a near-empty recording does not fill the height. */
    @Test fun yScale() {
        val s = DiscoveryChart.series(data(listOf(Bin(0, 10)), Run(0, null)), bin)
        val y = DiscoveryChart.layout(s, 100f, 100f, 2f, 6f).lines.single().first().y
        assertTrue(y > 1f && y < 30f)
        val low = DiscoveryChart.series(data(listOf(Bin(0, 0), Bin(bin, 0)), Run(0, null)), 2 * bin)
        assertTrue(DiscoveryChart.layout(low, 100f, 100f, 2f, 6f).lines.single().all { it.y == 99f })
        val half = DiscoveryChart.series(data(listOf(Bin(0, 1)), Run(0, null)), bin) // 2/min of the 4/min floor
        assertEquals(51f, DiscoveryChart.layout(half, 100f, 102f, 2f, 6f).lines.single().first().y, 1e-3f)
        // the label's room: the top rate sits at `top`, so the label above the end always fits
        val full = DiscoveryChart.series(data(listOf(Bin(0, 0), Bin(bin, 10)), Run(0, null)), 2 * bin)
        val shape = DiscoveryChart.layout(full, 100f, 100f, 2f, 6f, top = 20f)
        assertTrue(shape.lines.single().all { it.y >= 20f && it.y <= 99f })
        assertEquals(DiscoveryChart.Shape(emptyList(), 99f, null), DiscoveryChart.layout(full, 100f, 100f, 2f, 6f, top = 99f))
    }

    /** A long session costs the panel's width: at most four points per pixel column, the peaks and the end kept. */
    @Test fun longSessionsThinToThePixelWidth() {
        val hours = 8
        val n = hours * 120
        val counts = List(n) { k -> if (k == 500) 40 else k % 3 }
        val s = DiscoveryChart.series(data(counts.mapIndexed { k, c -> Bin(k * bin, c) }, Run(0, null)), n * bin + 5_000)
        assertEquals(n + 1, s.runs.single().size)
        val width = 300f
        val shape = DiscoveryChart.layout(s, width, 72f, 2f, 6f)
        val line = shape.lines.single()
        assertTrue("${line.size}", line.size <= 4 * width.toInt())
        assertTrue(line.zipWithNext().all { (a, b) -> a.x <= b.x })
        val unthinned = s.runs.single().maxOf { it.perMin }
        val top = 72f - 1f - unthinned / (unthinned * 1.15f) * 70f
        assertEquals(top, line.minOf { it.y }, 1e-3f)
        assertEquals(width - 1f, shape.end?.x ?: 0f, 1e-3f)
    }

    @Test fun thinKeepsShortLinesAndOrder() {
        val short = listOf(Offset(0f, 1f), Offset(0.2f, 5f), Offset(0.4f, 2f))
        assertEquals(short, DiscoveryChart.thin(short))
        val col = listOf(Offset(0.1f, 3f), Offset(0.2f, 9f), Offset(0.3f, 1f), Offset(0.4f, 5f), Offset(0.5f, 4f), Offset(1.5f, 2f))
        assertEquals(listOf(Offset(0.1f, 3f), Offset(0.2f, 9f), Offset(0.3f, 1f), Offset(0.5f, 4f), Offset(1.5f, 2f)), DiscoveryChart.thin(col))
    }
}
