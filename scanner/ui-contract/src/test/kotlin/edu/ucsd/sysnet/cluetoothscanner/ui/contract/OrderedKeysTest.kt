package edu.ucsd.sysnet.cluetoothscanner.ui.contract

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** G-9: the order is built once, changes only by appends, and filters only hide. */
class OrderedKeysTest {
    private fun keys(vararg k: Long) = DeviceKeys(k, 0)

    @Test fun keepsTheInitialOrder() {
        val o = OrderedKeys(longArrayOf(30, 10, 20))
        assertEquals(3, o.size)
        assertArrayEquals(longArrayOf(30, 10, 20), o.visible(keys(10, 20, 30)))
    }

    @Test fun appendsDedupAndGoLast() {
        val o = OrderedKeys(longArrayOf(30, 10, 20, 10))
        assertEquals(3, o.size)
        o.append(longArrayOf(20, 40, 5, 40))
        assertEquals(5, o.size)
        assertArrayEquals(longArrayOf(30, 10, 20, 40, 5), o.visible(keys(5, 10, 20, 30, 40)))
    }

    @Test fun filtersHideAndNeverReorder() {
        val o = OrderedKeys(longArrayOf(30, 10, 20, 40))
        assertArrayEquals(longArrayOf(30, 40), o.visible(keys(40, 30, 99)))
        assertArrayEquals(longArrayOf(), o.visible(keys()))
        assertArrayEquals(longArrayOf(), OrderedKeys(longArrayOf()).visible(keys(1, 2)))
    }

    /** Property: visible is always a subsequence of the order, holding exactly the passing keys it knows. */
    @Test fun visibleIsTheOrderedIntersection() {
        val r = Random(7)
        repeat(200) {
            val initial = LongArray(r.nextInt(0, 60)) { r.nextLong(0, 80) }
            val o = OrderedKeys(initial)
            val expected = initial.distinct().toMutableList()
            repeat(r.nextInt(0, 5)) {
                val add = LongArray(r.nextInt(0, 20)) { r.nextLong(0, 120) }
                o.append(add)
                add.forEach { if (it !in expected) expected += it }
            }
            assertEquals(expected.size, o.size)
            val passing = LongArray(r.nextInt(0, 100)) { r.nextLong(0, 150) }
            val set = passing.toSet()
            assertArrayEquals(expected.filter { it in set }.toLongArray(), o.visible(DeviceKeys(passing, 0)))
        }
    }

    @Test fun largeOrders() {
        val n = 100_000
        val o = OrderedKeys(LongArray(n) { (n - it).toLong() })
        val passing = LongArray(n / 2) { (it * 2 + 2).toLong() }
        val v = o.visible(DeviceKeys(passing, 0))
        assertEquals(n / 2, v.size)
        assertEquals(n.toLong(), v.first())
    }
}
