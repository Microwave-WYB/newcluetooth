package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.ExportShare
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.api.platform.SettingsStore
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Uploads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [SettingsStore] starting from [Settings.defaults]; it refuses an invalid user id like the real one. */
class FakeSettings(initial: Settings = Settings.defaults(appVersion = "0.1.0-fake")) : SettingsStore {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    override suspend fun update(transform: (Settings) -> Settings): PlatformResult<Unit> {
        while (true) {
            val current = _settings.value
            val next = transform(current)
            val userId = next.userId
            if (userId != null && !Settings.isValidUserId(userId)) return PlatformResult.Err(PlatformError.InvalidUserId(userId))
            if (_settings.compareAndSet(current, next)) return PlatformResult.Ok(Unit)
        }
    }
}

/** [Uploads] whose [uploadNow] uploads everything at once (O-1 "Pending uploads"). */
class FakeUploads(initial: UploadStatus = UploadStatus(pendingCount = 3, pendingBytes = 2_400_000, failedCount = 0, lastError = null)) :
    Uploads {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<UploadStatus> = _status.asStateFlow()

    var uploadNowCalls = 0
        private set

    override fun uploadNow(): PlatformResult<Unit> {
        uploadNowCalls++
        _status.value = UploadStatus(pendingCount = 0, pendingBytes = 0, failedCount = 0, lastError = null)
        return PlatformResult.Ok(Unit)
    }

    fun set(status: UploadStatus) {
        _status.value = status
    }
}

/** [ExportShare] that records what it was handed (O-1 item 10). */
class FakeExportShare : ExportShare {
    val shared = mutableListOf<PreparedExport>()
    val saved = mutableListOf<PreparedExport>()

    override fun share(export: PreparedExport): PlatformResult<Unit> {
        shared += export
        return PlatformResult.Ok(Unit)
    }

    override suspend fun saveToDownloads(export: PreparedExport): PlatformResult<Unit> {
        saved += export
        return PlatformResult.Ok(Unit)
    }
}
