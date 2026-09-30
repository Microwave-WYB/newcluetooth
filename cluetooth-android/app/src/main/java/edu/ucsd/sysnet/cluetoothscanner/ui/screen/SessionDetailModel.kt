package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import edu.ucsd.sysnet.cluetoothscanner.core.GatewayAdvertisementCluster
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class SessionSummaryRow(
    val label: String,
    val value: String,
)

data class ClusterDisplayRow(
    val clusterId: String,
    val advertisementTypes: String,
    val observations: String,
    val devices: String,
    val variants: String,
)

/**
 * User-facing summary rows for a scan session. Active sessions use the current
 * wall time (passed as [nowMs]) so the duration keeps advancing while the scan
 * is live; completed or interrupted sessions use their recorded end time.
 * Imported sessions fall back to the compact imported-session summary.
 */
fun sessionSummaryRows(
    item: ScanSessionListItem,
    nowMs: Long = System.currentTimeMillis(),
): List<SessionSummaryRow> {
    val session = item.nativeSession ?: return importedSessionSummaryRows(item)
    return listOf(
        SessionSummaryRow("Status", sessionStatusText(session.status)),
        SessionSummaryRow("Upload status", uploadStateText(session.uploadState)),
        SessionSummaryRow("Duration", formatDuration(durationEndMs(session, nowMs) - session.startedAtMs)),
        SessionSummaryRow("Start", formatDate(session.startedAtMs)),
        SessionSummaryRow("End", endText(session)),
        SessionSummaryRow("Observations", session.observationCount.toString()),
        SessionSummaryRow("Unique devices", session.uniqueMacCount.toString()),
        SessionSummaryRow("Advertisement variants", session.exactPayloadCount.toString()),
        SessionSummaryRow("Advertisement structures", session.clusters.size.toString()),
        SessionSummaryRow("Route distance", "${session.distanceMeters.roundToInt()} m"),
        SessionSummaryRow(
            "Average accuracy",
            session.averageAccuracyMeters?.let { "%.1f m".format(Locale.US, it) } ?: "Not available",
        ),
        SessionSummaryRow("Stored on device", formatBytes(session.retainedLocalBytes.toLong())),
    )
}

/**
 * Compact summary for imported scan sessions: status, upload state, date,
 * observations, and stored size.
 */
fun importedSessionSummaryRows(item: ScanSessionListItem): List<SessionSummaryRow> = listOf(
    SessionSummaryRow("Status", sessionStatusText(item.status)),
    SessionSummaryRow("Upload status", uploadStateText(item.uploadState)),
    SessionSummaryRow("Date", formatDate(item.startedAtMs)),
    SessionSummaryRow("Observations", item.observationCount.toString()),
    SessionSummaryRow("Stored on device", formatBytes(item.retainedBytes.toLong())),
)

fun clusterDisplayRows(clusters: List<GatewayAdvertisementCluster>): List<ClusterDisplayRow> =
    clusters.map { cluster ->
        ClusterDisplayRow(
            clusterId = cluster.clusterId,
            advertisementTypes = formatAdvertisementTypes(cluster.advTypes),
            observations = cluster.observationCount.toString(),
            devices = cluster.uniqueMacCount.toString(),
            variants = cluster.exactPayloadCount.toString(),
        )
    }

fun statusText(item: ScanSessionListItem): String = when (item.uploadState) {
    GatewaySessionUploadState.PENDING -> "Pending upload"
    GatewaySessionUploadState.UPLOADED -> "Uploaded"
    GatewaySessionUploadState.FAILED -> "Upload failed"
    GatewaySessionUploadState.EMPTY -> sessionStatusText(item.status)
}

private fun durationEndMs(session: GatewayScanSession, nowMs: Long): Long =
    if (session.status == GatewaySessionStatus.ACTIVE) {
        nowMs
    } else {
        session.endedAtMs ?: session.lastEventAtMs
    }

private fun endText(session: GatewayScanSession): String =
    if (session.status == GatewaySessionStatus.ACTIVE) {
        "In progress"
    } else {
        formatDate(session.endedAtMs ?: session.lastEventAtMs)
    }

private fun sessionStatusText(status: GatewaySessionStatus): String = when (status) {
    GatewaySessionStatus.ACTIVE -> "Scanning"
    GatewaySessionStatus.COMPLETED -> "Saved"
    GatewaySessionStatus.INTERRUPTED -> "Interrupted"
    GatewaySessionStatus.LEGACY -> "Imported scan"
}

private fun uploadStateText(state: GatewaySessionUploadState): String = when (state) {
    GatewaySessionUploadState.PENDING -> "Pending"
    GatewaySessionUploadState.UPLOADED -> "Uploaded"
    GatewaySessionUploadState.FAILED -> "Failed"
    GatewaySessionUploadState.EMPTY -> "Nothing to upload"
}

fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0) / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(Locale.US, value, units[unit])
}

fun formatDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun formatAdvertisementTypes(types: ByteArray): String =
    if (types.isEmpty()) {
        "None recorded"
    } else {
        types.joinToString(" · ") { "0x%02X".format(it.toInt() and 0xff) }
    }
