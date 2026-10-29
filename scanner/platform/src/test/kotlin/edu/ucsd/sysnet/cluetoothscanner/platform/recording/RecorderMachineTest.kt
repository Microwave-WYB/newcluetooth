package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The recorder's pure reducer against a simulated core and platform, without coroutines or Android. */
class RecorderMachineTest {
    /** Runs effects like [PlatformRecorder]: a failed effect stops its batch and comes back as [RecEvent.EffectFailed]. */
    private class Sim(val random: Random? = null, val failRate: Int = 0) {
        var model = RecModel()
        var corePhase = RecordingPhase.Idle
        var coreSession: String? = null
        var sessions = 0
        var foreground = false
        var scanning = false
        var locating = false
        var refuseForeground = false

        /** R-10: the core is below the storage floor; it refuses to resume until a cleanup. */
        var coreFull = false

        /** `recordingState()` fails right after a successful start (the #70 review's partial start). */
        var failReadAfterStart = false
        private var justStarted = false
        val log = mutableListOf<String>()
        val replies = mutableListOf<RecOutcome>()

        private sealed interface Result {
            data class Done(val event: RecEvent?) : Result
            data class Failed(val cause: Throwable) : Result
        }

        private fun flaky() = random != null && random.nextInt(100) < failRate

        private fun coreState() = CoreRecordingState(corePhase, coreSession, 0, 0, null, 26, 0.0)

        /** Sends one request and runs every effect until the machine settles; returns its replies. */
        fun request(event: RecEvent): List<RecOutcome> {
            val before = replies.size
            val pending = ArrayDeque<RecEvent>().apply { add(event) }
            while (pending.isNotEmpty()) {
                val transition = RecorderMachine.reduce(model, pending.removeFirst())
                model = transition.state
                for ((index, effect) in transition.effects.withIndex()) {
                    log += effect::class.simpleName.orEmpty()
                    when (val result = execute(effect)) {
                        is Result.Done -> result.event?.let(pending::add)
                        is Result.Failed -> {
                            pending.addFirst(RecEvent.EffectFailed(effect, result.cause, transition.effects.drop(index + 1)))
                            break
                        }
                    }
                }
            }
            return replies.subList(before, replies.size).toList()
        }

        private fun execute(effect: RecEffect): Result = when (effect) {
            RecEffect.EnterForeground -> {
                foreground = !(refuseForeground || flaky())
                Result.Done(RecEvent.ForegroundEntered(foreground))
            }
            RecEffect.LeaveForeground -> {
                foreground = false
                Result.Done(null)
            }
            RecEffect.CoreStart -> when {
                corePhase != RecordingPhase.Idle -> Result.Done(RecEvent.CoreFailed(CoreException.AlreadyRecording()))
                flaky() -> Result.Failed(CoreException.Io("disk"))
                else -> {
                    corePhase = RecordingPhase.Recording
                    val session = "s${++sessions}"
                    coreSession = session
                    justStarted = true
                    Result.Done(RecEvent.CoreStartReturned(SessionId(session)))
                }
            }
            RecEffect.CorePause -> when {
                corePhase != RecordingPhase.Recording -> Result.Done(RecEvent.CoreFailed(CoreException.NotRecording()))
                flaky() -> Result.Done(RecEvent.CoreFailed(CoreException.Io("pause")))
                else -> {
                    corePhase = RecordingPhase.Paused
                    Result.Done(RecEvent.CorePaused(coreState()))
                }
            }
            RecEffect.CoreResume -> when {
                corePhase != RecordingPhase.Paused -> Result.Done(RecEvent.CoreFailed(CoreException.NotRecording()))
                coreFull -> Result.Failed(CoreException.Io("storage is full"))
                flaky() -> Result.Failed(CoreException.Io("resume"))
                else -> {
                    corePhase = RecordingPhase.Recording
                    Result.Done(RecEvent.CoreResumed(coreState()))
                }
            }
            RecEffect.CoreStop -> {
                val session = coreSession
                // A failed stop may or may not have closed the session: the machine must read and reconcile.
                if (flaky() || session == null) {
                    if (random?.nextBoolean() == true) {
                        corePhase = RecordingPhase.Idle
                        coreSession = null
                    }
                    Result.Done(RecEvent.CoreFailed(CoreException.Io("seal")))
                } else {
                    corePhase = RecordingPhase.Idle
                    coreSession = null
                    Result.Done(RecEvent.CoreStopped(SessionId(session)))
                }
            }
            RecEffect.CoreRead -> {
                val afterStart = justStarted
                justStarted = false
                when {
                    afterStart && failReadAfterStart -> Result.Failed(CoreException.Io("read"))
                    flaky() -> Result.Done(RecEvent.CoreFailed(CoreException.Io("read")))
                    else -> Result.Done(RecEvent.CoreRead(coreState()))
                }
            }
            RecEffect.DrainScanner -> {
                scanning = false
                // A drain that times out still stopped the scanner; the failure only reaches the state.
                if (flaky()) Result.Failed(IllegalStateException("the scanner did not drain")) else Result.Done(null)
            }
            RecEffect.StartScanner -> {
                scanning = true
                Result.Done(null)
            }
            RecEffect.StartLocation -> if (flaky()) {
                Result.Failed(SecurityException("location revoked"))
            } else {
                locating = true
                Result.Done(null)
            }
            RecEffect.StopLocation -> {
                locating = false
                Result.Done(null)
            }
            is RecEffect.Reply -> {
                replies += effect.outcome
                Result.Done(null)
            }
        }
    }

