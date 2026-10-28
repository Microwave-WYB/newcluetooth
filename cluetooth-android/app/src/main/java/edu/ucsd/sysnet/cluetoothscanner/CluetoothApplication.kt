package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import android.os.SystemClock
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTriggerCache
import edu.ucsd.sysnet.cluetoothscanner.core.NativeCoreGateway
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDiscoveryManager
import edu.ucsd.sysnet.cluetoothscanner.gatt.FilePendingDiscoveryStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.RepositoryGattStore
import edu.ucsd.sysnet.cluetoothscanner.gatt.gattDeviceInfo
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

    override fun onCreate() {
        super.onCreate()
        val coreDirectory = File(filesDir, "cluetooth-core")
        val uploadService = UploadService(this)
        repository = CluetoothRepository(
            gatewayFactory = { NativeCoreGateway.open(coreDirectory.absolutePath) },
            parentScope = applicationScope,
            onUploadNeeded = {
                uploadService.autoUploadNow()
                uploadService.autoGattUploadNow()
            },
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
        uploadService.startPeriodicUpload()
        uploadService.autoUploadNow()
        uploadService.autoGattUploadNow()
    }
}
