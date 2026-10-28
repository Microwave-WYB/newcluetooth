package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import edu.ucsd.sysnet.cluetoothscanner.core.NativeCoreGateway
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

class CluetoothApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: CluetoothRepository
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
        uploadService.startPeriodicUpload()
        uploadService.autoUploadNow()
    }

    private companion object {
        const val WIKI_BUNDLE_ASSET = "wiki-bundle.json"
    }
}
