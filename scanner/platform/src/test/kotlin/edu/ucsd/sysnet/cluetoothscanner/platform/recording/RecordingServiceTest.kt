package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {
    private val state = MutableStateFlow(RecorderState.Idle.copy(phase = Phase.Starting))
    private var pauses = 0
    private var stops = 0

    private val recorder = object : Recorder {
        override val state = this@RecordingServiceTest.state
        override suspend fun start() = PlatformResult.Ok("s1")
        override suspend fun pause(): PlatformResult<Unit> {
            pauses++
            return PlatformResult.Ok(Unit)
        }

        override suspend fun resume() = PlatformResult.Ok(Unit)
        override suspend fun stop(): PlatformResult<String> {
            stops++
            return PlatformResult.Ok("s1")
        }
    }

    @Before
    fun link() {
        RecordingServiceLink.recorder = recorder
        RecordingServiceLink.foreground = CompletableDeferred()
    }

    @After
    fun unlink() {
        RecordingServiceLink.recorder = null
        RecordingServiceLink.foreground = null
    }

    private fun started() = Robolectric.buildService(
        RecordingService::class.java,
        Intent(ApplicationProvider.getApplicationContext<Application>(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_START),
    ).create().startCommand(0, 1).get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `start enters the foreground at once and confirms it`() {
        val service = started()
        assertTrue(RecordingServiceLink.foreground!!.isCompleted)
        assertTrue(RecordingServiceLink.foreground!!.getCompleted())
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `pause keeps the foreground service and releases the wake lock`() {
        val service = started()
        state.value = state.value.copy(phase = Phase.Recording, sessionId = "s1", runningSinceMs = 0)
        idle()
        assertTrue(service.holdsWakeLock)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)

        state.value = state.value.copy(phase = Phase.Paused, runningSinceMs = null)
        idle()
        assertFalse("pause keeps the foreground service", shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertFalse(service.holdsWakeLock)
    }

    @Test
    fun `Bluetooth off releases the wake lock while recording`() {
        val service = started()
        state.value = state.value.copy(phase = Phase.Recording, sessionId = "s1", runningSinceMs = 0, scannerDeferred = true)
        idle()
        assertFalse(service.holdsWakeLock)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `idle removes the notification and stops the service`() {
        val service = started()
        state.value = state.value.copy(phase = Phase.Recording, sessionId = "s1", runningSinceMs = 0)
        idle()
        state.value = RecorderState.Idle.copy(sessionId = "s1")
        idle()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(service.holdsWakeLock)
    }

    @Test
    fun `notification actions reach the recorder`() {
        val service = started()
        state.value = state.value.copy(phase = Phase.Recording, sessionId = "s1", runningSinceMs = 0)
        idle()
        service.onStartCommand(Intent(service, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE), 0, 2)
        service.onStartCommand(Intent(service, RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 3)
        idle()
        assertEquals(1, pauses)
        assertEquals(1, stops)
    }

    @Test
    fun `a stale action with no recording stops without entering the foreground`() {
        RecordingServiceLink.recorder = null
        val service = Robolectric.buildService(
            RecordingService::class.java,
            Intent(ApplicationProvider.getApplicationContext<Application>(), RecordingService::class.java)
                .setAction(RecordingService.ACTION_STOP),
        ).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(null, shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `with no recorder linked, a start is refused and the service leaves at once`() {
        RecordingServiceLink.recorder = null
        val service = started()
        assertTrue(RecordingServiceLink.foreground!!.isCompleted)
        assertFalse("no recorder: no confirmation, so no session starts", RecordingServiceLink.foreground!!.getCompleted())
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the wake lock is acquired only on entering a collecting state, so its 12 h bound holds`() {
        val service = started()
        state.value = state.value.copy(phase = Phase.Recording, sessionId = "s1", runningSinceMs = 0)
        idle()
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue(first.isHeld)
        // The bound expires (simulated): later updates of the same recording must not renew it.
        first.release()
        for (count in 1..5) {
            state.value = state.value.copy(deviceCount = count)
            idle()
        }
        assertFalse(service.holdsWakeLock)
        assertSame(first, ShadowPowerManager.getLatestWakeLock())
        // A resume is a new entry: it acquires again.
        state.value = state.value.copy(phase = Phase.Paused, runningSinceMs = null)
        idle()
        state.value = state.value.copy(phase = Phase.Recording, runningSinceMs = 10)
        idle()
        assertTrue(service.holdsWakeLock)
    }
}
