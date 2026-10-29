package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #70 review: the device cache is a true LRU, so the device the user is looking at is never evicted while heard. */
class LatestByKeyTest {
    @Test
    fun `a device heard repeatedly survives 2000 new addresses`() {
        val cache = LatestByKey<String, Int>(capacity = 1_024)
        cache.put("watched", 0)
        repeat(2_000) { n ->
            cache.put("new-$n", n)
            if (n % 10 == 0) cache.put("watched", n) // heard again, as a nearby device is
        }
        assertEquals(1_990, cache["watched"])
        assertNull("the stalest addresses went", cache["new-0"])
        assertEquals(1_999, cache["new-1999"])
    }

    @Test
    fun `a lookup also counts as use`() {
        val cache = LatestByKey<String, Int>(capacity = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache["a"]
        cache.put("d", 4)
        assertEquals(1, cache["a"])
        assertNull(cache["b"])
    }

    @Test
    fun `it never holds more than its capacity`() {
        val cache = LatestByKey<Int, Int>(capacity = 50)
        repeat(1_000) { cache.put(it, it) }
        assertEquals(50, (0 until 1_000).count { cache[it] != null })
    }
}
