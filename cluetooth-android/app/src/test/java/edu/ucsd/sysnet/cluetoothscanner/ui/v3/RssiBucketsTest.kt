package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.BucketPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiBucketer
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiBuckets
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiBucketsTest {
    private val now = 10_000L

    @Test
    fun bucketsEndAtNowAndKeepTheStrongestSample() {
        // 5 minutes in 5 s buckets: 60 of them, the last one holds seconds now-4..now.
        val b = RssiBucketer(now, 300, 5)
        assertEquals(60, b.count)
        b.add(now, -70)
        b.add(now - 4, -50) // same bucket: the strongest wins
        b.add(now - 5, -90) // the bucket before
        b.add(now - 299, -60) // the first second of the window: bucket 0
        b.add(now - 300, -40) // just outside the window
        b.add(now + 1, -40) // in the future
        val out = b.buckets()
        assertEquals(60, out.size)
        assertEquals(-50, out[59])
        assertEquals(-90, out[58])
        assertEquals(-60, out[0])
        assertEquals(3, out.count { it != null })
    }

    @Test
    fun windowsUseOneFiveAndFifteenSecondBucketsOfSixtyEach() {
        assertEquals(listOf(1 to 60, 5 to 60, 15 to 60), ChartWindow.entries.map { it.bucketSeconds to it.seconds / it.bucketSeconds })
        assertEquals(30, RssiBucketer(now, 60, 2).count) // the list sparkline
    }

    @Test
    fun manySamplesInOneBucketMakeOnePointNotAVerticalLine() {
        val b = RssiBucketer(now, 60, 1)
        listOf(-77, -95, -99, -80, -88).forEach { b.add(now - 10, it) }
        val points = RssiBuckets.segments(b.buckets()).flatten()
        assertEquals(listOf(BucketPoint(49, -77)), points)
    }

    @Test
    fun theLineBreaksOnlyAcrossMoreThanThreeEmptyBuckets() {
        fun series(vararg at: Int) = List(60) { i -> if (i in at) -70 else null }
        // three empty buckets between: still one line
        assertEquals(1, RssiBuckets.segments(series(10, 14)).size)
        // four empty buckets between: broken
        assertEquals(listOf(listOf(BucketPoint(10, -70)), listOf(BucketPoint(15, -70))), RssiBuckets.segments(series(10, 15)))
        assertEquals(2, RssiBuckets.segments(series(0, 1, 2, 30, 31)).size)
        assertTrue(RssiBuckets.segments(List(60) { null }).isEmpty())
        // x only increases within and across runs
        val xs = RssiBuckets.segments(series(3, 5, 6, 20, 40, 41)).flatten().map { it.index }
        assertEquals(xs.sorted(), xs)
        assertEquals(xs.distinct(), xs)
    }

    @Test
    fun aLoneBucketIsADotAndThereIsExactlyOneEndMarker() {
        val buckets = List(60) { i ->
            when (i) {
                5 -> -60 // lone: a dot
                20, 21, 22 -> -75 // a line
                59 -> -90 // lone and last: the end marker only
                else -> null
            }
        }
        val plan = RssiBuckets.plan(buckets)
        assertEquals(listOf(BucketPoint(5, -60)), plan.dots)
        assertEquals(1, plan.lines.size)
        assertEquals(BucketPoint(59, -90), plan.end)
        assertFalse(BucketPoint(59, -90) in plan.dots)
        // a line that ends on the last bucket: its last point is the marker, not a second dot
        val ending = List(60) { i -> if (i >= 55) -50 - i else null }
        assertEquals(BucketPoint(59, -109), RssiBuckets.plan(ending).end)
        assertTrue(RssiBuckets.plan(ending).dots.isEmpty())
        assertNull(RssiBuckets.plan(List(60) { null }).end)
    }

    @Test
    fun aDeviceHeardForUnderATenthOfTheWindowIsShortLived() {
        assertTrue(RssiBuckets.isShortLived(20, 300))
        assertFalse(RssiBuckets.isShortLived(30, 300))
        assertFalse(RssiBuckets.isShortLived(20, 60))
        assertTrue(RssiBuckets.isShortLived(5, 60))
        assertFalse(RssiBuckets.isShortLived(null, 300))
    }

    @Test
    fun theTrackerReportsHowLongADeviceHasBeenHeardAndTheSparklineUses2sBuckets() {
        val t = LiveDeviceTracker()
        val raw = byteArrayOf(2, 1, 6)
        fun sample(rssi: Int, ms: Long) = LiveSample("AA:BB:CC:DD:EE:01", rssi, ms, raw, null, true, true, null, null, null, 1)
        t.onSample(sample(-77, 1_000_000))
        t.onSample(sample(-60, 1_020_000))
        val series = t.chart("AA:BB:CC:DD:EE:01", ChartWindow.FiveMinutes, 1_020_400)
        assertEquals(20, series.heardSeconds)
        assertEquals(listOf(-77, -60), series.buckets.filterNotNull())
        assertEquals(59, series.buckets.indexOfLast { it != null })
        assertTrue(RssiBuckets.isShortLived(series.heardSeconds, ChartWindow.FiveMinutes.seconds))
        val row = t.row("AA:BB:CC:DD:EE:01", 1_020_400)!!
        assertEquals(30, row.history.size)
        assertEquals(listOf(-77, -60), row.history.filterNotNull())
    }
}
