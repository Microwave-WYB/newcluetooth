package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedRecordingNoticeTest {
    private fun session(id: String, status: GatewaySessionStatus, rows: ULong, started: Long = 1) = GatewayScanSession(
        sessionId = id, status = status, startedAtMs = started, endedAtMs = null, lastEventAtMs = started,
        observationCount = rows, uniqueMacCount = 0u, exactPayloadCount = 0u, retainedLocalBytes = 0u,
        routePoints = emptyList(), distanceMeters = 0.0, averageAccuracyMeters = null,
        uploadState = GatewaySessionUploadState.PENDING, diagnostic = null, clusters = emptyList(),
    )

    @Test
    fun `only interrupted sessions that saved rows and were not reported are listed, oldest first`() {
        val sessions = listOf(
            session("done", GatewaySessionStatus.COMPLETED, 500u, 1),
            session("active", GatewaySessionStatus.ACTIVE, 10u, 2),
            session("empty", GatewaySessionStatus.INTERRUPTED, 0u, 3),
            session("old", GatewaySessionStatus.INTERRUPTED, 40u, 4),
            session("seen", GatewaySessionStatus.INTERRUPTED, 90u, 5),
            session("new", GatewaySessionStatus.INTERRUPTED, 1_234u, 6),
            session("legacy", GatewaySessionStatus.LEGACY, 7u, 7),
        )
        val unseen = unseenInterruptedRecordings(sessions, acknowledged = setOf("seen"))
        assertEquals(listOf("old", "new"), unseen.map { it.sessionId })
        assertEquals(1_234uL, unseen.last().observationCount)
    }

    @Test
    fun `nothing is reported twice`() {
        val sessions = listOf(session("a", GatewaySessionStatus.INTERRUPTED, 5u))
        assertEquals(emptyList<GatewayScanSession>(), unseenInterruptedRecordings(sessions, setOf("a")))
    }
}
