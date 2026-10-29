package edu.ucsd.sysnet.cluetoothscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreException
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SchemaV2Row
import edu.ucsd.sysnet.cluetoothscanner.core.generated.apiVersion
import edu.ucsd.sysnet.cluetoothscanner.core.generated.inspectSchemaV2Parquet
import edu.ucsd.sysnet.cluetoothscanner.core.generated.writeSchemaV2Parquet
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CluetoothCoreSmokeTest {
    @Test
    fun packagedLibraryWritesAndInspectsSchemaV2AndMapsErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val smokeDirectory = File(context.cacheDir, "cluetooth-core-smoke")
        smokeDirectory.deleteRecursively()
        assertTrue("failed to create smoke-test cache directory", smokeDirectory.mkdir())

        try {
            assertEquals(6u, apiVersion())

            val payloadId = "0195c920-7c00-7abc-8def-0123456789ab"
            val payload = File(smokeDirectory, "$payloadId.parquet")
            val exactRaw = byteArrayOf(0x02, 0x01, 0x06, 0x05, -0x01, 0x00, -0x80, -0x02)
            val inspection = writeSchemaV2Parquet(
                path = payload.absolutePath,
                payloadId = payloadId,
                rows = listOf(
                    SchemaV2Row(
                        addr = "AA:BB:CC:DD:EE:FF",
                        rssi = -47,
                        scannedAtMs = 1_741_435_200_123,
                        raw = exactRaw,
                        localName = "sensor",
                        txPower = -12,
                        isConnectable = true,
                        lat = 32.8801,
                        lon = -117.234,
                        accuracy = 4.25,
                    ),
                ),
            )

            assertTrue(payload.isFile)
            assertTrue(payload.length() > 0)
            assertEquals(payloadId, inspection.payloadId)
            assertEquals("v2", inspection.schemaVersion)
            assertEquals(1uL, inspection.rowCount)
            assertEquals(EXPECTED_COLUMNS, inspection.columnNames)
            assertArrayEquals(
                byteArrayOf(0x02, 0x01, 0x06, 0x05, -0x01, 0x00, -0x80, -0x02),
                exactRaw,
            )

            val reinspected = inspectSchemaV2Parquet(payload.absolutePath)
            assertEquals(payloadId, reinspected.payloadId)
            assertEquals("v2", reinspected.schemaVersion)
            assertEquals(1uL, reinspected.rowCount)
            assertEquals(EXPECTED_COLUMNS, reinspected.columnNames)

            val invalidOutput = File(smokeDirectory, "invalid.parquet")
            try {
                writeSchemaV2Parquet(invalidOutput.absolutePath, "not-a-uuid", emptyList())
                fail("invalid payload ID should map to CoreException.InvalidPayload")
            } catch (error: CoreException.InvalidPayload) {
                assertTrue(error.detail.contains("invalid payload UUID"))
            }
            assertFalse(invalidOutput.exists())
        } finally {
            assertTrue("failed to clean smoke-test cache files", smokeDirectory.deleteRecursively())
        }
    }

    private companion object {
        val EXPECTED_COLUMNS = listOf(
            "addr",
            "rssi",
            "scanned_at",
            "raw",
            "local_name",
            "tx_power",
            "is_connectable",
            "lat",
            "lon",
            "accuracy",
        )
    }
}
