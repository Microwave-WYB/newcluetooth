package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.launch
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanViewModel
import edu.ucsd.sysnet.cluetoothscanner.ui.components.ScanFab
import edu.ucsd.sysnet.cluetoothscanner.ui.components.ScanListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ScanViewModel,
    onNavigateToUpload: () -> Unit,
    onShowBottomSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scanItems by viewModel.scanItems.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val coreState by viewModel.coreUiState.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    var deviceId by remember { mutableStateOf("loading...") }
    LaunchedEffect(Unit) {
        deviceId = edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager.getDeviceId(context)
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        SelectionContainer {
                            Text(
                                text = "ID: $deviceId",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SelectionContainer {
                            Text(
                                text = currentLocation?.let {
                                    String.format("%.6f, %.6f", it.latitude, it.longitude)
                                } ?: "Location unavailable",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = currentLocation?.let {
                                if (it.hasAccuracy()) "±${it.accuracy.toInt()}m" else "No accuracy"
                            } ?: "No accuracy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "BLE: ${scanStatus.state.name.lowercase().replace('_', ' ')}" +
                                (scanStatus.message?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                coreState.errorMessage != null -> "Core error: ${coreState.errorMessage}"
                                coreState.coreReady -> "Core ready (API ${coreState.apiVersion})"
                                else -> "Core initializing"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (coreState.errorMessage == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.sortByRssi()
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort by RSSI"
                        )
                    }
                    IconButton(onClick = onNavigateToUpload) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Scan sessions"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                ),
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            )
        },
        floatingActionButton = {
            ScanFab(
                isScanning = isScanning,
                onToggleScanning = { viewModel.toggleScanning() }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            state = pullToRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshScanList() },
            modifier = modifier.padding(paddingValues)
        ) {
            if (scanItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.PlayArrow else Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (isScanning) {
                                "Scanning for devices..."
                            } else {
                                "Tap the button to start scanning"
                            },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = scanItems,
                        key = { item -> item.mac }
                    ) { item ->
                        ScanListItem(
                            item = item,
                            onItemClick = { selectedItem ->
                                viewModel.selectItem(selectedItem)
                                onShowBottomSheet()
                            }
                        )
                    }
                }
            }
        }
    }
}
