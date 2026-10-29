package edu.ucsd.sysnet.cluetoothscanner.service

import androidx.work.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSchedulingTest {
    @Test
    fun uploadNowSealsOpenPayloadBeforeQueueingImmediateUpload() = runTest {
        val events = mutableListOf<String>()

        sealThenUpload(flush = { events += "seal"; true }, enqueueImmediate = { events += "enqueue" })

        assertEquals(listOf("seal", "enqueue"), events)
    }

    @Test
    fun uploadNowStillQueuesUploadWhenSealingFails() = runTest {
        val events = mutableListOf<String>()

        runCatching {
            sealThenUpload(flush = { error("seal failed") }, enqueueImmediate = { events += "enqueue" })
        }

        assertEquals(listOf("enqueue"), events)
    }

    @Test
    fun wifiOnlyRequiresUnmeteredNetworkOtherwiseAnyConnection() {
        assertEquals(NetworkType.CONNECTED, uploadNetworkType(wifiOnly = false))
        assertEquals(NetworkType.UNMETERED, uploadNetworkType(wifiOnly = true))
    }

    @Test
    fun expeditedUploadOnlyWhereNoForegroundNotificationIsNeeded() {
        assertFalse(expeditedUploadSupported(30))
        assertTrue(expeditedUploadSupported(31))
    }
}
