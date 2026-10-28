package edu.ucsd.sysnet.cluetoothscanner.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
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
import androidx.core.content.PermissionChecker
import edu.ucsd.sysnet.cluetoothscanner.BuildConfig
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTrigger
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTriggerCache
import edu.ucsd.sysnet.cluetoothscanner.ble.MAX_ROW_RAW_BYTES
import edu.ucsd.sysnet.cluetoothscanner.ble.ScanV3Fields
import edu.ucsd.sysnet.cluetoothscanner.ble.rawFitsRow
import edu.ucsd.sysnet.cluetoothscanner.ble.buildScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.requiredBluetoothPermissions
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.repository.ObservationAdmission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

class BleScanService(
    private val context: Context,
    private val locationService: LocationService,
    private val repository: CluetoothRepository,
    private val triggerCache: AdvertisementTriggerCache = AdvertisementTriggerCache(),
) {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val scanRequestState = ActiveRequestState<ScanCallback>()
    private var scanRequested = false
    private var pausedByBackpressure = false
    private var scanLifecycleListener: ((ScanLifecycleEvent) -> Unit)? = null

    // Every advertisement while the scanner runs, for the UI and the matcher's in-memory stores.
    // A slow collector loses the oldest items; the scanner never waits for it.
    private val _liveScans = MutableSharedFlow<LiveScan>(
        replay = 0,
        extraBufferCapacity = LIVE_SCAN_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Rows are written to the core only while a recording is active. Live scanning (app in the
     * foreground) feeds [liveScans] and nothing else: no session, no Parquet, no upload.
     */
    @Volatile
    private var rowWriting = false

    /** Advertisements kept out of the durable path because they exceed the row limit. */
    @Volatile
    private var oversizedRowsDropped = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackFence = CallbackFence(mainHandler::post)
    private var scanRestartRunnable: Runnable? = null
    private var scanStartedAtElapsedMs = 0L
    private var restartSkippedWhileScreenOff = false
    private var appliedCodedPhy = true
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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            if (isScanning && ScanRestartPolicy.screenOnRestartNow(
                    skippedWhileOff = restartSkippedWhileScreenOff,
                    appliedCodedPhy = appliedCodedPhy,
                    wantedCodedPhy = AppSettings.isCodedPhyEnabled(context),
                )
            ) {
                Log.i(TAG, "Screen on: applying the scan restart that was held back")
                restartScanNow()
            }
        }
    }

    /** The live ScanResult stream. Collect it from the UI or a store; it never blocks scanning. */
    val liveScans: SharedFlow<LiveScan> = _liveScans.asSharedFlow()

    /** Where the debug source set's fake scanner (`DebugFakeScan`) pushes synthetic advertisements; unused in release. */
    internal val liveSink: kotlinx.coroutines.flow.MutableSharedFlow<LiveScan> get() = _liveScans

    /** The same stream as UI records (kept for the nearby list). */
    val scanFlow: Flow<BleRecord> = liveScans.map { it.toBleRecord() }
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
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

        val codedPhyEnabled = AppSettings.isCodedPhyEnabled(context)
        appliedCodedPhy = codedPhyEnabled
        restartSkippedWhileScreenOff = false
        val settings = buildScanSettings(codedPhyEnabled)
        // The footer of the next payload must describe this scan. The core's command
        // queue is ordered, so this precedes every observation of the new scan.
        repository.configureScan(scanEnvironment(adapter, codedPhyEnabled))

        val activeRequest = scanRequestState.begin(::scanCallbackForGeneration)
        return try {
            scanner.startScan(scanFilters(), settings, activeRequest.callback)
            isScanning = true
            scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
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

    /**
     * Starts or stops writing rows. Enabling declares the footer environment first (ordered ahead
     * of the first row); disabling is followed by [fenceCallbacks] so that no row of this
     * recording is queued after the caller continues.
     */
    internal fun setRowWriting(enabled: Boolean) {
        if (enabled && !rowWriting) {
            bluetoothAdapter()?.let { adapter ->
                repository.configureScan(scanEnvironment(adapter, AppSettings.isCodedPhyEnabled(context)))
            }
        }
        rowWriting = enabled
    }

    /** Returns once every scan callback queued so far has been processed. */
    internal suspend fun fenceCallbacks() {
        callbackFence.await()
    }

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

            val location = locationService.getLastKnownLocation()
            val scanRecord = scanResult.scanRecord
            val advertisementData = scanRecord?.bytes?.copyOf() ?: byteArrayOf()
            val v3 = scanV3Fields(scanResult, device)

            if (!scanRequestState.accepts(generation)) return
            triggerCache.put(
                AdvertisementTrigger(
                    addr = mac,
                    device = device,
                    addrType = v3.addrType,
                    name = scanRecord?.deviceName,
                    raw = advertisementData,
                    rssi = rssi.takeUnless { it == 127 },
                    scannedAtMs = scannedAtMs,
                    fields = v3,
                ),
            )
            _liveScans.tryEmit(
                LiveScan(
                    addr = mac,
                    rssi = rssi.takeUnless { it == 127 },
                    scannedAtMs = scannedAtMs,
                    elapsedRealtimeNanos = scanResult.timestampNanos,
                    raw = advertisementData,
                    localName = scanRecord?.deviceName,
                    txPower = scanRecord?.txPowerLevel?.takeUnless { it == 127 || it == Int.MIN_VALUE },
                    isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) scanResult.isConnectable else null,
                    v3 = v3,
                    lat = location?.latitude,
                    lon = location?.longitude,
                    accuracyMeters = location?.accuracy,
                ),
            )
            // Durable rows exist only for a recording; live scanning stops here.
            val admission = if (!rowWriting) {
                ObservationAdmission.ACCEPTED
            } else if (!rawFitsRow(advertisementData)) {
                oversizedRowsDropped++
                if (oversizedRowsDropped == 1L || oversizedRowsDropped % 100 == 0L) {
                    Log.w(TAG, "Dropped $oversizedRowsDropped advertisements longer than $MAX_ROW_RAW_BYTES bytes")
                }
                ObservationAdmission.ACCEPTED
            } else repository.recordObservation(
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
                    isLegacy = v3.isLegacy,
                    primaryPhy = v3.primaryPhy,
                    secondaryPhy = v3.secondaryPhy,
                    advSid = v3.advSid,
                    advTxPower = v3.advTxPower,
                    periodicInterval = v3.periodicInterval,
                    dataStatus = v3.dataStatus,
                    addrType = v3.addrType,
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
            // A scan started while the screen is off delivers nothing on some phones (observed
            // on a Galaxy Note10) while one already running keeps going, so the restart waits for
            // the screen (the receiver restarts at once when it turns on), within a bound.
            val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val runningMs = SystemClock.elapsedRealtime() - scanStartedAtElapsedMs
            if (!ScanRestartPolicy.periodicRestartNow(screenOn, runningMs)) {
                restartSkippedWhileScreenOff = true
                schedulePeriodicScanRestart()
                return@Runnable
            }
            restartScanNow()
        }
        scanRestartRunnable = restart
        mainHandler.postDelayed(restart, 60 * 1000L)
    }

    /** Stops and starts the scan again with the current settings and footer environment. */
    @SuppressLint("MissingPermission")
    private fun restartScanNow() {
        if (!isScanning) return
        if (!hasBluetoothPermissions()) {
            markNotScanning("Bluetooth permission was revoked before scan restart")
            return
        }
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            deferScanning(
                ScanStatus(
                    ScanOperatingState.SCANNER_UNAVAILABLE,
                    "Bluetooth scanner became unavailable",
                ),
            )
            return
        }

        Log.i(TAG, "Restarting BLE scan to avoid Android timeout")
        val previousCallback = scanRequestState.stop()
        if (previousCallback == null) {
            markNotScanning("BLE scan restart lost its active callback")
            return
        }
        val nextRequest = scanRequestState.begin(::scanCallbackForGeneration)
        try {
            scanner.stopScan(previousCallback)
            // Re-read the switch so a change takes effect now, and keep the payload footer in
            // step with the settings actually in force.
            val codedPhyEnabled = AppSettings.isCodedPhyEnabled(context)
            val settings = buildScanSettings(codedPhyEnabled)
            bluetoothAdapter()?.let { adapter ->
                repository.configureScan(scanEnvironment(adapter, codedPhyEnabled))
            }
            scanner.startScan(scanFilters(), settings, nextRequest.callback)
            appliedCodedPhy = codedPhyEnabled
            restartSkippedWhileScreenOff = false
            scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
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
        for (receiver in listOf(bluetoothStateReceiver, screenReceiver)) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Cleanup may be called more than once by lifecycle teardown.
            }
        }
        // liveScans is a SharedFlow: it has no terminal state to close.
    }

    companion object {
        private const val TAG = "BleScanService"
        private const val DEFERRED_RECOVERY_RETRY_MS = 1_000L
        private const val LIVE_SCAN_BUFFER = 512
    }

    // Android pauses *unfiltered* scans while the screen is off (8.1+). One empty filter is
    // sent so that platforms that count any filter list as a filtered scan keep delivering with
    // the screen off. It only helps up to API 14: AOSP 15 treats an all-empty filter as
    // unfiltered, so on newer systems collection with the screen off relies on the recording
    // foreground service (RecordingService) instead.
    private fun scanFilters(): List<ScanFilter> = listOf(ScanFilter.Builder().build())

    // Receive extended advertisements as well as legacy ones where supported (API 26+),
    // on every PHY the controller can scan unless the Coded PHY switch is off.
    private fun buildScanSettings(codedPhyEnabled: Boolean): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setLegacy(false)
            builder.setPhy(
                if (codedPhyEnabled) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M,
            )
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun scanEnvironment(adapter: BluetoothAdapter, codedPhyEnabled: Boolean) =
        buildScanEnvironment(
            deviceModel = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            extendedAdvertisingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                adapter.isLeExtendedAdvertisingSupported,
            codedPhySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                adapter.isLeCodedPhySupported,
            maxAdvertisingDataLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                adapter.leMaximumAdvertisingDataLength
            } else {
                31
            },
            codedPhyEnabled = codedPhyEnabled,
        )

    private fun scanV3Fields(scanResult: ScanResult, device: BluetoothDevice): ScanV3Fields {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ScanV3Fields.ABSENT
        return ScanV3Fields.from(
            isLegacy = scanResult.isLegacy,
            primaryPhy = scanResult.primaryPhy,
            secondaryPhy = scanResult.secondaryPhy,
            advertisingSid = scanResult.advertisingSid,
            txPower = scanResult.txPower,
            periodicAdvertisingInterval = scanResult.periodicAdvertisingInterval,
            dataStatus = scanResult.dataStatus,
            addressType = if (Build.VERSION.SDK_INT >= 35) device.addressType else null,
        )
    }
}
