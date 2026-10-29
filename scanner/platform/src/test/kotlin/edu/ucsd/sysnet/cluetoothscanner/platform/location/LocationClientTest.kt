package edu.ucsd.sysnet.cluetoothscanner.platform.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationClientTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fixes = mutableListOf<LocationFix>()
    private var clears = 0
    private val core = object : TestCoreIngest() {
        override fun ingestLocation(fix: LocationFix) {
            fixes += fix
        }

        override fun clearLocation() {
            clears++
        }
    }
    private val uiStarted = MutableStateFlow(false)

    @After
    fun cancel() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun client() = LocationClient(context, CompletableDeferred<CoreIngest>(core), scope, uiStarted).also { idle() }

    private fun location(lat: Double, accuracy: Float? = 5f, provider: String = LocationManager.GPS_PROVIDER) = Location(provider).apply {
        latitude = lat
        longitude = -117.0
        accuracy?.let { this.accuracy = it }
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun grant() = shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun gpsOn() = shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

    @Test
    fun `P-5 Denied without the permission`() {
        gpsOn()
        val client = client()
        uiStarted.value = true
        idle()
        assertEquals(State.Denied, client.status.value.state)
        assertTrue(client.status.value.warn)
    }

    @Test
    fun `M-9 FOREGROUND fixes feed myLocation and the status, never the core`() {
        grant()
        gpsOn()
        val client = client()
        uiStarted.value = true
        idle()
        assertEquals(State.Waiting, client.status.value.state)
        // FOREGROUND is balanced: the network provider.
        shadowOf(manager).simulateLocation(location(32.88, provider = LocationManager.NETWORK_PROVIDER))
        idle()
        assertEquals(State.Ok, client.status.value.state)
        assertEquals(32.88, client.myLocation.value!!.at.lat, 0.0)
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `section 4_2 RECORDING fixes go to the core, and no accuracy or stop clears it`() {
        grant()
        gpsOn()
        val client = client()
        client.startRecording()
        idle()
        shadowOf(manager).simulateLocation(location(32.1))
        idle()
        assertEquals(1, fixes.size)
        assertEquals(5.0, fixes.single().accuracyMeters, 0.0)
        SystemClock.sleep(2_100) // past the 2 s RECORDING interval
        shadowOf(manager).simulateLocation(location(32.2, accuracy = null))
        idle()
        assertEquals(1, fixes.size)
        assertEquals(1, clears)
        client.stopRecording()
        idle()
        assertEquals(2, clears)
        assertNull("no request: no location", client.myLocation.value)
    }

    @Test
    fun `P-5 the provider turned off is Off`() {
        grant()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val client = client()
        uiStarted.value = true
        idle()
        assertEquals(State.Off, client.status.value.state)
        assertNotNull(client.status.value.action)
    }

    @Test
    fun `P-5 Turn on without Play services opens the system location settings`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        client().turnOn(activity)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }
}
