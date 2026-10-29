package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStartLimiterTest {
    private var now = 0L
    private val limiter = ScanStartLimiter(nowMs = { now })

    @Test
    fun `five starts are allowed within the window and the sixth waits for the oldest to leave it`() {
        repeat(5) {
            assertEquals(0L, limiter.waitMs())
            limiter.record()
            now += 1_000
        }
        // Starts at 0..4 s; now 5 s: the oldest leaves the 30.5 s window at 30.5 s.
        assertEquals(25_500L, limiter.waitMs())
        now = 30_499
        assertEquals(1L, limiter.waitMs())
        now = 30_500
        assertEquals(0L, limiter.waitMs())
        limiter.record()
        assertEquals("the second oldest (1 s) leaves at 31.5 s", 1_000L, limiter.waitMs())
    }

    @Test
    fun `starts spread over more than the window never wait`() {
        repeat(20) {
            assertEquals(0L, limiter.waitMs())
            limiter.record()
            now += 7_000
        }
    }
}
