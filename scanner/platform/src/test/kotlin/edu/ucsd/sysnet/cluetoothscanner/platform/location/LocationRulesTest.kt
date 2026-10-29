package edu.ucsd.sysnet.cluetoothscanner.platform.location

import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.Action
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRulesTest {
    private fun status(permitted: Boolean = true, providerOn: Boolean = true, since: Long? = 0, fix: Long? = null, now: Long) =
        locationStatus(permitted, providerOn, since, fix, now)

    @Test
    fun `P-5 a missing permission is Denied with Allow, before anything else`() {
        val denied = status(permitted = false, providerOn = false, now = 0)
        assertEquals(State.Denied, denied.state)
        assertTrue(denied.warn)
        assertEquals(Action.Allow, denied.action)
    }

    @Test
    fun `P-5 a provider that is off is Off with Turn on`() {
        val off = status(providerOn = false, now = 0)
        assertEquals(State.Off, off.state)
        assertTrue(off.warn)
        assertEquals(Action.TurnOn, off.action)
    }

    @Test
    fun `P-5 no first fix warns only after more than 30 s`() {
        assertEquals(State.Waiting, status(now = 0).state)
        assertFalse(status(now = 30_000).warn)
        assertTrue(status(now = 30_001).warn)
        assertNull(status(now = 30_001).action)
        assertFalse("no request, no warning", status(since = null, now = 99_000).warn)
    }

    @Test
    fun `P-5 a fix older than 2 min is Stale with its age in minutes`() {
        assertEquals(State.Ok, status(fix = 0, now = 120_000).state)
        val stale = status(fix = 0, now = 120_001)
        assertEquals(State.Stale, stale.state)
        assertTrue(stale.warn)
        assertEquals(2, stale.ageMin)
        assertEquals(5, status(fix = 0, now = 5 * 60_000 + 59_000).ageMin)
    }

    @Test
    fun `P-5 a fresh fix is Ok and does not warn`() {
        val ok = status(fix = 10_000, now = 20_000)
        assertEquals(State.Ok, ok.state)
        assertFalse(ok.warn)
    }

    @Test
    fun `section 4_2 RECORDING while recording, FOREGROUND while the UI is started`() {
        assertEquals(LocationProfile.RECORDING, LocationProfile.wanted(recording = true, uiStarted = false))
        assertEquals(LocationProfile.RECORDING, LocationProfile.wanted(recording = true, uiStarted = true))
        assertEquals(LocationProfile.FOREGROUND, LocationProfile.wanted(recording = false, uiStarted = true))
        assertNull(LocationProfile.wanted(recording = false, uiStarted = false))
        assertEquals(2_000L, LocationProfile.RECORDING.intervalMs)
        assertTrue(LocationProfile.RECORDING.highAccuracy)
        assertEquals(10_000L, LocationProfile.FOREGROUND.intervalMs)
        assertFalse(LocationProfile.FOREGROUND.highAccuracy)
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
