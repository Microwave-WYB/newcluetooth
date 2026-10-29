package edu.ucsd.sysnet.cluetoothscanner.core

import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreConfig

/**
 * The one place that tunes how often a payload file is sealed. The Rust core validates these
 * values (with hard maximums) when opened; its own constants are only defaults, so changing a
 * value here is a Kotlin-only change and does not rebuild the native library.
 */
object PayloadRotationConfig {
    const val ROTATION_MINUTES: Long = 5
    const val MAX_ESTIMATED_BYTES: ULong = 10_485_760uL // 10 MiB
    const val MAX_ROWS: UInt = 100_000u

    fun apply(
        base: CoreConfig,
        rotationMinutes: Long = ROTATION_MINUTES,
        maxEstimatedBytes: ULong = MAX_ESTIMATED_BYTES,
        maxRows: UInt = MAX_ROWS,
    ): CoreConfig = base.copy(
        payloadMaxAgeMs = (rotationMinutes * 60_000L).toULong(),
        payloadMaxEstimatedBytes = maxEstimatedBytes,
        payloadMaxRows = maxRows,
    )
}
