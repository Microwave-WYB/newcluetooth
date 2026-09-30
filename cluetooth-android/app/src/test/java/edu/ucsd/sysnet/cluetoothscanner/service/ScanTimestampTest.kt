package edu.ucsd.sysnet.cluetoothscanner.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTimestampTest {
    @Test
    fun delayedAndBatchedScansRetainDistinctObservationTimes() {
        val snapshot = ClockSnapshot(
            wallClockMillis = 1_700_000_010_000,
            elapsedRealtimeNanos = 20_000_000_000,
        )

        assertEquals(1_700_000_005_000, scanWallClockMillis(15_000_000_000, snapshot))
        assertEquals(1_700_000_009_250, scanWallClockMillis(19_250_000_000, snapshot))
    }

    @Test
    fun subMillisecondAgeUsesSafeMillisecondBoundary() {
        val snapshot = ClockSnapshot(10_000, 2_000_000)

        assertEquals(10_000, scanWallClockMillis(1_000_001, snapshot))
        assertEquals(9_999, scanWallClockMillis(1_000_000, snapshot))
    }

    @Test
    fun invalidAndFutureMonotonicValuesFallBackToSnapshotWallClock() {
        val snapshot = ClockSnapshot(123_456, 10_000)

        assertEquals(123_456, scanWallClockMillis(0, snapshot))
        assertEquals(123_456, scanWallClockMillis(-1, snapshot))
        assertEquals(123_456, scanWallClockMillis(10_001, snapshot))
    }

    @Test
    fun subtractionOverflowSaturatesSafely() {
        val snapshot = ClockSnapshot(Long.MIN_VALUE, Long.MAX_VALUE)

        assertEquals(Long.MIN_VALUE, scanWallClockMillis(1, snapshot))
    }
}
