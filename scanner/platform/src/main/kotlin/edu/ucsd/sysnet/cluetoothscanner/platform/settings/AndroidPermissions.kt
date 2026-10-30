package edu.ucsd.sysnet.cluetoothscanner.platform.settings

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SPEC O-2 and its "only the missing piece" rule, over the Android permission APIs.
 *
 * - [state] is refreshed after every request, on every Bluetooth state change and on [refresh] (call it when the UI
 *   starts, so a permission revoked in system settings shows up).
 * - A group is [Perm.PermanentlyDenied] once it was asked for, is still denied, and Android would no longer show the
 *   dialog (`shouldShowRequestPermissionRationale` is false); the UI then offers "Open settings" ([openAppSettings]).
 */
class AndroidPermissions(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : Permissions {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(compute(null))
    override val state: StateFlow<PermissionState> = _state.asStateFlow()

    /** The activity of the last request, for the rationale check (weak: the UI is disposable). */
    private var lastActivity = WeakReference<Activity>(null)

    init {
        runCatching {
            ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) = refresh()
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        }
    }

    /** Re-reads every grant and Bluetooth; [activity] enables the permanently-denied check. */
    fun refresh(activity: Activity? = lastActivity.get()) {
        activity?.let { lastActivity = WeakReference(it) }
        // A failing grant read keeps the last known state rather than crashing the caller.
        runCatching { compute(activity) }.onSuccess { _state.value = it }
    }

    override fun requestLauncherContract(): ActivityResultContract<Array<String>, Map<String, Boolean>> =
        object : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
            private val delegate = ActivityResultContracts.RequestMultiplePermissions()
            private var requested = emptyArray<String>()

            override fun createIntent(context: Context, input: Array<String>): Intent {
                (context as? Activity)?.let { lastActivity = WeakReference(it) }
                requested = input.ifEmpty { missing() }
                markAsked(requested)
                return delegate.createIntent(context, requested)
            }

            override fun getSynchronousResult(context: Context, input: Array<String>) =
                delegate.getSynchronousResult(context, input.ifEmpty { missing() })

            override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> {
                val result = delegate.parseResult(resultCode, intent)
                refresh()
                return result
            }
        }

    override fun openAppSettings(): PlatformResult<Unit> = launch {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** O-2 step 2: the system enable dialog; without `BLUETOOTH_CONNECT` (API 31+) the Bluetooth settings instead. */
    override fun requestEnableBluetooth(activity: ComponentActivity): PlatformResult<Unit> {
        lastActivity = WeakReference(activity)
        return when (val asked = launch { activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
            is PlatformResult.Ok -> asked
            is PlatformResult.Err -> launch { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    /** An activity launch: no activity for the intent, or a refusal, is [PlatformError.Unavailable], never a throw. */
    private fun launch(start: () -> Unit): PlatformResult<Unit> = try {
        start()
        PlatformResult.Ok(Unit)
    } catch (failure: RuntimeException) {
        PlatformResult.Err(PlatformError.Unavailable(failure.message ?: failure::class.java.simpleName))
    }

    /** Every permission of the three O-2 groups that is not granted. */
    fun missing(): Array<String> =
        (Permissions.nearby(sdkInt) + Permissions.location() + Permissions.notifications(sdkInt))
            .filterNot(::granted)
            .toTypedArray()

    private fun compute(activity: Activity?) = PermissionState(
        nearby = perm(Permissions.nearby(sdkInt), activity),
        location = perm(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), activity),
        notifications = perm(Permissions.notifications(sdkInt), activity),
        bluetoothOn = bluetoothOn(),
    )

    private fun perm(names: Array<String>, activity: Activity?): Perm {
        if (names.isEmpty()) return Perm.NotRequired
        if (names.all(::granted)) return Perm.Granted
        val denied = names.filterNot(::granted)
        val permanently = activity != null && denied.all { asked(it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        return if (permanently) Perm.PermanentlyDenied else Perm.Denied
    }

    private fun granted(name: String) =
        runCatching { ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun bluetoothOn() = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun asked(name: String) = prefs.getBoolean(name, false)

    private fun markAsked(names: Array<String>) {
        prefs.edit().apply { names.forEach { putBoolean(it, true) } }.apply()
    }

    private companion object {
        const val PREFS = "cluetooth_permissions_asked"
    }
}
