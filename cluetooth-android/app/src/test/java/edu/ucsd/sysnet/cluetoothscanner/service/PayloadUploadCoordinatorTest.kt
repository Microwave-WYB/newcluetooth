package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreState
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreUpdate
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPendingUpload
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedUpload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PayloadUploadCoordinatorTest {
    @Test
    fun successUsesExactRustIdentityAndAcknowledgesOnlyAfterTransfer() = runTest {
        val file = File.createTempFile("ciphertext", ".encrypted")
        val core = UploadFakeCore(file)
        val transfers = mutableListOf<Pair<String, File>>()
        val coordinator = PayloadUploadCoordinator(core) { path, localFile ->
            transfers += path to localFile
        }

        val summary = coordinator.uploadPending()

        assertEquals(PayloadUploadSummary(1, 0), summary)
        assertEquals(listOf(core.objectPath to file), transfers)
        assertEquals(listOf("prepare", "success"), core.outcomes)
        assertTrue(core.pendingUploads().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationIsRethrownWithoutFailureAcknowledgement() = runTest {
        val file = File.createTempFile("ciphertext", ".encrypted")
        val core = UploadFakeCore(file)
        val coordinator = PayloadUploadCoordinator(core) { _, _ -> awaitCancellation() }

        val upload = launch { coordinator.uploadPending() }
        runCurrent()
        upload.cancel()
        upload.join()

        assertTrue(upload.isCancelled)
        assertEquals(listOf("prepare"), core.outcomes)
        assertEquals(core.payloadId, core.pendingUploads().single().payloadId)
    }

    @Test
    fun transferFailureKeepsRetryIdentityAndReportsFailureToRust() = runTest {
        val file = File.createTempFile("ciphertext", ".encrypted")
        val core = UploadFakeCore(file)
        val coordinator = PayloadUploadCoordinator(core) { _, _ -> error("offline") }

        val first = coordinator.uploadPending()
        val second = coordinator.uploadPending()

        assertEquals(PayloadUploadSummary(0, 1), first)
        assertEquals(PayloadUploadSummary(0, 1), second)
        assertEquals(listOf("prepare", "failed:offline", "prepare", "failed:offline"), core.outcomes)
        assertEquals(core.objectPath, core.pendingUploads().single().objectPath)
    }
}

private class UploadFakeCore(private val ciphertext: File) : CoreGateway {
    val payloadId = "0195c920-7c00-7abc-8def-0123456789ab"
    val objectPath = "scans/v2/2025/03/24/$payloadId.parquet.encrypted"
    val outcomes = mutableListOf<String>()
    private var pending = true
    private val state = GatewayCoreState(
        totalObservations = 0u,
        observationsWithLocation = 0u,
        activePayloadRows = 0u,
        activePayloadEstimatedBytes = 0u,
        pendingUploadCount = 1u,
        invalidPendingPayloadCount = 0u,
        recentLocationFixCount = 0u,
        hasLocation = false,
        latestObservationAtMs = null,
        latestLocalName = null,
    )

    override fun apiVersion() = 6u
    override fun state() = state
    override fun refresh() = update()
    override fun updateLocation(fix: CoreLocationInput) = error("unexpected")
    override fun clearLocation() = error("unexpected")
    override fun recordObservations(observations: List<CoreScanInput>) = error("unexpected")
    override fun flushPayload() = false
    override fun pendingUploads() = if (pending) {
        listOf(GatewayPendingUpload(payloadId, "plain", objectPath, 1u, 4u))
    } else {
        emptyList()
    }

    override fun prepareUpload(payloadId: String): GatewayPreparedUpload {
        require(payloadId == this.payloadId)
        outcomes += "prepare"
        return GatewayPreparedUpload(payloadId, ciphertext.path, objectPath, 4u, 52u)
    }

    override fun markUploadSucceeded(payloadId: String): GatewayCoreUpdate {
        require(payloadId == this.payloadId)
        outcomes += "success"
        pending = false
        return update()
    }

    override fun markUploadFailed(payloadId: String, message: String): GatewayCoreUpdate {
        require(payloadId == this.payloadId)
        outcomes += "failed:$message"
        return update()
    }

    override fun close() = Unit
    private fun update() = GatewayCoreUpdate(state, emptyList())
}
