package edu.ucsd.sysnet.cluetoothscanner.map.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byUser classification (P-4, P-6, D-4, M-11, T-2). */
class MoveClassifierTest {
    @Test fun `an app move is never the user's`() {
        val m = MoveClassifier()
        assertFalse(m.onMoveStarted(byGesture = false, zoom = 15.0))
        assertEquals(MoveClassifier.Result(byUser = false, zoomed = false), m.onIdle(17.0))
    }

    @Test fun `a gesture is the user's and reports its start once`() {
        val m = MoveClassifier()
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0))
        assertFalse(m.onMoveStarted(byGesture = true, zoom = 15.0)) // pinch after drag in the same move
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = false), m.onIdle(15.0))
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0)) // the next move reports again
    }

    @Test fun `a user zoom is detected against the zoom at the gesture's start`() {
        val m = MoveClassifier()
        m.onMoveStarted(byGesture = true, zoom = 15.0)
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = true), m.onIdle(15.5))
        m.onMoveStarted(byGesture = true, zoom = 15.5)
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = false), m.onIdle(15.505))
    }

    @Test fun `a gesture that interrupts an app animation makes the move the user's`() {
        val m = MoveClassifier()
        m.onMoveStarted(byGesture = false, zoom = 12.0)
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 13.0))
        assertTrue(m.onIdle(13.0).byUser)
        // and the flag does not leak into the next app move
        m.onMoveStarted(byGesture = false, zoom = 13.0)
        assertFalse(m.onIdle(14.0).byUser)
    }
}
