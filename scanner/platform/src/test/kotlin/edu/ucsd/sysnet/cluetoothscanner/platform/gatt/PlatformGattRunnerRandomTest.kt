package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.platform.TestCoreIngest
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget as CoreGattTarget

/**
 * Overlapping discoveries on random fake devices, with collectors cancelled at random: every collector that was not
 * itself cancelled ends on a final view (nothing spins), and two links are never open at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformGattRunnerRandomTest {
    private class Memory : PendingDiscoveryStore {
        val map = linkedMapOf<String, String>()
        override fun put(id: String, json: String) {
            map[id] = json
        }

        override fun entries() = map.entries.sortedBy { it.key }.map { PendingDiscovery(it.key, it.value) }
        override fun remove(id: String) {
            map.remove(id)
        }
    }

    private val behaviors: List<FakeLink.Behavior> = listOf(
        Cooperative,
        object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink) = true // never answers
        },
        object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit // never connects
        },
        object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER))
                return true
            }
        },
    )

    @Test
    fun `random overlapping discoveries all end on a final view, one link at a time`() {
        repeat(SEEDS) { seed ->
            runTest {
                val random = Random(seed)
                var ids = 0
                var open = 0
                var maxOpen = 0
                val core = object : TestCoreIngest() {
                    override fun newGattDiscoveryId() = "0195ca99-5000-7131-b131-%012d".format(++ids)
                    override suspend fun gattTarget(key: DeviceKey) =
                        CoreGattTarget("AA:BB:CC:DD:EE:%02X".format(key), "random", null, byteArrayOf(2, 1, 6), -60, 0, true, 1, null, null, null)

                    override fun ingestGatt(gattV1Json: String): GattView {
                        val id = Json.parseToJsonElement(gattV1Json).jsonObject.getValue("id").jsonPrimitive.content
                        return GattView(id, 0, 0, ok = true, steps = emptyList(), services = emptyList())
                    }
                }
                val runner = PlatformGattRunner(
                    core = CompletableDeferred<CoreIngest>(core),
                    io = StandardTestDispatcher(testScheduler),
                    manager = { store ->
                        GattDiscoveryManager(
                            backgroundScope, store, Memory(), GattDeviceInfo("t", "m", "a"),
                            nowMs = { testScheduler.currentTime }, elapsedMs = { testScheduler.currentTime },
                        )
                    },
                    available = { random.nextInt(10) != 0 },
                    links = {
                        val behavior = behaviors.random(random)
                        GattLinkFactory { sink ->
                            val inner = FakeFactory(attempts = arrayOf(behavior), scope = backgroundScope).connect(sink)
                            open++
                            maxOpen = maxOf(maxOpen, open)
                            object : GattLink by inner {
                                override fun close() {
                                    open--
                                    inner.close()
                                }
                            }
                        }
                    },
                    nowMs = { testScheduler.currentTime },
                )
                val collectors = mutableListOf<Triple<Job, MutableList<GattView>, Boolean>>()
                repeat(DISCOVERIES) { n ->
                    val views = mutableListOf<GattView>()
                    val job = launch { runner.discover(n.toLong()).collect { views += it } }
                    val cancelIt = random.nextInt(4) == 0
                    collectors += Triple(job, views, cancelIt)
                    delay(random.nextLong(0, 8_000))
                    if (cancelIt) job.cancel()
                }
                collectors.forEach { it.first.join() }
                collectors.forEachIndexed { n, (job, views, cancelled) ->
                    if (cancelled || job.isCancelled) return@forEachIndexed
                    val last = views.lastOrNull() ?: fail("seed $seed discovery $n: no view at all")
                    if (last is GattView) {
                        assertTrue("seed $seed discovery $n ends on a final view: ${last.steps}", last.steps.none { it.state == GattStepState.Running })
                    }
                }
                assertTrue("seed $seed: two links were open at once", maxOpen <= 1)
            }
        }
    }

    private companion object {
        const val SEEDS = 40
        const val DISCOVERIES = 8
    }
}
