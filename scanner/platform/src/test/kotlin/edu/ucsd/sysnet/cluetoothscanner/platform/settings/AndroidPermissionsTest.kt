package edu.ucsd.sysnet.cluetoothscanner.platform.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPermissionsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val nearby = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION

    @Test
    fun `O-2 state follows the grants and Bluetooth`() {
        shadowOf(context).grantPermissions(*nearby)
        shadowOf(adapter).setEnabled(false)
        val permissions = AndroidPermissions(context)
        val state = permissions.state.value
        assertEquals(Perm.Granted, state.nearby)
        assertEquals(Perm.Denied, state.location)
        assertEquals(Perm.Denied, state.notifications)
        assertFalse(state.bluetoothOn)

        shadowOf(adapter).setEnabled(true)
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("O-2: Bluetooth turned back on is noticed", permissions.state.value.bluetoothOn)
    }

    @Test
    fun `O-2 groups the API level does not need are NotRequired`() {
        val state = AndroidPermissions(context, sdkInt = 30).state.value
        assertEquals(Perm.NotRequired, state.nearby)
        assertEquals(Perm.NotRequired, state.notifications)
    }

    @Test
    fun `O-2 an asked and refused group without a rationale is PermanentlyDenied`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val permissions = AndroidPermissions(context)
        permissions.refresh(activity)
        assertEquals("never asked: just Denied", Perm.Denied, permissions.state.value.location)

        permissions.requestLauncherContract().createIntent(activity, arrayOf(fine))
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(fine, false)
        permissions.refresh(activity)
        assertEquals(Perm.PermanentlyDenied, permissions.state.value.location)

        shadowOf(context).grantPermissions(fine)
        permissions.refresh(activity)
        assertEquals(Perm.Granted, permissions.state.value.location)
    }

    @Test
    fun `an empty request asks for every missing permission`() {
        shadowOf(context).grantPermissions(*nearby)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val permissions = AndroidPermissions(context)
        val intent = permissions.requestLauncherContract().createIntent(activity, emptyArray())
        val asked = intent.getStringArrayExtra("androidx.activity.result.contract.extra.PERMISSIONS")!!
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
            asked,
        )
    }

    @Test
    fun `O-2 step 2 asks Android to turn Bluetooth on`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        AndroidPermissions(context).requestEnableBluetooth(activity)
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, shadowOf(activity).nextStartedActivity.action)
    }
}
