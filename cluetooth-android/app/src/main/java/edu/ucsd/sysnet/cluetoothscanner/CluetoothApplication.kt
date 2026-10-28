package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTriggerCache
import edu.ucsd.sysnet.cluetoothscanner.core.NativeCoreGateway
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDiscoveryManager
import edu.ucsd.sysnet.cluetoothscanner.gatt.PrefsPendingDiscoveryStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.RepositoryGattStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.gattDeviceInfo
import edu.ucsd.sysnet.cluetoothscanner.local.AndroidSqlDatabase
import edu.ucsd.sysnet.cluetoothscanner.local.LocalData
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataHub
import edu.ucsd.sysnet.cluetoothscanner.local.NativeAdvertisementMatcher
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CluetoothApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: CluetoothRepository
        private set

    /** Latest advertisement per address; the scanner writes it, GATT discovery reads it. */
    val triggerCache = AdvertisementTriggerCache()

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
            onUploadNeeded = { uploadService.autoUploadNow() },
            localData = localDataHub,
        )
        gattManager = GattDiscoveryManager(
            scope = applicationScope,
            store = RepositoryGattStore(repository),
            pending = PrefsPendingDiscoveryStore(this),
            device = gattDeviceInfo(),
        )
        // A discovery cut short by process death counts as failed; store it now.
        applicationScope.launch { gattManager.recoverStale() }
        uploadService.startPeriodicUpload()
        uploadService.autoUploadNow()
    }

    private companion object {
        const val WIKI_BUNDLE_ASSET = "wiki-bundle.json"
    }
}
