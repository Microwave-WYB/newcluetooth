package edu.ucsd.sysnet.cluetoothscanner.service

internal data class ClockSnapshot(
    val wallClockMillis: Long,
    val elapsedRealtimeNanos: Long,
)

/** Maps Android's monotonic ScanResult timestamp onto the paired wall-clock snapshot. */
internal fun scanWallClockMillis(
    scanTimestampNanos: Long,
    snapshot: ClockSnapshot,
): Long {
    if (scanTimestampNanos <= 0 || snapshot.elapsedRealtimeNanos <= 0) {
        return snapshot.wallClockMillis
    }
    if (scanTimestampNanos > snapshot.elapsedRealtimeNanos) {
        return snapshot.wallClockMillis
    }

    val ageMillis = (snapshot.elapsedRealtimeNanos - scanTimestampNanos) / 1_000_000L
    return try {
        Math.subtractExact(snapshot.wallClockMillis, ageMillis)
    } catch (_: ArithmeticException) {
        Long.MIN_VALUE
    }
}
