package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.ExportShare
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.api.platform.SettingsStore
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [SettingsStore] starting from [Settings.defaults]. */
class FakeSettings(initial: Settings = Settings.defaults(appVersion = "0.1.0-fake")) : SettingsStore {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    override suspend fun update(transform: (Settings) -> Settings) = _settings.update(transform)
}

/** [Uploads] whose [uploadNow] uploads everything at once (O-1 "Pending uploads"). */
class FakeUploads(initial: UploadStatus = UploadStatus(pendingCount = 3, pendingBytes = 2_400_000, failedCount = 0, lastError = null)) :
    Uploads {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<UploadStatus> = _status.asStateFlow()

    var uploadNowCalls = 0
        private set

    override fun uploadNow() {
        uploadNowCalls++
        _status.value = UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null)
    }

    fun set(status: UploadStatus) {
        _status.value = status
    }
}

/** [ExportShare] that records what it was handed (O-1 item 10). */
class FakeExportShare : ExportShare {
    val shared = mutableListOf<PreparedExport>()
    val saved = mutableListOf<PreparedExport>()

    override fun share(export: PreparedExport) {
        shared += export
    }

    override suspend fun saveToDownloads(export: PreparedExport) {
        saved += export
    }
}
