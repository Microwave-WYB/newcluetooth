package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import edu.ucsd.sysnet.cluetoothscanner.BuildConfig
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanViewModel
import edu.ucsd.sysnet.cluetoothscanner.ui.components.ScanFab
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadStatusScreen(
    storageService: StorageService,
    uploadService: UploadService,
    scanViewModel: ScanViewModel,
    onExportSession: (String, GatewayExportFormat) -> Unit,
    onExportAll: (GatewayExportFormat) -> Unit,
    onExportLegacy: (java.io.File, GatewayExportFormat) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val legacyFiles by storageService.generatedFiles.collectAsState()
    val legacyObservationCounts by storageService.legacyObservationCounts.collectAsState()
    val coreState by scanViewModel.coreUiState.collectAsState()
    val isScanning by scanViewModel.isScanning.collectAsState()
    val uploadStatus by uploadService.getUploadStatus().collectAsState(initial = null)
    val sessions = remember(coreState.sessions, legacyFiles, legacyObservationCounts) {
        combinedChronologicalSessions(coreState.sessions, legacyFiles, legacyObservationCounts)
    }
    var selected by remember { mutableStateOf<ScanSessionListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ScanSessionListItem?>(null) }
    var exportTarget by remember { mutableStateOf<ScanSessionListItem?>(null) }
    var chooseFullExport by remember { mutableStateOf(false) }
    var autoUploadEnabled by remember { mutableStateOf(uploadService.isAutoUploadEnabled()) }

    LaunchedEffect(uploadStatus) {
        if (uploadStatus == WorkInfo.State.SUCCEEDED || uploadStatus == WorkInfo.State.FAILED) {
            storageService.refreshFileList()
            scanViewModel.refreshCoreState()
        }
    }

    selected?.let { detail ->
        SessionDetail(
            item = detail,
            onBack = { selected = null },
            onExport = { exportTarget = detail },
            onRetryUpload = { uploadService.forceUpload() },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ScanFab(isScanning = isScanning, onToggleScanning = scanViewModel::toggleScanning)
        },
    ) { padding ->
        Column(modifier.padding(padding).fillMaxSize()) {
            val pending = sessions.count { it.uploadState == GatewaySessionUploadState.PENDING || it.uploadState == GatewaySessionUploadState.FAILED }
            val totalBytes = sessions.fold(0uL) { total, item -> total + item.retainedBytes }
            if (coreState.queuedObservationCount > 0 || coreState.activePayloadRows > 0u) {
                Text(
                    "Scanning · ${coreState.queuedObservationCount + coreState.activePayloadRows.toInt()} observations waiting to be saved",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${sessions.size} sessions · $pending pending · ${formatBytes(totalBytes.toLong())} local",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto upload", style = MaterialTheme.typography.titleMedium)
                    Text(if (autoUploadEnabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoUploadEnabled, onCheckedChange = {
                    autoUploadEnabled = it
                    uploadService.setAutoUploadEnabled(it)
                })
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = uploadService::forceUpload,
                    enabled = pending > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Upload now")
                }
                OutlinedButton(
                    onClick = { chooseFullExport = true },
                    enabled = sessions.any { it.retainedBytes > 0u },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Export scans")
                }
            }
            coreState.lastUploadError?.let { error ->
                Text(
                    error.lineSequence().first().take(160),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val emptyMessage = sessionEmptyMessage(sessions, coreState.activePayloadRows)
            if (emptyMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(sessions, key = { it.id }) { item ->
                        SessionRow(item, onOpen = { selected = item }, onDelete = { deleteTarget = item }, onExport = { exportTarget = item })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (chooseFullExport) {
        ExportFormatDialog(
            title = "Export all scan sessions",
            onDismiss = { chooseFullExport = false },
            onChoose = {
                chooseFullExport = false
                onExportAll(it)
            },
        )
    }
    exportTarget?.let { target ->
        ExportFormatDialog(
            title = "Export session",
            onDismiss = { exportTarget = null },
            onChoose = { format ->
                exportTarget = null
                if (target.nativeSession != null) {
                    onExportSession(target.id, format)
                } else {
                    target.legacyFile?.let { onExportLegacy(it, format) }
                }
            },
            parquetEnabled = true,
        )
    }
    deleteTarget?.let { target ->
        val uploaded = target.uploadState == GatewaySessionUploadState.UPLOADED
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete local session?") },
            text = { Text(sessionDeleteWarning(target)) },
            confirmButton = {
                TextButton(onClick = {
                    target.legacyFile?.let(storageService::deleteFile)
                    if (target.nativeSession != null) {
                        scanViewModel.deleteSessionFromUi(target.id, destructive = sessionDeleteIsDestructive(target))
                    }
                    deleteTarget = null
                }) { Text(if (uploaded) "Remove local copy" else "Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionRow(
    item: ScanSessionListItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(if (item.status == GatewaySessionStatus.ACTIVE) "Scanning now" else formatDate(item.startedAtMs))
        },
        supportingContent = {
            val duration = item.endedAtMs?.let { formatDuration(it - item.startedAtMs) } ?: "In progress"
            val devices = item.uniqueMacCount?.let { " · $it devices" }.orEmpty()
            Text("$duration · ${item.observationCount} observations$devices\n${statusText(item)} · ${formatBytes(item.retainedBytes.toLong())}")
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Session actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export session") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete local session") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetail(
    item: ScanSessionListItem,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    val session = item.nativeSession
    var selectedCluster by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Session details") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                if (session != null) {
                    val route = session.routePoints.map { LatLng(it.lat, it.lon) }
                    val overlay = session.clusters
                        .filter { selectedCluster == null || it.clusterId == selectedCluster }
                        .flatMap { it.observationPoints }
                    if (BuildConfig.MAPS_CONFIGURED && route.isNotEmpty()) {
                        val camera = rememberCameraPositionState {
                            position = CameraPosition.fromLatLngZoom(route.first(), 15f)
                        }
                        GoogleMap(
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            cameraPositionState = camera,
                        ) {
                            Polyline(points = route)
                            Marker(state = MarkerState(route.first()), title = "Start")
                            if (route.size > 1) Marker(state = MarkerState(route.last()), title = "End")
                            overlay.take(200).forEach { point ->
                                Marker(state = MarkerState(LatLng(point.lat, point.lon)), title = "Observation")
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Text(if (route.isEmpty()) "No route recorded" else "Map unavailable: add a restricted Android Maps SDK key")
                        }
                    }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${formatDuration((session.endedAtMs ?: session.lastEventAtMs) - session.startedAtMs)} · ${statusText(item)}", style = MaterialTheme.typography.titleMedium)
                        Text("${session.observationCount} observations")
                        Text("${session.uniqueMacCount} unique MAC addresses")
                        Text("${session.exactPayloadCount} exact payload variants")
                        Text("${session.clusters.size} structural clusters")
                        Text("${session.distanceMeters.roundToInt()} m route · ${session.averageAccuracyMeters?.let { "%.1f m average accuracy".format(Locale.US, it) } ?: "no GPS accuracy"}")
                        Text("${formatBytes(session.retainedLocalBytes.toLong())} retained locally")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onExport) { Text("Export session") }
                            if (session.uploadState == GatewaySessionUploadState.FAILED) {
                                OutlinedButton(onClick = onRetryUpload) { Text("Retry upload") }
                            }
                        }
                    }
                } else {
                    Text("Retained legacy scan session", Modifier.padding(16.dp))
                }
            }
            if (session != null) {
                items(session.clusters, key = { it.clusterId }) { cluster ->
                    ListItem(
                        modifier = Modifier.clickable {
                            selectedCluster = if (selectedCluster == cluster.clusterId) null else cluster.clusterId
                        },
                        headlineContent = { Text("AD types ${cluster.advTypes.joinToString(" ") { "0x%02x".format(it.toInt() and 0xff) }}") },
                        supportingContent = { Text("${cluster.observationCount} observations · ${cluster.uniqueMacCount} devices · ${cluster.exactPayloadCount} payloads") },
                        trailingContent = { if (selectedCluster == cluster.clusterId) Text("Filtered") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportFormatDialog(
    title: String,
    onDismiss: () -> Unit,
    onChoose: (GatewayExportFormat) -> Unit,
    parquetEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("Choose an export format. JSONL uses lowercase hexadecimal for raw advertisement bytes.") },
        confirmButton = { TextButton(onClick = { onChoose(GatewayExportFormat.JSONL) }) { Text("JSONL") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(enabled = parquetEnabled, onClick = { onChoose(GatewayExportFormat.PARQUET) }) { Text("Parquet") }
            }
        },
    )
}

private fun statusText(item: ScanSessionListItem): String = when (item.uploadState) {
    GatewaySessionUploadState.PENDING -> "Pending upload"
    GatewaySessionUploadState.UPLOADED -> "Uploaded"
    GatewaySessionUploadState.FAILED -> "Upload failed"
    GatewaySessionUploadState.EMPTY -> when (item.status) {
        GatewaySessionStatus.ACTIVE -> "Scanning"
        GatewaySessionStatus.INTERRUPTED -> "Interrupted"
        GatewaySessionStatus.LEGACY -> "Legacy"
        GatewaySessionStatus.COMPLETED -> "Saved"
    }
}

private fun formatDate(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
private fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0) / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "%.1f %s".format(Locale.US, value, units[unit])
}
