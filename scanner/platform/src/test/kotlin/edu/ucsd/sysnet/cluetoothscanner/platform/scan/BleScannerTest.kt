package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Looper
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.concurrent.thread
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.DrainOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private var coded = true
    private var configureFails = false
    private var configures = 0
    private val core = object : TestCoreIngest() {
        override fun ingestScans(batch: List<ScanObservationInput>): IngestAck {
            ingested += batch
            return IngestAck(batch.size, 0, 0)
        }

        override fun configureScan(env: ScanEnvironment) {
            configures++
            if (configureFails) throw CoreException.Io("writer busy")
        }
    }
    private val deferredCore = CompletableDeferred<CoreIngest>(core)
    private val ingest = ScanIngest(deferredCore, scope, Dispatchers.Main.immediate, nowMs = { 0 })
    private val scanner = BleScanner(context, ingest, deferredCore, codedPhy = { coded }, appVersion = "0.1.0-test", scope = scope, io = Dispatchers.Main.immediate)

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
        coded = true
        assertEquals("all_supported", scanner.environment().scanPhy)
        coded = false
        assertEquals("1m", scanner.environment().scanPhy)
        assertEquals("0.1.0-test", scanner.environment().appVersion)
    }

    private companion object {
        const val ScanSettingsCallbackType = 1
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun bluetooth(on: Boolean) {
        shadowOf(adapter).setEnabled(on)
        context.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, if (on) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF),
        )
        idle()
    }

    /** Five starts at once: the initial one and four Bluetooth off/on cycles. */
    private fun fiveStarts() {
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        repeat(4) {
            bluetooth(false)
            bluetooth(true)
        }
        assertEquals("five starts so far, all allowed", 1, callbacks().size)
    }

    @Test
    fun `a sixth start within 30 s is held back, not dropped`() {
        fiveStarts()
        bluetooth(false)
        bluetooth(true)
        assertTrue("held back by the start limit", callbacks().isEmpty())
        advance(ScanStartLimiter.WINDOW_MS - 1)
        assertTrue(callbacks().isEmpty())
        advance(1)
        assertEquals("started once the oldest start left the window", 1, callbacks().size)
    }

    @Test
    fun `a stop during a held-back start never starts scanning after the recording ended`() {
        fiveStarts()
        bluetooth(false)
        bluetooth(true)
        assertTrue(callbacks().isEmpty())
        drain()
        advance(2 * ScanStartLimiter.WINDOW_MS)
        assertTrue(callbacks().isEmpty())
    }

    @Test
    fun `a Coded PHY restart over the start limit keeps the running scan and comes back`() {
        fiveStarts()
        val running = callbacks().single()
        coded = false
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        idle()
        assertEquals("no restart yet", 0, configures)
        assertSame(running, callbacks().single())
        advance(ScanStartLimiter.WINDOW_MS)
        assertEquals(1, configures)
        assertTrue("restarted with the new PHY", callbacks().single() !== running)
    }

    @Test
    fun `a failed configureScan keeps the scan, reports the error and re-arms the restart`() {
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        val running = callbacks().single()
        coded = false
        configureFails = true
        advance(BleScanner.RESTART_MS)
        assertEquals(1, configures)
        assertSame("the running scan and its settings are kept", running, callbacks().single())
        assertNotNull(scanner.error.value)
        advance(BleScanner.RESTART_MS)
        assertEquals("the restart was re-armed", 2, configures)
        configureFails = false
        advance(BleScanner.RESTART_MS)
        assertEquals(3, configures)
        assertTrue("the new PHY applies", callbacks().single() !== running)
        assertNull("a good start clears the error", scanner.error.value)
    }

    @Test
    fun `a recording that ends while configureScan is in flight is not restarted`() {
        // configureScan runs on an io queue the test releases by hand, so the stop lands while it is in flight.
        val ioQueue = ArrayDeque<Runnable>()
        val io = Executor { ioQueue.addLast(it) }.asCoroutineDispatcher()
        val slow = BleScanner(context, ingest, deferredCore, codedPhy = { coded }, appVersion = "0.1.0-test", scope = scope, io = io)
        shadowOf(adapter).setEnabled(true)
        slow.start()
        idle()
        coded = false
        advance(BleScanner.RESTART_MS)
        assertEquals("configureScan is queued", 0, configures)
        var stopped = false
        scope.launch {
            slow.stopAndDrain()
            stopped = true
        }
        repeat(5) { idle() }
        assertTrue(stopped)
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        repeat(5) { idle() }
        assertEquals(1, configures)
        advance(2 * BleScanner.RESTART_MS)
        assertTrue("no scan after the recording ended", callbacks().isEmpty())
    }

    private fun activePhy() = shadowOf(adapter.bluetoothLeScanner).activeScans.single().scanSettings()!!.phy

    @Test
    fun `a Coded PHY toggled while paused does not change the PHY at resume, only at the restart`() {
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, activePhy())
        drain() // pause
        coded = false
        scanner.start() // resume
        idle()
        assertEquals("the footer still says Coded PHY: so does the scan", ScanSettings.PHY_LE_ALL_SUPPORTED, activePhy())
        assertEquals(0, configures)
        advance(BleScanner.RESTART_MS)
        assertEquals("the restart tells the core first", 1, configures)
        assertEquals(BluetoothDevice.PHY_LE_1M, activePhy())
    }

    @Test
    fun `Bluetooth coming back does not change the PHY`() {
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        coded = false
        bluetooth(false)
        bluetooth(true)
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, activePhy())
        assertEquals(0, configures)
    }

    @Test
    fun `the recording starts with the PHY its environment declared`() {
        coded = false
        assertEquals("1m", scanner.environment().scanPhy)
        coded = true // changed between startRecording and the first scan
        shadowOf(adapter).setEnabled(true)
        scanner.start()
        idle()
        assertEquals(BluetoothDevice.PHY_LE_1M, activePhy())
    }

    @Test
    fun `a new recording starts on the PHY its footer records, not the previous recording's`() {
        shadowOf(adapter).setEnabled(true)
        coded = true
        assertEquals("all_supported", scanner.environment().scanPhy) // recording 1: core.startRecording(env)
        scanner.start()
        idle()
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, activePhy())
        drain() // stop
        coded = false
        val footer = scanner.environment() // recording 2: core.startRecording(env)
        scanner.start()
        idle()
        assertEquals("1m", footer.scanPhy)
        assertEquals("the scan agrees with the footer from the first start", BluetoothDevice.PHY_LE_1M, activePhy())
        assertEquals("no configureScan was needed", 0, configures)
    }

    @Test
    fun `a drain after the scanner's scope ended fails at once instead of hanging (#70 review)`() {
        scope.cancel()
        idle()
        var outcome: DrainOutcome? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { outcome = scanner.drain() }
        idle()
        assertTrue(outcome is DrainOutcome.Failed)
    }

    @Test
    fun `drains waiting on the actor are released when it ends, not left to their timeout (#70 re-review)`() {
        // From another thread, so the actor (main looper, paused) has not run them yet when it ends.
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<DrainOutcome>()
        val drains = List(2) { thread { kotlinx.coroutines.runBlocking { outcomes += scanner.drain() } } }
        Thread.sleep(200) // both Stop messages are in the inbox
        scope.cancel()
        idle()
        drains.forEach { it.join(2_000) }
        assertEquals("both released well before the 5 s bound", 2, outcomes.size)
        assertTrue(outcomes.all { it is DrainOutcome.Failed })
    }

    @Test
    fun `a drain whose flush never completes ends Failed after its bound (#70 review)`() {
        val stuck = object : TestCoreIngest() {
            override fun ingestScans(batch: List<ScanObservationInput>): IngestAck = throw CoreException.Backpressure()
        }
        val stuckCore = CompletableDeferred<CoreIngest>(stuck)
        val stuckIngest = ScanIngest(stuckCore, scope, Dispatchers.Main.immediate, nowMs = { 0 })
        val stuckScanner = BleScanner(context, stuckIngest, stuckCore, codedPhy = { coded }, appVersion = "0.1.0-test", scope = scope, io = Dispatchers.Main.immediate)
        shadowOf(adapter).setEnabled(true)
        stuckScanner.start()
        idle()
        callbacks().single().onScanResult(ScanSettingsCallbackType, result("AA:BB:CC:00:11:22", -60, byteArrayOf(2, 1, 6)))
        var outcome: DrainOutcome? = null
        scope.launch { outcome = stuckScanner.drain() }
        idle()
        assertNull("still waiting for the core", outcome)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BleScanner.DRAIN_TIMEOUT_MS + 1))
        assertEquals(DrainOutcome.Failed("the observations did not flush"), outcome)
        assertTrue("the scan stopped anyway", callbacks().isEmpty())
    }
}

