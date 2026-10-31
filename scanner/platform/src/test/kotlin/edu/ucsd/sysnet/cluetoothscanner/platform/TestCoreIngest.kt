package edu.ucsd.sysnet.cluetoothscanner.platform

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.PendingUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus

/** A [CoreIngest] for tests: every call is a no-op or an error; override what a test needs. */
open class TestCoreIngest : CoreIngest {
    override fun ingestScans(batch: List<ScanObservationInput>) = IngestAck(batch.size, 0, 0)
    override fun ingestLocation(fix: LocationFix) = Unit
    override fun clearLocation() = Unit
    override fun newGattDiscoveryId(): String = error("not in this test")
    override suspend fun gattTarget(key: DeviceKey): GattTarget? = null
    override fun ingestGatt(gattV1Json: String): GattView = error("not in this test")
    override fun startRecording(env: ScanEnvironment): String = error("not in this test")
    override fun pauseRecording() = Unit
    override fun resumeRecording() = Unit
    override fun stopRecording(): String = error("not in this test")
    override fun configureScan(env: ScanEnvironment) = Unit
    override fun recordingState() = CoreRecordingState(RecordingPhase.Idle, null, null, 0, null, 0, 0.0)
    override fun setDisplayFilter(filter: DisplayFilter) = Unit
    override fun uploadStatus() = UploadStatus(0, 0, 0, null)
    override fun pendingUploads() = emptyList<PendingUpload>()
    override fun prepareUpload(id: String): PreparedUpload = error("not in this test")
    override fun markUploadSucceeded(id: String) = Unit
    override fun markUploadFailed(id: String, message: String) = Unit
}
