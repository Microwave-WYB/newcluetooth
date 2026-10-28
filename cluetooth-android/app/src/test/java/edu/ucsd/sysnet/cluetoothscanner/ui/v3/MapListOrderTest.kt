package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.MapListOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.StableOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceSort
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import org.junit.Assert.assertEquals
import org.junit.Test

/** A map sheet's device list sorts once, then holds its order whatever the live values do. */
class MapListOrderTest {
    private fun row(id: String, rssi: Int, name: String = id) = MapListRowState(id, name, id, "Scanned now", rssi)

    @Test
    fun rssiChangesNeverMoveARowAndNewDevicesGoLast() {
        val order = StableOrder(settleMs = 3_000)
        order.reset("ProductMap(a)", 0)
        fun ids(rows: List<MapListRowState>, at: Long, sort: DeviceSort = DeviceSort.Signal) =
            MapListOrder.arrange(rows, sort, order, "ProductMap(a)", at).map { it.id }
        // The list appears: sorted by signal.
        assertEquals(listOf("b", "a", "c"), ids(listOf(row("a", -60), row("b", -40), row("c", -80)), 3_500))
        // Every RSSI changes, several times: no row moves, values only.
        assertEquals(listOf("b", "a", "c"), ids(listOf(row("a", -30), row("b", -90), row("c", -35)), 4_000))
        assertEquals(listOf("b", "a", "c"), ids(listOf(row("a", -99), row("b", -20), row("c", -50)), 5_000))
        val shown = MapListOrder.arrange(listOf(row("a", -99), row("b", -20), row("c", -50)), DeviceSort.Signal, order, "ProductMap(a)", 5_500)
        assertEquals(listOf(-20, -99, -50), shown.map { it.rssi }) // the new values, in the held places
        // A new device is appended, however strong.
        assertEquals(listOf("b", "a", "c", "d"), ids(listOf(row("d", -10), row("a", -99), row("b", -20), row("c", -50)), 6_000))
        // The user picks another sort: it sorts once more, by that sort, then holds again.
        order.reset("ProductMap(a)", 7_000)
        assertEquals(listOf("a", "b", "c", "d"), ids(listOf(row("d", -10), row("c", -50), row("b", -20), row("a", -99)), 10_500, DeviceSort.Name))
        assertEquals(listOf("a", "b", "c", "d"), ids(listOf(row("d", -10), row("c", -50), row("b", -20), row("a", -99)), 11_000, DeviceSort.Name))
    }

    @Test
    fun eachMapKeepsItsOwnOrder() {
        val order = StableOrder(settleMs = 0)
        MapListOrder.arrange(listOf(row("a", -60), row("b", -40)), DeviceSort.Signal, order, "RecordingMap(r1)", 1)
        MapListOrder.arrange(listOf(row("x", -60), row("y", -40)), DeviceSort.Signal, order, "Product(p)", 1)
        // Opening another page (another key) does not touch the first one's order.
        order.reset("Product(p)", 2)
        assertEquals(listOf("b", "a"), MapListOrder.arrange(listOf(row("a", -10), row("b", -90)), DeviceSort.Signal, order, "RecordingMap(r1)", 3).map { it.id })
    }
}
