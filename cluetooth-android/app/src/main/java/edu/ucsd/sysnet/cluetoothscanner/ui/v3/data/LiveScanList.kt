package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState

/**
 * The Scan page's list: the tracker's devices in the one order of [ScanOrder].
 *
 * The order changes in exactly three ways: a new device is appended; the one initial sort
 * happens [ScanOrder.INITIAL_SORT_DELAY_MS] after the first device; [refresh] (pull to refresh)
 * sorts again, live devices by signal first and stale ones after. Nothing else moves a row:
 * signal changes, going stale (the row only dims) and hearing a device again change the row's
 * content, never its place. A device that was forgotten (unheard for a long time) and is heard
 * again is a new device and is appended.
 *
 * All access goes through [lock]; the ViewModel also reads [tracker] under it.
 */
class LiveScanList(
    val tracker: LiveDeviceTracker = LiveDeviceTracker(),
    private val order: ScanOrder = ScanOrder(),
) {
    val lock = Any()

    /** What one publish produced; [sorted] is true when it applied the initial sort. */
    class Snapshot(val rows: List<DeviceRowState>, val sorted: Boolean)

    fun onSample(sample: LiveSample, nowMs: Long) = synchronized(lock) {
        if (tracker.onSample(sample)) order.onDevice(sample.addr, nowMs)
    }

    /** The rows in list order at [nowMs]; applies the initial sort once its time has come. */
    fun publish(nowMs: Long): Snapshot = synchronized(lock) {
        order.remove(tracker.prune(nowMs))
        val sorted = order.tick(nowMs, tracker::rssi) { tracker.isStale(it, nowMs) }
        Snapshot(order.addrs.mapNotNull { tracker.row(it, nowMs) }, sorted)
    }

    /** Pull to refresh: sort now, live devices by signal first, then the stale ones. */
    fun refresh(nowMs: Long): Snapshot = synchronized(lock) {
        order.resort(tracker::rssi) { tracker.isStale(it, nowMs) }
        publish(nowMs)
    }
}

/** The search box only hides rows: what is left keeps the list's order. */
fun filterScanRows(rows: List<DeviceRowState>, searching: Boolean, query: String): List<DeviceRowState> {
    val q = query.trim().lowercase()
    if (!searching || q.isEmpty()) return rows
    return rows.filter { (it.name.orEmpty() + " " + it.mac).lowercase().contains(q) }
}
