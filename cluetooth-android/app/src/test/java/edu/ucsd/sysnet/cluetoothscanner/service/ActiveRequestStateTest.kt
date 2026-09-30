package edu.ucsd.sysnet.cluetoothscanner.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRequestStateTest {
    @Test
    fun currentRegistrationFailureClearsStateAndPermitsRetry() {
        val state = ActiveRequestState<String>()
        val failed = state.begin { "callback-$it" }

        assertTrue(state.fail(failed.generation))
        assertFalse(state.isActive)
        assertFalse(state.accepts(failed.generation))

        val retry = state.begin { "callback-$it" }
        assertTrue(state.isActive)
        assertTrue(state.accepts(retry.generation))
        assertEquals("callback-${retry.generation}", retry.callback)
    }

    @Test
    fun staleFailureCannotClearNewerRequest() {
        val state = ActiveRequestState<String>()
        val first = state.begin { "first" }
        assertEquals("first", state.stop())
        val second = state.begin { "second" }

        assertFalse(state.fail(first.generation))
        assertTrue(state.isActive)
        assertTrue(state.accepts(second.generation))
        assertEquals("second", state.stop())
        assertNull(state.stop())
    }
}
