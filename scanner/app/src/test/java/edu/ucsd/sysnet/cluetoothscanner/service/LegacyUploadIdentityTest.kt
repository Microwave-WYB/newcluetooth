package edu.ucsd.sysnet.cluetoothscanner.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyUploadIdentityTest {
    @Test
    fun remoteIdentityPreservesOriginalProducerFilenameAcrossUpgrade() {
        val pending = "2025-08-21T18-49-02Z_device_0.0.4.jsonl.zst"

        assertEquals("$pending.encrypted", legacyRemoteObjectName(pending))
    }

    @Test
    fun preV2DebugLegacySuffixIsPreserved() {
        val pending = "2026-04-10T19-37-46Z_device_0.0.4-debug.jsonl.zst"

        assertEquals("$pending.encrypted", legacyRemoteObjectName(pending))
    }

    @Test
    fun unsupportedLocalFilesHaveNoLegacyIdentity() {
        assertNull(legacyRemoteObjectName("payload.parquet"))
        assertNull(legacyRemoteObjectName(".jsonl.zst"))
        assertNull(legacyRemoteObjectName("device_0.0.5.jsonl.zst"))
        assertNull(legacyRemoteObjectName("device_0.0.5-debug.jsonl.zst"))
        assertNull(legacyRemoteObjectName("nested/device_0.0.4.jsonl.zst"))
    }
}
