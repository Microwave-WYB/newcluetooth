package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The BLE scanner of a recording (§4.1, SPEC V-1): it scans only between [start] and [stopAndDrain], which the
 * recorder calls while recording and not paused. Every call and callback runs on the main looper.
 *
 * - ScanSettings as v3: low latency, all matches, `setLegacy(false)`, PHY `ALL_SUPPORTED` with "Scan LE Coded PHY"
 *   on (else 1M), and one empty `ScanFilter`.
 * - The scan restarts every 60 s; with the screen off the restart waits until the scan has run 20 min
 *   ([ScanRestartPolicy]). A Coded PHY change applies at the next restart, after `core.configureScan`.
 * - Bluetooth off (or no scanner) defers the scan: [deferred] is true and the scan restarts by itself on `STATE_ON`.
 * - Callbacks of a stopped or replaced scan are ignored (generation gate); [stopAndDrain] waits for the callbacks
 *   already queued (main-looper fence) and then flushes [ingest].
 */
@SuppressLint("MissingPermission") // The recorder checks BLUETOOTH_SCAN/CONNECT before a recording starts.
class BleScanner(
    private val context: Context,
    private val ingest: ScanIngest,
    private val core: Deferred<CoreIngest>,
    /** The "Scan LE Coded PHY" setting, read at every (re)start. */
    private val codedPhy: () -> Boolean,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** The latest `BluetoothDevice` per address, for GATT on random addresses before API 34. */
    val devices: LatestByKey<String, BluetoothDevice> = LatestByKey(1_024),
) : RecordingScanner {
    private val main = Handler(Looper.getMainLooper())
    private val fence = CallbackFence(main::post)
    private val requests = ActiveRequestState<ScanCallback>()
    private val _deferred = MutableStateFlow(false)
    override val deferred: StateFlow<Boolean> = _deferred.asStateFlow()
    override val dropped: StateFlow<Long> get() = ingest.dropped

    private var requested = false
    private var receiversRegistered = false
    private var startedAtElapsedMs = 0L
    private var appliedCodedPhy = true
    private var restartSkippedWhileScreenOff = false
    private var restart: Runnable? = null
    private var retry: Runnable? = null

    /** Advertisements kept out of the durable path because they exceed the row limit. */
    @Volatile
    var oversizedDropped = 0L
        private set

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> defer("Bluetooth is off")
                BluetoothAdapter.STATE_ON -> if (requested && !requests.isActive) startScan()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (requests.isActive && ScanRestartPolicy.screenOnRestartNow(restartSkippedWhileScreenOff, appliedCodedPhy, codedPhy())) {
                restartNow()
            }
        }
    }

    override fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::start)
            return
        }
        requested = true
        registerReceivers()
        if (!requests.isActive) startScan()
    }

    override suspend fun stopAndDrain() {
        withContext(Dispatchers.Main.immediate) {
            requested = false
            stopScan()
            _deferred.value = false
            unregisterReceivers()
        }
        fence.await()
        ingest.flush()
    }

    /** The footer facts of a scan with the current settings (`start_recording`, `configure_scan`). */
    fun environment(coded: Boolean = codedPhy()): ScanEnvironment {
        val adapter = adapter()
        val o = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return buildScanEnvironment(
            deviceModel = Build.MODEL ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = appVersion,
            extendedAdvertisingSupported = o && adapter?.isLeExtendedAdvertisingSupported == true,
            codedPhySupported = o && adapter?.isLeCodedPhySupported == true,
            maxAdvertisingDataLength = if (o) adapter?.leMaximumAdvertisingDataLength ?: 31 else 31,
            codedPhyEnabled = coded,
        )
    }

    private fun adapter(): BluetoothAdapter? = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: SecurityException) {
        null
    }

    private fun startScan() {
        cancel(retry)
        val adapter = adapter()
        val enabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        if (!enabled) return defer("Bluetooth is off")
        val scanner = adapter?.bluetoothLeScanner ?: return defer("No Bluetooth LE scanner", retryMs = RETRY_MS)
        val coded = codedPhy()
        val request = requests.begin(::callback)
        try {
            scanner.startScan(listOf(ScanFilter.Builder().build()), settings(coded), request.callback)
        } catch (error: Exception) {
            requests.fail(request.generation)
            return defer("startScan failed: ${error.message}", retryMs = RETRY_MS)
        }
        appliedCodedPhy = coded
        restartSkippedWhileScreenOff = false
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        _deferred.value = false
        scheduleRestart()
    }

    private fun stopScan() {
        cancel(restart)
        cancel(retry)
        val callback = requests.stop() ?: return
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (error: Exception) {
            Log.w(TAG, "stopScan failed", error)
        }
    }

    /** The scan waits for Bluetooth (or retries); the recording stays open. */
    private fun defer(reason: String, retryMs: Long? = null) {
        if (!requested) return
        Log.i(TAG, "Scan deferred: $reason")
        stopScan()
        _deferred.value = true
        if (retryMs != null) {
            retry = Runnable { if (requested && !requests.isActive) startScan() }.also { main.postDelayed(it, retryMs) }
        }
    }

    private fun scheduleRestart() {
        cancel(restart)
        restart = Runnable {
            if (!requests.isActive) return@Runnable
            val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            if (ScanRestartPolicy.periodicRestartNow(screenOn, SystemClock.elapsedRealtime() - startedAtElapsedMs)) {
                restartNow()
            } else {
                restartSkippedWhileScreenOff = true
                scheduleRestart()
            }
        }.also { main.postDelayed(it, RESTART_MS) }
    }

    /** Restarts with the current settings; a Coded PHY change first tells the core, so the footer stays true. */
    private fun restartNow() {
        val coded = codedPhy()
        if (coded == appliedCodedPhy) {
            stopScan()
            startScan()
            return
        }
        scope.launch {
            withContext(io) { core.await().configureScan(environment(coded)) }
            withContext(Dispatchers.Main.immediate) {
                if (requested) {
                    stopScan()
                    startScan()
                }
            }
        }
    }

    private fun callback(generation: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null && requests.accepts(generation)) deliver(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            if (requests.accepts(generation)) results?.forEach(::deliver)
        }

        override fun onScanFailed(errorCode: Int) {
            if (requests.fail(generation)) defer("scan failed with $errorCode", retryMs = RETRY_MS)
        }
    }

    private fun deliver(result: ScanResult) {
        val snapshot = ClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        val observation = toObservation(result, snapshot) ?: run {
            oversizedDropped++
            return
        }
        devices.put(observation.addr, result.device)
        ingest.offer(observation)
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        ContextCompat.registerReceiver(
            context, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        context.unregisterReceiver(bluetoothReceiver)
        context.unregisterReceiver(screenReceiver)
    }

    private fun cancel(runnable: Runnable?) {
        runnable?.let(main::removeCallbacks)
    }

    companion object {
        private const val TAG = "BleScanner"
        const val RESTART_MS = 60_000L
        const val RETRY_MS = 1_000L

        // Receive extended advertisements as well as legacy ones (API 26+), on every PHY the controller can scan
        // unless the Coded PHY switch is off.
        internal fun settings(coded: Boolean): ScanSettings {
            val builder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setLegacy(false)
                builder.setPhy(if (coded) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
            }
            return builder.build()
        }

        /** v3 rules: RSSI/TX power 127 → null, address type on API 35+, raw ≤ 1650 B (else null: not a row). */
        internal fun toObservation(result: ScanResult, snapshot: ClockSnapshot): ScanObservationInput? {
            val record = result.scanRecord
            val raw = record?.bytes?.copyOf() ?: ByteArray(0)
            if (!rawFitsRow(raw)) return null
            val o = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val v3 = if (o) {
                ScanV3Fields.from(
                    isLegacy = result.isLegacy,
                    primaryPhy = result.primaryPhy,
                    secondaryPhy = result.secondaryPhy,
                    advertisingSid = result.advertisingSid,
                    txPower = result.txPower,
                    periodicAdvertisingInterval = result.periodicAdvertisingInterval,
                    dataStatus = result.dataStatus,
                    addressType = if (Build.VERSION.SDK_INT >= 35) result.device.addressType else null,
                )
            } else {
                ScanV3Fields.ABSENT
            }
            return ScanObservationInput(
                addr = result.device.address,
                rssi = result.rssi.takeUnless { it == 127 },
                scannedAtMs = scanWallClockMillis(result.timestampNanos, snapshot),
                elapsedRealtimeNanos = result.timestampNanos,
                raw = raw,
                localName = record?.deviceName,
                txPower = record?.txPowerLevel?.takeUnless { it == 127 || it == Int.MIN_VALUE },
                isConnectable = if (o) result.isConnectable else null,
                isLegacy = v3.isLegacy,
                primaryPhy = v3.primaryPhy,
                secondaryPhy = v3.secondaryPhy,
                advSid = v3.advSid,
                advTxPower = v3.advTxPower,
                periodicInterval = v3.periodicInterval,
                dataStatus = v3.dataStatus,
                addrType = v3.addrType,
            )
        }
    }
}

/** A bounded map keeping the latest value per key, least recently written first out. Thread-safe. */
class LatestByKey<K, V>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    fun put(key: K, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    operator fun get(key: K): V? = synchronized(entries) { entries[key] }
}
