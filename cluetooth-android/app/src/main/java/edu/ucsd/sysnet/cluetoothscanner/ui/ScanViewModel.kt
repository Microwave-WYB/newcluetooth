package edu.ucsd.sysnet.cluetoothscanner.ui

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.ble.BleParser
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import edu.ucsd.sysnet.cluetoothscanner.service.BleScanService
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingController
import edu.ucsd.sysnet.cluetoothscanner.service.ScanSupervisor
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingState
import edu.ucsd.sysnet.cluetoothscanner.service.ScanStatus
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import edu.ucsd.sysnet.cluetoothscanner.repository.AppUiState
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayDeleteResult
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayLegacySessionRows
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.service.sealThenUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

data class DisplayItem(
    val mac: String,
    val name: String,
    val rssi: Int?,
    val lastSeen: Instant,
    val record: BleRecord
) {
    val isStale: Boolean
        get() = ChronoUnit.SECONDS.between(lastSeen, Instant.now()) > 30

    val rssiColor: RssiColor
        get() = when {
            rssi == null -> RssiColor.GREY
            rssi > -70 -> RssiColor.GREEN
            rssi > -90 -> RssiColor.YELLOW
            else -> RssiColor.RED
        }
}

enum class RssiColor {
    GREEN, YELLOW, RED, GREY
}

