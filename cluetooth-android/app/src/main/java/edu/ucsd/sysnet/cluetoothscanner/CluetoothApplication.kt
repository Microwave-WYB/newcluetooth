package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import android.os.SystemClock
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTriggerCache
import edu.ucsd.sysnet.cluetoothscanner.core.NativeCoreGateway
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDiscoveryManager
import edu.ucsd.sysnet.cluetoothscanner.gatt.FilePendingDiscoveryStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.RepositoryGattStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.gattDeviceInfo
import edu.ucsd.sysnet.cluetoothscanner.local.AndroidSqlDatabase
import edu.ucsd.sysnet.cluetoothscanner.local.LocalData
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataHub
import edu.ucsd.sysnet.cluetoothscanner.local.NativeAdvertisementMatcher
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.service.AndroidRecordingHost
import edu.ucsd.sysnet.cluetoothscanner.service.BleScanService
import edu.ucsd.sysnet.cluetoothscanner.service.LocationService
import edu.ucsd.sysnet.cluetoothscanner.service.InterruptedRecordingNotifier
import edu.ucsd.sysnet.cluetoothscanner.service.LocationPort
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingController
import edu.ucsd.sysnet.cluetoothscanner.service.ScanSupervisor
import edu.ucsd.sysnet.cluetoothscanner.service.ScannerPort
import edu.ucsd.sysnet.cluetoothscanner.service.ScanLifecycleEvent
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionCoordinator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CluetoothApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: CluetoothRepository
        private set

    /** Latest advertisement per address; the scanner writes it, GATT discovery reads it. */
    val triggerCache = AdvertisementTriggerCache()

    /** Process-wide scanner and location, owned here so a recording outlives the Activity. */
    lateinit var locationService: LocationService
        private set
    lateinit var bleScanService: BleScanService
        private set

    /** Decides when the scanner runs; the Activity reports foreground/background to it. */
    lateinit var scanSupervisor: ScanSupervisor
        private set

    /** The recording API (state plus start/pause/resume/stop); see [RecordingController]. */
    lateinit var recordingController: RecordingController
        private set

    /** Process-wide, so there is one GATT connection at a time whatever the UI does. */
    lateinit var gattManager: GattDiscoveryManager
        private set
    private lateinit var localDataHub: LocalDataHub

    /** The phone's local stores: seen products, recordings, sighting history. See [LocalData]. */
    val localData: LocalData get() = localDataHub

    override fun onCreate() {
        super.onCreate()
        val coreDirectory = File(filesDir, "cluetooth-core")
        val uploadService = UploadService(this)
        localDataHub = LocalDataHub(
            openDatabase = { AndroidSqlDatabase.open(this) },
            loadBundleJson = { assets.open(WIKI_BUNDLE_ASSET).bufferedReader().use { it.readText() } },
            createMatcher = NativeAdvertisementMatcher::fromBundleJson,
            scope = applicationScope,
        ).also { it.start() }
        repository = CluetoothRepository(
            gatewayFactory = { NativeCoreGateway.open(coreDirectory.absolutePath) },
            parentScope = applicationScope,
            onUploadNeeded = {
                uploadService.autoUploadNow()
                uploadService.autoGattUploadNow()
            },
            localData = localDataHub,
        )
        gattManager = GattDiscoveryManager(
            scope = applicationScope,
            store = RepositoryGattStore(repository),
            pending = FilePendingDiscoveryStore(File(filesDir, "gatt-pending")),
            device = gattDeviceInfo(),
            elapsedMs = SystemClock::elapsedRealtime,
        )
        // A discovery cut short by process death counts as failed; store it now.
        applicationScope.launch { gattManager.recoverStale() }
        locationService = LocationService(this, repository)
        bleScanService = BleScanService(this, locationService, repository, triggerCache)
        // Live scanning (foreground) and recording (session + rows) are separate; the supervisor
        // decides when the scanner runs and when rows are written.
        val supervisor = ScanSupervisor(
            scanner = object : ScannerPort {
                override fun start() = bleScanService.startScanning()
                override suspend fun stopAndWait() = bleScanService.stopScanningAndWait()
                override fun isScanning() = bleScanService.isScanning()
                override fun setRowWriting(enabled: Boolean) = bleScanService.setRowWriting(enabled)
                override suspend fun fence() = bleScanService.fenceCallbacks()
            },
            location = object : LocationPort {
                override fun start() = locationService.startLocationUpdates()
                override fun stop() = locationService.stopLocationUpdates()
            },
            scope = applicationScope,
        )
        scanSupervisor = supervisor
        val coordinator = ScanTransitionCoordinator(
            startSession = repository::startScanSessionAndWait,
            startScanner = supervisor::beginRowWriting,
            stopScannerAndFence = supervisor::endRowWriting,
            finishSession = repository::finishScanSessionAndWait,
            onRequestRejected = { recordingController.wantsRecording = false },
        )
        recordingController = RecordingController(
            coordinator = coordinator,
            observationCount = repository.uiState
                .map { it.totalObservations.toLong() }
                .stateIn(applicationScope, SharingStarted.Eagerly, 0L),
            host = AndroidRecordingHost(this),
            scope = applicationScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
        )
        bleScanService.setScanLifecycleListener { event ->
            applicationScope.launch {
                when (event) {
                    ScanLifecycleEvent.DEFERRED_INTENT_RETAINED -> coordinator.scannerDeferred()
                    ScanLifecycleEvent.RECOVERY_AVAILABLE ->
                        // A recording owns its own recovery; otherwise live scanning resumes.
                        if (!coordinator.resumeDeferred()) supervisor.liveRecoveryAvailable()
                    ScanLifecycleEvent.REJECTED -> coordinator.scannerRejected()
                }
            }
        }
        // If the process died during a recording, say so once at the next launch.
        val interruptedNotifier = InterruptedRecordingNotifier(this)
        applicationScope.launch {
            val ready = repository.uiState.first { it.coreReady }
            interruptedNotifier.check(ready.sessions)
        }
        uploadService.startPeriodicUpload()
        uploadService.autoUploadNow()
        uploadService.autoGattUploadNow()
    }

    private companion object {
        const val WIKI_BUNDLE_ASSET = "wiki-bundle.json"
    }
}
