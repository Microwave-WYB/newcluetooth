package edu.ucsd.sysnet.cluetoothscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.ucsd.sysnet.cluetoothscanner.service.BleScanService
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.StorageService
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanReadiness
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanReadinessAction
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanViewModel
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.app.V3App
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.app.V3ViewModel
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MissingPermissionsScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.OnboardingActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.OnboardingScreen
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.PermissionNeed
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
            V3Theme {
                // Gives every Text its content color; without it the screens render black on dark.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { CluetoothApp() }
            }
        }
    }

    @Composable
    fun CluetoothApp() {
        val context = LocalContext.current

        val requiredPermissions = remember { requiredRuntimePermissions(Build.VERSION.SDK_INT) }
        // Asked for together with the required ones (onboarding); recording works without it.
        val notificationPermission = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        }
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

        var allPermissionsGranted by remember { mutableStateOf(requiredPermissions.all(::granted)) }
        // Set from a permission result: a required one is denied and Android will not ask again.
        var permanentlyDenied by remember { mutableStateOf(false) }
        var bluetoothOn by remember { mutableStateOf(true) }
        var permissionTick by remember { mutableStateOf(0) }
        // First launch only; an install that already has a user ID went through the old startup dialog.
        var onboarded by remember { mutableStateOf(AppSettings.isOnboarded(context) || DeviceIdManager.getUserId(context) != null) }

        val repository = remember { (application as CluetoothApplication).repository }
        val serviceScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val cluetoothApplication = remember { application as CluetoothApplication }
        // Process-owned (see CluetoothApplication) so a recording outlives this Activity.
        val locationService = cluetoothApplication.locationService
        val uploadService = remember { UploadService(context) }
        val storageService = remember { StorageService(context, serviceScope, uploadService) }
        val scanViewModel: ScanViewModel = viewModel {
            ScanViewModel(
                locationService,
                storageService,
                repository,
                cluetoothApplication.recordingController,
                cluetoothApplication.scanSupervisor,
            )
        }

        fun refreshReadiness(requestBluetoothEnable: Boolean) {
            val hasAllPermissions = requiredPermissions.all(::granted)
            allPermissionsGranted = hasAllPermissions
            if (hasAllPermissions) permanentlyDenied = false
            permissionTick++
            if (!hasAllPermissions) {
                scanViewModel.startWhenReady(
                    ScanReadiness(
                        permissionsGranted = false,
                        bluetoothSupported = true,
                        bluetoothEnabled = false,
                    ),
                )
                return
            }
            bluetoothOn = checkBluetoothAndStartScanning(scanViewModel, requestBluetoothEnable)
        }

        fun requestPermissions() {
            permissionLauncher.launch((requiredPermissions + listOfNotNull(notificationPermission)).toTypedArray())
        }

        fun openAppSettings() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, scanViewModel) {
            permissionCallback = { _ ->
                val rejected = requiredPermissions.any { !granted(it) && !shouldShowRequestPermissionRationale(it) }
                if (rejected) {
                    permanentlyDenied = true
                    scanViewModel.onPermissionsPermanentlyRejected()
                }
                refreshReadiness(requestBluetoothEnable = false)
            }
            bluetoothReadyCallback = { refreshReadiness(requestBluetoothEnable = false) }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> refreshReadiness(requestBluetoothEnable = false)
                    // Live scanning is for the foreground; a recording (foreground service) goes on.
                    Lifecycle.Event.ON_STOP -> scanViewModel.onActivityStopped()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // Bluetooth switched on or off from the shade: follow it without waiting for a resume.
            val bluetoothReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refreshReadiness(requestBluetoothEnable = false)
            }
            ContextCompat.registerReceiver(
                context, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                context.unregisterReceiver(bluetoothReceiver)
                permissionCallback = null
                bluetoothReadyCallback = null
            }
        }

        // Nothing is asked for at launch: onboarding asks, and afterwards only the missing piece offers its fix.
        LaunchedEffect(Unit) { refreshReadiness(requestBluetoothEnable = false) }

        val needs = remember(permissionTick, allPermissionsGranted) {
            listOfNotNull(
                PermissionNeed(
                    R.string.permission_nearby, R.string.permission_nearby_why,
                    requiredBluetoothPermissions(Build.VERSION.SDK_INT).all(::granted),
                ),
                PermissionNeed(
                    R.string.permission_location, R.string.permission_location_why,
                    granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                ),
                notificationPermission?.let { PermissionNeed(R.string.permission_notifications, R.string.permission_notifications_why, granted(it), required = false) },
            )
        }

        if (!onboarded) {
            OnboardingScreen(
                permissions = needs,
                permanentlyDenied = permanentlyDenied,
                bluetoothOn = allPermissionsGranted && bluetoothOn,
                initialUserId = DeviceIdManager.getUserId(context).orEmpty(),
                actions = OnboardingActions(
                    onRequestPermissions = ::requestPermissions,
                    onOpenSettings = ::openAppSettings,
                    onEnableBluetooth = { refreshReadiness(requestBluetoothEnable = true) },
                    onFinish = { userId, autoRecord ->
                        DeviceIdManager.setUserId(context, userId)
                        AppSettings.setAutoRecord(context, autoRecord)
                        AppSettings.setOnboarded(context)
                        onboarded = true
                    },
                ),
            )
            return
        }

        if (!allPermissionsGranted) {
            MissingPermissionsScreen(
                missing = needs.filter { it.required && !it.granted },
                permanentlyDenied = permanentlyDenied,
                onRequest = ::requestPermissions,
                onOpenSettings = ::openAppSettings,
            )
            return
        }

        val v3: V3ViewModel = viewModel { V3ViewModel(cluetoothApplication) }
        // Debug builds: `am start ... --es v3_demo_map selected` opens the map page over fake data.
        LaunchedEffect(Unit) {
            if (BuildConfig.DEBUG) intent?.getStringExtra("v3_demo_map")?.let(v3::openDemoMap)
            // Debug builds: `am start ... --ez fake_scan true` feeds about 300 synthetic devices into the live list.
            if (BuildConfig.DEBUG && intent?.getBooleanExtra("fake_scan", false) == true) {
                runCatching {
                    Class.forName("edu.ucsd.sysnet.cluetoothscanner.ui.v3.app.DebugFakeScan")
                        .getMethod("start", edu.ucsd.sysnet.cluetoothscanner.service.BleScanService::class.java)
                        .invoke(null, cluetoothApplication.bleScanService)
                }
            }
        }
        V3App(v3, bluetoothOff = !bluetoothOn, onEnableBluetooth = { refreshReadiness(requestBluetoothEnable = true) })
    }

    /** Starts what the readiness allows; true when Bluetooth is on (or absent, which no banner can fix). */
    private fun checkBluetoothAndStartScanning(
        scanViewModel: ScanViewModel,
        requestBluetoothEnable: Boolean,
    ): Boolean {
        if (!hasRequiredPermissions()) {
            scanViewModel.startWhenReady(
                ScanReadiness(
                    permissionsGranted = false,
                    bluetoothSupported = true,
                    bluetoothEnabled = false,
                ),
            )
            return false
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
        return bluetoothEnabled || adapter == null
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredRuntimePermissions(Build.VERSION.SDK_INT).all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PermissionChecker.PERMISSION_GRANTED
        }

    companion object {
        private const val TAG = "MainActivity"
    }
}

