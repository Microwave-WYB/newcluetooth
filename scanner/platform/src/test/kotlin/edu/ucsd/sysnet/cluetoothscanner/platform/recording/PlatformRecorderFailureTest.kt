package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #72 lesson for the recorder's runner: a platform or core call that throws (anything, not only a CoreException)
 * becomes a typed failure. runTest fails on any uncaught exception, so these pass only if nothing escapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformRecorderFailureTest {
    private var hostThrows = false
    private var locationThrows = false
    private var foreground = false
    private var drainFails = false
    private var readThrows = false
    private var scannerStarts = 0
    private var drains = 0
    private val storageFull = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    private val core = object : TestCoreIngest() {
        var phase = RecordingPhase.Idle
        override fun startRecording(env: ScanEnvironment): String {
            phase = RecordingPhase.Recording
            return "s1"
        }

        var full = false
        override fun resumeRecording() {
            if (full) throw IllegalStateException("storage is full")
            phase = RecordingPhase.Recording
        }

        override fun stopRecording(): String {
            phase = RecordingPhase.Idle
            return "s1"
        }

        override fun recordingState(): CoreRecordingState {
            if (readThrows) throw IllegalStateException("state unreadable")
            return CoreRecordingState(phase, if (phase == RecordingPhase.Idle) null else "s1", 0, 0, 0, 1, 0.0)
        }
    }

    private fun TestScope.recorder(coreDeferred: CompletableDeferred<CoreIngest> = CompletableDeferred(core)) = PlatformRecorder(
        core = coreDeferred,
        scanner = object : RecordingScanner {
            override val deferred = MutableStateFlow(false)
            override val dropped = MutableStateFlow(0L)
            override val error = MutableStateFlow<String?>(null)
            override fun start() {
                scannerStarts++
            }

            override suspend fun stopAndDrain() = Unit
            override suspend fun drain(): DrainOutcome {
                drains++
                return if (drainFails) DrainOutcome.Failed("the observations did not flush") else DrainOutcome.Drained
            }
        },
        location = object : RecordingLocation {
            override fun startRecording() {
                if (locationThrows) throw SecurityException("location revoked")
            }

            override fun stopRecording() = Unit
        },
        host = object : ForegroundHost {
            override suspend fun enterForeground(): Boolean {
                if (hostThrows) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
                foreground = true
                return true
            }

            override fun leaveForeground() {
                foreground = false
            }
        },
        grants = { emptyList() },
        environment = { ScanEnvironment("m", "15", 35, "0.1.0", true, true, 1650, "all_supported", false) },
        io = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
        storageFull = storageFull,
    ).also { runCurrent() }

    @Test
    fun `a foreground host that throws is a refused service, with no session`() = runTest {
        hostThrows = true
        val recorder = recorder()
        assertTrue((recorder.start() is PlatformResult.Err))
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertEquals(RecordingPhase.Idle, core.phase)
    }

    @Test
    fun `a location call that throws is reported and the recording goes on`() = runTest {
        locationThrows = true
        val recorder = recorder()
        assertEquals(PlatformResult.Ok("s1"), recorder.start())
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertTrue(recorder.state.value.error != null)
    }

    @Test
    fun `a core that failed to open fails the start and leaves the service`() = runTest {
        val broken = CompletableDeferred<CoreIngest>().apply { completeExceptionally(IllegalStateException("core failed to open")) }
        val recorder = recorder(broken)
        assertTrue((recorder.start() is PlatformResult.Err))
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertTrue("the service was left", !foreground)
    }

    @Test
    fun `a state read that throws after a successful start keeps the session, its service and the scan (#70 review)`() = runTest {
        readThrows = true
        val recorder = recorder()
        assertEquals(PlatformResult.Ok("s1"), recorder.start())
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertTrue(foreground)
        assertEquals(1, scannerStarts)
        assertTrue(recorder.state.value.error != null)
    }

    @Test
    fun `a drain that fails stops its batch, yet the stop still reaches the core (#70 review)`() = runTest {
        val recorder = recorder()
        assertEquals(PlatformResult.Ok("s1"), recorder.start())
        drainFails = true
        assertEquals(PlatformResult.Ok("s1"), recorder.stop())
        assertEquals(RecordingPhase.Idle, core.phase)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertTrue("the service was left", !foreground)
    }

    @Test
    fun `R-10 a StorageFull event pauses the recording, keeps the service, and Resume goes through the core`() = runTest {
        val recorder = recorder()
        assertEquals(PlatformResult.Ok("s1"), recorder.start())
        core.full = true
        core.phase = RecordingPhase.Paused // the core paused itself
        storageFull.emit(150_000_000)
        runCurrent()
        assertEquals(Phase.Paused, recorder.state.value.phase)
        assertEquals(1, drains)
        assertTrue("the service stays", foreground)
        assertEquals(150_000_000L, recorder.storageFullBytes.value)
        assertEquals(recorder.storageFullBytes, RecordingServiceLink.storageFull)

        recorder.resume()
        assertEquals("refused while full", Phase.Paused, recorder.state.value.phase)
        assertEquals(1, scannerStarts)
        core.full = false
        recorder.resume()
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertEquals(2, scannerStarts)
        assertEquals(null, recorder.storageFullBytes.value)
    }
}
