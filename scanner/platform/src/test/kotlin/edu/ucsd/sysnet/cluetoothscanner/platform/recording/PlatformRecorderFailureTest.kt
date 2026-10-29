package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val core = object : TestCoreIngest() {
        var phase = RecordingPhase.Idle
        override fun startRecording(env: ScanEnvironment): String {
            phase = RecordingPhase.Recording
            return "s1"
        }

        override fun recordingState() = CoreRecordingState(phase, if (phase == RecordingPhase.Idle) null else "s1", 0, 0, 0, 1, 0.0)
    }

    private fun TestScope.recorder(coreDeferred: CompletableDeferred<CoreIngest> = CompletableDeferred(core)) = PlatformRecorder(
        core = coreDeferred,
        scanner = object : RecordingScanner {
            override val deferred = MutableStateFlow(false)
            override val dropped = MutableStateFlow(0L)
            override val error = MutableStateFlow<String?>(null)
            override fun start() = Unit
            override suspend fun stopAndDrain() = Unit
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
    ).also { runCurrent() }

    @Test
    fun `a foreground host that throws is a refused service, with no session`() = runTest {
        hostThrows = true
        val recorder = recorder()
        assertTrue(recorder.start().isFailure)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertEquals(RecordingPhase.Idle, core.phase)
    }

    @Test
    fun `a location call that throws is reported and the recording goes on`() = runTest {
        locationThrows = true
        val recorder = recorder()
        assertEquals("s1", recorder.start().getOrNull())
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertTrue(recorder.state.value.error != null)
    }

    @Test
    fun `a core that failed to open fails the start and leaves the service`() = runTest {
        val broken = CompletableDeferred<CoreIngest>().apply { completeExceptionally(IllegalStateException("core failed to open")) }
        val recorder = recorder(broken)
        assertTrue(recorder.start().isFailure)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertTrue("the service was left", !foreground)
    }
}
