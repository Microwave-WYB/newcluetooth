package edu.ucsd.sysnet.cluetoothscanner.corebridge

import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreAssets
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The packaged `.so` loads on a device and a recording round-trips through the real core. */
class CoreBridgeSmokeTest {
    private val env = ScanEnvironment("Pixel 8", "15", 35, "0.1.0", true, true, 1650, "all_supported", false)

    // flags(02 01 06) + complete local name "SmokeTag"
    private val raw = byteArrayOf(2, 1, 6, 9, 9) + "SmokeTag".toByteArray()

    @Test
    fun recordIngestAndQuery() = runBlocking {
        val bundle = InstrumentationRegistry.getInstrumentation().context.assets.open("fake-bundle.json")
            .bufferedReader().use { it.readText() }
        val dir = Files.createTempDirectory("core-bridge-smoke").toFile()
        val core = CoreBridge.open(
            CoreConfig(dataDirectory = dir.path, recipientPublicKey = ByteArray(32)),
            CoreAssets(bundle, "company\t0057\tHarman International\n"),
        )
        try {
            val session = core.startRecording(env)
            val now = System.currentTimeMillis()
            var nanos = 0L
            for (i in 0 until 5) {
                nanos += 1_000_000_000
                core.ingestLocation(LocationFix(32.880 + i * 1e-5, -117.234, 8.0, now + i, nanos))
                val addr = "AA:BB:CC:DD:EE:0$i"
                val ack = core.ingestScans(listOf(ScanObservationInput(
                    addr, -50 - i, now + i, nanos, raw, "SmokeTag", -12, true, true, 1, 0, null, null, null, 0, 1,
                )))
                assertEquals(1, ack.accepted)
            }
            assertEquals(session, core.stopRecording())

            val scope = Scope.session(session)
            val keys = core.deviceKeys(scope, DeviceFilter())
            assertTrue("no Discover keys", keys.keys.isNotEmpty())
            val rows = core.deviceRows(scope, keys.keys)
            assertTrue("no Discover rows", rows.isNotEmpty())
            val map = core.mapPoints(scope, DeviceFilter(), Bbox(-117.236, 32.878, -117.232, 32.882), 16.0)
            assertTrue("no map points", map.count > 0)
        } finally {
            core.close()
            dir.deleteRecursively()
        }
    }
}
