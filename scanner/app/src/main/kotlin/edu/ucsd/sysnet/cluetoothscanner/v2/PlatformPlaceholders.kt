package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.ExportShare
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeExportShare
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeUploads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * PLACEHOLDER slots for the platform pieces that are not merged yet (integration step 1). This is the only file of
 * :app that names :platform-fake; each slot is replaced by lane B's real class when it lands: the upload worker
 * ([uploads]), the export hand-off ([exportShare]) and the GATT runner adapter ([gattRunner]). [storageCleanup] waits for
 * lane A's R-10 calls: no StorageFull yet, and every call answers Err.
 */
internal class PlatformPlaceholders {
    val uploads: Uploads = FakeUploads(UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null))
    val exportShare: ExportShare = FakeExportShare()
    val gattRunner: GattRunner = FakeGattRunner({ null })
    val storageCleanup: StorageCleanup = object : StorageCleanup {
        override val storageFull: Flow<Bytes> = emptyFlow()
        override suspend fun reclaimable(): CleanupResult<Reclaim> = NotYet
        override suspend fun deleteUploadedRaw(): CleanupResult<Bytes> = NotYet
        override suspend fun clearLocalDatabase(): CleanupResult<Bytes> = NotYet
    }

    private companion object {
        val NotYet = CleanupResult.Err("R-10 storage calls are not in the core yet")
    }
}
