package edu.ucsd.sysnet.cluetoothscanner.gatt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.BuildConfig
import edu.ucsd.sysnet.cluetoothscanner.ble.AdvertisementTriggerCache
import edu.ucsd.sysnet.cluetoothscanner.repository.CluetoothRepository

/** [GattStore] over the process-wide core repository. */
class RepositoryGattStore(private val repository: CluetoothRepository) : GattStore {
    override suspend fun newDiscoveryId(): String = repository.newGattDiscoveryId()
    override suspend fun save(json: String) = repository.saveGattDiscovery(json)
}

/** Keeps the in-flight / unsaved discovery JSON in app-private preferences. */
class PrefsPendingDiscoveryStore(context: Context) : PendingDiscoveryStore {
    private val prefs = context.applicationContext.getSharedPreferences("cluetooth_gatt", Context.MODE_PRIVATE)

    // commit(): the slot exists for process death, so it must be on disk when this returns.
    override fun put(json: String) {
        prefs.edit().putString(KEY, json).commit()
    }

    override fun peek(): String? = prefs.getString(KEY, null)
    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "pending_discovery_json"
    }
}

fun gattDeviceInfo() = GattDeviceInfo(
    appVersion = BuildConfig.VERSION_NAME,
    deviceModel = Build.MODEL.ifBlank { "unknown" },
    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
)

sealed interface GattStartResult {
    data object Started : GattStartResult
    data object Busy : GattStartResult
    data object PermissionMissing : GattStartResult
    data object BluetoothUnavailable : GattStartResult
}

/**
 * Turns "the operator tapped GATT for this address" into a [GattStartRequest]: the latest
 * advertisement seen for it (also giving the exact device object, which matters for random
 * addresses), the active scan session and the last location fix.
 */
@SuppressLint("MissingPermission")
fun startGattDiscovery(
    context: Context,
    manager: GattDiscoveryManager,
    triggers: AdvertisementTriggerCache,
    repository: CluetoothRepository,
    addr: String,
    location: Location?,
): GattStartResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return GattStartResult.PermissionMissing
    }
    val trigger = triggers.latest(addr)
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val bluetoothDevice = trigger?.device
        ?: runCatching { adapter?.getRemoteDevice(addr) }.getOrNull()
        ?: return GattStartResult.BluetoothUnavailable
    val target = GattTarget(
        addr = addr,
        addrType = when (trigger?.addrType) {
            0 -> "public"
            1 -> "random"
            else -> null
        },
        name = trigger?.name,
        triggerAdv = trigger?.let {
            GattTriggerAdv(
                raw = it.raw,
                rssi = it.rssi,
                scannedAtMs = it.scannedAtMs,
                isLegacy = it.fields.isLegacy,
                primaryPhy = it.fields.primaryPhy,
                secondaryPhy = it.fields.secondaryPhy,
                advSid = it.fields.advSid,
            )
        },
    )
    val started = manager.start(
        GattStartRequest(
            target = target,
            sessionId = repository.activeSessionId(),
            location = location?.let {
                GattLocation(it.latitude, it.longitude, it.accuracy.toDouble())
            },
            linkFactory = AndroidGattLinkFactory(context.applicationContext, bluetoothDevice),
        ),
    )
    return if (started) GattStartResult.Started else GattStartResult.Busy
}
