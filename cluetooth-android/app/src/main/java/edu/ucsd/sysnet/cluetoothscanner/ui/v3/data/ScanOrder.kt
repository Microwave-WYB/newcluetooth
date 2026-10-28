package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

/**
 * The order of the scan list. Devices are listed as they first appear; three seconds after the
 * first one the list is sorted by RSSI once. After that the order is fixed (only the numbers in
 * the rows change), devices that appear later are appended at the end, and only [resort] (the
 * pull-to-refresh) sorts again.
 */
class ScanOrder(private val initialSortDelayMs: Long = INITIAL_SORT_DELAY_MS) {
    private val order = ArrayList<String>()
    private val members = HashSet<String>()
    private var firstArrivalMs: Long? = null
    private var initialSortDone = false

    val addrs: List<String> get() = order

    fun onDevice(addr: String, nowMs: Long) {
        if (!members.add(addr)) return
        order.add(addr)
        if (firstArrivalMs == null) firstArrivalMs = nowMs
    }

    fun remove(addrs: Collection<String>) {
        if (addrs.isEmpty()) return
        order.removeAll(addrs.toSet())
        members.removeAll(addrs.toSet())
    }

    /** Applies the one-time initial sort when its time has come. */
    fun tick(nowMs: Long, rssiOf: (String) -> Int?) {
        val first = firstArrivalMs ?: return
        if (!initialSortDone && nowMs - first >= initialSortDelayMs) resort(rssiOf)
    }

    fun resort(rssiOf: (String) -> Int?) {
        initialSortDone = true
        order.sortByDescending { rssiOf(it) ?: Int.MIN_VALUE }
    }

    companion object {
        const val INITIAL_SORT_DELAY_MS = 3_000L
    }
}
