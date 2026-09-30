package edu.ucsd.sysnet.cluetoothscanner.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import edu.ucsd.sysnet.cluetoothscanner.requiredBluetoothPermissions
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.repository.ObservationAdmission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BleScanService(
    private val context: Context,
    private val locationService: LocationService,
    private val repository: CluetoothRepository,
) {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val scanRequestState = ActiveRequestState<ScanCallback>()
    private var scanRequested = false
    private var pausedByBackpressure = false
    private var scanLifecycleListener: ((ScanLifecycleEvent) -> Unit)? = null

    private val scanResults = Channel<BleRecord>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackFence = CallbackFence(mainHandler::post)
    private var scanRestartRunnable: Runnable? = null
    private var deferredRecoveryRunnable: Runnable? = null
    private val _scanStatus = MutableStateFlow(ScanStatus())
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    deferScanning(
                        ScanStatus(
                            ScanOperatingState.BLUETOOTH_OFF,
                            "Bluetooth is not enabled",
                        ),
                    )
                }
                BluetoothAdapter.STATE_ON -> if (scanRequested && !pausedByBackpressure) {
                    scanLifecycleListener?.invoke(ScanLifecycleEvent.RECOVERY_AVAILABLE)
                }
            }
        }
    }

    val scanFlow: Flow<BleRecord> = scanResults.receiveAsFlow()
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        repository.setBackpressureListener { paused ->
            mainHandler.post {
                pausedByBackpressure = paused
                if (paused) {
                    deferScanning(
                        ScanStatus(
                            ScanOperatingState.PAUSED_BACKPRESSURE,
                            "Durable storage backpressure",
                        ),
                    )
                    Log.w(TAG, "BLE scan paused for durable-storage backpressure")
                } else if (scanRequested) {
                    Log.i(TAG, "Durable storage recovered; requesting coordinated BLE resume")
                    scanLifecycleListener?.invoke(ScanLifecycleEvent.RECOVERY_AVAILABLE)
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions(Build.VERSION.SDK_INT).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PermissionChecker.PERMISSION_GRANTED
        }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter
    }

    internal fun setScanLifecycleListener(listener: ((ScanLifecycleEvent) -> Unit)?) {
        scanLifecycleListener = listener
    }

    @SuppressLint("MissingPermission")
    internal fun startScanning(): ScannerStartOutcome {
        cancelDeferredRecoveryRetry()
        scanRequested = true
        if (pausedByBackpressure) {
            _scanStatus.value = ScanStatus(
                ScanOperatingState.PAUSED_BACKPRESSURE,
                "Durable storage backpressure",
            )
            Log.w(TAG, "BLE scan start deferred while durable ingress is paused")
            return ScannerStartOutcome.DEFERRED_INTENT_RETAINED
        }
        if (!hasBluetoothPermissions()) {
            markNotScanning("Bluetooth permission is not granted", notifyRejection = false)
            return ScannerStartOutcome.REJECTED
        }

        val adapter = try {
            bluetoothAdapter()
        } catch (error: SecurityException) {
            markNotScanning("Bluetooth adapter access denied", error, notifyRejection = false)
            return ScannerStartOutcome.REJECTED
        } ?: run {
            deferScanning(
                ScanStatus(
                    ScanOperatingState.SCANNER_UNAVAILABLE,
                    "Bluetooth adapter is unavailable",
                ),
            )
            return ScannerStartOutcome.DEFERRED_INTENT_RETAINED
        }

        val enabled = try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            markNotScanning("Bluetooth state access denied", error, notifyRejection = false)
            return ScannerStartOutcome.REJECTED
        }
        if (!enabled) {
            deferScanning(
                ScanStatus(
                    ScanOperatingState.BLUETOOTH_OFF,
                    "Bluetooth is not enabled",
                ),
            )
            return ScannerStartOutcome.DEFERRED_INTENT_RETAINED
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (error: SecurityException) {
            markNotScanning("Bluetooth scanner access denied", error, notifyRejection = false)
            return ScannerStartOutcome.REJECTED
        } ?: run {
            deferScanning(
                ScanStatus(
                    ScanOperatingState.SCANNER_UNAVAILABLE,
                    "Bluetooth scanner is unavailable",
                ),
            )
            return ScannerStartOutcome.DEFERRED_INTENT_RETAINED
        }
        bluetoothLeScanner = scanner

        if (isScanning) {
            Log.d(TAG, "BLE scan already active")
            return ScannerStartOutcome.STARTED
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val activeRequest = scanRequestState.begin(::scanCallbackForGeneration)
        return try {
            scanner.startScan(null, settings, activeRequest.callback)
            isScanning = true
            _scanStatus.value = ScanStatus(ScanOperatingState.SCANNING)
            Log.i(TAG, "BLE scan started (generation ${activeRequest.generation})")
            schedulePeriodicScanRestart()
            ScannerStartOutcome.STARTED
        } catch (error: SecurityException) {
            scanRequestState.fail(activeRequest.generation)
            markNotScanning("BLE scan permission denied", error, notifyRejection = false)
            ScannerStartOutcome.REJECTED
        } catch (error: Exception) {
            scanRequestState.fail(activeRequest.generation)
            markNotScanning("Failed to start BLE scan", error, notifyRejection = false)
            ScannerStartOutcome.REJECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        stopScanningInternal(preserveRequest = false)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal(
        preserveRequest: Boolean,
        retainedStatus: ScanStatus = ScanStatus(
            ScanOperatingState.PAUSED_BACKPRESSURE,
            "Durable storage backpressure",
        ),
    ) {
        if (!preserveRequest) scanRequested = false
        val activeCallback = scanRequestState.stop()
        val wasScanning = isScanning
        isScanning = false
        _scanStatus.value = if (preserveRequest) retainedStatus else ScanStatus(ScanOperatingState.STOPPED)
        cancelPeriodicScanRestart()
        if (!preserveRequest) cancelDeferredRecoveryRetry()
        if (!wasScanning || activeCallback == null) {
            Log.d(TAG, "BLE scan already stopped")
            return
        }
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Bluetooth permission was revoked before scan stop")
            return
        }
        try {
            bluetoothLeScanner?.stopScan(activeCallback)
            Log.i(TAG, "BLE scan stopped")
        } catch (error: SecurityException) {
            Log.w(TAG, "BLE scan stop denied", error)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop BLE scan", error)
        }
    }

    @SuppressLint("MissingPermission")
    internal suspend fun stopScanningAndWait() {
        // Always post a main-queue fence, even when a failure/backpressure path has
        // already stopped the scanner. Admitted callbacks ahead of this fence must
        // drain before lifecycle code requests the final durable flush.
        stopScanning()
        callbackFence.await()
        Log.i(TAG, "All BLE scan callbacks processed")
    }

    fun isScanning(): Boolean = isScanning

    private fun scanCallbackForGeneration(generation: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (!scanRequestState.accepts(generation)) return
            result?.let { scanResult ->
                processScannedDevice(scanResult, clockSnapshot(), generation)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            if (!scanRequestState.accepts(generation)) return
            val snapshot = clockSnapshot()
            results?.forEach { scanResult ->
                processScannedDevice(scanResult, snapshot, generation)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanRequestState.fail(generation)) return
            markNotScanning(
                "BLE scan failed with error code: $errorCode",
                state = ScanOperatingState.FAILED,
            )
        }
    }

    private fun clockSnapshot() = ClockSnapshot(
        wallClockMillis = System.currentTimeMillis(),
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
    )

    private fun processScannedDevice(
        scanResult: ScanResult,
        snapshot: ClockSnapshot,
        generation: Long,
    ) {
        if (!scanRequestState.accepts(generation)) return
        if (!hasBluetoothPermissions()) {
            markNotScanning("Bluetooth permission was revoked during scanning")
            return
        }
        try {
            val device = scanResult.device
            val mac = device.address
            val rssi = scanResult.rssi
            val scannedAtMs = scanWallClockMillis(scanResult.timestampNanos, snapshot)
            val timestamp = Instant.ofEpochMilli(scannedAtMs).atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT)

            val location = locationService.getLastKnownLocation()
            val scanRecord = scanResult.scanRecord
            val advertisementData = scanRecord?.bytes?.copyOf() ?: byteArrayOf()

            val bleRecord = BleRecord(
                mac = mac,
                rssi = rssi,
                timestamp = timestamp,
                lat = location?.latitude,
                lon = location?.longitude,
                accuracy = location?.accuracy,
                raw = advertisementData
            )

            if (!scanRequestState.accepts(generation)) return
            // Preserve the current UI record stream; 0.0.5 persistence is owned by Rust.
            scanResults.trySend(bleRecord)
            val admission = repository.recordObservation(
                CoreScanInput(
                    addr = mac,
                    rssi = rssi.takeUnless { it == 127 },
                    scannedAtMs = scannedAtMs,
                    elapsedRealtimeNanos = scanResult.timestampNanos,
                    raw = advertisementData.copyOf(),
                    localName = scanRecord?.deviceName,
                    txPower = scanRecord?.txPowerLevel?.takeUnless { it == 127 || it == Int.MIN_VALUE },
                    isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        scanResult.isConnectable
                    } else {
                        null
                    },
                ),
            )
            when (admission) {
                ObservationAdmission.ACCEPTED -> Unit
                ObservationAdmission.PAUSE_REQUIRED -> {
                    pausedByBackpressure = true
                    deferScanning(
                        ScanStatus(
                            ScanOperatingState.PAUSED_BACKPRESSURE,
                            "Durable storage backpressure",
                        ),
                    )
                }
                ObservationAdmission.REJECTED_DEGRADED -> {
                    pausedByBackpressure = true
                    deferScanning(
                        ScanStatus(
                            ScanOperatingState.PAUSED_BACKPRESSURE,
                            "Durable storage is degraded",
                        ),
                    )
                    Log.e(TAG, "Observation rejected because durable ingress is degraded")
                }
            }

        } catch (error: SecurityException) {
            markNotScanning("Bluetooth device access denied", error)
        } catch (error: Exception) {
            Log.e(TAG, "Error processing scanned device", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun schedulePeriodicScanRestart() {
        cancelPeriodicScanRestart()

        val restart = Runnable {
            if (!isScanning) return@Runnable
            if (!hasBluetoothPermissions()) {
                markNotScanning("Bluetooth permission was revoked before scan restart")
                return@Runnable
            }
            val scanner = bluetoothLeScanner
            if (scanner == null) {
                deferScanning(
                    ScanStatus(
                        ScanOperatingState.SCANNER_UNAVAILABLE,
                        "Bluetooth scanner became unavailable",
                    ),
                )
                return@Runnable
            }

            Log.i(TAG, "Restarting BLE scan to avoid Android timeout")
            val previousCallback = scanRequestState.stop()
            if (previousCallback == null) {
                markNotScanning("BLE scan restart lost its active callback")
                return@Runnable
            }
            val nextRequest = scanRequestState.begin(::scanCallbackForGeneration)
            try {
                scanner.stopScan(previousCallback)
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build()
                scanner.startScan(null, settings, nextRequest.callback)
                Log.i(TAG, "BLE scan restarted (generation ${nextRequest.generation})")
                schedulePeriodicScanRestart()
            } catch (error: SecurityException) {
                markNotScanning("BLE scan restart denied", error)
            } catch (error: Exception) {
                deferScanning(
                    ScanStatus(
                        ScanOperatingState.SCANNER_UNAVAILABLE,
                        "Failed to restart BLE scan: ${error.message}",
                    ),
                )
            }
        }
        scanRestartRunnable = restart
        mainHandler.postDelayed(restart, 60 * 1000L)
    }

    private fun cancelPeriodicScanRestart() {
        scanRestartRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            scanRestartRunnable = null
        }
    }

    private fun scheduleDeferredRecoveryRetry() {
        cancelDeferredRecoveryRetry()
        val retry = Runnable {
            deferredRecoveryRunnable = null
            if (scanRequested && !pausedByBackpressure) {
                scanLifecycleListener?.invoke(ScanLifecycleEvent.RECOVERY_AVAILABLE)
            }
        }
        deferredRecoveryRunnable = retry
        mainHandler.postDelayed(retry, DEFERRED_RECOVERY_RETRY_MS)
    }

    private fun cancelDeferredRecoveryRetry() {
        deferredRecoveryRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            deferredRecoveryRunnable = null
        }
    }

    private fun deferScanning(status: ScanStatus) {
        val requestRetained = scanRequested
        stopScanningInternal(preserveRequest = true, retainedStatus = status)
        if (requestRetained) {
            scanLifecycleListener?.invoke(ScanLifecycleEvent.DEFERRED_INTENT_RETAINED)
            if (status.state == ScanOperatingState.SCANNER_UNAVAILABLE) {
                scheduleDeferredRecoveryRetry()
            }
        }
    }

    private fun markNotScanning(
        message: String,
        error: Throwable? = null,
        state: ScanOperatingState = ScanOperatingState.FAILED,
        notifyRejection: Boolean = true,
    ) {
        val requestWasActive = scanRequested
        scanRequested = false
        scanRequestState.stop()
        isScanning = false
        _scanStatus.value = ScanStatus(state, message)
        cancelPeriodicScanRestart()
        cancelDeferredRecoveryRetry()
        if (notifyRejection && requestWasActive) {
            scanLifecycleListener?.invoke(ScanLifecycleEvent.REJECTED)
        }
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, error)
        }
    }

    fun cleanup() {
        scanLifecycleListener = null
        repository.setBackpressureListener(null)
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Cleanup may be called more than once by lifecycle teardown.
        }
        scanResults.close()
    }

    companion object {
        private const val TAG = "BleScanService"
        private const val DEFERRED_RECOVERY_RETRY_MS = 1_000L
    }
}
