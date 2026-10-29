package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** G-9: the persistent order behaves like :ui-contract's OrderedKeys, and old values never change. */
class KeyOrderTest {
    private fun keys(vararg k: Long) = DeviceKeys(k, 0)

    @Test
    fun `appends dedup and go last, visible keeps the order`() {
        val o = KeyOrder.of(longArrayOf(30, 10, 20, 10))
        assertEquals(3, o.size)
        assertArrayEquals(longArrayOf(30, 10, 20), o.toLongArray())
        val p = o.append(longArrayOf(5, 30, 7))
        assertArrayEquals(longArrayOf(30, 10, 20, 5, 7), p.toLongArray())
        assertArrayEquals(longArrayOf(30, 20, 7), p.visible(keys(7, 30, 20, 99)))
    }

    @Test
    fun `old values never change, appending to an old value forks`() {
        val a = KeyOrder.of(longArrayOf(1, 2, 3))
        val b = a.append(longArrayOf(4))
        val c = a.append(longArrayOf(5)) // a is no longer the newest: copies
        assertArrayEquals(longArrayOf(1, 2, 3), a.toLongArray())
        assertArrayEquals(longArrayOf(1, 2, 3, 4), b.toLongArray())
        assertArrayEquals(longArrayOf(1, 2, 3, 5), c.toLongArray())
        assertFalse(4L in a)
        assertTrue(4L in b)
        assertFalse(4L in c)
        assertTrue(5L in c)
    }

    @Test
    fun `fixed-seed random appends match OrderedKeys, and every version stays a prefix`() {
        val rnd = Random(11)
        var order = KeyOrder.Empty
        val reference = OrderedKeys(LongArray(0))
        val versions = mutableListOf<Pair<KeyOrder, LongArray>>()
        repeat(500) {
            val batch = LongArray(rnd.nextInt(0, 6)) { rnd.nextLong(0, 200) }
            order = order.append(batch)
            reference.append(batch)
            versions += order to order.toLongArray()
            val probe = DeviceKeys(LongArray(20) { rnd.nextLong(0, 200) }, 0)
            assertArrayEquals(reference.visible(probe), order.visible(probe))
        }
        for ((v, snapshot) in versions) assertArrayEquals(snapshot, v.toLongArray())
    }

    /** Bytes this thread allocated so far (HotSpot's per-thread counter; reflective, the unit tests compile against android.jar). */
    private fun allocated(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counter = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        return counter.invoke(bean, Thread.currentThread().id) as Long
    }

    @Test
    fun `600k keys stay within about 9 MB, with no boxing`() {
        val n = 600_000
        val rnd = Random(5)
        val input = LongArray(n) { rnd.nextLong() }
        val before = allocated()
        val order = KeyOrder.of(input)
        val built = allocated() - before
        // keys 4.8 MB + a 2^20-slot int index 4.2 MB; a HashMap<Long, Int> index alone is 35-40 MB
        assertTrue("footprint ${order.footprintBytes()}", order.footprintBytes() <= 9_100_000)
        assertTrue("allocated $built", built < 12_000_000)
        // incremental appends (the live path) double the buffers, so at most twice that
        var live = KeyOrder.Empty
        val startLive = allocated()
        for (i in 0 until n step 1000) live = live.append(input.copyOfRange(i, i + 1000))
        assertTrue("footprint ${live.footprintBytes()}", live.footprintBytes() <= 18_000_000)
        assertTrue("allocated ${allocated() - startLive}", allocated() - startLive < 40_000_000)
        // and still exact
        assertEquals(n, order.size)
        assertArrayEquals(input, order.toLongArray())
        assertArrayEquals(input, live.toLongArray())
        for (i in 0 until n step 997) assertTrue(input[i] in order && input[i] in live)
        repeat(1000) { assertFalse(rnd.nextLong() in order) }
        val probe = DeviceKeys(LongArray(5000) { input[rnd.nextInt(n)] }, 0)
        val wanted = probe.keys.toHashSet()
        assertArrayEquals(input.filter { it in wanted }.toLongArray(), order.visible(probe))
    }
}
