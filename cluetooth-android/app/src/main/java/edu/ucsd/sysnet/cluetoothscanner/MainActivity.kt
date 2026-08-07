package edu.ucsd.sysnet.cluetoothscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.ucsd.sysnet.cluetoothscanner.service.BleScanService
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayLegacyRow
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayLegacySessionRows
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanReadiness
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanReadinessAction
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanViewModel
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.DeviceDetailsBottomSheet
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.MainScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.screen.UploadStatusScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.theme.CluetoothScannerTheme
import edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager
import edu.ucsd.sysnet.cluetoothscanner.utils.CompressionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothManager?.adapter
    }

    private var bluetoothReadyCallback: (() -> Unit)? = null

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
            bluetoothReadyCallback?.invoke()
        } else {
            Log.w(TAG, "Bluetooth not enabled")
        }
    }

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        Log.i(TAG, "Permissions result: $permissions")
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
        } else {
            Log.w(TAG, "Some permissions denied")
        }
        permissionCallback?.invoke(allGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CluetoothScannerTheme {
                CluetoothApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CluetoothApp() {
        val context = LocalContext.current
        val navController = rememberNavController()

        val requiredPermissions = remember {
            requiredRuntimePermissions(Build.VERSION.SDK_INT)
        }

        var allPermissionsGranted by remember { mutableStateOf(false) }
        var showPermissionRationale by remember { mutableStateOf(false) }
        var showUserIdDialog by remember { mutableStateOf(DeviceIdManager.getUserId(context) == null) }

        val repository = remember { (application as CluetoothApplication).repository }
        val serviceScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val locationService = remember { LocationService(context, repository) }
        val bleScanService = remember { BleScanService(context, locationService, repository) }
        val uploadService = remember { UploadService(context) }
        val storageService = remember { StorageService(context, serviceScope, uploadService) }
        val exportScope = rememberCoroutineScope()
        var preparedExport by remember {
            mutableStateOf<edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport?>(null)
        }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val prepared = preparedExport
            preparedExport = null
            if (prepared != null) {
                exportScope.launch(Dispatchers.IO) {
                    try {
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                File(prepared.localPath).inputStream().use { input -> input.copyTo(output) }
                            } ?: error("Unable to open export destination")
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "Exported ${prepared.fileCount} session${if (prepared.fileCount == 1uL) "" else "s"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Export failed", error)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Export failed: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        if (prepared.exportId.startsWith("legacy:")) {
                            File(prepared.localPath).delete()
                        } else {
                            runCatching { repository.acknowledgeExport(prepared.exportId) }
                        }
                    }
                }
            }
        }

        val scanViewModel: ScanViewModel = viewModel {
            ScanViewModel(
                bleScanService,
                locationService,
                storageService,
                uploadService,
                repository,
            )
        }

        fun refreshReadiness(
            requestMissingPermissions: Boolean,
            requestBluetoothEnable: Boolean,
        ) {
            val hasAllPermissions = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PermissionChecker.PERMISSION_GRANTED
            }
            allPermissionsGranted = hasAllPermissions

            if (!hasAllPermissions) {
                scanViewModel.startWhenReady(
                    ScanReadiness(
                        permissionsGranted = false,
                        bluetoothSupported = true,
                        bluetoothEnabled = false,
                    ),
                )
                if (requestMissingPermissions) {
                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                }
                return
            }
            checkBluetoothAndStartScanning(scanViewModel, requestBluetoothEnable)
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, scanViewModel) {
            permissionCallback = { allGranted ->
                if (!allGranted) {
                    val permanentlyRejected = requiredPermissions.any { permission ->
                        ContextCompat.checkSelfPermission(context, permission) !=
                            PermissionChecker.PERMISSION_GRANTED &&
                            !shouldShowRequestPermissionRationale(permission)
                    }
                    if (permanentlyRejected) {
                        scanViewModel.onPermissionsPermanentlyRejected()
                    }
                }
                refreshReadiness(
                    requestMissingPermissions = false,
                    requestBluetoothEnable = true,
                )
            }
            bluetoothReadyCallback = {
                refreshReadiness(
                    requestMissingPermissions = false,
                    requestBluetoothEnable = false,
                )
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> refreshReadiness(
                        requestMissingPermissions = false,
                        requestBluetoothEnable = false,
                    )
                    Lifecycle.Event.ON_STOP -> Unit // Preserve best-effort background scan intent.
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                permissionCallback = null
                bluetoothReadyCallback = null
            }
        }

        LaunchedEffect(Unit) {
            refreshReadiness(
                requestMissingPermissions = true,
                requestBluetoothEnable = true,
            )
        }

        val bottomSheetState = rememberModalBottomSheetState()
        var showBottomSheet by remember { mutableStateOf(false) }
        val selectedItem by scanViewModel.selectedItem.collectAsState()


        if (showUserIdDialog) {
            UserIdDialog(
                onConfirm = { userId, autoUploadEnabled ->
                    DeviceIdManager.setUserId(context, userId)
                    uploadService.setAutoUploadEnabled(autoUploadEnabled)
                    showUserIdDialog = false
                }
            )
            return
        }

        if (allPermissionsGranted) {
            NavHost(
                navController = navController,
                startDestination = "main"
            ) {
                composable("main") {
                    MainScreen(
                        viewModel = scanViewModel,
                        onNavigateToUpload = {
                            navController.navigate("upload")
                        },
                        onShowBottomSheet = {
                            showBottomSheet = true
                        }
                    )
                }

                composable("upload") {
                    UploadStatusScreen(
                        storageService = storageService,
                        uploadService = uploadService,
                        scanViewModel = scanViewModel,
                        onExportSession = { sessionId, format ->
                            exportScope.launch {
                                try {
                                    val prepared = scanViewModel.prepareSessionExport(sessionId, format)
                                    preparedExport = prepared
                                    exportLauncher.launch(prepared.suggestedFileName)
                                } catch (error: Exception) {
                                    Log.e(TAG, "Session export preparation failed", error)
                                    Toast.makeText(context, "Export failed: ${error.message ?: "unable to save scans"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onExportAll = { format ->
                            exportScope.launch {
                                try {
                                    val legacy = withContext(Dispatchers.IO) {
                                        storageService.generatedFiles.value.map(::readLegacySessionRows)
                                    }
                                    val prepared = scanViewModel.prepareFullExport(format, legacy)
                                    preparedExport = prepared
                                    exportLauncher.launch(prepared.suggestedFileName)
                                } catch (error: Exception) {
                                    Log.e(TAG, "Full export preparation failed", error)
                                    Toast.makeText(context, "Export failed: ${error.message ?: "unable to save scans"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onExportLegacy = { legacyFile, format ->
                            exportScope.launch {
                                try {
                                    val session = withContext(Dispatchers.IO) { readLegacySessionRows(legacyFile) }
                                    val prepared = scanViewModel.prepareLegacySessionExport(
                                        session,
                                        format,
                                    )
                                    preparedExport = prepared
                                    exportLauncher.launch(prepared.suggestedFileName)
                                } catch (error: Exception) {
                                    Log.e(TAG, "Legacy export preparation failed", error)
                                    Toast.makeText(context, "Export failed: ${error.message ?: "unable to read legacy session"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }

            if (showBottomSheet && selectedItem != null) {
                ModalBottomSheet(
                    onDismissRequest = {
                        showBottomSheet = false
                        scanViewModel.clearSelection()
                    },
                    sheetState = bottomSheetState
                ) {
                    DeviceDetailsBottomSheet(
                        item = selectedItem!!,
                        onDismiss = {
                            showBottomSheet = false
                            scanViewModel.clearSelection()
                        }
                    )
                }
            }
        } else {
            PermissionScreen(
                requiredPermissions = requiredPermissions,
                onRequestPermissions = {
                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                }
            )
        }
    }

    private fun checkBluetoothAndStartScanning(
        scanViewModel: ScanViewModel,
        requestBluetoothEnable: Boolean,
    ) {
        if (!hasRequiredPermissions()) {
            scanViewModel.startWhenReady(
                ScanReadiness(
                    permissionsGranted = false,
                    bluetoothSupported = true,
                    bluetoothEnabled = false,
                ),
            )
            return
        }

        val adapter = try {
            bluetoothAdapter
        } catch (error: SecurityException) {
            Log.w(TAG, "Bluetooth adapter access denied", error)
            null
        }
        val bluetoothEnabled = try {
            adapter?.isEnabled == true
        } catch (error: SecurityException) {
            Log.w(TAG, "Bluetooth state access denied", error)
            false
        }
        val readiness = ScanReadiness(
            permissionsGranted = true,
            bluetoothSupported = adapter != null,
            bluetoothEnabled = bluetoothEnabled,
        )
        scanViewModel.startWhenReady(readiness)

        when (readiness.action) {
            ScanReadinessAction.BLUETOOTH_UNAVAILABLE -> {
                Log.w(TAG, "Bluetooth is unavailable")
            }
            ScanReadinessAction.REQUEST_BLUETOOTH -> {
                if (requestBluetoothEnable) {
                    try {
                        enableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        )
                    } catch (error: SecurityException) {
                        Log.w(TAG, "Bluetooth enable request denied", error)
                    }
                }
            }
            ScanReadinessAction.START_SERVICES -> Unit
            ScanReadinessAction.REQUEST_PERMISSIONS -> Unit
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredRuntimePermissions(Build.VERSION.SDK_INT).all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PermissionChecker.PERMISSION_GRANTED
        }

    @Composable
    fun UserIdDialog(onConfirm: (String, Boolean) -> Unit) {
        var text by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }
        var autoUploadEnabled by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = {},
            title = { Text("Enter User ID") },
            text = {
                Column {
                    Text(
                        text = "Lowercase letters and numbers only, max 9 characters.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            isError = it.isNotEmpty() && !DeviceIdManager.isValidUserId(it)
                        },
                        label = { Text("User ID") },
                        isError = isError,
                        supportingText = if (isError) {
                            { Text("Only lowercase a-z and 0-9, 1-9 chars") }
                        } else null,
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto upload",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (autoUploadEnabled) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoUploadEnabled,
                            onCheckedChange = { autoUploadEnabled = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(text, autoUploadEnabled) },
                    enabled = text.isNotEmpty() && DeviceIdManager.isValidUserId(text)
                ) {
                    Text("Confirm")
                }
            }
        )
    }

    @Composable
    fun PermissionScreen(
        requiredPermissions: List<String>,
        onRequestPermissions: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "This app requires Bluetooth and Location permissions to scan for nearby devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = TextAlign.Center
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Required permissions:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        requiredPermissions.forEach { permission ->
                            Text(
                                text = "• ${getPermissionDisplayName(permission)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permissions")
                }
            }
        }
    }

    private fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Precise Location"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate Location"
            Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scanning"
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connection"
            Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth Advertising"
            Manifest.permission.BLUETOOTH -> "Bluetooth"
            Manifest.permission.BLUETOOTH_ADMIN -> "Bluetooth Administration"
            else -> permission.substringAfterLast(".")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

private fun readLegacySessionRows(file: File): GatewayLegacySessionRows {
    val text = String(CompressionUtils.decompressData(file.readBytes()), Charsets.UTF_8)
    val rows = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val record = BleRecord.fromJson(line)
        GatewayLegacyRow(
            addr = record.mac.uppercase(Locale.US),
            rssi = record.rssi,
            scannedAtMs = Instant.parse(record.timestamp).toEpochMilli(),
            raw = record.raw,
            lat = record.lat,
            lon = record.lon,
            accuracy = record.accuracy,
        )
    }.toList()
    val started = rows.minOfOrNull { it.scannedAtMs } ?: file.lastModified()
    val ended = rows.maxOfOrNull { it.scannedAtMs } ?: started
    return GatewayLegacySessionRows(
        sessionId = "legacy-${UUID.nameUUIDFromBytes(file.name.toByteArray())}",
        startedAtMs = started,
        endedAtMs = ended,
        rows = rows,
    )
}