class ScanViewModel(
    private val bleScanService: BleScanService,
    private val locationService: LocationService,
    private val storageService: StorageService,
    private val uploadService: UploadService,
    private val repository: CluetoothRepository,
    private val recording: RecordingController,
    private val supervisor: ScanSupervisor,
) : ViewModel() {
    val coreUiState: StateFlow<AppUiState> = repository.uiState

    private val _scanItems = MutableStateFlow<List<DisplayItem>>(emptyList())
    val scanItems: StateFlow<List<DisplayItem>> = _scanItems.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    val scanStatus: StateFlow<ScanStatus> = bleScanService.scanStatus

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _selectedItem = MutableStateFlow<DisplayItem?>(null)
    val selectedItem: StateFlow<DisplayItem?> = _selectedItem.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val deviceMap = mutableMapOf<String, DisplayItem>()
    private val deviceOrder = mutableListOf<String>()  // Preserve insertion order

    private var locationRetryJob: Job? = null

    // The recording (session, scanner, foreground service) belongs to the process, not to this
    // Activity-scoped ViewModel: it keeps running when the Activity goes away.
    private val scanTransitionCoordinator = recording.coordinator
    val scanTransitionState: StateFlow<ScanTransitionState> = scanTransitionCoordinator.state

    /** Start/pause/resume/stop and the elapsed/row state for the record button. */
    val recordingState: StateFlow<RecordingState> = recording.state

    init {
        observeBleScanResults()
        observeScanStatus()
        observeLocationUpdates()
        // Start periodic upload when app is running
    }

    internal fun startWhenReady(readiness: ScanReadiness) {
        // Live scanning and location follow the foreground (see ScanSupervisor), which also
        // starts location updates; they are not started from here.
        supervisor.foregroundChanged(readiness.permissionsGranted)
        if (readiness.permissionsGranted) retryLocationWhileUnavailable()
        viewModelScope.launch {
            reconcileScanReadiness(readiness, recording.wantsRecording, scanTransitionCoordinator)
        }
    }

    /** The Activity left the foreground: live scanning stops unless a recording is active. */
    internal fun onActivityStopped() {
        supervisor.foregroundChanged(false)
    }

    internal fun onPermissionsPermanentlyRejected() {
        recording.stop()
    }

    /** If location services are off, retry (through the supervisor) with a single loop. */
    private fun retryLocationWhileUnavailable() {
        if (locationService.isLocationEnabled() || locationRetryJob?.isActive == true) return
        locationRetryJob = viewModelScope.launch {
            while (
                !locationService.isLocationEnabled() &&
                !locationService.isLocationAvailable()
            ) {
                delay(5000)
                supervisor.startLocationIfWanted()
            }
        }
    }

    fun retryLocationUpdates() {
        if (supervisor.wantsLocation) locationService.forceRestartLocationUpdates()
    }

    fun refreshCoreState() {
        repository.refresh()
    }

    suspend fun prepareSessionExport(sessionId: String, format: GatewayExportFormat): GatewayPreparedExport =
        repository.prepareSessionExport(sessionId, format)

    suspend fun prepareLegacySessionExport(
        session: GatewayLegacySessionRows,
        format: GatewayExportFormat,
    ): GatewayPreparedExport = repository.prepareLegacySessionExport(session, format)

    suspend fun prepareFullExport(
        format: GatewayExportFormat,
        legacySessions: List<GatewayLegacySessionRows> = emptyList(),
    ): GatewayPreparedExport = repository.prepareFullExport(format, legacySessions)

    suspend fun acknowledgeExport(exportId: String) = repository.acknowledgeExport(exportId)

    suspend fun deleteSession(sessionId: String, destructive: Boolean): GatewayDeleteResult =
        repository.deleteScanSession(sessionId, destructive)

    fun deleteSessionFromUi(sessionId: String, destructive: Boolean) {
        viewModelScope.launch { repository.deleteScanSession(sessionId, destructive) }
    }

    private fun observeBleScanResults() {
        viewModelScope.launch {
            bleScanService.scanFlow.collect { record ->
                processNewRecord(record)
            }
        }
    }

    private fun observeScanStatus() {
        viewModelScope.launch {
            scanTransitionCoordinator.state.collect { transition ->
                // Requested/deferred remains a Stop action in the UI even when the
                // platform scanner is temporarily inactive.
                _isScanning.value = transition.isRequested
            }
        }
    }

    private fun observeLocationUpdates() {
        viewModelScope.launch {
            locationService.currentLocation.collect { location ->
                _currentLocation.value = location
            }
        }
    }

    private fun processNewRecord(record: BleRecord) {
        val packet = BleParser.parseAdvertisementData(record.raw)
        val deviceName = BleParser.extractDeviceName(packet) ?: "Unknown"

        // Update record with current location if available
        val currentLoc = _currentLocation.value
        val updatedRecord = if (currentLoc != null) {
            record.copy(
                lat = currentLoc.latitude,
                lon = currentLoc.longitude,
                accuracy = if (currentLoc.hasAccuracy()) currentLoc.accuracy else null
            )
        } else {
            record
        }

        val displayItem = DisplayItem(
            mac = record.mac,
            name = deviceName,
            rssi = record.rssi,
            lastSeen = Instant.now(),
            record = updatedRecord
        )

        // Only add new devices if we haven't reached the limit
        if (!deviceMap.containsKey(record.mac)) {
            if (deviceMap.size < MAX_DEVICES) {
                // Add new device only if under limit
                deviceOrder.add(record.mac)
                deviceMap[record.mac] = displayItem
            } else {
                // Ignore new devices when at capacity
                return
            }
        } else {
            // Update existing device in place
            deviceMap[record.mac] = displayItem
        }

        // Maintain insertion order, not sorted by lastSeen
        _scanItems.value = deviceOrder.mapNotNull { mac -> deviceMap[mac] }
    }

    fun startScanning() = recording.start()

    fun stopScanning() = recording.stop()

    fun pauseRecording() = recording.pause()

    fun resumeRecording() = recording.resume()

    fun toggleScanning() {
        if (_isScanning.value) stopScanning() else startScanning()
    }

    fun clearScanList() {
        deviceMap.clear()
        deviceOrder.clear()
        _scanItems.value = emptyList()
    }

    fun selectItem(item: DisplayItem) {
        _selectedItem.value = item
    }

    fun clearSelection() {
        _selectedItem.value = null
    }

    fun sortByRssi() {
        deviceOrder.sortByDescending { mac -> deviceMap[mac]?.rssi ?: Int.MIN_VALUE }
        _scanItems.value = deviceOrder.mapNotNull { mac -> deviceMap[mac] }
    }

    fun refreshScanList() {
        _isRefreshing.value = true
        clearScanList()
        if (!_isScanning.value) {
            startScanning()
        }
        // Auto-hide refresh indicator after short delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _isRefreshing.value = false
        }
    }

    fun getLocationString(): String {
        val location = _currentLocation.value
        return if (location != null) {
            String.format("%.6f, %.6f", location.latitude, location.longitude)
        } else {
            if (!locationService.isLocationEnabled()) {
                "Location services disabled"
            } else {
                "Location unavailable"
            }
        }
    }

    fun getLocationAccuracy(): String {
        val location = _currentLocation.value
        return if (location != null && location.hasAccuracy()) {
            "±${location.accuracy.toInt()}m"
        } else {
            "No accuracy"
        }
    }

    /** "Upload now": seal the open payload, then upload immediately. */
    fun uploadNow() {
        viewModelScope.launch {
            sealThenUpload(flush = { repository.flushPayloadAndWait() }, enqueueImmediate = uploadService::forceUpload)
        }
    }

    override fun onCleared() {
        locationRetryJob?.cancel()
        locationRetryJob = null
        // Not stopped here: the recording, scanner and location updates are process-owned and
        // keep running (in the foreground service) when the Activity is destroyed. Only
        // Activity-scoped helpers are released.
        storageService.cleanup()
        super.onCleared()
    }

    companion object {
        private const val MAX_DEVICES = 100
    }
}
