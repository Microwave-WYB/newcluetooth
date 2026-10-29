package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleScannerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ingested = mutableListOf<ScanObservationInput>()
    private val core = object : TestCoreIngest() {
        override fun ingestScans(batch: List<ScanObservationInput>): IngestAck {
            ingested += batch
            return IngestAck(batch.size, 0, 0)
        }
    }
    private val deferredCore = CompletableDeferred<CoreIngest>(core)
    private val ingest = ScanIngest(deferredCore, scope, Dispatchers.Main.immediate, nowMs = { 0 })
    private val scanner = BleScanner(context, ingest, deferredCore, codedPhy = { true }, appVersion = "0.1.0-test", scope = scope, io = Dispatchers.Main.immediate)

    @After
    fun cancel() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun callbacks(): Set<ScanCallback> = shadowOf(adapter.bluetoothLeScanner).scanCallbacks

    private fun result(addr: String, rssi: Int, raw: ByteArray): ScanResult {
        val record = ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java).invoke(null, raw) as ScanRecord
        return ScanResult(adapter.getRemoteDevice(addr), 0x1b, 1, 0, 255, 127, rssi, 0, record, 5_000_000_000)
    }

    private fun drain() {
        var done = false
        scope.launch {
            scanner.stopAndDrain()
            done = true
        }
        repeat(5) { idle() }
        assertTrue("stopAndDrain finished", done)
    }

    @Test
    fun `section 4_1 Bluetooth off defers the scan and STATE_ON starts it`() {
        shadowOf(adapter).setEnabled(false)
        scanner.start()
        idle()
        assertTrue(scanner.deferred.value)

        shadowOf(adapter).setEnabled(true)
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        idle()
        assertFalse(scanner.deferred.value)
        assertEquals(1, callbacks().size)
    }

    @Test
    fun `V-1 the scan runs only between start and stopAndDrain, and drain delivers what was scanned`() {
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        val callback = callbacks().single()
        callback.onScanResult(ScanSettingsCallbackType, result("AA:BB:CC:00:11:22", -61, byteArrayOf(2, 1, 6, 4, 9, 'Q'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())))
        drain()
        assertTrue(callbacks().isEmpty())
        val observation = ingested.single()
        assertEquals("AA:BB:CC:00:11:22", observation.addr)
        assertEquals(-61, observation.rssi)
        assertEquals("QT1", observation.localName)
        assertNull("SID 255 is not present", observation.advSid)
        assertNotNull(scanner.devices["AA:BB:CC:00:11:22"])

        // A callback of the stopped scan is ignored.
        callback.onScanResult(ScanSettingsCallbackType, result("AA:BB:CC:00:11:33", -70, byteArrayOf(2, 1, 6)))
        drain()
        assertEquals(1, ingested.size)
    }

    @Test
    fun `section 4_1 conversion maps 127 to null and keeps oversized advertisements out`() {
        val snapshot = ClockSnapshot(wallClockMillis = 10_000, elapsedRealtimeNanos = 6_000_000_000)
        val unavailable = BleScanner.toObservation(result("AA:BB:CC:00:11:22", 127, byteArrayOf(2, 1, 6)), snapshot)!!
        assertNull(unavailable.rssi)
        assertEquals("scanned 1 s before the snapshot", 9_000L, unavailable.scannedAtMs)
        assertNull(BleScanner.toObservation(result("AA:BB:CC:00:11:22", -50, ByteArray(1_651) { 0 }), snapshot))
    }

    @Test
    fun `the scan environment states the Coded PHY setting`() {
        assertEquals("all_supported", scanner.environment(coded = true).scanPhy)
        assertEquals("1m", scanner.environment(coded = false).scanPhy)
        assertEquals("0.1.0-test", scanner.environment().appVersion)
    }

    private companion object {
        const val ScanSettingsCallbackType = 1
    }
}
