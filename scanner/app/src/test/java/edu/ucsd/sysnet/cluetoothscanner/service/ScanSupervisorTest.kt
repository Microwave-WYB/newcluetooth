package edu.ucsd.sysnet.cluetoothscanner.service

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
        var starts = 0
        var profile: LocationProfile? = null
        override fun start(profile: LocationProfile) { running = true; starts++; this.profile = profile }
        override fun stop() { running = false; profile = null }
    }

    private fun TestScope.setup(outcome: ScannerStartOutcome = ScannerStartOutcome.STARTED): Triple<ScanSupervisor, FakeScanner, FakeLocation> {
        val scanner = FakeScanner(outcome)
        val location = FakeLocation()
        return Triple(ScanSupervisor(scanner, location, backgroundScope), scanner, location)
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

    @Test
    fun `location is wanted exactly while the app is in the foreground or rows are written`() = runTest {
        val (supervisor, _, location) = setup()
        assertFalse(supervisor.wantsLocation)
        supervisor.setForeground(true)
        assertTrue(supervisor.wantsLocation)
        supervisor.beginRowWriting()
        supervisor.setForeground(false)
        assertTrue("a recording keeps location", supervisor.wantsLocation && location.running)
        supervisor.endRowWriting()
        assertFalse(supervisor.wantsLocation)
        assertFalse(location.running)
    }

    @Test
    fun `a location retry never starts updates when nothing wants them`() = runTest {
        val (supervisor, _, location) = setup()
        supervisor.startLocationIfWanted()
        assertFalse(location.running)
        assertEquals(0, location.starts)

        supervisor.setForeground(true)
        location.stop()
        supervisor.startLocationIfWanted()
        assertTrue(location.running)

        supervisor.setForeground(false)
        supervisor.startLocationIfWanted()
        assertFalse(location.running)
    }

    @Test
    fun `foreground changes run in the owning scope and apply in order`() = runTest {
        val (supervisor, scanner, location) = setup()
        supervisor.foregroundChanged(true)
        supervisor.foregroundChanged(false)
        runCurrent()
        assertFalse(scanner.scanning)
        assertFalse(location.running)
        assertEquals(listOf("start", "stop"), scanner.events)
    }

    @Test
    fun `location is low power while live and high rate while recording`() = runTest {
        val (supervisor, _, location) = setup()
        supervisor.setForeground(true)
        assertEquals(LocationProfile.LIVE, location.profile)

        supervisor.beginRowWriting()
        assertEquals(LocationProfile.RECORDING, location.profile)

        supervisor.endRowWriting()
        assertTrue("still in the foreground", location.running)
        assertEquals(LocationProfile.LIVE, location.profile)
    }

    @Test
    fun `a recording in the background keeps the recording profile until it stops`() = runTest {
        val (supervisor, _, location) = setup()
        supervisor.setForeground(true)
        supervisor.beginRowWriting()
        supervisor.setForeground(false)
        assertEquals(LocationProfile.RECORDING, location.profile)

        supervisor.endRowWriting()
        assertFalse(location.running)
        assertEquals(null, location.profile)
    }

    @Test
    fun `a recording started with no foreground uses the recording profile and a retry keeps it`() = runTest {
        val (supervisor, _, location) = setup()
        supervisor.beginRowWriting()
        supervisor.startLocationIfWanted()
        assertEquals(LocationProfile.RECORDING, location.profile)
    }

    @Test
    fun `a rejected recording start falls back to live location`() = runTest {
        val (supervisor, _, location) = setup(ScannerStartOutcome.REJECTED)
        supervisor.setForeground(true)
        supervisor.beginRowWriting()
        assertEquals(LocationProfile.LIVE, location.profile)
    }

    @Test
    fun `the recording profile asks for a fix every two seconds with no distance filter`() {
        val recording = LocationProfile.RECORDING
        assertEquals(2_000L, recording.intervalMs)
        assertEquals(0f, recording.minDistanceMeters, 0f)
        assertTrue(recording.highAccuracy)
        assertFalse(LocationProfile.LIVE.highAccuracy)
        assertTrue(LocationProfile.LIVE.intervalMs >= 5 * recording.intervalMs)
    }

    @Test
    fun `the newest fix wins and an older or repeated fix is ignored`() {
        val current = FixStamp("fused", 20f, 10_000_000_000L)
        assertTrue(acceptsFix(null, current))
        assertTrue("worse but newer, same provider", acceptsFix(current, FixStamp("fused", 40f, 12_000_000_000L)))
        assertFalse("older", acceptsFix(current, FixStamp("fused", 5f, 9_000_000_000L)))
        assertFalse("same instant", acceptsFix(current, FixStamp("fused", 5f, 10_000_000_000L)))
    }

    @Test
    fun `a less accurate fix from another provider waits out the grace period`() {
        val gps = FixStamp("gps", 6f, 10_000_000_000L)
        assertFalse(acceptsFix(gps, FixStamp("network", 30f, 11_000_000_000L)))
        assertTrue("more accurate", acceptsFix(gps, FixStamp("network", 5f, 11_000_000_000L)))
        assertTrue("gps went quiet", acceptsFix(gps, FixStamp("network", 30f, 16_000_000_000L)))
    }
}
