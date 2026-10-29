package edu.ucsd.sysnet.cluetoothscanner.api.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    /** §2.1: the first UI builds only Scope(All) and Scope(Sessions([id])), with no session filter. */
    @Test
    fun scopeHelpers() {
        assertEquals(Scope(SessionScope.All, null), Scope.All)
        assertEquals(Scope(SessionScope.Sessions(listOf("s1"))), Scope.session("s1"))
        assertNull(Scope.session("s1").filter)
    }

    /** §2.2: the default filter has no constraint (V-7 resets to it). */
    @Test
    fun defaultFilterIsUnconstrained() {
        val filter = DeviceFilter()
        assertNull(filter.area)
        assertEquals(Identification.Any, filter.identification)
        assertTrue(filter.categories.isEmpty())
        assertFalse(filter.readable || filter.trackers || filter.vulnerable || filter.inRangeOnly)
        assertNull(filter.page)
        assertNull(filter.query)
    }

    /** §2.3: CoreConfig defaults are the documented ones. */
    @Test
    fun coreConfigDefaults() {
        val config = CoreConfig(dataDirectory = "/data", recipientPublicKey = ByteArray(32))
        assertEquals(5_000L, config.durabilityIntervalMs)
        assertEquals(20_000L, config.staleAfterMs)
        assertEquals(1_000L, config.liveIntervalMs)
        assertEquals(100_000, config.payloadMaxRows)
    }
}
