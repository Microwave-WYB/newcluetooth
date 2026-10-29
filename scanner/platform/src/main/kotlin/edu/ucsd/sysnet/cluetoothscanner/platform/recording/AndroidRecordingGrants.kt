package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions

/** The permissions a recording needs before its foreground service may start (§1.2.3). */
fun recordingPermissions(sdkInt: Int): List<String> =
    Permissions.nearby(sdkInt).toList() + Manifest.permission.ACCESS_FINE_LOCATION

/** [RecordingGrants] over `checkSelfPermission`. */
class AndroidRecordingGrants(private val context: Context) : RecordingGrants {
    override fun missing(): List<String> = recordingPermissions(Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
