package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

/** One bucket that has data: its place in the window (0 = oldest) and its RSSI. */
data class BucketPoint(val index: Int, val rssi: Int)

/**
 * Samples aggregated into fixed buckets that end at "now": the window is `windowSec` seconds ending
 * at `nowSec` (inclusive), cut into `windowSec / bucketSec` buckets, and each bucket holds the
 * strongest RSSI heard in it. The last bucket is the one containing now, so a bucket's x position is
 * simply its index: strictly increasing, one point per x.
 */
class RssiBucketer(private val nowSec: Long, private val windowSec: Int, private val bucketSec: Int) {
    val count = windowSec / bucketSec
    private val start = nowSec - windowSec + 1
    private val max = IntArray(count) { NONE }

    fun add(second: Long, rssi: Int) {
        if (second < start || second > nowSec) return
        val i = ((second - start) / bucketSec).toInt().coerceIn(0, count - 1)
        if (rssi > max[i]) max[i] = rssi
    }

    fun buckets(): List<Int?> = List(count) { if (max[it] == NONE) null else max[it] }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}

object RssiBuckets {
    /** The line is broken where more than this many empty buckets lie between two with data. */
    const val MAX_GAP_BUCKETS = 3

    /** Runs of buckets to draw as one line; a run of one is a lone bucket, drawn as a dot. */
    fun segments(buckets: List<Int?>, maxGap: Int = MAX_GAP_BUCKETS): List<List<BucketPoint>> {
        val out = ArrayList<List<BucketPoint>>()
        var current = ArrayList<BucketPoint>()
        buckets.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (current.isNotEmpty() && i - current.last().index - 1 > maxGap) {
                out.add(current)
                current = ArrayList()
            }
            current.add(BucketPoint(i, v))
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** What to draw: lines for runs of two or more, a small dot per lone bucket (never the end one), and the single end marker. */
    class Plan(val lines: List<List<BucketPoint>>, val dots: List<BucketPoint>, val end: BucketPoint?)

    fun plan(buckets: List<Int?>, maxGap: Int = MAX_GAP_BUCKETS): Plan {
        val end = endMarker(buckets)
        val runs = segments(buckets, maxGap)
        return Plan(runs.filter { it.size >= 2 }, runs.filter { it.size == 1 && it[0] != end }.map { it[0] }, end)
    }

    /** The one end marker: the last bucket with data, colored by its band by the caller. */
    fun endMarker(buckets: List<Int?>): BucketPoint? =
        buckets.indexOfLast { it != null }.takeIf { it >= 0 }?.let { BucketPoint(it, buckets[it]!!) }

    /** The window is drawn as a short one when the device was heard for less than a tenth of it. */
    fun isShortLived(heardSeconds: Int?, windowSec: Int): Boolean = heardSeconds != null && heardSeconds * 10 < windowSec
}

/** What the detail chart draws: [buckets] (always [RssiBucketer.count] long, null = empty) and how long the device has been heard. */
class RssiSeries(val buckets: List<Int?>, val heardSeconds: Int?)
