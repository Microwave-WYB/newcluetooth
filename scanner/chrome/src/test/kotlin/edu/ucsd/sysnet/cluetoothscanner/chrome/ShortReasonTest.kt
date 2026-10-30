package edu.ucsd.sysnet.cluetoothscanner.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

/** O-1 item 5: the Pending uploads row shows the latest failure's short reason. */
class ShortReasonTest {
    @Test
    fun theReasonBeforeTheDiagnostic() {
        assertEquals("Denied (403)", shortReason("Denied (403) — StorageException code=-13021 http=403: no permission"))
        assertEquals("Network error", shortReason("Network error — java.net.UnknownHostException: firebasestorage"))
        val old = "StorageException code=-13000 http=-1: An unknown error occurred, please check the HTTP result code"
        assertEquals(40, shortReason(old).length)
        assertEquals('…', shortReason(old).last())
    }

    @Test
    fun aDenialIsNotRetrying() {
        assertEquals("1 upload failed · Denied (403)", failedUploads(1, "Denied (403) — StorageException code=-13021 http=403: x"))
        assertEquals("2 uploads failed; retrying · Network error", failedUploads(2, "Network error — java.io.IOException"))
        assertEquals("2 uploads failed; retrying", failedUploads(2, null))
    }
}
