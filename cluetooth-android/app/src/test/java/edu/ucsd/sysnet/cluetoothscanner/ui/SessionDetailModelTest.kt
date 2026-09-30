package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.core.GatewayAdvertisementCluster
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.ScanSessionListItem
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.clusterDisplayRows
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.formatBytes
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.sessionSummaryRows
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailModelTest {
    @Test
    fun nativeSummaryHasStableUserFacingOrderAndFormattedValues() {
        val rows = sessionSummaryRows(sessionItem(), nowMs = 5_401_000L)

        assertEquals(
            listOf(
                "Status",
                "Upload status",
                "Duration",
                "Start",
                "End",
                "Observations",
                "Unique devices",
                "Advertisement variants",
                "Advertisement structures",
                "Route distance",
                "Average accuracy",
                "Stored on device",
            ),
            rows.map { it.label },
        )
        assertEquals("Saved", rows.single { it.label == "Status" }.value)
        assertEquals("Pending", rows.single { it.label == "Upload status" }.value)
        assertEquals("1 h 30 min", rows.single { it.label == "Duration" }.value)
        assertEquals("42", rows.single { it.label == "Observations" }.value)
        assertEquals("5", rows.single { it.label == "Unique devices" }.value)
        assertEquals("7", rows.single { it.label == "Advertisement variants" }.value)
        assertEquals("1", rows.single { it.label == "Advertisement structures" }.value)
        assertEquals("124 m", rows.single { it.label == "Route distance" }.value)
        assertEquals("7.3 m", rows.single { it.label == "Average accuracy" }.value)
        assertEquals("2.0 KB", rows.single { it.label == "Stored on device" }.value)
    }

    @Test
    fun activeSessionUsesCurrentWallTimeForDurationAndInProgressEnd() {
        val item = sessionItem(status = GatewaySessionStatus.ACTIVE, startedAtMs = 1_000L, endedAtMs = null)

        val rows = sessionSummaryRows(item, nowMs = 5_401_000L)

        assertEquals("Scanning", rows.single { it.label == "Status" }.value)
        assertEquals("1 h 30 min", rows.single { it.label == "Duration" }.value)
        assertEquals("In progress", rows.single { it.label == "End" }.value)
    }

    @Test
    fun activeDurationAdvancesAsNowMsAdvances() {
        val item = sessionItem(status = GatewaySessionStatus.ACTIVE, startedAtMs = 1_000L, endedAtMs = null)

        assertEquals("30 min", sessionSummaryRows(item, nowMs = 1_801_000L).single { it.label == "Duration" }.value)
        assertEquals("1 h 30 min", sessionSummaryRows(item, nowMs = 5_401_000L).single { it.label == "Duration" }.value)
    }

    @Test
    fun completedAndInterruptedUseRecordedEndTime() {
        val completed = sessionItem(status = GatewaySessionStatus.COMPLETED, startedAtMs = 1_000L, endedAtMs = 5_401_000L)
        val interrupted = sessionItem(status = GatewaySessionStatus.INTERRUPTED, startedAtMs = 1_000L, endedAtMs = 3_601_000L)

        assertEquals("1 h 30 min", sessionSummaryRows(completed, nowMs = 99_999_000L).single { it.label == "Duration" }.value)
        assertEquals("Interrupted", sessionSummaryRows(interrupted, nowMs = 99_999_000L).single { it.label == "Status" }.value)
        assertEquals("1 h 0 min", sessionSummaryRows(interrupted, nowMs = 99_999_000L).single { it.label == "Duration" }.value)
    }

    @Test
    fun startAndEndRowsAreReadableDates() {
        val rows = sessionSummaryRows(sessionItem(), nowMs = 5_401_000L)

        assertTrue(rows.single { it.label == "Start" }.value.isNotBlank())
        assertTrue(rows.single { it.label == "End" }.value.isNotBlank())
        assertFalse(rows.single { it.label == "End" }.value.contains("In progress"))
    }

    @Test
    fun everyUploadStateHasUserFacingStatusText() {
        assertEquals("Pending upload", statusText(sessionItem(uploadState = GatewaySessionUploadState.PENDING)))
        assertEquals("Uploaded", statusText(sessionItem(uploadState = GatewaySessionUploadState.UPLOADED)))
        assertEquals("Upload failed", statusText(sessionItem(uploadState = GatewaySessionUploadState.FAILED)))
        assertEquals("Saved", statusText(sessionItem(uploadState = GatewaySessionUploadState.EMPTY)))
    }

    @Test
    fun everyUploadStateHasReadableUploadStatusRowValue() {
        listOf(
            GatewaySessionUploadState.PENDING to "Pending",
            GatewaySessionUploadState.UPLOADED to "Uploaded",
            GatewaySessionUploadState.FAILED to "Failed",
            GatewaySessionUploadState.EMPTY to "Nothing to upload",
        ).forEach { (state, expected) ->
            val rows = sessionSummaryRows(sessionItem(uploadState = state), nowMs = 5_401_000L)
            assertEquals(expected, rows.single { it.label == "Upload status" }.value)
        }
    }

    @Test
    fun negativeDurationClampsToZeroMinutes() {
        assertEquals("0 min", formatDuration(-5_000L))
        assertEquals("0 min", formatDuration(-1L))
        assertEquals("0 min", formatDuration(0L))
    }

    @Test
    fun missingAverageAccuracyUsesReadableValue() {
        val item = sessionItem(averageAccuracyMeters = null)

        assertEquals(
            "Not available",
            sessionSummaryRows(item, nowMs = 5_401_000L).single { it.label == "Average accuracy" }.value,
        )
    }

    @Test
    fun importedSessionUsesCompactSummaryTableRows() {
        val rows = sessionSummaryRows(importedItem(), nowMs = 5_401_000L)

        assertEquals(
            listOf("Status", "Upload status", "Date", "Observations", "Stored on device"),
            rows.map { it.label },
        )
        assertEquals("Imported scan", rows.single { it.label == "Status" }.value)
        assertEquals("Pending", rows.single { it.label == "Upload status" }.value)
        assertEquals("42", rows.single { it.label == "Observations" }.value)
        assertEquals("2.0 KB", rows.single { it.label == "Stored on device" }.value)
        assertTrue(rows.single { it.label == "Date" }.value.isNotBlank())
    }

    @Test
    fun importedUploadedStateShowsUploadedStatusRow() {
        val rows = sessionSummaryRows(importedItem(uploadState = GatewaySessionUploadState.UPLOADED), nowMs = 1L)

        assertEquals("Uploaded", rows.single { it.label == "Upload status" }.value)
        assertEquals("Imported scan", rows.single { it.label == "Status" }.value)
    }

    @Test
    fun legacySessionStatusIsNeverShownAsLegacy() {
        val item = sessionItem(
            status = GatewaySessionStatus.LEGACY,
            uploadState = GatewaySessionUploadState.EMPTY,
        )

        assertEquals("Imported scan", statusText(item))
        val rows = sessionSummaryRows(item, nowMs = 1L)
        assertEquals("Imported scan", rows.single { it.label == "Status" }.value)
    }

    @Test
    fun clusterRowsFormatTypesAndAlignedCountsWithoutInternalIds() {
        val rows = clusterDisplayRows(
            listOf(
                cluster(
                    id = "internal-structure-hash",
                    advTypes = byteArrayOf(0x01, 0x09, 0xff.toByte()),
                ),
                cluster(id = "empty", advTypes = byteArrayOf()),
            ),
        )

        assertEquals("0x01 · 0x09 · 0xFF", rows[0].advertisementTypes)
        assertEquals("42", rows[0].observations)
        assertEquals("5", rows[0].devices)
        assertEquals("7", rows[0].variants)
        assertEquals("None recorded", rows[1].advertisementTypes)
    }

    @Test
    fun formatBytesUsesReadableUnits() {
        assertEquals("512.0 B", formatBytes(512L))
        assertEquals("2.0 KB", formatBytes(2_048L))
        assertEquals("1.5 MB", formatBytes(1_572_864L))
    }

    private fun sessionItem(
        status: GatewaySessionStatus = GatewaySessionStatus.COMPLETED,
        startedAtMs: Long = 1_000L,
        endedAtMs: Long? = 5_401_000L,
        uploadState: GatewaySessionUploadState = GatewaySessionUploadState.PENDING,
        averageAccuracyMeters: Double? = 7.25,
    ): ScanSessionListItem {
        val session = GatewayScanSession(
            sessionId = "session-id",
            status = status,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            lastEventAtMs = endedAtMs ?: 5_401_000L,
            observationCount = 42u,
            uniqueMacCount = 5u,
            exactPayloadCount = 7u,
            retainedLocalBytes = 2_048u,
            routePoints = emptyList(),
            distanceMeters = 123.6,
            averageAccuracyMeters = averageAccuracyMeters,
            uploadState = uploadState,
            diagnostic = null,
            clusters = listOf(cluster("cluster-id", byteArrayOf(0x01))),
        )
        return ScanSessionListItem(
            id = session.sessionId,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            observationCount = session.observationCount,
            uniqueMacCount = session.uniqueMacCount,
            retainedBytes = session.retainedLocalBytes,
            status = session.status,
            uploadState = session.uploadState,
            nativeSession = session,
        )
    }

    private fun importedItem(uploadState: GatewaySessionUploadState = GatewaySessionUploadState.PENDING): ScanSessionListItem =
        ScanSessionListItem(
            id = "legacy-file",
            startedAtMs = 1_000L,
            endedAtMs = 2_000L,
            observationCount = 42u,
            uniqueMacCount = null,
            retainedBytes = 2_048u,
            status = GatewaySessionStatus.LEGACY,
            uploadState = uploadState,
            nativeSession = null,
        )

    private fun cluster(id: String, advTypes: ByteArray) = GatewayAdvertisementCluster(
        clusterId = id,
        advTypes = advTypes,
        fieldLengths = emptyList(),
        observationCount = 42u,
        uniqueMacCount = 5u,
        exactPayloadCount = 7u,
        observationPoints = emptyList(),
    )
}