    private fun Sim.start() = request(RecEvent.Start(emptyList())).single()

    /** R-10: the core runs out of space; it pauses the recording itself and reports StorageFull. */
    private fun Sim.storageFull(freeBytes: Long = 150_000_000) {
        coreFull = true
        if (corePhase == RecordingPhase.Recording) corePhase = RecordingPhase.Paused
        assertTrue("a signal is not answered", request(RecEvent.StorageFull(freeBytes)).isEmpty())
    }

    @Test
    fun `R-10 storage full pauses the recording - no scan, the service and the session stay`() {
        val sim = Sim()
        sim.start()
        sim.log.clear()
        sim.storageFull()
        assertEquals(listOf("DrainScanner", "StopLocation", "CoreRead"), sim.log)
        assertEquals(RecPhase.StorageFull(SessionId("s1"), 150_000_000), sim.model.phase)
        assertFalse(sim.scanning || sim.locating)
        assertTrue("the service stays", sim.foreground)
        assertEquals(RecordingPhase.Paused, sim.corePhase)
        val public = sim.model.public()
        assertEquals(Phase.Paused, public.phase)
        assertEquals("s1", public.sessionId)
        assertEquals(150_000_000L, sim.model.storageFullBytes())
        assertTrue(public.error.orEmpty().startsWith(RecorderMachine.STORAGE_FULL))
        // Pause and Start change nothing.
        sim.request(RecEvent.Pause)
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.start())
        assertTrue(sim.model.phase is RecPhase.StorageFull)
        assertFalse(sim.scanning)
    }

    @Test
    fun `R-10 Resume goes through the core - refused while still full, then resumes after a cleanup`() {
        val sim = Sim()
        sim.start()
        sim.storageFull()
        sim.request(RecEvent.Resume)
        assertEquals("still full: the pause goes on", RecPhase.StorageFull(SessionId("s1"), 150_000_000), sim.model.phase)
        assertFalse(sim.scanning || sim.locating)
        sim.coreFull = false // the cleanup freed space
        sim.log.clear()
        sim.request(RecEvent.Resume)
        assertEquals(listOf("CoreResume", "StartLocation", "StartScanner", "Reply"), sim.log)
        assertEquals(RecPhase.Live(SessionId("s1"), paused = false), sim.model.phase)
        assertTrue(sim.scanning && sim.locating)
        assertEquals(null, sim.model.storageFullBytes())
    }

    @Test
    fun `R-10 Stop while paused for storage closes the session`() {
        val sim = Sim()
        sim.start()
        sim.storageFull()
        sim.log.clear()
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.request(RecEvent.Stop).single())
        assertEquals(listOf("CoreStop", "StopLocation", "Reply", "LeaveForeground"), sim.log)
        assertEquals(RecordingPhase.Idle, sim.corePhase)
        assertFalse(sim.foreground)
    }

    @Test
    fun `R-10 storage full of a user-paused or unidentified recording, and outside a recording`() {
        val pausedByUser = RecorderMachine.reduce(RecModel(phase = RecPhase.Live(SessionId("s1"), paused = true)), RecEvent.StorageFull(5))
        assertEquals(RecPhase.StorageFull(SessionId("s1"), 5), pausedByUser.state.phase)
        assertEquals("nothing was collected", listOf(RecEffect.CoreRead), pausedByUser.effects)

        val unidentified = RecorderMachine.reduce(RecModel(phase = RecPhase.Unidentified), RecEvent.StorageFull(5))
        assertEquals(RecPhase.StorageFull(null, 5), unidentified.state.phase)
        assertEquals(listOf(RecEffect.DrainScanner, RecEffect.StopLocation), unidentified.effects)
        val resumed = RecorderMachine.reduce(
            RecorderMachine.reduce(unidentified.state, RecEvent.Resume).state,
            RecEvent.CoreResumed(CoreRecordingState(RecordingPhase.Recording, "x", 0, 0, 0, 1, 0.0)),
        )
        assertEquals("the core names the session on resume", RecPhase.Live(SessionId("x"), paused = false), resumed.state.phase)

        val idle = RecModel()
        val ignored = RecorderMachine.reduce(idle, RecEvent.StorageFull(5))
        assertEquals(idle, ignored.state)
        assertTrue(ignored.effects.isEmpty())
    }

    @Test
    fun `a missing grant gives an error and no service or session`() {
        val sim = Sim()
        val reply = sim.request(RecEvent.Start(listOf("android.permission.BLUETOOTH_SCAN"))).single()
        assertTrue(reply is RecOutcome.Failed)
        assertEquals(listOf("Reply"), sim.log)
        assertFalse(sim.foreground)
        assertEquals(RecorderMachine.MISSING_GRANTS, sim.model.error)
    }

    @Test
    fun `a refused foreground service creates no session`() {
        val sim = Sim().apply { refuseForeground = true }
        assertTrue(sim.start() is RecOutcome.Failed)
        assertEquals(0, sim.sessions)
        assertEquals(Phase.Idle, sim.model.public().phase)
    }

    @Test
    fun `start, pause, resume and stop keep the call order`() {
        val sim = Sim()
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.start())
        assertEquals(listOf("EnterForeground", "CoreStart", "CoreRead", "StartLocation", "StartScanner", "Reply"), sim.log)
        sim.log.clear()
        sim.request(RecEvent.Pause)
        assertEquals(listOf("DrainScanner", "CorePause", "StopLocation", "Reply"), sim.log)
        assertTrue("pause keeps the foreground service", sim.foreground)
        sim.log.clear()
        sim.request(RecEvent.Resume)
        assertEquals(listOf("CoreResume", "StartLocation", "StartScanner", "Reply"), sim.log)
        sim.log.clear()
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.request(RecEvent.Stop).single())
        assertEquals(listOf("DrainScanner", "CoreStop", "StopLocation", "Reply", "LeaveForeground"), sim.log)
        assertEquals("s1", sim.model.public().sessionId)
    }

    @Test
    fun `a state read that fails right after a successful start keeps the session and its service (#70 review)`() {
        val sim = Sim().apply { failReadAfterStart = true }
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.start())
        assertEquals(RecPhase.Live(SessionId("s1"), paused = false), sim.model.phase)
        assertTrue(sim.foreground && sim.scanning && sim.locating)
        assertTrue(sim.model.error != null)
        assertEquals(RecOutcome.Session(SessionId("s1")), sim.request(RecEvent.Stop).single())
        assertEquals(RecordingPhase.Idle, sim.corePhase)
        assertFalse(sim.foreground)
    }

    @Test
    fun `a core that is already recording is adopted, not left without a service`() {
        val sim = Sim()
        sim.corePhase = RecordingPhase.Recording
        sim.coreSession = "old"
        assertEquals(RecOutcome.Session(SessionId("old")), sim.start())
        assertTrue(sim.foreground && sim.scanning && sim.locating)
    }

    @Test
    fun `an adoption whose read fails keeps the unknown session's service, and the core naming it adopts it (#70 review)`() {
        val adopting = RecModel(phase = RecPhase.AdoptingCore)
        val failed = RecorderMachine.reduce(adopting, RecEvent.CoreFailed(CoreException.Io("read")))
        assertEquals(RecPhase.Unidentified, failed.state.phase)
        assertEquals(Phase.Recording, failed.state.public().phase)
        assertFalse(RecEffect.LeaveForeground in failed.effects)
        assertTrue(RecEffect.StartScanner in failed.effects)
        assertTrue(failed.effects.last() is RecEffect.Reply)
        val named = RecorderMachine.reduce(failed.state, RecEvent.CoreChanged(CoreRecordingState(RecordingPhase.Recording, "x", 0, 0, 0, 3, 0.0)))
        assertEquals(RecPhase.Live(SessionId("x"), paused = false), named.state.phase)
        val stopped = RecorderMachine.reduce(failed.state, RecEvent.Stop)
        assertEquals("an unidentified session can be stopped", listOf(RecEffect.DrainScanner, RecEffect.CoreStop), stopped.effects)
    }

    @Test
    fun `a restore that finds the core recording enters the service before going live (#70 review)`() {
        val sim = Sim()
        sim.corePhase = RecordingPhase.Recording
        sim.coreSession = "left"
        sim.request(RecEvent.Restore)
        assertEquals(listOf("CoreRead", "EnterForeground", "StartLocation", "StartScanner", "Reply"), sim.log)
        assertEquals(RecPhase.Live(SessionId("left"), paused = false), sim.model.phase)
        assertTrue(sim.foreground && sim.scanning && sim.locating)
    }

    @Test
    fun `a restore that finds the core paused keeps the service and does not scan`() {
        val sim = Sim()
        sim.corePhase = RecordingPhase.Paused
        sim.coreSession = "left"
        sim.request(RecEvent.Restore)
        assertEquals(RecPhase.Live(SessionId("left"), paused = true), sim.model.phase)
        assertTrue(sim.foreground)
        assertFalse(sim.scanning || sim.locating)
    }

    @Test
    fun `a restore whose service is refused closes the session instead of running without it (#70 review)`() {
        val sim = Sim().apply { refuseForeground = true }
        sim.corePhase = RecordingPhase.Recording
        sim.coreSession = "left"
        sim.request(RecEvent.Restore)
        assertEquals(RecordingPhase.Idle, sim.corePhase)
        assertFalse(sim.foreground || sim.scanning || sim.locating)
        assertEquals(RecPhase.Idle(SessionId("left")), sim.model.phase)
        assertEquals(RecorderMachine.SERVICE_REFUSED, sim.model.error)
    }

    @Test
    fun `a failed effect stops its batch, and the machine runs what still matters (#70 review)`() {
        val live = RecModel(phase = RecPhase.Live(SessionId("s1"), paused = false))
        val failed = RecorderMachine.reduce(
            live,
            RecEvent.EffectFailed(RecEffect.StartLocation, SecurityException("revoked"), listOf(RecEffect.StartScanner, RecEffect.Reply(RecOutcome.Done))),
        )
        assertEquals(listOf(RecEffect.StartScanner, RecEffect.Reply(RecOutcome.Done)), failed.effects)
        assertTrue(failed.state.error != null)

        val pausing = RecModel(phase = RecPhase.Pausing(SessionId("s1")))
        val drainFailed = RecorderMachine.reduce(pausing, RecEvent.EffectFailed(RecEffect.DrainScanner, IllegalStateException("timeout"), listOf(RecEffect.CorePause)))
        assertEquals("the core step still runs after a failed drain", listOf(RecEffect.CorePause), drainFailed.effects)
        assertEquals(pausing.phase, drainFailed.state.phase)

        // A failed core step answers through the core path and never runs the batch's later effects twice.
        val starting = RecModel(phase = RecPhase.StartingCore)
        val startFailed = RecorderMachine.reduce(starting, RecEvent.EffectFailed(RecEffect.CoreStart, CoreException.Io("disk")))
        assertEquals(1, startFailed.effects.filterIsInstance<RecEffect.Reply>().size)
    }

    @Test
    fun `a stop request while starting is answered, not dropped`() {
        val transition = RecorderMachine.reduce(RecModel(), RecEvent.Start(emptyList()))
        val replies = RecorderMachine.reduce(transition.state, RecEvent.Stop).effects.filterIsInstance<RecEffect.Reply>()
        assertEquals(1, replies.size)
        assertTrue(replies.single().outcome is RecOutcome.Failed)
    }

    @Test
    fun `a failed stop whose outcome cannot be read keeps the session and its service`() {
        val model = RecModel(phase = RecPhase.Reconciling(SessionId("s1"), wasPaused = false, CoreException.Io("seal"), "could not stop"))
        val transition = RecorderMachine.reduce(model, RecEvent.CoreFailed(CoreException.Io("read")))
        assertEquals(RecPhase.Live(SessionId("s1"), paused = false), transition.state.phase)
        assertFalse(RecEffect.LeaveForeground in transition.effects)
        assertTrue(RecEffect.StartScanner in transition.effects)
    }

    @Test
    fun `step results that arrive in the wrong phase change nothing`() {
        val idle = RecModel()
        for (late in listOf(
            RecEvent.ForegroundEntered(true),
            RecEvent.CoreStartReturned(SessionId("x")),
            RecEvent.CoreRead(CoreRecordingState(RecordingPhase.Recording, "x", 0, 0, 0, 1, 0.0)),
            RecEvent.CorePaused(CoreRecordingState(RecordingPhase.Paused, "x", 0, 0, null, 1, 0.0)),
            RecEvent.CoreStopped(SessionId("x")),
        )) {
            val transition = RecorderMachine.reduce(idle, late)
            assertEquals(late.toString(), idle, transition.state)
            assertTrue(late.toString(), transition.effects.isEmpty())
        }
    }

    @Test
    fun `an effect that throws in the runner is a typed failure, never a crash`() {
        val starting = RecorderMachine.reduce(RecModel(), RecEvent.Start(emptyList())).state
        val refused = RecorderMachine.reduce(starting, RecEvent.EffectFailed(RecEffect.EnterForeground, IllegalStateException("not allowed")))
        assertEquals(RecPhase.Idle(lastSession = null), refused.state.phase)
        assertEquals(RecorderMachine.SERVICE_REFUSED, refused.state.error)
        assertTrue(refused.effects.single() is RecEffect.Reply)

        val coreStarting = starting.copy(phase = RecPhase.StartingCore)
        val coreFailed = RecorderMachine.reduce(coreStarting, RecEvent.EffectFailed(RecEffect.CoreStart, RuntimeException("binding")))
        assertTrue("the service is left", RecEffect.LeaveForeground in coreFailed.effects)
        assertEquals(RecPhase.Idle(lastSession = null), coreFailed.state.phase)

        val live = RecModel(phase = RecPhase.Live(SessionId("s1"), paused = false))
        val locationFailed = RecorderMachine.reduce(live, RecEvent.EffectFailed(RecEffect.StartLocation, SecurityException("revoked")))
        assertEquals("the recording goes on", live.phase, locationFailed.state.phase)
        assertTrue(locationFailed.state.error != null)
    }

    /**
     * Random requests against a core and platform that fail at random (including a state read right after a
     * successful start, a drain and a location step); after every request: it was answered exactly once, an open
     * core session always has the foreground service and a phase that owns it, the scanner and location run only
     * while the recording is live and not paused, and after a stop nothing runs.
     */
    @Test
    fun `random request sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim(random, failRate = 15)
            var fullUntilResume = false
            repeat(STEPS) { step ->
                when (random.nextInt(8)) {
                    // R-10: the core runs out of space (while recording or not), or a cleanup frees it.
                    0 -> {
                        val recording = sim.model.phase is RecPhase.Live || sim.model.phase == RecPhase.Unidentified
                        sim.storageFull(random.nextLong(0, 200_000_000))
                        if (recording) fullUntilResume = true
                    }
                    1 -> sim.coreFull = false
                }
                val request = when (random.nextInt(6)) {
                    0, 1 -> RecEvent.Start(if (random.nextInt(10) == 0) listOf("p") else emptyList())
                    2 -> RecEvent.Pause
                    3 -> RecEvent.Resume
                    4 -> RecEvent.Stop
                    else -> RecEvent.Restore
                }
                sim.refuseForeground = random.nextInt(10) == 0
                sim.failReadAfterStart = random.nextInt(4) == 0
                val replies = sim.request(request)
                val where = "seed $seed step $step $request: ${sim.model} core=${sim.corePhase}/${sim.coreSession} full=${sim.coreFull}"
                assertEquals("exactly one reply; $where", 1, replies.size)
                // After StorageFull nothing scans until a Resume (or the recording ended).
                if (request == RecEvent.Resume || sim.model.phase is RecPhase.Idle) fullUntilResume = false
                if (fullUntilResume) {
                    assertFalse("no scan after StorageFull until Resume; $where", sim.scanning)
                    assertFalse("no location after StorageFull until Resume; $where", sim.locating)
                    assertTrue("the service stays after StorageFull; $where", sim.foreground)
                }
                when (val phase = sim.model.phase) {
                    is RecPhase.Idle -> {
                        assertFalse("no scan when idle; $where", sim.scanning)
                        assertFalse("no location when idle; $where", sim.locating)
                        assertFalse("no service when idle; $where", sim.foreground)
                        assertEquals("the core has no session either; $where", RecordingPhase.Idle, sim.corePhase)
                    }
                    is RecPhase.Live -> {
                        assertTrue("a live recording has its service; $where", sim.foreground)
                        // After a stop whose outcome could not be read the core may already be idle (a later stop
                        // settles it); while the core has a session, it is this one, paused alike.
                        sim.coreSession?.let {
                            assertEquals("the core has the session; $where", phase.session.value, it)
                            assertEquals("paused matches the core; $where", phase.paused, sim.corePhase == RecordingPhase.Paused)
                        }
                        if (phase.paused) {
                            assertFalse("no scan while paused; $where", sim.scanning)
                            assertFalse("no location while paused; $where", sim.locating)
                        } else {
                            assertTrue("a recording scans; $where", sim.scanning)
                        }
                    }
                    RecPhase.Unidentified -> assertTrue("an unidentified session keeps its service; $where", sim.foreground)
                    is RecPhase.StorageFull -> {
                        assertTrue("a recording paused for storage keeps its service; $where", sim.foreground)
                        assertFalse("no scan while storage is full; $where", sim.scanning)
                        assertFalse("no location while storage is full; $where", sim.locating)
                    }
                    RecPhase.AwaitingForeground, RecPhase.StartingCore, is RecPhase.ReadingStarted, RecPhase.AdoptingCore,
                    RecPhase.Restoring, is RecPhase.RestoreForeground, is RecPhase.Pausing, is RecPhase.Resuming,
                    is RecPhase.ResumingFromFull, is RecPhase.Stopping, is RecPhase.Reconciling,
                    -> fail("a request left the machine unsettled; $where")
                }
                if (sim.corePhase != RecordingPhase.Idle) {
                    assertTrue("an open session always has the service; $where", sim.foreground)
                    assertTrue(
                        "an open session is never orphaned; $where",
                        sim.model.phase is RecPhase.Live || sim.model.phase == RecPhase.Unidentified || sim.model.phase is RecPhase.StorageFull,
                    )
                }
            }
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 300
    }
}
