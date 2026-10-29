package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the real engine against a scripted peripheral and compares the `gatt/v1` object the
 * real serializer produces with the fixtures under `test-fixtures/gatt-v1`, which the Rust
 * core validates against the producer contract and the sync side ingests. Set
 * `CLUETOOTH_UPDATE_FIXTURES=1` to rewrite them after an intended change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattFixtureTest {
    private val midnight = 1_742_860_800_000L
    private val pretty = Json { prettyPrint = true }

    private fun request(n: Int) = GattRequest(
        id = "0195ca99-5000-7001-8000-0000000000$n",
        sessionId = "0195ca99-4fff-7555-9555-555555555555",
        target = GattTarget(
            addr = "AA:BB:CC:DD:EE:FF",
            addrType = "random",
            name = "sensor",
            triggerAdv = GattTriggerAdv(
                raw = byteArrayOf(2, 1, 6, 3, 2, 0xf0.toByte(), 0xfe.toByte()),
                rssi = -61,
                scannedAtMs = midnight - 2_000,
                isLegacy = false,
                primaryPhy = 1,
                secondaryPhy = 2,
                advSid = 1,
            ),
        ),
        location = GattLocation(32.8801, -117.234, 4.5),
        appVersion = "0.1.0-debug",
        deviceModel = "Pixel 8",
        androidVersion = "15 (API 35)",
    )

    private suspend fun TestScope.run(n: Int, vararg behavior: FakeLink.Behavior): JsonElement {
        val engine = GattEngine(
            linkFactory = FakeFactory(attempts = behavior),
            nowMs = { midnight + testScheduler.currentTime },
        )
        // runTest drives virtual time, so a timeout costs nothing.
        return GattJson.toJson(engine.run(request(n)))
    }

    private fun check(n: Int, actual: JsonElement) {
        val file = File("../../test-fixtures/gatt-v1/2025/03/25/0195ca99-5000-7001-8000-0000000000$n.json")
        if (System.getenv("CLUETOOTH_UPDATE_FIXTURES") != null) {
            file.parentFile.mkdirs()
            file.writeText(pretty.encodeToString(JsonElement.serializer(), actual) + "\n")
        }
        assertEquals(
            "fixture ${file.name} differs from the producer output; rerun with CLUETOOTH_UPDATE_FIXTURES=1",
            Json.parseToJsonElement(file.readText()),
            actual,
        )
    }

    @Test
    fun `engine output for a complete discovery`() = runTest {
        check(11, run(11, Cooperative))
    }

    @Test
    fun `engine output for a remote disconnect mid read`() = runTest {
        check(
            12,
            run(12, object : FakeLink.Behavior {
                override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                    if (ref == AttributeRef(0, 1)) {
                        link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER))
                    } else {
                        link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                    }
                    return true
                }
            }),
        )
    }

    @Test
    fun `engine output with authentication required reads`() = runTest {
        check(
            13,
            run(13, object : FakeLink.Behavior {
                override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                    val status = when (ref) {
                        AttributeRef(0, 0) -> GattStatus.INSUFFICIENT_AUTHENTICATION
                        AttributeRef(1, 0) -> GattStatus.INSUFFICIENT_ENCRYPTION
                        else -> GattStatus.SUCCESS
                    }
                    link.emit(GattEvent.AttributeRead(ref, if (status == 0) valueFor(ref) else null, status))
                    return true
                }
            }),
        )
    }

    @Test
    fun `engine output for a status 133 then success`() = runTest {
        val failsWith133 = object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) {
                link.emit(GattEvent.Disconnected(GattStatus.ERROR))
            }
        }
        check(14, run(14, failsWith133, Cooperative))
    }

    @Test
    fun `engine output for a discovery timeout`() = runTest {
        check(
            15,
            run(15, object : FakeLink.Behavior {
                override fun onDiscover(link: FakeLink) = true
            }),
        )
    }

    @Test
    fun `engine output with error status reads on characteristics and descriptors`() = runTest {
        check(
            16,
            run(16, object : FakeLink.Behavior {
                override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                    val status = when (ref) {
                        AttributeRef(0, 0) -> GattStatus.ERROR // error:133
                        AttributeRef(0, 0, 0) -> 14 // error:14
                        AttributeRef(1, 0, 0) -> GattStatus.READ_NOT_PERMITTED
                        else -> GattStatus.SUCCESS
                    }
                    link.emit(GattEvent.AttributeRead(ref, if (status == 0) valueFor(ref) else null, status))
                    return true
                }
            }),
        )
    }

    @Test
    fun `engine output for a stalled read queue (read timeout and error status -3)`() = runTest {
        check(
            17,
            run(17, object : FakeLink.Behavior {
                override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                    if (ref == AttributeRef(0, 0)) {
                        link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                    }
                    return true // every later read stays silent
                }
            }),
        )
    }

    @Test
    fun `the placeholder a killed process leaves behind (error status -2)`() = runTest {
        val manager = GattDiscoveryManager(
            scope = this,
            store = object : GattStore {
                override suspend fun newDiscoveryId() = error("not used")
                override suspend fun save(json: String) = Unit
            },
            pending = object : PendingDiscoveryStore {
                override fun put(id: String, json: String) = Unit
                override fun entries() = emptyList<PendingDiscovery>()
                override fun remove(id: String) = Unit
            },
            device = GattDeviceInfo("0.1.0-debug", "Pixel 8", "15 (API 35)"),
            nowMs = { midnight + 4_000 },
        )
        val json = manager.interruptedJson(request(18), midnight, GattStage.READ)
        check(18, Json.parseToJsonElement(json))
    }
}
