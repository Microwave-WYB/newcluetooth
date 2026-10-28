package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveScanList
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.coordinatesText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.filterScanRows
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.scanLocationOf
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanLocation
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Scan list order over simulated time: one initial sort, appends, and a sort only on refresh. */
class ScanListOrderTest {
    private val raw = byteArrayOf(2, 1, 6)
    private val start = 1_000_000_000L
    private fun addr(i: Int) = "AA:BB:CC:DD:EE:%02X".format(i)

    private fun sample(i: Int, rssi: Int, t: Long) = LiveSample(addr(i), rssi, t, raw, null, true, true, null, null, null, 1)

    private fun ids(rows: List<DeviceRowState>) = rows.map { it.id }

    @Test
    fun orderOnlyChangesByAppendUntilRefresh_over60SimulatedSeconds() {
        val list = LiveScanList()
        val rnd = Random(7)
        val rssi = IntArray(20) { -45 - rnd.nextInt(45) }
        // Devices 0..7 are there from the start, the rest arrive later; 3 stays silent, 5 goes silent and comes back.
        val arrivals = mapOf(8 to 9, 9 to 14, 10 to 33, 11 to 47, 12 to 59)
        val silent = mapOf(3 to (4..60), 5 to (10..40))
        val uiInactive = 22..31 // the ViewModel's uiActive was false: no samples, no publish

        var previous: List<String> = emptyList()
        var sortedAt: Long? = null
        var firstShown: Long? = null
        val staleSeen = HashSet<String>()

        for (second in 0..60) {
            val now = start + second * 1_000L
            if (second !in uiInactive) {
                for (i in 0..12) {
                    val arrival = arrivals[i] ?: 0
                    if (second < arrival || (silent[i]?.contains(second) == true)) continue
                    rssi[i] = (rssi[i] + rnd.nextInt(-6, 7)).coerceIn(-100, -35) // numbers change every second
                    list.onSample(sample(i, rssi[i], now), now)
                }
                val snap = list.publish(now)
                val current = ids(snap.rows)
                if (firstShown == null && current.isNotEmpty()) firstShown = now
                if (snap.sorted) sortedAt = now

                if (previous.isNotEmpty() && snap.sorted) {
                    // the one initial sort: strongest first, nothing stale yet
                    assertEquals(current.sortedByDescending { id -> snap.rows.first { it.id == id }.rssi }, current)
                } else if (previous.isNotEmpty()) {
                    // every other second: the old order is a prefix, only new devices come after it
                    assertEquals("second $second", previous, current.take(previous.size))
                    assertTrue(current.size >= previous.size)
                }
                snap.rows.filter { it.stale }.forEach { staleSeen += it.id }
                // search filtering only hides rows: the rest keep the list's order
                val filtered = ids(filterScanRows(snap.rows, searching = true, query = "ee:0"))
                assertEquals(current.filter { it in filtered }, filtered)
                previous = current
            }
            // opening and closing the device sheet is selection state in the ViewModel: it never touches the list
        }

        assertEquals("one sort, 3 s after the first device", firstShown!! + 3_000, sortedAt)
        assertTrue("devices 3 and 5 went stale and were dimmed in place", addr(3) in staleSeen && addr(5) in staleSeen)
        assertEquals("every device is listed once", 13, previous.size)
        assertEquals(previous.distinct(), previous)

        // Pull to refresh: live devices by signal first, then the stale ones.
        val now = start + 61_000L
        val refreshed = list.refresh(now).rows
        val live = refreshed.filter { !it.stale }
        val stale = refreshed.filter { it.stale }
        assertTrue(stale.isNotEmpty())
        assertEquals(live + stale, refreshed)
        assertEquals(live.sortedByDescending { it.rssi }, live)
        assertEquals(stale.sortedByDescending { it.rssi }, stale)
        // and it holds still again afterwards
        val after = ids(list.publish(now + 5_000).rows)
        assertEquals(ids(refreshed), after.take(refreshed.size))
    }

    @Test
    fun aDeviceHeardAgainAfterItWasForgottenIsAppendedAtTheEnd() {
        val list = LiveScanList()
        list.onSample(sample(1, -50, start), start)
        list.onSample(sample(2, -60, start + 100), start + 100)
        list.publish(start + 3_100) // initial sort
        // Device 2 stays heard, device 1 is forgotten after 30 minutes of silence.
        var t = start
        while (t < start + 31 * 60_000L) { t += 10_000; list.onSample(sample(2, -60, t), t); list.publish(t) }
        assertEquals(listOf(addr(2)), ids(list.publish(t).rows))
        list.onSample(sample(1, -40, t + 1_000), t + 1_000)
        assertEquals(listOf(addr(2), addr(1)), ids(list.publish(t + 1_000).rows))
    }

    @Test
    fun nothingMovesWhenNothingNewHappens() {
        val list = LiveScanList()
        for (i in 0..5) list.onSample(sample(i, -40 - i * 5, start), start)
        val first = ids(list.publish(start + 3_000).rows)
        // weaker devices become the strongest: the order must not follow
        for (second in 4..40) {
            val now = start + second * 1_000L
            for (i in 0..5) list.onSample(sample(i, -90 + i * 10, now), now)
            assertEquals(first, ids(list.publish(now).rows))
        }
        assertFalse(list.publish(start + 41_000).sorted)
    }
}

class ScanLocationTest {
    private val now = 10_000_000L

    @Test
    fun noFixIsUnavailable() {
        assertEquals(ScanLocation.Unavailable, scanLocationOf(null, now))
    }

    @Test
    fun aFreshFixShowsCoordinatesAndRoundedAccuracy() {
        assertEquals(
            ScanLocation.Fix(32.88012, -117.23421, 8),
            scanLocationOf(LocationFix(32.88012, -117.23421, 7.6f, now - 10_000), now),
        )
        assertEquals(ScanLocation.Fix(1.0, 2.0, null), scanLocationOf(LocationFix(1.0, 2.0, null, now), now))
        assertEquals("32.88012, -117.23421", coordinatesText(32.88012, -117.23421))
        assertEquals("-0.00100, 0.50000", coordinatesText(-0.001, 0.5))
    }

    @Test
    fun aFixOlderThanAMinuteIsStaleInWholeMinutes() {
        assertTrue(scanLocationOf(LocationFix(1.0, 2.0, 5f, now - 60_000), now) is ScanLocation.Fix)
        assertEquals(ScanLocation.Stale(1), scanLocationOf(LocationFix(1.0, 2.0, 5f, now - 61_000), now))
        assertEquals(ScanLocation.Stale(2), scanLocationOf(LocationFix(1.0, 2.0, 5f, now - 125_000), now))
    }
}
