package edu.ucsd.sysnet.cluetoothscanner.platform.export

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export acknowledgement reducer (§4.5), without Android: orderings, then long random sequences. */
class ExportLedgerTest {
    private class Sim(val random: Random? = null) {
        var ledger = Ledger()
        var remembered = emptySet<String>()
        val acknowledged = mutableListOf<String>()
        val inFlight = mutableSetOf<String>()

        fun send(event: LedgerEvent) {
            // Loaded is what prefs already hold.
            if (event is LedgerEvent.Loaded) remembered = remembered + event.pending
            val pending = ArrayDeque<LedgerEvent>().apply { add(event) }
            while (pending.isNotEmpty()) {
                val step = ExportLedger.reduce(ledger, pending.removeFirst())
                ledger = step.state
                for (effect in step.effects) {
                    when (effect) {
                        is LedgerEffect.Remember -> remembered = effect.pending
                        is LedgerEffect.Acknowledge -> {
                            assertTrue("never two acknowledgements of ${effect.exportId} at once", inFlight.add(effect.exportId))
                            pending += if (random?.nextInt(4) == 0) {
                                LedgerEvent.AckFailed(effect.exportId)
                            } else {
                                acknowledged += effect.exportId
                                LedgerEvent.Acknowledged(effect.exportId)
                            }
                            inFlight -= effect.exportId
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a shared export waits for the next export, a saved one goes after its copy`() {
        val sim = Sim()
        sim.send(LedgerEvent.Handed("e1"))
        assertTrue(sim.acknowledged.isEmpty())
        assertEquals("it survives process death", setOf("e1"), sim.remembered)
        sim.send(LedgerEvent.Handed("e2"))
        assertEquals(listOf("e1"), sim.acknowledged)
        sim.send(LedgerEvent.Saved("e2"))
        assertEquals(listOf("e1", "e2"), sim.acknowledged)
        assertTrue(sim.remembered.isEmpty())
    }

    @Test
    fun `what a previous process left goes at app start, once`() {
        val sim = Sim()
        sim.send(LedgerEvent.Loaded(setOf("old")))
        sim.send(LedgerEvent.AppStarted)
        sim.send(LedgerEvent.AppStarted)
        assertEquals(listOf("old"), sim.acknowledged)
    }

    @Test
    fun `a failed acknowledgement stays pending, and a save of an unknown id acknowledges nothing`() {
        val failed = ExportLedger.reduce(Ledger(setOf("e1"), setOf("e1")), LedgerEvent.AckFailed("e1")).state
        assertEquals(Ledger(setOf("e1"), emptySet()), failed)
        assertTrue(ExportLedger.reduce(Ledger(), LedgerEvent.Saved("x")).effects.none { it is LedgerEffect.Acknowledge })
    }

    /**
     * Random shares, saves, app starts and failing acknowledgements. After every step: the remembered set is the
     * pending set, nothing is acknowledged twice at once, a pending id is never one that was acknowledged for good;
     * and once acknowledgements succeed, an app start leaves nothing pending.
     */
    @Test
    fun `random sequences keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val sim = Sim(random)
            repeat(STEPS) { step ->
                val id = "e${random.nextInt(6)}"
                sim.send(
                    when (random.nextInt(4)) {
                        0 -> LedgerEvent.Handed(id)
                        1 -> LedgerEvent.Saved(id)
                        2 -> LedgerEvent.AppStarted
                        else -> LedgerEvent.Loaded(setOf(id))
                    },
                )
                val where = "seed $seed step $step: ${sim.ledger}"
                assertEquals("remembered is pending; $where", sim.ledger.pending, sim.remembered)
                assertTrue("nothing in flight after the runner settled; $where", sim.ledger.acking.isEmpty())
            }
            val settled = Sim().apply {
                ledger = sim.ledger
                send(LedgerEvent.AppStarted)
            }
            assertTrue("seed $seed: an app start with a working core leaves nothing pending", settled.ledger.pending.isEmpty())
            assertFalse("seed $seed", settled.remembered.isNotEmpty())
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 200
    }
}
