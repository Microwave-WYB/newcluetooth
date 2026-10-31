package edu.ucsd.sysnet.cluetoothscanner.map.camera

import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P-4 visibility and the P-5 warning override. */
class LocateLogicTest {
    private val ok = LocationStatus(State.Ok, warn = false)
    private val off = LocationStatus(State.Off, warn = true, action = LocationStatus.Action.TurnOn)

    @Test fun `hidden by default`() {
        val l = LocateLogic()
        assertFalse(l.showLocate(ok, hasLocation = true))
        assertFalse(l.showWarning(ok))
    }

    @Test fun `shows after a user pan takes the location more than 40 dp off centre`() {
        val l = LocateLogic()
        l.onUserIdle(zoomed = false, offsetDp = 40.0)
        assertFalse(l.showLocate(ok, true))
        l.onUserIdle(zoomed = false, offsetDp = 40.5)
        assertTrue(l.showLocate(ok, true))
        // panning back near the location hides it again
        l.onUserIdle(zoomed = false, offsetDp = 10.0)
        assertFalse(l.showLocate(ok, true))
    }

    @Test fun `any user zoom shows it until the next recenter`() {
        val l = LocateLogic()
        l.onUserIdle(zoomed = true, offsetDp = 0.0)
        assertTrue(l.showLocate(ok, true))
        l.onUserIdle(zoomed = false, offsetDp = 0.0)
        assertTrue(l.showLocate(ok, true))
        l.onLocate()
        assertFalse(l.showLocate(ok, true))
        l.onUserIdle(zoomed = false, offsetDp = 0.0)
        assertFalse(l.showLocate(ok, true))
    }

    @Test fun `never without a location`() {
        val l = LocateLogic()
        l.onUserIdle(zoomed = true, offsetDp = null)
        assertFalse(l.showLocate(ok, hasLocation = false))
        l.onUserIdle(zoomed = true, offsetDp = 100.0)
        assertFalse(l.showLocate(ok, hasLocation = false))
    }

    @Test fun `P-5 the warning overrides the locate button and an unusable location resets it`() {
        val l = LocateLogic()
        l.onUserIdle(zoomed = true, offsetDp = 100.0)
        assertTrue(l.showLocate(ok, true))
        l.onStatus(off)
        assertTrue(l.showWarning(off))
        assertFalse(l.showLocate(off, true))
        l.onStatus(ok)
        assertFalse(l.showWarning(ok))
        // back to usable: the button waits for the user's next move
        assertFalse(l.showLocate(ok, true))
        l.onUserIdle(zoomed = false, offsetDp = 100.0)
        assertTrue(l.showLocate(ok, true))
    }

    @Test fun `waiting and stale warn too`() {
        val l = LocateLogic()
        assertTrue(l.showWarning(LocationStatus(State.Waiting, warn = true)))
        assertTrue(l.showWarning(LocationStatus(State.Stale, warn = true, ageMin = 3)))
        assertTrue(l.showWarning(LocationStatus(State.Denied, warn = true, action = LocationStatus.Action.Allow)))
    }
}
