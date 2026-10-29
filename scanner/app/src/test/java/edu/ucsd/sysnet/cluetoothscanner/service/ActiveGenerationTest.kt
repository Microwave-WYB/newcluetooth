package edu.ucsd.sysnet.cluetoothscanner.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGenerationTest {
    @Test
    fun stoppedGenerationRejectsQueuedCallback() {
        val generations = ActiveGeneration()
        val active = generations.activate()
        assertTrue(generations.accepts(active))

        generations.invalidate()

        assertFalse(generations.accepts(active))
    }

    @Test
    fun restartedRequestRejectsPriorCallbackAndAcceptsCurrentOne() {
        val generations = ActiveGeneration()
        val first = generations.activate()
        val second = generations.activate()

        assertFalse(generations.accepts(first))
        assertTrue(generations.accepts(second))
    }
}
