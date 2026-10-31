package edu.ucsd.sysnet.cluetoothscanner.service

/**
 * How hard location is requested. [ScanSupervisor] picks it: [RECORDING] exactly while rows are
 * written, [LIVE] for the foreground-only case.
 */
enum class LocationProfile(
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceMeters: Float,
    val highAccuracy: Boolean,
) {
    /** App visible, nothing recorded: a coarse fix every ~10 s is enough for the live list. */
    LIVE(intervalMs = 10_000L, minIntervalMs = 5_000L, minDistanceMeters = 0f, highAccuracy = false),

    /** Recording: a fix every ~2 s even when stationary, so the route and the per-minute best sample are dense. */
    RECORDING(intervalMs = 2_000L, minIntervalMs = 1_000L, minDistanceMeters = 0f, highAccuracy = true),
}

/** The facts of a fix that decide whether it replaces the current one. */
internal data class FixStamp(val provider: String?, val accuracyMeters: Float, val elapsedRealtimeNanos: Long)

/**
 * The newest fix wins. The one exception is the platform fallback, where GPS and network fixes
 * interleave: a less accurate fix from another provider does not replace a fix less than
 * [PROVIDER_SWITCH_GRACE_NANOS] old.
 */
internal fun acceptsFix(current: FixStamp?, candidate: FixStamp): Boolean {
    if (current == null) return true
    if (candidate.elapsedRealtimeNanos <= current.elapsedRealtimeNanos) return false
    val age = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos
    return candidate.provider == current.provider ||
        candidate.accuracyMeters <= current.accuracyMeters ||
        age >= PROVIDER_SWITCH_GRACE_NANOS
}

private const val PROVIDER_SWITCH_GRACE_NANOS = 5_000_000_000L
