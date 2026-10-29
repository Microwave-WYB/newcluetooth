package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.PendingUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformRecorderTest {
    private val log = mutableListOf<String>()

    private inner class Core : CoreIngest {
        var phase = RecordingPhase.Idle
        var session: String? = null
        var sessions = 0
        var activeBeforeMs = 0L
        var runningSinceMs: Long? = null
        var failStart: Exception? = null
        var failStop: Exception? = null

        override fun startRecording(env: ScanEnvironment): String {
            log += "core.start"
            failStart?.let { throw it }
            if (phase != RecordingPhase.Idle) throw CoreException.AlreadyRecording()
            phase = RecordingPhase.Recording
            session = "s${++sessions}"
            runningSinceMs = 1_000
            return session!!
        }

        override fun pauseRecording() {
            log += "core.pause"
            phase = RecordingPhase.Paused
            activeBeforeMs += 5_000
            runningSinceMs = null
        }

        override fun resumeRecording() {
            log += "core.resume"
            phase = RecordingPhase.Recording
            runningSinceMs = 9_000
        }

        override fun stopRecording(): String {
            log += "core.stop"
            failStop?.let { throw it }
            phase = RecordingPhase.Idle
            return session!!
        }

        override fun recordingState() = CoreRecordingState(
            phase, if (phase == RecordingPhase.Idle) null else session, 1_000, activeBeforeMs, runningSinceMs,
            deviceCount = 26, distanceM = 0.0,
        )

        override fun ingestScans(batch: List<ScanObservationInput>) = IngestAck(batch.size, 0, 0)
        override fun ingestLocation(fix: LocationFix) = Unit
        override fun clearLocation() = Unit
        override fun newGattDiscoveryId() = error("unused")
        override suspend fun gattTarget(key: DeviceKey): GattTarget? = error("unused")
        override fun ingestGatt(gattV1Json: String): GattView = error("unused")
        override fun configureScan(env: ScanEnvironment) = Unit
        override fun setDisplayFilter(filter: DisplayFilter) = Unit
        override fun uploadStatus() = UploadStatus(0, 0, 0, null)
        override fun pendingUploads() = emptyList<PendingUpload>()
        override fun prepareUpload(id: String): PreparedUpload = error("unused")
        override fun markUploadSucceeded(id: String) = Unit
        override fun markUploadFailed(id: String, message: String) = Unit
    }

    private val core = Core()
    private val deferred = MutableStateFlow(false)
    private var missing = emptyList<String>()
    private var foregroundAllowed = true
    private var inForeground = false

    private val scanner = object : RecordingScanner {
        override val deferred = this@PlatformRecorderTest.deferred
        override val dropped = MutableStateFlow(0L)
        override fun start() {
            log += "scanner.start"
        }

        override suspend fun stopAndDrain() {
            log += "scanner.drain"
        }
    }
    private val location = object : RecordingLocation {
        override fun startRecording() {
            log += "location.start"
        }

        override fun stopRecording() {
            log += "location.stop"
        }
    }
    private val host = object : ForegroundHost {
        override suspend fun enterForeground(): Boolean {
            log += "host.enter"
            inForeground = foregroundAllowed
            return foregroundAllowed
        }

        override fun leaveForeground() {
            log += "host.leave"
            inForeground = false
        }
    }

    private fun TestScope.recorder(coreStates: MutableSharedFlow<CoreRecordingState>? = null) = PlatformRecorder(
        core = CompletableDeferred<CoreIngest>(core),
        scanner = scanner,
        location = location,
        host = host,
        grants = { missing },
        environment = { ScanEnvironment("m", "15", 35, "0.1.0", true, true, 1650, "all_supported", false) },
        io = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
        coreStates = coreStates,
    ).also { runCurrent() }

    @Test
    fun `section 1_2_3 grants are checked before the service starts, and a missing one gives an error and no session`() = runTest {
        missing = listOf("android.permission.BLUETOOTH_SCAN")
        val recorder = recorder()
        val result = recorder.start()
        assertTrue(result.isFailure)
        assertTrue(log.isEmpty())
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertNotNull(recorder.state.value.error)
        assertEquals(0, core.sessions)
    }

    @Test
    fun `a refused foreground service creates no session`() = runTest {
        foregroundAllowed = false
        val recorder = recorder()
        assertTrue(recorder.start().isFailure)
        assertEquals(listOf("host.enter"), log)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertEquals(PlatformRecorder.SERVICE_REFUSED, recorder.state.value.error)
        assertEquals(0, core.sessions)
    }

    @Test
    fun `R-2 start enters the foreground, then opens the session, then collects`() = runTest {
        val recorder = recorder()
        assertEquals("s1", recorder.start().getOrThrow())
        assertEquals(listOf("host.enter", "core.start", "location.start", "scanner.start"), log)
        val state = recorder.state.value
        assertEquals(Phase.Recording, state.phase)
        assertEquals("s1", state.sessionId)
        assertEquals(1_000L, state.runningSinceMs)
        assertEquals(26, state.deviceCount)
        assertNull(state.error)
        // A second start is the same recording.
        assertEquals("s1", recorder.start().getOrThrow())
        assertEquals(1, core.sessions)
    }

    @Test
    fun `a core failure at start leaves the foreground and stays idle`() = runTest {
        core.failStart = CoreException.Io("disk full")
        val recorder = recorder()
        assertTrue(recorder.start().isFailure)
        assertEquals(listOf("host.enter", "core.start", "host.leave"), log)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertFalse(inForeground)
    }

    @Test
    fun `R-6 pause drains the scanner first, stops location and keeps the foreground service`() = runTest {
        val recorder = recorder()
        recorder.start()
        log.clear()
        recorder.pause()
        assertEquals(listOf("scanner.drain", "core.pause", "location.stop"), log)
        assertTrue("pause keeps the foreground service", inForeground)
        val state = recorder.state.value
        assertEquals(Phase.Paused, state.phase)
        assertEquals("paused time is excluded: the core's active time", 5_000L, state.activeBeforeMs)
        assertNull(state.runningSinceMs)
    }

    @Test
    fun `R-6 resume continues the same session`() = runTest {
        val recorder = recorder()
        recorder.start()
        recorder.pause()
        log.clear()
        recorder.resume()
        assertEquals(listOf("core.resume", "location.start", "scanner.start"), log)
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertEquals("s1", recorder.state.value.sessionId)
        assertEquals(9_000L, recorder.state.value.runningSinceMs)
        assertTrue(inForeground)
    }

    @Test
    fun `R-7 stop drains before the core seals, then leaves the foreground and keeps the session id`() = runTest {
        val recorder = recorder()
        recorder.start()
        log.clear()
        assertEquals("s1", recorder.stop().getOrThrow())
        assertEquals(listOf("scanner.drain", "core.stop", "location.stop", "host.leave"), log)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertEquals("V-3 stays on the ended session", "s1", recorder.state.value.sessionId)
        assertFalse(inForeground)
    }

    @Test
    fun `stop while paused does not drain a stopped scanner`() = runTest {
        val recorder = recorder()
        recorder.start()
        recorder.pause()
        log.clear()
        recorder.stop()
        assertEquals(listOf("core.stop", "location.stop", "host.leave"), log)
    }

    @Test
    fun `a failed stop with the session still open keeps recording`() = runTest {
        core.failStop = CoreException.Io("seal failed")
        val recorder = recorder()
        recorder.start()
        assertTrue(recorder.stop().isFailure)
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertNotNull(recorder.state.value.error)
        assertTrue(inForeground)
        assertEquals("scanner.start", log.last())
    }

    @Test
    fun `scanner deferral and core device counts flow into the state`() = runTest {
        val coreStates = MutableSharedFlow<CoreRecordingState>()
        val recorder = recorder(coreStates)
        recorder.start()
        deferred.value = true
        runCurrent()
        assertTrue(recorder.state.value.scannerDeferred)
        coreStates.emit(core.recordingState().copy(deviceCount = 40))
        runCurrent()
        assertEquals(40, recorder.state.value.deviceCount)
    }

    @Test
    fun `restore adopts nothing after Core open closed the session`() = runTest {
        val recorder = recorder()
        recorder.restore()
        assertEquals(Phase.Idle, recorder.state.value.phase)
    }
}
