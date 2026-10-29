package edu.ucsd.sysnet.cluetoothscanner.gatt

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattJsonTest {
    // 2025-03-25T00:00:00Z
    private val midnight = 1_742_860_800_000L

    private fun id(n: Int) = "0195ca99-5000-7001-8000-00000000000$n"

    private fun fixture(n: Int) =
        Json.parseToJsonElement(File("../../test-fixtures/gatt-v1/2025/03/25/${id(n)}.json").readText())

    private val device = GattRequest(
        id = "0195ca99-5000-7131-b131-313131313131",
        sessionId = null,
        target = GattTarget("AA:BB:CC:DD:EE:FF", null, null, null),
        location = null,
        appVersion = "0.1.0",
        deviceModel = "Pixel 8",
        androidVersion = "15 (API 35)",
    )

    private fun completeResult() = GattDiscoveryResult(
        request = device.copy(
            id = id(1),
            sessionId = "0195ca99-4fff-7555-9555-555555555555",
            target = GattTarget(
                "AA:BB:CC:DD:EE:FF", "random", "sensor",
                GattTriggerAdv(
                    raw = byteArrayOf(2, 1, 6, 3, 2, 0xf0.toByte(), 0xfe.toByte()),
                    rssi = -61, scannedAtMs = midnight, isLegacy = false,
                    primaryPhy = 1, secondaryPhy = 2, advSid = 1,
                ),
            ),
            location = GattLocation(32.88, -117.23, 4.5),
        ),
        startedAtMs = midnight + 1_000,
        endedAtMs = midnight + 9_500,
        outcome = GattOutcome.COMPLETE,
        stageReached = GattStage.DISCONNECT,
        error = null,
        disconnect = GattDisconnect(DisconnectInitiator.LOCAL, 0),
        mtu = 247,
        phy = GattPhy(2, 2),
        timings = GattTimings(800, 120, 1_500, 4_000),
        services = listOf(
            GattServiceResult(
                "180A", true, 12,
                listOf(
                    GattCharacteristicResult(
                        "2A29", 14, listOf("read"),
                        GattReadValue.ok("Micro".toByteArray()),
                        listOf(GattDescriptorResult("2901", GattReadValue.ok(ByteArray(0)))),
                    ),
                ),
            ),
            GattServiceResult(
                "180F", true, 20,
                listOf(
                    GattCharacteristicResult(
                        "2A19", 22, listOf("read", "notify"),
                        GattReadValue.ok(byteArrayOf(0x5a)),
                        listOf(GattDescriptorResult("2902", GattReadValue.failed(GattStatus.INSUFFICIENT_AUTHENTICATION))),
                    ),
                    GattCharacteristicResult(
                        "6e400002-b5a3-f393-e0a9-e50e24dcca9e", 24, listOf("write"),
                        GattReadValue.NOT_READ_VALUE, emptyList(),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `complete discovery matches the golden contract object`() {
        assertEquals(fixture(1), GattJson.toJson(completeResult()))
    }

    @Test
    fun `partial discovery after a remote disconnect matches the golden object`() {
        val result = GattDiscoveryResult(
            request = device.copy(id = id(2), target = GattTarget("AA:BB:CC:DD:EE:FF", "public", null, null)),
            startedAtMs = midnight + 1_000,
            endedAtMs = midnight + 5_250,
            outcome = GattOutcome.PARTIAL,
            stageReached = GattStage.READ,
            error = GattError(GattStage.READ, 19, "GATT_CONN_TERMINATE_PEER_USER"),
            disconnect = GattDisconnect(DisconnectInitiator.REMOTE, 19),
            mtu = null,
            phy = null,
            timings = GattTimings(600, 3_000, 900, 1_200),
            services = listOf(
                GattServiceResult(
                    "180A", true, 12,
                    listOf(
                        GattCharacteristicResult("2A29", 14, listOf("read"), GattReadValue.ok("Micro".toByteArray()), emptyList()),
                        GattCharacteristicResult("2A25", 16, listOf("read"), GattReadValue.NOT_READ_VALUE, emptyList()),
                    ),
                ),
            ),
        )
        assertEquals(fixture(2), GattJson.toJson(result))
    }

    @Test
    fun `failed connect timeout matches the golden object`() {
        val result = GattDiscoveryResult(
            request = device.copy(id = id(3)),
            startedAtMs = midnight + 1_000,
            endedAtMs = midnight + 11_000,
            outcome = GattOutcome.FAILED,
            stageReached = GattStage.CONNECT,
            error = GattError(GattStage.CONNECT, GattStatus.TIMED_OUT, "TIMEOUT"),
            disconnect = GattDisconnect(DisconnectInitiator.TIMEOUT, null),
            mtu = null,
            phy = null,
            timings = GattTimings(null, null, null, null),
            services = emptyList(),
        )
        assertEquals(fixture(3), GattJson.toJson(result))
    }

    @Test
    fun `encoded text is parseable and keeps nullable members present`() {
        val text = GattJson.encode(completeResult())
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("gatt/v1", root.getValue("schema").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("error"))
        val keys = root.keys.toList()
        assertEquals(
            listOf(
                "schema", "id", "session_id", "app_version", "device", "target", "started_at", "ended_at",
                "location", "outcome", "stage_reached", "error", "disconnect", "mtu", "phy", "timings_ms", "services",
            ),
            keys,
        )
    }

    @Test
    fun `NUL characters from the peer are stripped from every string`() {
        val base = completeResult()
        val dirty = base.copy(
            request = base.request.copy(
                target = base.request.target.copy(name = "se\u0000nsor"),
            ),
            error = GattError(GattStage.READ, 19, "ERR\u0000OR"),
        )
        val root = GattJson.toJson(dirty)
        assertEquals("sensor", root.getValue("target").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("ERROR", root.getValue("error").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue('\u0000' !in GattJson.encode(dirty).replace("\\u0000", ""))
        // A name made only of NULs is "no name".
        val onlyNul = base.copy(request = base.request.copy(target = base.request.target.copy(name = "\u0000")))
        assertEquals(JsonNull, GattJson.toJson(onlyNul).getValue("target").jsonObject.getValue("name"))
    }

    @Test
    fun `a location the server would reject is dropped`() {
        assertEquals(GattLocation(32.0, -117.0, 4.0), GattLocation.validOrNull(32.0, -117.0, 4.0))
        assertEquals(null, GattLocation.validOrNull(91.0, 0.0, 1.0))
        assertEquals(null, GattLocation.validOrNull(0.0, 181.0, 1.0))
        assertEquals(null, GattLocation.validOrNull(0.0, 0.0, -1.0))
        assertEquals(null, GattLocation.validOrNull(Double.NaN, 0.0, 1.0))
        assertEquals(null, GattLocation.validOrNull(0.0, 0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `values over the 512 byte attribute limit are an error not a clipped ok`() {
        assertEquals(GattReadValue.ok(ByteArray(512)).status, "ok")
        assertEquals(GattReadValue(null, "error:13"), GattReadValue.ok(ByteArray(513)))
    }

    @Test
    fun `timestamps always carry exactly three fractional digits in UTC`() {
        assertEquals("2025-03-25T00:00:00.000Z", GattJson.timestamp(midnight))
        assertEquals("2025-03-25T00:00:01.050Z", GattJson.timestamp(midnight + 1_050))
        assertEquals("1970-01-01T00:00:00.001Z", GattJson.timestamp(1))
    }

    @Test
    fun `sixteen bit uuids are uppercase and the rest are lowercase 128 bit`() {
        assertEquals("180A", contractUuid(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")))
        assertEquals(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            contractUuid(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")),
        )
        // A 32-bit alias is not a 16-bit UUID.
        assertEquals(
            "12345678-0000-1000-8000-00805f9b34fb",
            contractUuid(UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")),
        )
    }

    @Test
    fun `property bits map in the contract order`() {
        assertEquals(listOf("read", "notify"), propertyNames(0x12))
        assertEquals(
            listOf("broadcast", "read", "write_no_response", "write", "notify", "indicate", "signed_write", "extended_props"),
            propertyNames(0xff),
        )
        assertTrue(propertyNames(0).isEmpty())
    }

    @Test
    fun `read statuses use the contract vocabulary`() {
        assertEquals("error:133", GattReadValue.failed(133).status)
        assertEquals("insufficient_encryption", GattReadValue.failed(15).status)
        assertEquals("read_not_permitted", GattReadValue.failed(2).status)
        assertEquals("00ff10", GattReadValue.ok(byteArrayOf(0, -1, 16)).hex)
    }
}
