package edu.ucsd.sysnet.cluetoothscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayDeleteResult
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayLegacySessionRows
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport
import edu.ucsd.sysnet.cluetoothscanner.repository.AppUiState
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingController
import edu.ucsd.sysnet.cluetoothscanner.service.ScanSupervisor
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.service.sealThenUpload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the Activity needs besides the v3 screens: reporting readiness and foreground state to the
 * scan supervisor, location updates, and the session export/delete/upload calls of the sessions
 * and upload screen. The scan list, recording and device detail belong to `V3ViewModel`.
 */
class ScanViewModel(
    private val locationService: LocationService,
    private val storageService: StorageService,
    private val uploadService: UploadService,
    private val repository: CluetoothRepository,
    private val recording: RecordingController,
    private val supervisor: ScanSupervisor,
) : ViewModel() {
    val coreUiState: StateFlow<AppUiState> = repository.uiState

    private var locationRetryJob: Job? = null

    internal fun startWhenReady(readiness: ScanReadiness) {
        if (readiness.permissionsGranted) startLocationUpdates()
        viewModelScope.launch {
            // Live scanning follows the foreground; it writes nothing (see ScanSupervisor).
            supervisor.setForeground(readiness.permissionsGranted)
            reconcileScanReadiness(readiness, recording.wantsRecording, recording.coordinator)
        }
    }

    /** The Activity left the foreground: live scanning stops unless a recording is active. */
    internal fun onActivityStopped() {
        viewModelScope.launch { supervisor.setForeground(false) }
    }

    internal fun onPermissionsPermanentlyRejected() {
        recording.stop()
    }

    private fun startLocationUpdates() {
        locationService.startLocationUpdates()

        // If location services are disabled, periodically retry with only one retry loop.
        if (!locationService.isLocationEnabled() && locationRetryJob?.isActive != true) {
            locationRetryJob = viewModelScope.launch {
                while (
                    !locationService.isLocationEnabled() &&
                    !locationService.isLocationAvailable()
                ) {
                    delay(5000)
                    locationService.startLocationUpdates()
                }
            }
        }
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

    companion object
}
