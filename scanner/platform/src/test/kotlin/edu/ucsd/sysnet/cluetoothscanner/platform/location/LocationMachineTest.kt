package edu.ucsd.sysnet.cluetoothscanner.platform.location

import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The location client's pure reducer, without Android: orderings, then long random sequences. */
class LocationMachineTest {
    private class Sim {
        var env = LocEnv(LocElapsedMs(0), permitted = true, providerOn = true)
        var state = LocState()
        val registrations = mutableMapOf<RegistrationId, LocationProfile>()
        var ticking = false
        var ingested = 0
        var cleared = 0

        fun send(event: LocEvent) {
            val transition = LocationMachine.reduce(state, event, env)
            state = transition.state
            for (effect in transition.effects) {
                when (effect) {
                    is LocEffect.Register -> {
                        if (registrations.isNotEmpty()) fail("a second registration while $registrations")
                        registrations[effect.id] = effect.profile
                    }
                    is LocEffect.Unregister -> registrations.remove(effect.id)
                    LocEffect.StartTicking -> ticking = true
                    LocEffect.StopTicking -> ticking = false
                    is LocEffect.Ingest -> {
                        val fed = (event as? LocEvent.Fixed)?.id?.let(registrations::get)
                        if (fed != LocationProfile.RECORDING) fail("a fix of $fed reached the core")
                        ingested++
                    }
                    LocEffect.ClearCore -> cleared++
                }
            }
        }

        fun current() = registrations.keys.singleOrNull()

        fun fix(id: RegistrationId?, accuracy: Float? = 5f) {
            env = env.copy(now = LocElapsedMs(env.now.value + 2_000))
            send(LocEvent.Fixed(id ?: RegistrationId(999), FixReading("gps", 32.0, -117.0, accuracy, 0, env.now.value * 1_000_000)))
        }
    }

    @Test
    fun `P-5 without the permission nothing registers and the status is Denied, then it registers when granted`() {
        val sim = Sim()
        sim.env = sim.env.copy(permitted = false)
        sim.send(LocEvent.UiStarted(true))
        assertTrue(sim.registrations.isEmpty())
        assertEquals(State.Denied, sim.state.status.state)
        sim.env = sim.env.copy(permitted = true)
        sim.send(LocEvent.Tick)
        assertEquals(LocationProfile.FOREGROUND, sim.registrations.values.single())
    }

    @Test
    fun `M-9 FOREGROUND fixes are shown but never reach the core`() {
        val sim = Sim()
        sim.send(LocEvent.UiStarted(true))
        sim.fix(sim.current())
        assertEquals(0, sim.ingested)
        assertEquals(32.0, sim.state.shown?.at?.lat ?: 0.0, 0.0)
        assertEquals(State.Ok, sim.state.status.state)
    }

    @Test
    fun `section 4_2 recording switches to RECORDING, feeds the core, and its end clears it`() {
        val sim = Sim()
        sim.send(LocEvent.UiStarted(true))
        val foreground = sim.current()
        sim.send(LocEvent.Recording(true))
        assertEquals(LocationProfile.RECORDING, sim.registrations.values.single())
        sim.fix(foreground)
        assertEquals("a late FOREGROUND fix is stale", 0, sim.ingested)
        sim.fix(sim.current())
        assertEquals(1, sim.ingested)
        sim.fix(sim.current(), accuracy = null)
        assertEquals(1, sim.cleared)
        sim.send(LocEvent.Recording(false))
        assertEquals(2, sim.cleared)
        assertEquals(LocationProfile.FOREGROUND, sim.registrations.values.single())
    }

    @Test
    fun `a failed registration is retried at the next tick`() {
        val sim = Sim()
        sim.send(LocEvent.Recording(true))
        val failed = sim.current()
        sim.registrations.clear()
        sim.send(LocEvent.RegistrationFailed(failed ?: RegistrationId(0)))
        sim.send(LocEvent.Tick)
        assertEquals(LocationProfile.RECORDING, sim.registrations.values.single())
    }

    @Test
    fun `P-5 waiting warns after 30 s without a fix and a fix older than 2 min is stale`() {
        val sim = Sim()
        sim.send(LocEvent.UiStarted(true))
        sim.env = sim.env.copy(now = LocElapsedMs(30_001))
        sim.send(LocEvent.Tick)
        assertTrue(sim.state.status.warn)
        sim.fix(sim.current())
        sim.env = sim.env.copy(now = LocElapsedMs(sim.env.now.value + 120_001))
        sim.send(LocEvent.Tick)
        assertEquals(State.Stale, sim.state.status.state)
    }

    @Test
    fun `random event sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim()
            var recording = false
            repeat(STEPS) { step ->
                val action = random.nextInt(9)
                val clearedBefore = sim.cleared
                when (action) {
                    0 -> {
                        val wasRecording = recording
                        recording = random.nextBoolean()
                        sim.send(LocEvent.Recording(recording))
                        if (wasRecording && !recording) assertTrue("the end of a recording clears the core", sim.cleared > clearedBefore)
                    }
                    1 -> sim.send(LocEvent.UiStarted(random.nextBoolean()))
                    2 -> sim.send(LocEvent.Tick)
                    3 -> sim.env = sim.env.copy(permitted = random.nextInt(5) != 0)
                    4 -> sim.env = sim.env.copy(providerOn = random.nextInt(5) != 0)
                    5 -> sim.current()?.let {
                        sim.registrations.remove(it)
                        sim.send(LocEvent.RegistrationFailed(it))
                    }
                    6 -> sim.fix(if (random.nextInt(4) == 0) RegistrationId(random.nextLong(1, 20)) else sim.current(), if (random.nextBoolean()) 5f else null)
                    7 -> sim.env = sim.env.copy(now = LocElapsedMs(sim.env.now.value + random.nextLong(0, 200_000)))
                    else -> sim.send(LocEvent.RegistrationFailed(RegistrationId(random.nextLong(1, 20))))
                }
                val where = "seed $seed step $step action $action: ${sim.state}"
                when (val request = sim.state.request) {
                    LocRequest.None -> {
                        assertTrue("no registration without a wanted request; $where", sim.registrations.isEmpty())
                        assertFalse("no tick without a wanted request; $where", sim.ticking)
                        assertNull("nothing shown without a wanted request; $where", sim.state.shown)
                    }
                    is LocRequest.Active -> {
                        assertEquals("the wanted profile; $where", LocationProfile.wanted(sim.state.recording, sim.state.uiStarted), request.profile)
                        assertTrue("a wanted request ticks; $where", sim.ticking)
                        sim.registrations.values.singleOrNull()?.let { assertEquals("registered as wanted; $where", request.profile, it) }
                        request.registration?.let { assertTrue("the state's registration is live; $where", it.id in sim.registrations || sim.registrations.isEmpty()) }
                    }
                }
            }
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 300
    }
}
