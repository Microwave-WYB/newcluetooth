package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import java.io.File
import java.util.UUID

data class ScanSessionListItem(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val observationCount: ULong,
    val uniqueMacCount: ULong?,
    val retainedBytes: ULong,
    val status: GatewaySessionStatus,
    val uploadState: GatewaySessionUploadState,
    val nativeSession: GatewayScanSession?,
    internal val legacyFile: File? = null,
)

fun combinedChronologicalSessions(
    sessions: List<GatewayScanSession>,
    legacyFiles: List<File>,
    legacyObservationCounts: Map<String, ULong> = emptyMap(),
): List<ScanSessionListItem> {
    val native = sessions.map { session ->
        ScanSessionListItem(
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
    val legacy = legacyFiles.map { file ->
        ScanSessionListItem(
            id = "legacy-${UUID.nameUUIDFromBytes(file.name.toByteArray())}",
            startedAtMs = file.lastModified(),
            endedAtMs = file.lastModified(),
            observationCount = legacyObservationCounts[file.absolutePath] ?: 0u,
            uniqueMacCount = null,
            retainedBytes = file.length().toULong(),
            status = GatewaySessionStatus.LEGACY,
            uploadState = if (file.name.endsWith(".uploaded")) {
                GatewaySessionUploadState.UPLOADED
            } else {
                GatewaySessionUploadState.PENDING
            },
            nativeSession = null,
            legacyFile = file,
        )
    }
    return (native + legacy).sortedWith(
        compareByDescending<ScanSessionListItem> { it.status == GatewaySessionStatus.ACTIVE }
            .thenByDescending { it.startedAtMs }
            .thenBy { it.id },
    )
}

fun sessionDeleteWarning(item: ScanSessionListItem): String =
    if (item.uploadState == GatewaySessionUploadState.UPLOADED) {
        "This removes the local copy only. Uploaded cloud data remains."
    } else {
        "This session may not be uploaded. Deleting it can permanently lose scan data."
    }

fun sessionDeleteIsDestructive(item: ScanSessionListItem): Boolean =
    item.uploadState != GatewaySessionUploadState.UPLOADED

fun sessionEmptyMessage(items: List<ScanSessionListItem>, activeRows: ULong): String? = when {
    items.isNotEmpty() -> null
    activeRows > 0u -> "Current observations are waiting to be saved"
    else -> "No scans saved yet"
}
