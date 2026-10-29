package edu.ucsd.sysnet.cluetoothscanner.core

import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PayloadRotationConfigTest {
    private val base = CoreConfig(
        locationMaxAgeMs = 5_000uL,
        recentLocationCapacity = 32u,
        payloadMaxRows = 1u,
        payloadMaxEstimatedBytes = 1uL,
        payloadMaxAgeMs = 1uL,
        recipientPublicKey = ByteArray(32) { it.toByte() },
    )

    @Test
    fun defaultsAreFiveMinutesTenMebibytesAndOneHundredThousandRows() {
        val config = PayloadRotationConfig.apply(base)

        assertEquals(300_000uL, config.payloadMaxAgeMs)
        assertEquals(10uL * 1024uL * 1024uL, config.payloadMaxEstimatedBytes)
        assertEquals(100_000u, config.payloadMaxRows)
    }

    @Test
    fun configuredRotationReachesTheCoreConfigWithoutTouchingOtherFields() {
        val config = PayloadRotationConfig.apply(base, rotationMinutes = 60, maxRows = 5u)

        assertEquals(3_600_000uL, config.payloadMaxAgeMs)
        assertEquals(5u, config.payloadMaxRows)
        assertEquals(base.locationMaxAgeMs, config.locationMaxAgeMs)
        assertArrayEquals(base.recipientPublicKey, config.recipientPublicKey)
    }
}
