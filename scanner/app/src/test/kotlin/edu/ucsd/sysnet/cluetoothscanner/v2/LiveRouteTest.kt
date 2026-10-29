package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-2 / §2.6: the live route grows by the deltas' points, and a list once handed out never changes. */
class LiveRouteTest {
    private fun pts(from: Int, n: Int) = List(n) { GeoPoint(from + it.toDouble(), 0.0) }

    @Test fun appendsKeepEveryEarlierListAsItWas() {
        val route = LiveRoute(pts(0, 3))
        val first = route.snapshot()
        val second = route.append(pts(3, 2))
        // far past the first buffer, so it moves at least once
        val third = route.append(pts(5, 100))
        assertEquals(pts(0, 3), first)
        assertEquals(pts(0, 5), second)
        assertEquals(pts(0, 105), third)
        assertEquals(pts(0, 106), route.append(pts(105, 1)))
        assertEquals(pts(0, 105), third)
    }

    @Test fun anEmptyRouteGrowsToo() {
        val route = LiveRoute(emptyList())
        assertTrue(route.snapshot().isEmpty())
        assertEquals(pts(0, 40), route.append(pts(0, 40)))
    }

    @Test fun indexesPastTheSizeAreOutOfBounds() {
        val list = LiveRoute(pts(0, 2)).snapshot()
        assertTrue(runCatching { list[2] }.exceptionOrNull() is IndexOutOfBoundsException)
        assertTrue(runCatching { list[-1] }.exceptionOrNull() is IndexOutOfBoundsException)
    }
}
