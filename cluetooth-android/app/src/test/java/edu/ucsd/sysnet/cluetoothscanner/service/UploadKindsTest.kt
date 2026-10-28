package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.core.EXPECTED_CORE_API_VERSION
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreState
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreUpdate
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPendingUpload
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedUpload
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A core with a scan payload, a second scan payload and a GATT object pending. */
private class MixedCore : CoreGateway {
    val scanA = "0195ca99-5000-7111-8111-111111111111"
    val scanB = "0195ca99-5000-7222-8222-222222222222"
    val gatt = "0195ca99-5000-7333-8333-333333333333"
    private val ciphertext = File.createTempFile("cipher", ".encrypted").apply { writeText("x") }
    val paths = mapOf(
        scanA to "scans/v3/2025/03/25/$scanA.parquet.encrypted",
        scanB to "scans/v3/2025/03/25/$scanB.parquet.encrypted",
        gatt to "gatt/v1/2025/03/25/$gatt.json.encrypted",
    )
    val pending = paths.keys.toMutableSet()
    val events = mutableListOf<String>()
    private val state = GatewayCoreState(0u, 0u, 0u, 0u, 3u, 0u, recentLocationFixCount = 0u, hasLocation = false, latestObservationAtMs = null, latestLocalName = null)

    override fun apiVersion() = EXPECTED_CORE_API_VERSION
    override fun state() = state
    override fun refresh() = GatewayCoreUpdate(state, emptyList())
    override fun updateLocation(fix: CoreLocationInput) = error("unexpected")
    override fun clearLocation() = error("unexpected")
    override fun recordObservations(observations: List<CoreScanInput>) = error("unexpected")
    override fun flushPayload() = false
    override fun pendingUploads() = pending.sorted().map { GatewayPendingUpload(it, "plain", paths.getValue(it), 1u, 1u) }
    override fun prepareUpload(payloadId: String) =
        GatewayPreparedUpload(payloadId, ciphertext.path, paths.getValue(payloadId), 1u, 49u)

    override fun markUploadSucceeded(payloadId: String): GatewayCoreUpdate {
        events += "ok:${payloadId.take(18).takeLast(4)}"
        pending -= payloadId
        return refresh()
    }

    override fun markUploadFailed(payloadId: String, message: String): GatewayCoreUpdate {
        events += "failed:${payloadId.takeLast(3)}:$message"
        return refresh()
    }

    override fun close() = Unit
}

class UploadKindsTest {
    private fun denied() = UploadDeniedException(IllegalStateException("User does not have permission to access this object."))

    @Test
    fun `scan uploads and gatt uploads are separate runs over disjoint objects`() = runTest {
        val core = MixedCore()
        val sent = mutableListOf<String>()
        val uploader = ObjectFileUploader { path, _ -> sent += path }

        val scans = PayloadUploadCoordinator(core, uploader, null, UploadKind.SCANS).uploadPending()
        assertEquals(PayloadUploadSummary(2, 0), scans)
        assertTrue(sent.all { it.startsWith("scans/") })
        assertEquals(setOf(core.gatt), core.pending)

        sent.clear()
        val gatt = PayloadUploadCoordinator(core, uploader, null, UploadKind.GATT).uploadPending()
        assertEquals(PayloadUploadSummary(1, 0), gatt)
        assertEquals(1, sent.size)
        assertTrue(sent.single().startsWith("gatt/"))
        assertTrue(core.pending.isEmpty())
    }

    @Test
    fun `a denied gatt object is recorded and counted as denied without failing anything else`() = runTest {
        val core = MixedCore()
        val uploader = ObjectFileUploader { path, _ -> if (path.startsWith("gatt/")) throw denied() }

        val scans = PayloadUploadCoordinator(core, uploader, null, UploadKind.SCANS).uploadPending()
        val gatt = PayloadUploadCoordinator(core, uploader, null, UploadKind.GATT).uploadPending()

        // Scans were never delayed or failed by the GATT denial.
        assertEquals(PayloadUploadSummary(2, 0, 0), scans)
        assertEquals(PayloadUploadSummary(0, 0, 1), gatt)
        assertEquals(setOf(core.gatt), core.pending)
        assertTrue(core.events.any { it.startsWith("failed:") && it.contains("User does not have permission") })
    }

    @Test
    fun `a denied scan file does not stop the other scan files or count as a retryable failure`() = runTest {
        val core = MixedCore()
        val uploader = ObjectFileUploader { path, _ -> if (path.contains(core.scanA)) throw denied() }

        val scans = PayloadUploadCoordinator(core, uploader, null, UploadKind.SCANS).uploadPending()

        assertEquals(PayloadUploadSummary(succeeded = 1, failed = 0, denied = 1), scans)
        assertEquals(setOf(core.scanA, core.gatt), core.pending)
    }

    @Test
    fun `a transient failure is still a retryable failure and a denial is not`() = runTest {
        val core = MixedCore()
        val uploader = ObjectFileUploader { path, _ ->
            if (path.contains(core.scanA)) error("offline")
            if (path.contains(core.scanB)) throw denied()
        }
        val scans = PayloadUploadCoordinator(core, uploader, null, UploadKind.SCANS).uploadPending()
        assertEquals(PayloadUploadSummary(succeeded = 0, failed = 1, denied = 1), scans)
    }

    @Test
    fun `the diagnostic of a denial is the storage exception it wraps`() {
        assertEquals(
            "User does not have permission to access this object.",
            uploadDiagnostic(denied()),
        )
        assertEquals("boom", uploadDiagnostic(IllegalStateException("boom")))
    }

    @Test
    fun `a resume session of the other kind is not discarded as an orphan`() = runTest {
        val core = MixedCore()
        val store = object : UploadSessionStore {
            val sessions = mutableMapOf<String, UploadSession>()
            override fun get(payloadId: String) = sessions[payloadId]
            override fun put(payloadId: String, session: UploadSession) { sessions[payloadId] = session }
            override fun remove(payloadId: String) { sessions.remove(payloadId) }
            override fun payloadIds() = sessions.keys.toSet()
        }
        val cache = kotlin.io.path.createTempDirectory().toFile()
        val gattCache = File(cache, "${core.gatt}.encrypted").apply { writeText("x") }
        store.put(core.gatt, UploadSession("https://session/gatt", gattCache.path))

        PayloadUploadCoordinator(core, ObjectFileUploader { _, _ -> }, UploadResume(store, cache), UploadKind.SCANS).uploadPending()

        assertTrue(store.sessions.containsKey(core.gatt))
        assertTrue(gattCache.isFile)
    }
}
