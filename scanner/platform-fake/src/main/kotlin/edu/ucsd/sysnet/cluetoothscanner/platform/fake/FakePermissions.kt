package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [Permissions] without system dialogs: a request grants what it asks for at once (a synchronous result, so no
 * Activity is ever launched), and enabling Bluetooth turns it on. [set] plays revoked or permanently denied states.
 */
class FakePermissions(
    initial: PermissionState = PermissionState(Perm.Denied, Perm.Denied, Perm.Denied, bluetoothOn = false),
) : Permissions {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PermissionState> = _state.asStateFlow()

    var openAppSettingsCalls = 0
        private set

    fun set(state: PermissionState) {
        _state.value = state
    }

    override fun requestLauncherContract(): ActivityResultContract<Array<String>, Map<String, Boolean>> =
        object : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
            override fun createIntent(context: Context, input: Array<String>) = Intent()

            override fun parseResult(resultCode: Int, intent: Intent?) = emptyMap<String, Boolean>()

            override fun getSynchronousResult(context: Context, input: Array<String>) =
                SynchronousResult(grant(input))
        }

    /** What a request of [names] does; an empty array grants every field that is not already granted. */
    fun grant(names: Array<String>): Map<String, Boolean> {
        val all = names.isEmpty()
        val nearby = all || names.any { it in NEARBY }
        val location = all || names.any { it in LOCATION }
        val notifications = all || Manifest.permission.POST_NOTIFICATIONS in names
        _state.update {
            it.copy(
                nearby = if (nearby) it.nearby.granted() else it.nearby,
                location = if (location) it.location.granted() else it.location,
                notifications = if (notifications) it.notifications.granted() else it.notifications,
            )
        }
        return names.associateWith { true }
    }

    override fun openAppSettings(): PlatformResult<Unit> {
        openAppSettingsCalls++
        return PlatformResult.Ok(Unit)
    }

    override fun requestEnableBluetooth(activity: ComponentActivity): PlatformResult<Unit> {
        _state.update { it.copy(bluetoothOn = true) }
        return PlatformResult.Ok(Unit)
    }

    private fun Perm.granted() = if (this == Perm.NotRequired) this else Perm.Granted

    private companion object {
        val NEARBY = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val LOCATION = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
