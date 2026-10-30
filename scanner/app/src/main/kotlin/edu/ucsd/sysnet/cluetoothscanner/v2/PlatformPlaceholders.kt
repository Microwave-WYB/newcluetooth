package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeUploads

/**
 * PLACEHOLDER slots for the platform pieces that are not merged yet (integration step 1). This is the only file of
 * :app that names :platform-fake; each slot is replaced by lane B's real class when it lands: the upload worker
 * ([uploads]).
 */
internal class PlatformPlaceholders {
    val uploads: Uploads = FakeUploads(UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null))
}
