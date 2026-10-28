package edu.ucsd.sysnet.cluetoothscanner.gatt

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattDiscoveryManagerTest {
    private class MemoryStore(var failSaves: Boolean = false) : GattStore {
        val saved = mutableListOf<String>()
        var ids = 0
        override suspend fun newDiscoveryId() = "0195ca99-5000-7131-b131-31313131313${ids++}"
        override suspend fun save(json: String) {
            if (failSaves) error("core rejected the object")
            saved += json
        }
    }

    private class MemoryPending : PendingDiscoveryStore {
        var json: String? = null
        override fun put(json: String) { this.json = json }
        override fun peek() = json
        override fun clear() { json = null }
    }

    private val info = GattDeviceInfo("0.1.0", "Pixel 8", "15 (API 35)")
    private val target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null)

    private fun TestScope.manager(store: MemoryStore, pending: MemoryPending) = GattDiscoveryManager(
        scope = this,
        store = store,
        pending = pending,
        device = info,
        nowMs = { 1_742_860_800_000L + testScheduler.currentTime },
    )

    private fun start(factory: GattLinkFactory) = GattStartRequest(target, null, null, factory)

    @Test
    fun `a finished discovery is stored once and the slot is cleared`() = runTest {
        val store = MemoryStore()
        val pending = MemoryPending()
        val manager = manager(store, pending)
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()

        val state = manager.state.value as GattUiState.Finished
        assertTrue(state.saved)
        assertEquals(GattOutcome.COMPLETE, state.result.outcome)
        assertEquals(1, store.saved.size)
        val saved = Json.parseToJsonElement(store.saved.single()).jsonObject
        assertEquals("complete", saved.getValue("outcome").jsonPrimitive.content)
        assertEquals("0195ca99-5000-7131-b131-313131313130", saved.getValue("id").jsonPrimitive.content)
        assertNull(pending.json)
        manager.dismissResult()
        assertEquals(GattUiState.Idle, manager.state.value)
    }

    @Test
    fun `only one discovery runs at a time`() = runTest {
        val store = MemoryStore()
        val manager = manager(store, MemoryPending())
        val hanging = object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit
        }
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(hanging)))))
        runCurrent()
        assertTrue(manager.state.value is GattUiState.Running)
        assertFalse(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))

        advanceTimeBy(1_000)
        manager.cancel()
        runCurrent()
        val finished = manager.state.value as GattUiState.Finished
        assertEquals(GattOutcome.CANCELLED, finished.result.outcome)
        assertEquals(1, store.saved.size)
        // Free again after the cancelled run was stored.
        assertTrue(manager.start(start(FakeFactory(attempts = arrayOf(Cooperative)))))
        runCurrent()
        assertEquals(2, store.saved.size)
    }

    @Test
    fun `while running the slot holds a failed object for the current stage`() = runTest {
        val pending = MemoryPending()
        val manager = manager(MemoryStore(), pending)
        val stuckInDiscovery = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        }
        manager.start(start(FakeFactory(attempts = arrayOf(stuckInDiscovery))))
        advanceTimeBy(500)
        runCurrent()

        val interrupted = Json.parseToJsonElement(pending.json!!).jsonObject
        assertEquals("failed", interrupted.getValue("outcome").jsonPrimitive.content)
        assertEquals("discover", interrupted.getValue("stage_reached").jsonPrimitive.content)
        assertEquals("PROCESS_DEATH", interrupted.getValue("error").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `after process death the interrupted discovery is stored as failed on next start`() = runTest {
        val pending = MemoryPending()
        val dying = manager(MemoryStore(), pending)
        val stuck = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        }
        dying.start(start(FakeFactory(attempts = arrayOf(stuck))))
        advanceTimeBy(500)
        runCurrent()
        val leftBehind = pending.json!!

        // A new process: a fresh manager with an empty memory but the same slot.
        val store = MemoryStore()
        val restarted = GattDiscoveryManager(this, store, pending, info)
        restarted.recoverStale()
        assertEquals(listOf(leftBehind), store.saved)
        assertNull(pending.json)
        // Nothing left to recover the second time.
        restarted.recoverStale()
        assertEquals(1, store.saved.size)
        dying.cancel()
        runCurrent()
    }

    @Test
    fun `a result the core rejected stays in the slot and is retried at the next start`() = runTest {
        val store = MemoryStore(failSaves = true)
        val pending = MemoryPending()
        val manager = manager(store, pending)
        manager.start(start(FakeFactory(attempts = arrayOf(Cooperative))))
        runCurrent()

        val state = manager.state.value as GattUiState.Finished
        assertFalse(state.saved)
        assertEquals("core rejected the object", state.saveError)
        assertEquals("complete", Json.parseToJsonElement(pending.json!!).jsonObject.getValue("outcome").jsonPrimitive.content)

        store.failSaves = false
        manager.recoverStale()
        assertEquals(1, store.saved.size)
        assertNull(pending.json)
    }

    @Test
    fun `recovery does nothing while a discovery is running`() = runTest {
        val store = MemoryStore()
        val pending = MemoryPending()
        val manager = manager(store, pending)
        val hanging = object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit
        }
        manager.start(start(FakeFactory(attempts = arrayOf(hanging))))
        runCurrent()
        manager.recoverStale()
        assertTrue(store.saved.isEmpty())
        assertTrue(pending.json != null)
        manager.cancel()
        runCurrent()
    }
}
