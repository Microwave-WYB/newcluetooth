package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import com.google.firebase.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Test

/** O-1 item 5: a failed upload's reason, short and with its code, kept before the full diagnostic. */
class UploadReasonTest {
    @Test
    fun reasonsAreShortWithTheirCode() {
        assertEquals("Denied (403)", uploadReason(StorageException.ERROR_NOT_AUTHORIZED, 403, network = false))
        assertEquals("Denied (401)", uploadReason(StorageException.ERROR_UNKNOWN, 401, network = false))
        assertEquals("Denied (${StorageException.ERROR_NOT_AUTHENTICATED})", uploadReason(StorageException.ERROR_NOT_AUTHENTICATED, -1, network = false))
        assertEquals("Timed out", uploadReason(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, -1, network = false))
        assertEquals("Rate limited (429)", uploadReason(StorageException.ERROR_UNKNOWN, 429, network = false))
        assertEquals("Storage quota exceeded", uploadReason(StorageException.ERROR_QUOTA_EXCEEDED, 429, network = false))
        assertEquals("Storage quota exceeded", uploadReason(StorageException.ERROR_QUOTA_EXCEEDED, -1, network = false))
        assertEquals("Server error (503)", uploadReason(StorageException.ERROR_UNKNOWN, 503, network = false))
        assertEquals("Network error", uploadReason(StorageException.ERROR_UNKNOWN, -1, network = true))
        assertEquals("Network error", uploadReason(StorageException.ERROR_UNKNOWN, 0, network = false))
        assertEquals("Bucket not found", uploadReason(StorageException.ERROR_BUCKET_NOT_FOUND, 404, network = false))
        assertEquals("Failed (400)", uploadReason(StorageException.ERROR_UNKNOWN, 400, network = false))
    }

    @Test
    fun theReasonLeadsTheStoredDiagnostic() {
        val stored = withReason("Denied (403)", "StorageException code=-13021 http=403: User does not have permission")
        assertEquals("Denied (403) — StorageException code=-13021 http=403: User does not have permission", stored)
        assertEquals("Denied (403)", stored.substringBefore(REASON_SEPARATOR))
    }

    @Test
    fun anOlderStoredDiagnosticGetsItsReason() {
        val owners = "StorageException code=-13021 http=403: User does not have permission to access this object."
        assertEquals("Denied (403) — $owners", withShownReason(owners))
        val already = withReason("Network error", "StorageException code=-13000 http=-1: x")
        assertEquals(already, withShownReason(already))
        assertEquals("the pending uploads could not be read: x", withShownReason("the pending uploads could not be read: x"))
    }
}
