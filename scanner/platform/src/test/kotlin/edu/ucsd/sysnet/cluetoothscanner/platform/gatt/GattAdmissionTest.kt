package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admission reducer (#70 review items 3 and 4): one owner orders start, cancel, engine creation and end. */
class GattAdmissionTest {
    private val target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null)

    private fun ticket() = GattTicket(GattStartRequest(target, null, null, GattLinkFactory { null }))

    private fun engine() = GattEngine(GattLinkFactory { null }, GattTimeouts(), nowMs = { 0 }, elapsedMs = { 0 }, onProgress = {})

    private class Sim {
        var state: Admission = Admission.Free
        val launched = mutableListOf<GattTicket>()
        val refused = mutableSetOf<GattTicket>()
        val ended = mutableSetOf<GattTicket>()
        val proceeded = mutableSetOf<GattTicket>()
        val cancelledEngines = mutableSetOf<GattEngine>()
        val superseded = mutableSetOf<GattTicket>()

        fun send(event: AdmissionEvent): List<AdmissionEffect> {
            val step = GattAdmission.reduce(state, event)
            state = step.state
            for (effect in step.effects) {
                when (effect) {
                    is AdmissionEffect.Launch -> launched += effect.ticket
                    is AdmissionEffect.Refuse -> refused += effect.ticket
                    is AdmissionEffect.Superseded -> superseded += effect.ticket
                    is AdmissionEffect.CancelEngine -> cancelledEngines += effect.engine
                    is AdmissionEffect.Proceed -> proceeded += effect.ticket
                }
            }
            return step.effects
        }

        fun current() = (state as? Admission.Busy)?.current
    }

    @Test
    fun `a cancel while starting means the engine never proceeds`() {
        val sim = Sim()
        val t = ticket()
        sim.send(AdmissionEvent.Start(t, supersede = true))
        sim.send(AdmissionEvent.Cancel(t))
        assertTrue(sim.send(AdmissionEvent.Ready(t, engine())).isEmpty())
        assertFalse(t in sim.proceeded)
        sim.send(AdmissionEvent.Ended(t))
        assertEquals(Admission.Free, sim.state)
    }

    @Test
    fun `a cancel once running reaches the engine`() {
        val sim = Sim()
        val t = ticket()
        val e = engine()
        sim.send(AdmissionEvent.Start(t, supersede = false))
        sim.send(AdmissionEvent.Ready(t, e))
        assertEquals(listOf(AdmissionEffect.CancelEngine(e)), sim.send(AdmissionEvent.Cancel(null)))
    }

    @Test
    fun `a plain start is refused while busy, a superseding one waits and replaces a waiting one`() {
        val sim = Sim()
        val (a, b, c, d) = List(4) { ticket() }
        sim.send(AdmissionEvent.Start(a, supersede = false))
        assertEquals(listOf(AdmissionEffect.Refuse(b)), sim.send(AdmissionEvent.Start(b, supersede = false)))
        sim.send(AdmissionEvent.Start(c, supersede = true))
        sim.send(AdmissionEvent.Start(d, supersede = true))
        assertTrue(c in sim.refused)
        assertEquals(listOf(AdmissionEffect.Launch(d)), sim.send(AdmissionEvent.Ended(a)))
        assertEquals(setOf(a), sim.superseded)
    }

    /**
     * Random starts, cancels (of any ticket), engine creations and ends. After every step: at most one discovery is
     * launched and not ended; a ticket is launched at most once and never both launched and refused; one cancelled
     * while starting never proceeds. At the end every ticket was either launched and ended or refused.
     */
    @Test
    fun `random admission sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim()
            val tickets = mutableListOf<GattTicket>()
            val cancelledWhileStarting = mutableSetOf<GattTicket>()
            fun endCurrent() {
                val current = sim.current() ?: return
                sim.ended += current.ticket
                sim.send(AdmissionEvent.Ended(current.ticket))
            }
            repeat(STEPS) { step ->
                when (random.nextInt(6)) {
                    0, 1 -> {
                        val supersede = random.nextInt(4) != 0
                        val current = sim.current()
                        if (supersede && current != null && current.stage == AdmittedStage.Starting) cancelledWhileStarting += current.ticket
                        ticket().also { tickets += it }.let { sim.send(AdmissionEvent.Start(it, supersede)) }
                    }
                    2 -> {
                        val target = if (tickets.isEmpty() || random.nextInt(3) == 0) null else tickets.random(random)
                        val current = sim.current()
                        if (current != null && current.stage == AdmittedStage.Starting && (target == null || target == current.ticket)) {
                            cancelledWhileStarting += current.ticket
                        }
                        sim.send(AdmissionEvent.Cancel(target))
                    }
                    3 -> sim.current()?.let { current ->
                        if (current.stage == AdmittedStage.Starting) sim.send(AdmissionEvent.Ready(current.ticket, engine()))
                    }
                    4 -> endCurrent()
                    // A stray end of a ticket that is not current changes nothing.
                    else -> tickets.randomOrNull(random)?.let { if (it == sim.current()?.ticket) endCurrent() else sim.send(AdmissionEvent.Ended(it)) }
                }
                val where = "seed $seed step $step: ${sim.state}"
                val live = sim.launched.filter { it !in sim.ended }
                assertTrue("one discovery at a time; $where", live.size <= 1)
                assertEquals("launched once; $where", sim.launched.size, sim.launched.toSet().size)
                assertTrue("never launched and refused; $where", sim.launched.none { it in sim.refused })
                assertTrue("a cancel while starting never connects; $where", cancelledWhileStarting.none { it in sim.proceeded })
                sim.current()?.let { assertTrue("the current one was launched; $where", it.ticket in sim.launched) }
            }
            while (sim.state != Admission.Free) endCurrent()
            for (t in tickets) {
                assertTrue("seed $seed: every ticket resolves", (t in sim.launched && t in sim.ended) xor (t in sim.refused))
            }
        }
    }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 200
    }
}
