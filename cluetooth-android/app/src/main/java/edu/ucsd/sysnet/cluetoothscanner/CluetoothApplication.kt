package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import edu.ucsd.sysnet.cluetoothscanner.core.NativeCoreGateway
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

    override fun onCreate() {
        super.onCreate()
        val coreDirectory = File(filesDir, "cluetooth-core")
        val uploadService = UploadService(this)
        repository = CluetoothRepository(
            gatewayFactory = { NativeCoreGateway.open(coreDirectory.absolutePath) },
            parentScope = applicationScope,
            onUploadNeeded = { uploadService.autoUploadNow() },
        )
        uploadService.startPeriodicUpload()
        uploadService.autoUploadNow()
    }
}
