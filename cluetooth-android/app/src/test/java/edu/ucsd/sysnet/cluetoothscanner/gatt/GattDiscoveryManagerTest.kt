package edu.ucsd.sysnet.cluetoothscanner.gatt

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GattDiscoveryManagerTest {
    private class MemoryStore(var failSaves: Boolean = false) : GattStore {
        val saved = mutableListOf<String>()
        var saveAttempts = 0
        var ids = 0
        var idGate: CompletableDeferred<Unit>? = null
        override suspend fun newDiscoveryId(): String {
            idGate?.await()
            return "0195ca99-5000-7131-b131-3131313131${(30 + ids++)}"
        }

        override suspend fun save(json: String) {
            saveAttempts++
            if (failSaves) error("core rejected the object")
            saved += json
        }
    }

    private class MemoryPending : PendingDiscoveryStore {
        val map = linkedMapOf<String, String>()
        var puts = 0
        override fun put(id: String, json: String) {
            puts++
            map[id] = json
        }

        override fun entries() = map.entries.sortedBy { it.key }.map { PendingDiscovery(it.key, it.value) }
        override fun remove(id: String) {
            map.remove(id)
        }
    }

    private val info = GattDeviceInfo("0.1.0", "Pixel 8", "15 (API 35)")
    private val target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null)

    private fun TestScope.manager(
        store: MemoryStore,
        pending: MemoryPending,
        scope: CoroutineScope = this,
        retryFirstMs: Long = 30_000,
    ) = GattDiscoveryManager(
        scope = scope,
        store = store,
        pending = pending,
        device = info,
        nowMs = { 1_742_860_800_000L + testScheduler.currentTime },
        elapsedMs = { testScheduler.currentTime },
        retryFirstMs = retryFirstMs,
    )

    private fun start(factory: GattLinkFactory) = GattStartRequest(target, null, null, factory)
    private val hanging = object : FakeLink.Behavior {
        override fun onConnect(link: FakeLink) = Unit
    }

    private fun outcomeOf(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("outcome").jsonPrimitive.content

    @Test
    fun `a finished discovery is stored once and its queue entry removed`() = runTest {
        val store = MemoryStore()
        val pending = MemoryPending()
        val manager = manager(store, pending)
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()

        val state = manager.state.value as GattUiState.Finished
        assertTrue(state.saved)
        assertEquals(GattOutcome.COMPLETE, state.result.outcome)
        assertEquals(1, store.saved.size)
        assertEquals("complete", outcomeOf(store.saved.single()))
        assertTrue(pending.map.isEmpty())
        manager.dismissResult()
        assertEquals(GattUiState.Idle, manager.state.value)
    }

    @Test
    fun `only one discovery runs at a time and a cancelled one is stored`() = runTest {
        val store = MemoryStore()
        val manager = manager(store, MemoryPending())
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(hanging)))))
        runCurrent()
        assertTrue(manager.state.value is GattUiState.Running)
        assertFalse(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))

        advanceTimeBy(1_000)
        manager.cancel()
        runCurrent()
        assertEquals(GattOutcome.CANCELLED, (manager.state.value as GattUiState.Finished).result.outcome)
        assertEquals(1, store.saved.size)
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()
        assertEquals(2, store.saved.size)
    }

    @Test
    fun `while running the queue holds a failed object for the current stage`() = runTest {
        val pending = MemoryPending()
        val manager = manager(MemoryStore(), pending)
        val stuckInDiscovery = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        }
        manager.start(start(FakeFactory(attempts = arrayOf(stuckInDiscovery))))
        advanceTimeBy(500)
        runCurrent()

        val interrupted = Json.parseToJsonElement(pending.map.values.single()).jsonObject
        assertEquals("failed", interrupted.getValue("outcome").jsonPrimitive.content)
        assertEquals("discover", interrupted.getValue("stage_reached").jsonPrimitive.content)
        assertEquals("PROCESS_DEATH", interrupted.getValue("error").jsonObject.getValue("name").jsonPrimitive.content)
        manager.cancel()
        runCurrent()
    }

    @Test
    fun `the queue entry is written per stage change and not per read`() = runTest {
        val manyReads = listOf(
            GattServiceSnapshot(
                uuid16(0x1800), true, 1,
                (0 until 40).map { GattCharacteristicSnapshot(uuid16(0x2A00 + it), it, 0x02, emptyList()) },
            ),
        )
        val pending = MemoryPending()
        val manager = manager(MemoryStore(), pending)
        manager.start(start(FakeFactory(manyReads, attempts = arrayOf(Cooperative))))
        runCurrent()
        // connect marker, then one write per stage change (mtu, discover, read, disconnect), then the result.
        assertTrue("puts=${pending.puts}", pending.puts <= 6)
    }

    @Test
    fun `after process death the interrupted discovery is stored as failed at the next start`() = runTest {
        val pending = MemoryPending()
        val dying = manager(MemoryStore(), pending)
        dying.start(start(FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        }))))
        advanceTimeBy(500)
        runCurrent()
        val leftBehind = pending.map.values.single()

        // A new process: a fresh manager with an empty memory but the same queue.
        val store = MemoryStore()
        val restarted = GattDiscoveryManager(this, store, pending, info)
        restarted.recoverStale()
        assertEquals(listOf(leftBehind), store.saved)
        assertTrue(pending.map.isEmpty())
        restarted.recoverStale()
        assertEquals(1, store.saved.size)
        dying.cancel()
        runCurrent()
    }

    @Test
    fun `a result the core rejected is never overwritten by the next discovery`() = runTest {
        val store = MemoryStore(failSaves = true)
        val pending = MemoryPending()
        val manager = manager(store, pending)
        manager.start(start(FakeFactory(attempts = arrayOf(Cooperative))))
        runCurrent()
        val first = manager.state.value as GattUiState.Finished
        assertFalse(first.saved)
        assertEquals("core rejected the object", first.saveError)
        assertEquals(1, pending.map.size)

        // A second discovery starts while the first is still unsaved.
        manager.start(start(FakeFactory(attempts = arrayOf(Cooperative))))
        runCurrent()
        assertEquals(2, pending.map.size)
        assertEquals(setOf("complete"), pending.map.values.map(::outcomeOf).toSet())
        assertTrue(store.saved.isEmpty())

        // Once the core accepts again, both are stored, oldest first, and the queue empties.
        store.failSaves = false
        manager.recoverStale()
        assertEquals(2, store.saved.size)
        assertTrue(pending.map.isEmpty())
        val ids = store.saved.map { Json.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `unsaved results are retried by themselves with a growing pause`() = runTest {
        val store = MemoryStore(failSaves = true)
        val pending = MemoryPending()
        val manager = manager(store, pending)
        manager.start(start(FakeFactory(attempts = arrayOf(Cooperative))))
        runCurrent()
        val afterFirst = store.saveAttempts

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(afterFirst + 1, store.saveAttempts)
        advanceTimeBy(30_000) // the pause doubled: nothing yet at +60 s
        runCurrent()
        assertEquals(afterFirst + 1, store.saveAttempts)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(afterFirst + 2, store.saveAttempts)

        store.failSaves = false
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, store.saved.size)
        assertTrue(pending.map.isEmpty())
        // The loop ends when the queue is empty.
        val attempts = store.saveAttempts
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(attempts, store.saveAttempts)
    }

    @Test
    fun `recovery never saves the placeholder of a discovery that is starting or running`() = runTest {
        val store = MemoryStore()
        val pending = MemoryPending()
        val oldId = "0195ca99-4000-7131-b131-313131313131"
        pending.put(oldId, GattJson.encode(sampleFailed(oldId)))
        val manager = manager(store, pending)
        store.idGate = CompletableDeferred()

        // start() and recovery are issued together; the new discovery has no id yet.
        manager.start(start(FakeFactory(attempts = arrayOf(hanging))))
        val recovery = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler)).also {
            it.launch { manager.recoverStale() }
        }
        runCurrent()
        assertEquals(1, store.saved.size) // only the old process's entry
        assertEquals(oldId, Json.parseToJsonElement(store.saved.single()).jsonObject.getValue("id").jsonPrimitive.content)

        store.idGate!!.complete(Unit)
        runCurrent()
        val running = pending.map.keys.single()
        // Recovery while the new discovery runs leaves its placeholder alone.
        manager.recoverStale()
        assertEquals(1, store.saved.size)
        assertTrue(pending.map.containsKey(running))
        manager.cancel()
        runCurrent()
        recovery.cancel()
    }

    private fun sampleFailed(id: String) = GattDiscoveryResult(
        request = GattRequest(id, null, target, null, "0.1.0", "Pixel 8", "15 (API 35)"),
        startedAtMs = 1_742_860_800_000L,
        endedAtMs = 1_742_860_800_100L,
        outcome = GattOutcome.FAILED,
        stageReached = GattStage.CONNECT,
        error = GattError(GattStage.CONNECT, GattStatus.PROCESS_DEATH, "PROCESS_DEATH"),
        disconnect = null, mtu = null, phy = null,
        timings = GattTimings(null, null, null, null),
        services = emptyList(),
    )

    @Test
    fun `cancelling the owning scope mid discovery resets the state and keeps the entry`() = runTest {
        val pending = MemoryPending()
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val factory = FakeFactory(attempts = arrayOf(hanging))
        val manager = manager(MemoryStore(), pending, scope = owner)
        manager.start(start(factory))
        advanceTimeBy(500)
        runCurrent()
        assertTrue(manager.state.value is GattUiState.Running)

        owner.cancel()
        runCurrent()
        assertEquals(GattUiState.Idle, manager.state.value)
        assertEquals(1, factory.links.single().closes)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, pending.map.size) // recovered as failed at the next start
        // The manager can run again (with a live scope) after the old one died.
        val again = manager(MemoryStore(), pending)
        assertTrue(again.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()
    }

    @Test
    fun `an unexpected exception from the link is stored as a failed discovery`() = runTest {
        val store = MemoryStore()
        val pending = MemoryPending()
        val manager = manager(store, pending)
        manager.start(start(FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean = throw IllegalStateException("stack bug")
        }))))
        runCurrent()

        val state = manager.state.value as GattUiState.Finished
        assertEquals(GattOutcome.FAILED, state.result.outcome)
        assertEquals(GattStatus.INTERNAL_ERROR, state.result.error?.status)
        assertEquals(1, store.saved.size)
        assertTrue(pending.map.isEmpty())
    }

    @Test
    fun `a stack that throws SecurityException still yields a result and the ui leaves running`() = runTest {
        val store = MemoryStore()
        val manager = manager(store, MemoryPending())
        manager.start(start(FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean = throw SecurityException("revoked")
        }))))
        advanceTimeBy(2_000) // the refusal retries pause in virtual time
        runCurrent()
        val state = manager.state.value as GattUiState.Finished
        assertEquals(GattOutcome.PARTIAL, state.result.outcome)
        assertEquals("READ_REFUSED", state.result.error?.name)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `a start that cannot mint an id leaves nothing queued and stays idle`() = runTest {
        val pending = MemoryPending()
        val failing = object : GattStore {
            override suspend fun newDiscoveryId(): String = error("core unavailable")
            override suspend fun save(json: String) = Unit
        }
        val manager = GattDiscoveryManager(this, failing, pending, info)
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()
        assertEquals(GattUiState.Idle, manager.state.value)
        assertTrue(pending.map.isEmpty())
        assertNull((manager.state.value as? GattUiState.Finished))
    }

    @Test
    fun `the interrupted placeholder is a valid object even when the wall clock stepped back`() = runTest {
        val request = GattRequest("0195ca99-5000-7131-b131-313131313131", null, target, null, "0.1.0", "Pixel 8", "15 (API 35)")
        var now = 1_742_860_810_000L
        val manager = GattDiscoveryManager(
            scope = this,
            store = MemoryStore(),
            pending = MemoryPending(),
            device = info,
            nowMs = { now },
        )
        val startedAt = now
        now -= 60_000 // the clock jumps a minute back
        val json = Json.parseToJsonElement(manager.interruptedJson(request, startedAt, GattStage.READ)).jsonObject
        assertEquals(json.getValue("started_at").jsonPrimitive.content, json.getValue("ended_at").jsonPrimitive.content)
    }
}

class FilePendingDiscoveryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = "0195ca99-5000-7131-b131-313131313130"
    private val b = "0195ca99-5001-7131-b131-313131313131"

    @Test
    fun `entries survive a restart, are ordered by id and replaced in place`() {
        val directory = File(folder.root, "gatt-pending")
        val store = FilePendingDiscoveryStore(directory)
        store.put(b, "{\"v\":2}")
        store.put(a, "{\"v\":1}")
        store.put(b, "{\"v\":3}")

        val reopened = FilePendingDiscoveryStore(directory)
        assertEquals(listOf(PendingDiscovery(a, "{\"v\":1}"), PendingDiscovery(b, "{\"v\":3}")), reopened.entries())
        reopened.remove(a)
        assertEquals(listOf(b), reopened.entries().map { it.id })
        reopened.remove(a) // idempotent
    }

    @Test
    fun `a torn temporary file and foreign files are never listed`() {
        val directory = File(folder.root, "gatt-pending")
        val store = FilePendingDiscoveryStore(directory)
        store.put(a, "{}")
        File(directory, "$b.json.tmp").writeText("{\"partial\"")
        File(directory, "notes.json").writeText("{}")
        assertEquals(listOf(a), store.entries().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an id that is not a uuid v7 is refused`() {
        FilePendingDiscoveryStore(File(folder.root, "q")).put("../escape", "{}")
    }
}
