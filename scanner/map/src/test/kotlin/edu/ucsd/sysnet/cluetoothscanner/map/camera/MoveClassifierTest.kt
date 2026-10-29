package edu.ucsd.sysnet.cluetoothscanner.map.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byUser classification (P-4, P-6, D-4, M-11, T-2). */
class MoveClassifierTest {
    @Test fun `an app move is never the user's`() {
        val m = MoveClassifier()
        assertFalse(m.onMoveStarted(byGesture = false, zoom = 15.0, nowMs = 0))
        assertEquals(MoveClassifier.Result(byUser = false, zoomed = false), m.onIdle(17.0, nowMs = 500))
    }

    @Test fun `a gesture is the user's and reports its start once`() {
        val m = MoveClassifier()
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 0))
        assertFalse(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 50)) // pinch after drag in the same move
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = false), m.onIdle(15.0, nowMs = 400))
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 2_000)) // the next touch reports again
    }

    @Test fun `a user zoom is detected against the zoom at the gesture's start`() {
        val m = MoveClassifier()
        m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 0)
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = true), m.onIdle(15.5, nowMs = 300))
        m.onMoveStarted(byGesture = true, zoom = 15.5, nowMs = 2_000)
        assertEquals(MoveClassifier.Result(byUser = true, zoomed = false), m.onIdle(15.505, nowMs = 2_300))
    }

    @Test fun `a gesture that interrupts an app animation makes the move the user's`() {
        val m = MoveClassifier()
        m.onMoveStarted(byGesture = false, zoom = 12.0, nowMs = 0)
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 13.0, nowMs = 100))
        assertTrue(m.onIdle(13.0, nowMs = 400).byUser)
        // and the flag does not leak into the next app move
        m.onMoveStarted(byGesture = false, zoom = 13.0, nowMs = 2_000)
        assertFalse(m.onIdle(14.0, nowMs = 2_500).byUser)
    }

    @Test fun `the fling edge - a gesture move right after the user's idle continues it`() {
        val m = MoveClassifier()
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 0))
        assertTrue(m.onIdle(15.0, nowMs = 300).byUser) // MapLibre's idle on finger lift
        assertFalse(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 300 + MoveClassifier.CONTINUATION_MS))
        assertTrue(m.onIdle(15.0, nowMs = 1_200).byUser) // the fling's own idle is the user's too
        // an app move right after a user idle is still the app's
        assertFalse(m.onMoveStarted(byGesture = false, zoom = 15.0, nowMs = 1_210))
        assertFalse(m.onIdle(15.0, nowMs = 1_500).byUser)
        // a new touch later is a new gesture
        assertTrue(m.onMoveStarted(byGesture = true, zoom = 15.0, nowMs = 1_200 + MoveClassifier.CONTINUATION_MS + 1))
    }
}
