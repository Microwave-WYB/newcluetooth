package edu.ucsd.sysnet.cluetoothscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingController
import edu.ucsd.sysnet.cluetoothscanner.service.ScanSupervisor
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the Activity needs besides the v3 screens: reporting readiness and foreground state to the
 * scan supervisor, location updates, and the session export/delete/upload calls of the sessions
 * and upload screen. The scan list, recording and device detail belong to `V3ViewModel`.
 */
class ScanViewModel(
    private val locationService: LocationService,
    private val storageService: StorageService,
    private val repository: CluetoothRepository,
    private val recording: RecordingController,
    private val supervisor: ScanSupervisor,
) : ViewModel() {

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
