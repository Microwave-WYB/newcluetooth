package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.StableOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class StableOrderTest {
    private data class Item(val id: String, val rssi: Int)

    private fun strongestFirst(vararg items: Item) = items.sortedByDescending { it.rssi }

    @Test
    fun theFirstSecondsSortThenEveryItemKeepsItsPlace() {
        val order = StableOrder(settleMs = 3_000)
        order.reset(0)
        fun ids(list: List<Item>, at: Long) = order.arrange("audio", list, at) { it.id }.map { it.id }
        // Settling: the incoming (strongest-first) order is taken as it comes.
        assertEquals(listOf("a", "b"), ids(strongestFirst(Item("a", -50), Item("b", -60)), 1_000))
        assertEquals(listOf("c", "a", "b"), ids(strongestFirst(Item("a", -50), Item("b", -60), Item("c", -40)), 2_000))
        // Held: signals swap, nothing moves; a new item goes last; a gone one leaves.
        assertEquals(listOf("c", "a", "b"), ids(strongestFirst(Item("a", -90), Item("b", -30), Item("c", -70)), 4_000))
        assertEquals(listOf("c", "a", "b", "d"), ids(strongestFirst(Item("d", -20), Item("a", -90), Item("b", -30), Item("c", -70)), 5_000))
        assertEquals(listOf("c", "b", "d"), ids(strongestFirst(Item("d", -20), Item("b", -30), Item("c", -70)), 6_000))
        // Lists are held apart.
        assertEquals(listOf("x", "y"), order.arrange("wear", strongestFirst(Item("x", -40), Item("y", -50)), 7_000) { it.id }.map { it.id })
        // A reset (the tab opened again, or the mode changed) sorts afresh.
        order.reset(10_000)
        assertEquals(listOf("d", "b", "c"), ids(strongestFirst(Item("d", -20), Item("b", -30), Item("c", -70)), 10_500))
    }
}
