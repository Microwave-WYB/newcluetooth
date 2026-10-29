package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The scan session's pure reducer, without Android: the event orderings behind tonight's bugs (#51, #64), then a
 * long randomized run that checks the invariants after every step.
 */
class ScanMachineTest {
    /** A tiny simulated platform: the effects applied to a radio, timers and an in-flight `configureScan`. */
    private class Sim(var env: ScanEnv = ScanEnv(ElapsedMs(0), Radio.On, Phy.Coded, screenOn = true)) {
        var state = ScanState()
        val active = mutableMapOf<ScanGeneration, Phy>()
        val timers = mutableMapOf<TimerKind, Pair<TimerToken, ElapsedMs>>()
        var configuring: Phy? = null
        val starts = mutableListOf<ElapsedMs>()
        val log = mutableListOf<ScanEffect>()
        /** startScan calls that Android refuses (the runner turns them into StartFailed). */
        var refuseStarts = false

        /** The PHY the core's footer records: set by the recording's environment and a successful configureScan. */
        var coreFooter: Phy? = null

        fun send(event: ScanEvent) {
            when (event) {
                is ScanEvent.FooterDeclared -> coreFooter = event.phy
                is ScanEvent.Configured -> if (event.error == null) coreFooter = event.phy
                else -> Unit
            }
            val transition = ScanMachine.reduce(state, event, env)
            state = transition.state
            val followUps = mutableListOf<ScanEvent>()
            for (effect in transition.effects) {
                log += effect
                when (effect) {
                    ScanEffect.RegisterReceivers, ScanEffect.UnregisterReceivers -> Unit
                    is ScanEffect.StartScan -> {
                        if (active.isNotEmpty()) fail("a second scan started while one runs: $active")
                        // The first scan with no declared footer chooses it; every later scan must match the core.
                        val footer = coreFooter ?: effect.phy.also { coreFooter = it }
                        if (effect.phy != footer) fail("scan PHY ${effect.phy} differs from the core's footer $footer")
                        starts += env.now
                        if (refuseStarts) followUps += ScanEvent.StartFailed(effect.generation, "refused") else active[effect.generation] = effect.phy
                    }
                    is ScanEffect.StopScan -> active.remove(effect.generation)
                    is ScanEffect.Arm -> timers[effect.kind] = effect.token to ElapsedMs(env.now.value + effect.delay.value)
                    is ScanEffect.Cancel -> timers.remove(effect.kind)
                    is ScanEffect.Configure -> {
                        if (configuring != null) fail("a second configureScan while one is in flight")
                        configuring = effect.phy
                    }
                }
            }
            followUps.forEach(::send)
        }

        /** Advances the clock, firing due timers in order. */
        fun advance(ms: Long) {
            val target = env.now.value + ms
            while (true) {
                val due = timers.entries.filter { it.value.second.value <= target }.minByOrNull { it.value.second.value } ?: break
                timers.remove(due.key)
                env = env.copy(now = due.value.second)
                send(ScanEvent.TimerFired(due.key, due.value.first))
            }
            env = env.copy(now = ElapsedMs(target))
        }

        fun configured(error: String? = null) {
            val phy = configuring ?: return
            configuring = null
            send(ScanEvent.Configured(phy, error))
        }

        fun startsIn(window: Long, at: ElapsedMs) = starts.count { at.value - it.value < window }
        fun scanningPhy() = active.values.singleOrNull()
    }

    @Test
    fun `a start is held back over the limit and never dropped`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        repeat(4) {
            sim.env = sim.env.copy(radio = Radio.Off)
            sim.send(ScanEvent.BluetoothOff)
            sim.env = sim.env.copy(radio = Radio.On)
            sim.send(ScanEvent.BluetoothOn)
        }
        assertEquals(5, sim.starts.size)
        sim.env = sim.env.copy(radio = Radio.Off)
        sim.send(ScanEvent.BluetoothOff)
        sim.env = sim.env.copy(radio = Radio.On)
        sim.send(ScanEvent.BluetoothOn)
        assertTrue(sim.active.isEmpty())
        assertEquals(ScanPhase.Waiting(Wait.Limited), sim.state.phase)
        sim.advance(ScanStartLimiter.WINDOW_MS - 1)
        assertTrue(sim.active.isEmpty())
        sim.advance(1)
        assertEquals(1, sim.active.size)
    }

    @Test
    fun `a stop during a held-back start never scans afterwards (#51)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        repeat(5) {
            sim.send(ScanEvent.BluetoothOff)
            sim.send(ScanEvent.BluetoothOn)
        }
        assertEquals(ScanPhase.Waiting(Wait.Limited), sim.state.phase)
        sim.send(ScanEvent.Stop)
        sim.advance(10 * ScanStartLimiter.WINDOW_MS)
        assertTrue(sim.active.isEmpty())
        assertTrue(sim.timers.isEmpty())
    }

    @Test
    fun `a failed configureScan keeps the scan, reports and re-arms the restart (#51)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        val running = sim.active.keys.single()
        sim.env = sim.env.copy(wanted = Phy.OneM)
        sim.advance(ScanMachine.RESTART.value)
        assertEquals(Phy.OneM, sim.configuring)
        sim.configured(error = "writer busy")
        assertEquals(setOf(running), sim.active.keys)
        assertEquals(Phy.Coded, sim.scanningPhy())
        assertTrue(sim.state.error != null)
        assertTrue("re-armed", TimerKind.Restart in sim.timers)
        sim.advance(ScanMachine.RESTART.value)
        sim.configured()
        assertEquals(Phy.OneM, sim.scanningPhy())
        assertNull(sim.state.error)
    }

    @Test
    fun `a recording that ends while configureScan is in flight is not restarted (#51)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        sim.env = sim.env.copy(wanted = Phy.OneM)
        sim.advance(ScanMachine.RESTART.value)
        sim.send(ScanEvent.Stop)
        sim.configured()
        sim.advance(5 * ScanMachine.RESTART.value)
        assertTrue(sim.active.isEmpty())
        assertEquals("the footer follows the core anyway", Phy.OneM, sim.state.footer)
    }

    @Test
    fun `a PHY toggled while paused is unchanged at resume and applied by the restart (#64)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        sim.send(ScanEvent.Stop)
        sim.env = sim.env.copy(wanted = Phy.OneM)
        sim.send(ScanEvent.Start)
        assertEquals(Phy.Coded, sim.scanningPhy())
        sim.advance(ScanMachine.RESTART.value)
        sim.configured()
        assertEquals(Phy.OneM, sim.scanningPhy())
    }

    @Test
    fun `Bluetooth back or an error retry never switch the PHY (#64)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        sim.env = sim.env.copy(wanted = Phy.OneM)
        sim.send(ScanEvent.BluetoothOff)
        sim.send(ScanEvent.BluetoothOn)
        assertEquals(Phy.Coded, sim.scanningPhy())
        val failed = sim.active.keys.single()
        sim.active.remove(failed) // Android ends a failed scan itself
        sim.send(ScanEvent.ScanFailed(failed, 2))
        sim.advance(ScanMachine.RETRY.value)
        assertEquals(Phy.Coded, sim.scanningPhy())
    }

    @Test
    fun `a new recording starts on the PHY its environment declared (#64 review)`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        sim.send(ScanEvent.Stop)
        sim.send(ScanEvent.FooterDeclared(Phy.OneM))
        sim.env = sim.env.copy(wanted = Phy.Coded) // changed again before the first scan
        sim.send(ScanEvent.Start)
        assertEquals(Phy.OneM, sim.scanningPhy())
    }

    @Test
    fun `a stale timer or a stale scan failure changes nothing`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        val before = sim.state
        sim.send(ScanEvent.TimerFired(TimerKind.Restart, TimerToken(999)))
        sim.send(ScanEvent.ScanFailed(ScanGeneration(999), 1))
        sim.send(ScanEvent.TimerFired(TimerKind.Retry, TimerToken(1)))
        assertEquals(before, sim.state)
    }

    @Test
    fun `the screen-off restart waits up to 20 min, the screen-on restart applies a held-back one`() {
        val sim = Sim()
        sim.env = sim.env.copy(screenOn = false)
        sim.send(ScanEvent.Start)
        val first = sim.active.keys.single()
        sim.advance(19 * ScanMachine.RESTART.value)
        assertEquals("no restart with the screen off", setOf(first), sim.active.keys)
        sim.env = sim.env.copy(screenOn = true)
        sim.send(ScanEvent.ScreenOn)
        assertFalse(first in sim.active)
    }

    /**
     * Long random event sequences with a fixed seed; after every step: no scan after stop, the scan's PHY is the
     * footer's, at most one scan, at most 5 starts in any 30.5 s window, and a waiting session always has a way
     * forward.
     */
    @Test
    fun `random event sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim()
            repeat(STEPS) { step ->
                val action = random.nextInt(14)
                when (action) {
                    0 -> sim.send(ScanEvent.Start)
                    1 -> sim.send(ScanEvent.Stop)
                    2 -> if (sim.state.phase == ScanPhase.Stopped) sim.send(ScanEvent.FooterDeclared(Phy.entries.random(random)))
                    3 -> {
                        sim.env = sim.env.copy(radio = Radio.Off)
                        sim.send(ScanEvent.BluetoothOff)
                    }
                    4 -> {
                        sim.env = sim.env.copy(radio = Radio.On)
                        sim.send(ScanEvent.BluetoothOn)
                    }
                    5 -> sim.env = sim.env.copy(radio = if (random.nextBoolean()) Radio.NoScanner else Radio.On)
                    6 -> sim.env = sim.env.copy(wanted = Phy.entries.random(random))
                    7 -> {
                        sim.env = sim.env.copy(screenOn = true)
                        sim.send(ScanEvent.ScreenOn)
                    }
                    8 -> sim.env = sim.env.copy(screenOn = false)
                    9 -> sim.active.keys.firstOrNull()?.let {
                        sim.active.remove(it)
                        sim.send(ScanEvent.ScanFailed(it, random.nextInt(1, 7)))
                    }
                    10 -> sim.configured(if (random.nextBoolean()) null else "busy")
                    11 -> sim.refuseStarts = random.nextInt(10) == 0
                    12 -> {
                        // A late timer event: stale tokens must change nothing; the armed token is a real fire.
                        val kind = TimerKind.entries.random(random)
                        val token = TimerToken(random.nextLong(1, 50))
                        if (sim.timers[kind]?.first == token) sim.timers.remove(kind)
                        sim.send(ScanEvent.TimerFired(kind, token))
                    }
                    else -> sim.advance(random.nextLong(0, 45_000))
                }
                val where = "seed $seed step $step after action $action: ${sim.state}"
                when (val phase = sim.state.phase) {
                    ScanPhase.Stopped -> {
                        assertTrue("no scan after stop; $where", sim.active.isEmpty())
                        assertTrue("no timer after stop; $where", sim.timers.isEmpty())
                    }
                    is ScanPhase.Scanning -> {
                        assertEquals("the scan is the state's; $where", setOf(phase.generation), sim.active.keys)
                        assertTrue("a running scan always restarts; $where", TimerKind.Restart in sim.timers || sim.configuring != null)
                    }
                    is ScanPhase.Waiting -> {
                        assertTrue("no scan while waiting; $where", sim.active.isEmpty())
                        val forward = when (phase.wait) {
                            Wait.BluetoothOff -> true // STATE_ON brings it back
                            Wait.Limited -> TimerKind.LimiterSlot in sim.timers
                            Wait.NoScanner, is Wait.Failed -> TimerKind.Retry in sim.timers
                        }
                        assertTrue("a waiting session has a way forward; $where", forward)
                    }
                }
                sim.scanningPhy()?.let { assertEquals("the scan PHY is the core's footer; $where", sim.coreFooter, it) }
                assertTrue("at most 5 starts in 30.5 s; $where", sim.startsIn(ScanStartLimiter.WINDOW_MS, sim.env.now) <= ScanStartLimiter.MAX_STARTS)
            }
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 400
    }

    @Test
    fun `a startScan that throws in the runner retries like a refused start`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        val started = sim.log.filterIsInstance<ScanEffect.StartScan>().single()
        sim.active.clear()
        sim.send(ScanEvent.EffectFailed(started, "DeadObjectException"))
        assertTrue(sim.state.phase is ScanPhase.Waiting)
        assertTrue(TimerKind.Retry in sim.timers)
        sim.advance(ScanMachine.RETRY.value)
        assertEquals(1, sim.active.size)
    }

    @Test
    fun `a receiver registration that throws is reported, the session goes on`() {
        val sim = Sim()
        sim.send(ScanEvent.Start)
        sim.send(ScanEvent.EffectFailed(ScanEffect.RegisterReceivers, "SecurityException"))
        assertTrue(sim.state.phase is ScanPhase.Scanning)
        assertTrue(sim.state.error != null)
    }
}
