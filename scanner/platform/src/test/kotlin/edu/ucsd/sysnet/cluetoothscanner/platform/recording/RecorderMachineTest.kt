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
    private class Sim(val random: Random? = null, val failRate: Int = 0) {
        var model = RecModel()
        var corePhase = RecordingPhase.Idle
        var coreSession: String? = null
        var sessions = 0
        var foreground = false
        var scanning = false
        var locating = false
        var refuseForeground = false
        val log = mutableListOf<String>()
        val replies = mutableListOf<RecOutcome>()

        private fun flaky() = random != null && random.nextInt(100) < failRate

        private fun coreState() = CoreRecordingState(corePhase, coreSession, 0, 0, null, 26, 0.0)

        /** Sends one request and runs every effect until the machine settles; returns its replies. */
        fun request(event: RecEvent): List<RecOutcome> {
            val before = replies.size
            val pending = ArrayDeque<RecEvent>().apply { add(event) }
            while (pending.isNotEmpty()) {
                val transition = RecorderMachine.reduce(model, pending.removeFirst())
                model = transition.state
                for (effect in transition.effects) {
                    log += effect::class.simpleName.orEmpty()
                    execute(effect)?.let(pending::add)
                }
            }
            return replies.subList(before, replies.size).toList()
        }

        private fun execute(effect: RecEffect): RecEvent? = when (effect) {
            RecEffect.EnterForeground -> {
                foreground = !(refuseForeground || flaky())
                RecEvent.ForegroundEntered(foreground)
            }
            RecEffect.LeaveForeground -> {
                foreground = false
                null
            }
            RecEffect.CoreStart -> when {
                corePhase != RecordingPhase.Idle -> RecEvent.CoreFailed(CoreException.AlreadyRecording())
                flaky() -> RecEvent.CoreFailed(CoreException.Io("disk"))
                else -> {
                    corePhase = RecordingPhase.Recording
                    coreSession = "s${++sessions}"
                    RecEvent.CoreStarted(coreState())
                }
            }
            RecEffect.CorePause -> if (corePhase != RecordingPhase.Recording) {
                RecEvent.CoreFailed(CoreException.NotRecording())
            } else if (flaky()) {
                RecEvent.CoreFailed(CoreException.Io("pause"))
            } else {
                corePhase = RecordingPhase.Paused
                RecEvent.CorePaused(coreState())
            }
            RecEffect.CoreResume -> if (corePhase != RecordingPhase.Paused) {
                RecEvent.CoreFailed(CoreException.NotRecording())
            } else if (flaky()) {
                RecEvent.CoreFailed(CoreException.Io("resume"))
            } else {
                corePhase = RecordingPhase.Recording
                RecEvent.CoreResumed(coreState())
            }
            RecEffect.CoreStop -> {
                val session = coreSession
                // A failed stop may or may not have closed the session: the machine must read and reconcile.
                if (flaky() || session == null) {
                    if (random?.nextBoolean() == true) {
                        corePhase = RecordingPhase.Idle
                        coreSession = null
                    }
                    RecEvent.CoreFailed(CoreException.Io("seal"))
                } else {
                    corePhase = RecordingPhase.Idle
                    coreSession = null
                    RecEvent.CoreStopped(SessionId(session))
                }
            }
            RecEffect.CoreRead -> if (flaky()) RecEvent.CoreFailed(CoreException.Io("read")) else RecEvent.CoreRead(coreState())
            RecEffect.DrainScanner -> {
                scanning = false
                null
            }
            RecEffect.StartScanner -> {
                scanning = true
                null
            }
            RecEffect.StartLocation -> {
                locating = true
                null
            }
            RecEffect.StopLocation -> {
                locating = false
                null
            }
            is RecEffect.Reply -> {
                replies += effect.outcome
                null
            }
        }
    }

    private fun Sim.start() = request(RecEvent.Start(emptyList())).single()

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
        assertEquals(listOf("EnterForeground", "CoreStart", "StartLocation", "StartScanner", "Reply"), sim.log)
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
    fun `a core that is already recording is adopted, not left without a service`() {
        val sim = Sim()
        sim.corePhase = RecordingPhase.Recording
        sim.coreSession = "old"
        assertEquals(RecOutcome.Session(SessionId("old")), sim.start())
        assertTrue(sim.foreground && sim.scanning && sim.locating)
    }

    @Test
    fun `a stop request while starting is answered, not dropped`() {
        val sim = Sim()
        val transition = RecorderMachine.reduce(RecModel(), RecEvent.Start(emptyList()))
        val replies = RecorderMachine.reduce(transition.state, RecEvent.Stop).effects.filterIsInstance<RecEffect.Reply>()
        assertEquals(1, replies.size)
        assertTrue(replies.single().outcome is RecOutcome.Failed)
        assertTrue(sim.replies.isEmpty())
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
            RecEvent.CoreStarted(CoreRecordingState(RecordingPhase.Recording, "x", 0, 0, 0, 1, 0.0)),
            RecEvent.CorePaused(CoreRecordingState(RecordingPhase.Paused, "x", 0, 0, null, 1, 0.0)),
            RecEvent.CoreStopped(SessionId("x")),
        )) {
            val transition = RecorderMachine.reduce(idle, late)
            assertEquals(late.toString(), idle, transition.state)
            assertTrue(late.toString(), transition.effects.isEmpty())
        }
    }

    /**
     * Random requests against a core and platform that fail at random; after every request: it was answered exactly
     * once, an open core session always has the foreground service, the scanner and location run only while the
     * recording is live and not paused, and after a stop nothing runs.
     */
    @Test
    fun `random request sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim(random, failRate = 15)
            repeat(STEPS) { step ->
                val request = when (random.nextInt(6)) {
                    0, 1 -> RecEvent.Start(if (random.nextInt(10) == 0) listOf("p") else emptyList())
                    2 -> RecEvent.Pause
                    3 -> RecEvent.Resume
                    4 -> RecEvent.Stop
                    else -> RecEvent.Restore
                }
                sim.refuseForeground = random.nextInt(10) == 0
                val replies = sim.request(request)
                val where = "seed $seed step $step $request: ${sim.model} core=${sim.corePhase}/${sim.coreSession}"
                assertEquals("exactly one reply; $where", 1, replies.size)
                val phase = sim.model.phase
                when (phase) {
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
                    RecPhase.AwaitingForeground, RecPhase.StartingCore, RecPhase.AdoptingCore, RecPhase.Restoring,
                    is RecPhase.Pausing, is RecPhase.Resuming, is RecPhase.Stopping, is RecPhase.Reconciling,
                    -> fail("a request left the machine unsettled; $where")
                }
                if (sim.corePhase != RecordingPhase.Idle) {
                    assertTrue("an open session always has the service; $where", sim.foreground)
                    assertTrue("an open session is never orphaned; $where", phase is RecPhase.Live)
                }
            }
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 300
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
}
