package edu.ucsd.sysnet.cluetoothscanner.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSupervisorTest {
    private class FakeScanner(var startOutcome: ScannerStartOutcome = ScannerStartOutcome.STARTED) : ScannerPort {
        val events = mutableListOf<String>()
        var scanning = false
        var rowsOn = false
        override fun start(): ScannerStartOutcome {
            events += "start"
            if (startOutcome == ScannerStartOutcome.STARTED) scanning = true
            return startOutcome
        }

        override suspend fun stopAndWait() {
            events += "stop"
            scanning = false
        }

        override fun isScanning() = scanning
        override fun setRowWriting(enabled: Boolean) {
            events += "rows-$enabled"
            rowsOn = enabled
        }

        override suspend fun fence() {
            events += "fence"
        }
    }

    private class FakeLocation : LocationPort {
        var running = false
        override fun start() { running = true }
        override fun stop() { running = false }
    }

    private fun setup(outcome: ScannerStartOutcome = ScannerStartOutcome.STARTED): Triple<ScanSupervisor, FakeScanner, FakeLocation> {
        val scanner = FakeScanner(outcome)
        val location = FakeLocation()
        return Triple(ScanSupervisor(scanner, location), scanner, location)
    }

    @Test
    fun `live scanning in the foreground writes no rows`() = runTest {
        val (supervisor, scanner, location) = setup()
        supervisor.setForeground(true)

        assertTrue(scanner.scanning)
        assertFalse("live scanning must not write rows", scanner.rowsOn)
        assertTrue(location.running)
        assertEquals(listOf("start"), scanner.events)
    }

    @Test
    fun `leaving the foreground without a recording stops the scanner and location`() = runTest {
        val (supervisor, scanner, location) = setup()
        supervisor.setForeground(true)
        supervisor.setForeground(false)

        assertFalse(scanner.scanning)
        assertFalse(location.running)
    }

    @Test
    fun `a recording started during live scanning reuses the running scanner and then writes rows`() = runTest {
        val (supervisor, scanner, _) = setup()
        supervisor.setForeground(true)
        scanner.events.clear()

        assertEquals(ScannerStartOutcome.STARTED, supervisor.beginRowWriting())

        // No restart: the scanner was already running.
        assertEquals(listOf("rows-true"), scanner.events)
        assertTrue(scanner.rowsOn)
    }

    @Test
    fun `stopping a recording in the foreground keeps live scanning and stops the rows behind a fence`() = runTest {
        val (supervisor, scanner, _) = setup()
        supervisor.setForeground(true)
        supervisor.beginRowWriting()
        scanner.events.clear()

        supervisor.endRowWriting()

        assertEquals(listOf("rows-false", "fence"), scanner.events)
        assertTrue("live scanning continues", scanner.scanning)
        assertFalse(scanner.rowsOn)
    }

    @Test
    fun `a recording keeps the scanner and location running in the background`() = runTest {
        val (supervisor, scanner, location) = setup()
        supervisor.setForeground(true)
        supervisor.beginRowWriting()

        supervisor.setForeground(false)

        assertTrue(scanner.scanning)
        assertTrue(scanner.rowsOn)
        assertTrue(location.running)
    }

    @Test
    fun `stopping a recording in the background stops the scanner and location`() = runTest {
        val (supervisor, scanner, location) = setup()
        supervisor.setForeground(true)
        supervisor.beginRowWriting()
        supervisor.setForeground(false)

        supervisor.endRowWriting()

        assertFalse(scanner.scanning)
        assertFalse(location.running)
        assertEquals(listOf("rows-false", "fence", "stop"), scanner.events.takeLast(3))
    }

    @Test
    fun `a recording can start without any foreground and then needs no activity`() = runTest {
        val (supervisor, scanner, location) = setup()
        assertEquals(ScannerStartOutcome.STARTED, supervisor.beginRowWriting())
        assertTrue(scanner.scanning && scanner.rowsOn && location.running)
    }

    @Test
    fun `rows are enabled only after the scanner is running`() = runTest {
        val (supervisor, scanner, _) = setup()
        supervisor.beginRowWriting()
        assertEquals(listOf("start", "rows-true"), scanner.events)
    }

    @Test
    fun `a deferred start (bluetooth off) still opens row writing so recovery records`() = runTest {
        val (supervisor, scanner, _) = setup(ScannerStartOutcome.DEFERRED_INTENT_RETAINED)
        assertEquals(ScannerStartOutcome.DEFERRED_INTENT_RETAINED, supervisor.beginRowWriting())
        assertTrue(scanner.rowsOn)
    }

    @Test
    fun `a rejected start leaves rows off`() = runTest {
        val (supervisor, scanner, _) = setup(ScannerStartOutcome.REJECTED)
        assertEquals(ScannerStartOutcome.REJECTED, supervisor.beginRowWriting())
        assertFalse(scanner.rowsOn)
        assertFalse(scanner.scanning)
    }

    @Test
    fun `live recovery restarts the scanner only for a foreground without a recording`() = runTest {
        val (supervisor, scanner, _) = setup()
        supervisor.setForeground(true)
        scanner.scanning = false
        scanner.events.clear()
        supervisor.liveRecoveryAvailable()
        assertEquals(listOf("start"), scanner.events)

        // With a recording the recording layer owns recovery.
        supervisor.beginRowWriting()
        scanner.scanning = false
        scanner.events.clear()
        supervisor.liveRecoveryAvailable()
        assertTrue(scanner.events.isEmpty())
    }

    @Test
    fun `foreground off then on starts live scanning again`() = runTest {
        val (supervisor, scanner, _) = setup()
        supervisor.setForeground(true)
        supervisor.setForeground(false)
        supervisor.setForeground(true)
        assertTrue(scanner.scanning)
        assertEquals(listOf("start", "stop", "start"), scanner.events)
    }
}
