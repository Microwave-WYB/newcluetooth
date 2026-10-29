package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget as CoreGattTarget

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformGattRunnerTest {
    private val ids = ArrayDeque(listOf(
        "0195ca99-5000-7131-b131-313131313131",
        "0195ca99-5000-7131-b131-313131313132",
        "0195ca99-5000-7131-b131-313131313133",
    ))
    private val ingested = mutableListOf<String>()
    private var refuseIngest = false
    private val coreViews = mutableMapOf<String, GattView>()

    private val core = object : TestCoreIngest() {
        override fun newGattDiscoveryId() = ids.removeFirst()
        override suspend fun gattTarget(key: DeviceKey) = if (key == 99L) null else CoreGattTarget(
            "AA:BB:CC:DD:EE:FF", "random", "sensor", byteArrayOf(2, 1, 6), -60, 1_000, true, 1, null, null, "s1",
        )

        override fun ingestGatt(gattV1Json: String): GattView {
            if (refuseIngest) error("disk full")
            ingested += gattV1Json
            val id = Json.parseToJsonElement(gattV1Json).jsonObject["id"]!!.jsonPrimitive.content
            return GattView(id, 7, 0, ok = true, steps = emptyList(), services = emptyList()).also { coreViews[id] = it }
        }
    }

    private class MemoryPending : PendingDiscoveryStore {
        val map = linkedMapOf<String, String>()
        override fun put(id: String, json: String) {
            map[id] = json
        }

        override fun entries() = map.entries.sortedBy { it.key }.map { PendingDiscovery(it.key, it.value) }
        override fun remove(id: String) {
            map.remove(id)
        }
    }

    private val pending = MemoryPending()

    private fun TestScope.runner(available: Boolean = true, behavior: FakeLink.Behavior = Cooperative): PlatformGattRunner {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PlatformGattRunner(
            core = CompletableDeferred<CoreIngest>(core),
            io = dispatcher,
            manager = { store ->
                GattDiscoveryManager(
                    scope = backgroundScope,
                    store = store,
                    pending = pending,
                    device = GattDeviceInfo("0.1.0-test", "Pixel 8", "15 (API 35)"),
                    nowMs = { testScheduler.currentTime },
                    elapsedMs = { testScheduler.currentTime },
                )
            },
            available = { available },
            links = { FakeFactory(attempts = arrayOf(behavior), scope = backgroundScope) },
            nowMs = { testScheduler.currentTime },
        )
    }

    private fun json(n: Int) = Json.parseToJsonElement(ingested[n]).jsonObject

    @Test
    fun `section 4_3 Bluetooth unavailable fails Connecting with GATT_UNAVAILABLE`() = runTest {
        val views = runner(available = false).discover(7).toList()
        assertEquals(GattFailure.Other(GattRunner.GATT_UNAVAILABLE), views.single().steps.single().failure)
        assertTrue("nothing connected or stored", ingested.isEmpty())
    }

    @Test
    fun `section 4_3 no core target fails Connecting with GATT_NO_TARGET`() = runTest {
        val views = runner().discover(99).toList()
        assertEquals(GattFailure.Other(GattRunner.GATT_NO_TARGET), views.single().steps.single().failure)
        assertTrue(ingested.isEmpty())
    }

    @Test
    fun `D-13 progress views, then the view the core stored`() = runTest {
        val views = runner().discover(7).toList()
        val final = views.last()
        assertSame(coreViews.values.single(), final)
        assertTrue(views.dropLast(1).isNotEmpty())
        assertTrue(views.dropLast(1).all { view -> view.steps.last().state == GattStepState.Running })
        assertEquals("complete", json(0)["outcome"]!!.jsonPrimitive.content)
        assertEquals("s1", json(0)["session_id"]!!.jsonPrimitive.content)
        assertTrue("stored, so nothing is left queued", pending.map.isEmpty())
    }

    @Test
    fun `section 4_3 a failed save keeps the result queued and shows it`() = runTest {
        refuseIngest = true
        val final = runner().discover(7).toList().last()
        assertTrue(final.ok)
        assertEquals(1, pending.map.size)
    }

    @Test
    fun `D-13_8 cancelling the collector cancels the discovery, which is saved as cancelled`() = runTest {
        val gatt = runner(behavior = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true // never answers: the run stays in discovery
        })
        val views = mutableListOf<GattView>()
        val collector = launch { gatt.discover(7).collect { views += it } }
        runCurrent()
        assertTrue(views.isNotEmpty())
        // The manager runs in backgroundScope, which advanceUntilIdle does not drive: join instead.
        collector.cancelAndJoin()
        assertEquals("cancelled", json(0)["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `one discovery at a time - a new one ends the previous run, not its collector`() = runTest {
        val gatt = runner(behavior = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true
        })
        val firstViews = mutableListOf<GattView>()
        val first = launch { gatt.discover(7).collect { firstViews += it } }
        runCurrent()
        assertEquals("device X's tab is spinning", GattStepState.Running, firstViews.last().steps.last().state)
        val second = launch { gatt.discover(8).collect { } }
        first.join()
        second.join()
        assertTrue(first.isCompleted)
        assertFalse("the earlier collector is not cancelled", first.isCancelled)
        val final = firstViews.last()
        assertTrue("the last view is final: nothing spins", final.steps.none { it.state == GattStepState.Running })
        assertEquals(GattFailure.Cancelled, final.steps.last().failure)
        assertFalse(final.ok)
        assertEquals("the first run was stored as cancelled", "cancelled", json(0)["outcome"]!!.jsonPrimitive.content)
        assertTrue(second.isCompleted)
    }
}
