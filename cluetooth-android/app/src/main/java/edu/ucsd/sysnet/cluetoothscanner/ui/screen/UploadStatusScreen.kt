package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay

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
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selectedSession = selectedId?.let { id -> sessions.firstOrNull { it.id == id } }
    LaunchedEffect(selectedId, selectedSession) {
        if (selectedId != null && selectedSession == null) {
            selectedId = null
        }
    }
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

    selectedSession?.let { detail ->
        SessionDetail(
            item = detail,
            onBack = { selectedId = null },
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
                "${sessions.size} sessions · $pending pending · ${formatBytes(totalBytes.toLong())} stored on device",
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Export", maxLines = 1)
                }
            }
            if (coreState.lastUploadError != null) {
                Text(
                    "Some scans could not be uploaded. Try again.",
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
                        SessionRow(item, onOpen = { selectedId = item.id }, onDelete = { deleteTarget = item }, onExport = { exportTarget = item })
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
    val nowMs = rememberMinuteTicker(isActive = session?.status == GatewaySessionStatus.ACTIVE)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (session != null) {
                item {
                    val route = session.routePoints.map { LatLng(it.lat, it.lon) }
                    val overlay = session.clusters
                        .filter { selectedCluster == null || it.clusterId == selectedCluster }
                        .flatMap { it.observationPoints }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        if (route.isNotEmpty() && BuildConfig.MAPS_CONFIGURED) {
                            val camera = rememberCameraPositionState {
                                position = CameraPosition.fromLatLngZoom(route.first(), 15f)
                            }
                            GoogleMap(
                                modifier = Modifier.fillMaxWidth().height(280.dp),
                                cameraPositionState = camera,
                            ) {
                                Polyline(points = route)
                                Marker(state = MarkerState(route.first()), title = "Start")
                                if (route.size > 1) {
                                    Marker(state = MarkerState(route.last()), title = "End")
                                }
                                overlay.take(200).forEach { point ->
                                    Marker(
                                        state = MarkerState(LatLng(point.lat, point.lon)),
                                        title = "Observation",
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(160.dp).padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (route.isEmpty()) {
                                        "No route recorded"
                                    } else {
                                        "Route map unavailable."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("Session summary")
                        SummaryTable(sessionSummaryRows(item, nowMs = nowMs))
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onExport,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text("Export", maxLines = 1)
                            }
                            if (session.uploadState == GatewaySessionUploadState.FAILED) {
                                OutlinedButton(
                                    onClick = onRetryUpload,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Text("Retry upload", maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (session.clusters.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeading("Advertisement structures")
                            Text(
                                "Select a row to filter the observations shown on the map.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AdvertisementStructureTable(
                                rows = clusterDisplayRows(session.clusters),
                                selectedCluster = selectedCluster,
                                onToggleCluster = { clusterId ->
                                    selectedCluster = if (selectedCluster == clusterId) null else clusterId
                                },
                            )
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("Session summary")
                        SummaryTable(sessionSummaryRows(item, nowMs = nowMs))
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onExport,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text("Export", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMinuteTicker(isActive: Boolean): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            val untilNextMinute = 60_000 - (System.currentTimeMillis() % 60_000)
            delay(untilNextMinute)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun SummaryTable(rows: List<SessionSummaryRow>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SummaryTableRow(row)
            }
        }
    }
}

@Composable
private fun SummaryTableRow(row: SessionSummaryRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            modifier = Modifier.weight(0.45f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.value,
            modifier = Modifier.weight(0.55f).padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AdvertisementStructureTable(
    rows: List<ClusterDisplayRow>,
    selectedCluster: String?,
    onToggleCluster: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tableWidth = maxWidth.coerceAtLeast(620.dp)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(tableWidth)) {
                    AdvertisementStructureHeader()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (rows.isEmpty()) {
                        Text(
                            "No advertisement structures recorded",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            AdvertisementStructureRow(
                                row = row,
                                selected = row.clusterId == selectedCluster,
                                onClick = { onToggleCluster(row.clusterId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvertisementStructureHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(48.dp))
        ClusterCell("Types", Modifier.weight(2.2f), header = true)
        ClusterCell("Observations", Modifier.weight(1.2f), header = true)
        ClusterCell("Devices", Modifier.weight(1f), header = true)
        ClusterCell("Variants", Modifier.weight(1f), header = true)
    }
}

@Composable
private fun AdvertisementStructureRow(
    row: ClusterDisplayRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                RectangleShape,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = selected, onCheckedChange = null)
        }
        ClusterCell(row.advertisementTypes, Modifier.weight(2.2f))
        ClusterCell(row.observations, Modifier.weight(1.2f))
        ClusterCell(row.devices, Modifier.weight(1f))
        ClusterCell(row.variants, Modifier.weight(1f))
    }
}

@Composable
private fun ClusterCell(
    text: String,
    modifier: Modifier,
    header: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    )
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
        text = { Text("Choose a file format.") },
        confirmButton = { TextButton(onClick = { onChoose(GatewayExportFormat.JSONL) }) { Text("JSONL") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(enabled = parquetEnabled, onClick = { onChoose(GatewayExportFormat.PARQUET) }) { Text("Parquet") }
            }
        },
    )
}