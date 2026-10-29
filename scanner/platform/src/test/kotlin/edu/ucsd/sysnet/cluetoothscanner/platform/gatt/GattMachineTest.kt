package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The discovery stages' pure reducer, driven by a random simulated link and clock, without coroutines. */
class GattMachineTest {
    private val request = GattRequest(
        id = "0195ca99-5000-7131-b131-313131313131",
        sessionId = null,
        target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null),
        location = null,
        appVersion = "0.1.0-test",
        deviceModel = "Pixel 8",
        androidVersion = "15 (API 35)",
    )

    private class Sim(val random: Random) {
        var now = 0L
        var cancelled = false
        var opened = 0
        var closed = 0
        var finishes = 0
        var result: GattDiscoveryResult? = null
        var listed = false
        lateinit var step: EngineStep

        fun env() = EngineEnv(now, wallMs = 1_000 + now, cancelled = cancelled)

        /** Runs the effects like the runner: synchronous answers are fed back at once. */
        fun apply(next: EngineStep) {
            step = next
            val pending = ArrayDeque(next.effects)
            while (pending.isNotEmpty()) {
                val effect = pending.removeFirst()
                if (finishes > 0) fail("an effect after Finish: $effect")
                val answer: EngineInput? = when (effect) {
                    is EngineEffect.Connect -> if (random.nextInt(10) == 0) {
                        EngineInput.ConnectRejected
                    } else {
                        opened++
                        EngineInput.Linked
                    }
                    EngineEffect.ReadPhy, EngineEffect.Disconnect, is EngineEffect.Progress -> null
                    is EngineEffect.RequestMtu -> EngineInput.MtuRequested(random.nextInt(4) != 0)
                    EngineEffect.DiscoverServices -> EngineInput.DiscoverRequested(random.nextInt(8) != 0)
                    EngineEffect.ListServices -> {
                        listed = true
                        EngineInput.Listed(sampleServices())
                    }
                    is EngineEffect.Read -> EngineInput.ReadRequested(random.nextInt(5) != 0)
                    EngineEffect.Close -> {
                        closed++
                        null
                    }
                    is EngineEffect.Finish -> {
                        finishes++
                        result = effect.result
                        if (pending.isNotEmpty()) fail("Finish is not the last effect")
                        null
                    }
                }
                if (answer != null) {
                    step = GattMachine.reduce(step.model, answer, env())
                    pending.addAll(0, step.effects)
                }
            }
        }

        fun send(input: EngineInput) = apply(GattMachine.reduce(step.model, input, env()))

        fun timeout() {
            step.model.wait.until?.let { now = maxOf(now, it) }
            send(EngineInput.TimedOut)
        }

        fun randomEvent(attempt: Int): GattEvent = when (random.nextInt(8)) {
            0 -> GattEvent.Connected
            1 -> GattEvent.Disconnected(listOf(0, 8, 19, 22, 62, 133).random(random))
            2 -> GattEvent.MtuChanged(listOf(23, 185, 247, 517, 600).random(random), if (random.nextBoolean()) 0 else 133)
            3 -> GattEvent.PhyRead(random.nextInt(0, 4), random.nextInt(0, 4), 0)
            4 -> GattEvent.ServicesDiscovered(if (random.nextInt(4) == 0) 129 else 0)
            else -> {
                val wait = step.model.wait
                val ref = (wait as? EngineWait.Reading)?.read?.current ?: AttributeRef(0, 0)
                GattEvent.AttributeRead(ref, byteArrayOf(attempt.toByte()), if (random.nextInt(4) == 0) 5 else 0)
            }
        }
    }

    @Test
    fun `a cooperative link reads everything and closes once`() {
        val sim = Sim(Random(0))
        sim.apply(GattMachine.start(GattTimeouts(), request, sim.env()))
        sim.send(EngineInput.Link(1, GattEvent.Connected))
        sim.send(EngineInput.Link(1, GattEvent.MtuChanged(247, 0)))
        sim.send(EngineInput.Link(1, GattEvent.ServicesDiscovered(0)))
        while (sim.step.model.wait is EngineWait.Reading) {
            val read = (sim.step.model.wait as EngineWait.Reading).read
            sim.send(EngineInput.Link(1, GattEvent.AttributeRead(read.current, byteArrayOf(1), 0)))
        }
        sim.send(EngineInput.Link(1, GattEvent.Disconnected(0)))
        assertEquals(GattOutcome.COMPLETE, sim.result?.outcome)
        assertEquals(1, sim.closed)
    }

    @Test
    fun `callbacks of an earlier attempt are ignored`() {
        val sim = Sim(Random(1))
        sim.apply(GattMachine.start(GattTimeouts(), request, sim.env()))
        sim.send(EngineInput.Link(1, GattEvent.Disconnected(133))) // first attempt fails with 133
        sim.timeout() // backoff
        assertEquals(2, sim.step.model.attempt)
        val before = sim.step.model
        sim.send(EngineInput.Link(1, GattEvent.Connected))
        assertEquals(before, sim.step.model)
    }

    @Test
    fun `cancel asked before a wait ends it at once`() {
        val sim = Sim(Random(2))
        sim.cancelled = true
        sim.apply(GattMachine.start(GattTimeouts(), request, sim.env()))
        assertTrue(sim.step.model.wait is EngineWait.Disconnecting)
        sim.timeout()
        assertEquals(GattOutcome.CANCELLED, sim.result?.outcome)
    }

    /**
     * Random links, callbacks (also of other attempts), timeouts and cancel. After every step: an unfinished machine
     * waits with a deadline or for an answer it was just given; Finish comes once and last; every opened link is
     * closed exactly once; and timeouts alone always finish the run.
     */
    @Test
    fun `random discoveries always end with one result and every link closed`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim(random)
            sim.apply(GattMachine.start(GattTimeouts(), request, sim.env()))
            var steps = 0
            while (sim.finishes == 0) {
                steps++
                val where = "seed $seed step $steps: ${sim.step.model.wait}"
                assertNotNull("an unfinished machine waits with a deadline; $where", sim.step.model.wait.until)
                // Late in a run only timeouts come: the stages must still finish on their own.
                if (steps > 200 || random.nextInt(4) == 0) {
                    sim.now += random.nextLong(0, 3_000)
                    if (random.nextBoolean()) sim.timeout() else sim.send(EngineInput.TimedOut.also { sim.step.model.wait.until?.let { sim.now = maxOf(sim.now, it) } })
                } else {
                    when (random.nextInt(10)) {
                        0 -> {
                            sim.cancelled = true
                            sim.send(EngineInput.Cancel)
                        }
                        1 -> sim.send(EngineInput.Link(random.nextInt(1, 3), sim.randomEvent(sim.step.model.attempt)))
                        else -> sim.send(EngineInput.Link(sim.step.model.attempt, sim.randomEvent(sim.step.model.attempt)))
                    }
                }
                if (steps > 2_000) fail("no result after 2000 steps; $where")
            }
            val result = sim.result ?: return fail("seed $seed: no result")
            assertEquals("seed $seed: every opened link closed exactly once", sim.opened, sim.closed)
            assertEquals("seed $seed: one result", 1, sim.finishes)
            if (result.outcome == GattOutcome.COMPLETE) {
                assertNull("seed $seed: complete has no error", result.error)
                assertEquals("seed $seed: complete reached the disconnect", GattStage.DISCONNECT, result.stageReached)
            }
            if (result.outcome == GattOutcome.CANCELLED) assertTrue("seed $seed: cancelled only when asked", sim.cancelled)
            if (result.services.isNotEmpty()) assertTrue("seed $seed: services only after listing", sim.listed)
            assertFalse("seed $seed: ended before it started", result.endedAtMs < result.startedAtMs)
        }
    }

    private companion object {
        const val SEEDS = 500
    }
}
