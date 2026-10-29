package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint

/**
 * The live view's route (V-2): the snapshot's route, then each live delta's `routeAppended` (§2.6). Every list it
 * hands out never changes afterwards: an append writes only past the size of every earlier list, and a full buffer
 * moves to a larger one (earlier lists keep the old buffer). So the route is copied only on growth (amortized O(1)
 * per point), never per delta. Single writer: the view job.
 */
internal class LiveRoute(initial: List<GeoPoint>) {
    private var items: Array<GeoPoint> = Array(maxOf(MIN_CAPACITY, initial.size * 2)) { i -> initial.getOrElse(i) { FILL } }
    private var size = initial.size

    /** The route with [points] appended; the lists handed out before are unchanged. */
    fun append(points: List<GeoPoint>): List<GeoPoint> {
        val n = size
        if (n + points.size > items.size) {
            val old = items
            items = Array(maxOf(old.size * 2, n + points.size)) { i -> if (i < n) old[i] else FILL }
        }
        points.forEachIndexed { i, p -> items[n + i] = p }
        size = n + points.size
        return snapshot()
    }

    fun snapshot(): List<GeoPoint> = Prefix(items, size)

    /** The first [size] points of a buffer whose first [size] slots are never written again. */
    private class Prefix(private val items: Array<GeoPoint>, override val size: Int) : AbstractList<GeoPoint>() {
        override fun get(index: Int): GeoPoint {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
            return items[index]
        }
    }

    private companion object {
        const val MIN_CAPACITY = 16
        val FILL = GeoPoint(0.0, 0.0)
    }
}
