package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The upload pass reducer (§1.6, §2.8, v3's coordinator rules) against a simulated core and store. */
class UploadMachineTest {
    private class Sim(val kind: UploadKind, val random: Random? = null) {
        val pending = mutableListOf<PendingFile>()
        val sessions = mutableSetOf<String>()
        val stored = mutableSetOf<String>()
        val sends = mutableMapOf<String, MutableList<SendMode>>()
        val markedSucceeded = mutableListOf<String>()
        val markedFailed = mutableListOf<Pair<String, String>>()
        var tally: UploadTally? = null
        var lateArrivals = 0

        /** Scripted answers for the orderings; random ones otherwise. */
        var putResult: (PendingFile, Boolean) -> StoreResult = { _, _ -> StoreResult.Stored }
        var existsResult: (PendingFile) -> Boolean = { false }
        var markSucceedsOk: (String) -> Boolean = { true }

        fun run(maxSteps: Int = 10_000) {
            var step = UploadMachine.start(kind)
            val effects = ArrayDeque(step.effects)
            var steps = 0
            while (tally == null) {
                if (++steps > maxSteps) fail("the pass never finished")
                val effect = effects.removeFirstOrNull() ?: throw AssertionError("no effect left before Finish")
                val event: UploadEvent? = when (effect) {
                    UploadEffect.ListPending -> UploadEvent.Listed(pending.toList())
                    is UploadEffect.DiscardOrphans -> {
                        sessions.retainAll(effect.pending)
                        null
                    }
                    is UploadEffect.Send -> {
                        assertEquals("only files of the pass's kind", kind, effect.file.kind)
                        // A file sealed while the pass runs: the same pass must take it.
                        if (random != null && lateArrivals < 3 && random.nextInt(3) == 0) {
                            lateArrivals++
                            pending += file(random).copy(objectPath = if (kind == UploadKind.GATT) "gatt/v1/late$lateArrivals.json.encrypted" else "scans/v3/late$lateArrivals.parquet.encrypted")
                        }
                        sends.getOrPut(effect.file.id) { mutableListOf() } += effect.mode
                        val resumed = effect.mode == SendMode.ResumeIfSaved && effect.file.id in sessions
                        val result = random?.let { randomPut(it, resumed) } ?: putResult(effect.file, resumed)
                        if (result == StoreResult.Stored) stored += effect.file.id
                        if (result != StoreResult.Stored) sessions += effect.file.id // a session was started
                        when (result) {
                            StoreResult.Stored -> UploadEvent.Sent(effect.file.id)
                            StoreResult.Expired -> UploadEvent.SendFailed(effect.file.id, SendFailure.Expired)
                            is StoreResult.Denied -> UploadEvent.SendFailed(effect.file.id, SendFailure.Denied(result.message))
                            is StoreResult.Failed -> UploadEvent.SendFailed(effect.file.id, SendFailure.Transient(result.message))
                        }
                    }
                    is UploadEffect.CheckStored -> {
                        val there = random?.let { effect.file.id in stored || it.nextInt(5) == 0 } ?: existsResult(effect.file)
                        if (there) stored += effect.file.id
                        UploadEvent.Checked(effect.file.id, there)
                    }
                    is UploadEffect.MarkSucceeded -> {
                        assertTrue("succeeded only once stored", effect.id in stored)
                        if (random?.let { it.nextInt(10) != 0 } ?: markSucceedsOk(effect.id)) {
                            markedSucceeded += effect.id
                            pending.removeAll { it.id == effect.id }
                            UploadEvent.MarkedSucceeded(effect.id)
                        } else {
                            UploadEvent.MarkSucceededFailed(effect.id, "core busy")
                        }
                    }
                    is UploadEffect.MarkFailed -> {
                        markedFailed += effect.id to effect.message
                        UploadEvent.MarkedFailed(effect.id)
                    }
                    is UploadEffect.Discard -> {
                        sessions -= effect.id
                        null
                    }
                    is UploadEffect.Finish -> {
                        tally = effect.tally
                        null
                    }
                }
                if (event != null) {
                    step = UploadMachine.reduce(step.pass, event)
                    effects.addAll(step.effects)
                }
            }
        }

        private fun randomPut(random: Random, resumed: Boolean): StoreResult = when (random.nextInt(6)) {
            0 -> if (resumed) StoreResult.Expired else StoreResult.Failed("timeout")
            1 -> StoreResult.Denied("http=403")
            2 -> StoreResult.Failed("http=503")
            else -> StoreResult.Stored
        }

        private var n = 0
        fun file(random: Random) = PendingFile(
            "p${n++}",
            if (random.nextInt(4) == 0) "gatt/v1/2026/10/29/x$n.json.encrypted" else "scans/v3/2026/10/29/x$n.parquet.encrypted",
        )
    }

    private val scan = PendingFile("a", "scans/v3/a.parquet.encrypted")
    private val scan2 = PendingFile("b", "scans/v3/b.parquet.encrypted")
    private val gatt = PendingFile("g", "gatt/v1/g.json.encrypted")

    @Test
    fun `each pending file of the kind is sent and marked succeeded, the other kind is left alone`() {
        val sim = Sim(UploadKind.SCANS).apply { pending += listOf(scan, gatt, scan2) }
        sim.run()
        assertEquals(listOf("a", "b"), sim.markedSucceeded)
        assertEquals(UploadTally(succeeded = 2), sim.tally)
        assertFalse("gatt" in sim.sends.keys)
    }

    @Test
    fun `an expired session is sent fresh once`() {
        val sim = Sim(UploadKind.SCANS).apply {
            pending += scan
            sessions += "a"
            putResult = { _, resumed -> if (resumed) StoreResult.Expired else StoreResult.Stored }
        }
        sim.run()
        assertEquals(listOf(SendMode.ResumeIfSaved, SendMode.Fresh), sim.sends["a"])
        assertEquals(listOf("a"), sim.markedSucceeded)
    }

    @Test
    fun `a failed send whose object is stored anyway counts as stored`() {
        val sim = Sim(UploadKind.SCANS).apply {
            pending += scan
            putResult = { _, _ -> StoreResult.Failed("http=412 overwrite denied") }
            existsResult = { true }
        }
        sim.run()
        assertEquals(listOf("a"), sim.markedSucceeded)
        assertTrue(sim.markedFailed.isEmpty())
    }

    @Test
    fun `a denial is marked failed with its diagnostic and counted apart, and a mark that fails becomes a failure`() {
        val denied = Sim(UploadKind.GATT).apply {
            pending += gatt
            putResult = { _, _ -> StoreResult.Denied("http=403") }
        }
        denied.run()
        assertEquals(listOf("g" to "http=403"), denied.markedFailed)
        assertEquals(UploadTally(denied = 1), denied.tally)

        val markFails = Sim(UploadKind.SCANS).apply {
            pending += scan
            markSucceedsOk = { false }
        }
        markFails.run()
        assertEquals(listOf("a" to "core busy"), markFails.markedFailed)
        assertEquals(UploadTally(failed = 1), markFails.tally)
    }

    @Test
    fun `a file the core prepared for another payload is never uploaded`() {
        val pass = UploadMachine.reduce(UploadMachine.start(UploadKind.SCANS).pass, UploadEvent.Listed(listOf(scan))).pass
        val step = UploadMachine.reduce(pass, UploadEvent.SendFailed("a", SendFailure.Mismatch("prepared b")))
        assertEquals(listOf(UploadEffect.MarkFailed("a", "prepared b")), step.effects)
    }

    @Test
    fun `answers for another file, or after the end, change nothing`() {
        val pass = UploadMachine.reduce(UploadMachine.start(UploadKind.SCANS).pass, UploadEvent.Listed(listOf(scan))).pass
        assertTrue(UploadMachine.reduce(pass, UploadEvent.Sent("zzz")).effects.isEmpty())
        val ended = UploadMachine.reduce(UploadMachine.start(UploadKind.SCANS).pass, UploadEvent.Listed(emptyList())).pass
        assertTrue(UploadMachine.reduce(ended, UploadEvent.Sent("a")).effects.isEmpty())
    }

    /**
     * Random pending files of both kinds (some arriving during the pass), random store answers and failing marks.
     * The pass always finishes; every file of its kind it took is marked exactly once (succeeded or failed); succeeded
     * only once stored; at most one fresh resend after an expiry; files sealed during the pass go in the same pass;
     * the tally counts each file once.
     */
    @Test
    fun `random passes keep the invariants`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val kind = if (random.nextBoolean()) UploadKind.SCANS else UploadKind.GATT
            val sim = Sim(kind, random)
            repeat(random.nextInt(0, 12)) { sim.pending += sim.file(random) }
            repeat(random.nextInt(0, 4)) { sim.sessions += "p${random.nextInt(12)}" }
            sim.run()
            val where = "seed $seed"
            val tally = sim.tally ?: return fail("$where: no tally")
            val taken = sim.sends.keys
            val finals = sim.markedSucceeded + sim.markedFailed.map { it.first }
            assertEquals("$where: every taken file marked once", taken.sorted(), finals.sorted())
            assertEquals("$where: the tally counts each once", taken.size, tally.succeeded + tally.failed + tally.denied)
            assertEquals("$where: succeeded count", sim.markedSucceeded.size, tally.succeeded)
            sim.sends.forEach { (id, modes) ->
                assertTrue("$where: $id sent at most twice", modes.size <= 2)
                if (modes.size == 2) assertEquals("$where: the second send is fresh", SendMode.Fresh, modes[1])
            }
            val left = sim.pending.filter { it.kind == kind && it.id !in finals }
            assertTrue("$where: nothing of the kind left untried: $left", left.isEmpty())
        }
    }

    private companion object {
        const val SEEDS = 500
    }
}
