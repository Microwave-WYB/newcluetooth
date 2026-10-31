package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.combinedChronologicalSessions
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.sessionDeleteIsDestructive
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.sessionDeleteWarning
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.sessionEmptyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SessionListModelTest {
    @Test
    fun combinedModelKeepsActiveFirstThenNewestAndSynthesizesLegacyWithoutFilenameId() {
        val directory = createTempDirectory("session-list-test").toFile()
        val legacy = File(directory, "2025-01-01_device_0.0.4.jsonl.zst").apply {
            writeBytes(com.github.luben.zstd.Zstd.compress("{}\n".toByteArray()))
            setLastModified(150)
        }
        val result = combinedChronologicalSessions(
            sessions = listOf(
                session("older", 100, GatewaySessionStatus.COMPLETED),
                session("active", 50, GatewaySessionStatus.ACTIVE),
                session("newer", 200, GatewaySessionStatus.INTERRUPTED),
            ),
            legacyFiles = listOf(legacy),
            legacyObservationCounts = mapOf(legacy.absolutePath to 1u),
        )

        assertEquals(listOf("active", "newer", result[2].id, "older"), result.map { it.id })
        assertTrue(result[2].id.startsWith("legacy-"))
        assertFalse(result[2].id.contains(legacy.name))
        assertEquals(1uL, result[2].observationCount)
    }

    @Test
    fun emptyAndActiveWaitingMessagesAreDomainLanguageOnly() {
        assertEquals("No scans saved yet", sessionEmptyMessage(emptyList(), 0u))
        assertEquals("Current observations are waiting to be saved", sessionEmptyMessage(emptyList(), 2u))
        assertNull(sessionEmptyMessage(listOf(sessionItem()), 0u))
    }

    @Test
    fun deletionWarningDistinguishesUploadedLocalCopyFromPermanentLoss() {
        val uploaded = sessionItem(GatewaySessionUploadState.UPLOADED)
        val pending = sessionItem(GatewaySessionUploadState.PENDING)
        assertFalse(sessionDeleteIsDestructive(uploaded))
        assertTrue(sessionDeleteWarning(uploaded).contains("cloud data remains"))
        assertTrue(sessionDeleteIsDestructive(pending))
        assertTrue(sessionDeleteWarning(pending).contains("permanently lose"))
    }

    private fun session(
        id: String,
        started: Long,
        status: GatewaySessionStatus,
    ) = GatewayScanSession(
        sessionId = id,
        status = status,
        startedAtMs = started,
        endedAtMs = started + 1,
        lastEventAtMs = started + 1,
        observationCount = 1u,
        uniqueMacCount = 1u,
        exactPayloadCount = 1u,
        retainedLocalBytes = 1u,
        routePoints = emptyList(),
        distanceMeters = 0.0,
        averageAccuracyMeters = null,
        uploadState = GatewaySessionUploadState.UPLOADED,
        diagnostic = null,
        clusters = emptyList(),
    )

    private fun sessionItem(upload: GatewaySessionUploadState = GatewaySessionUploadState.UPLOADED) =
        edu.ucsd.sysnet.cluetoothscanner.ui.screen.ScanSessionListItem(
            id = "id",
            startedAtMs = 1,
            endedAtMs = 2,
            observationCount = 1u,
            uniqueMacCount = 1u,
            retainedBytes = 1u,
            status = GatewaySessionStatus.COMPLETED,
            uploadState = upload,
            nativeSession = null,
        )
}
