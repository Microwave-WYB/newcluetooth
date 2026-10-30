package edu.ucsd.sysnet.cluetoothscanner.corebridge

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreAssets
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.fake.core.CoreContracts
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real core through the real UniFFI bindings and the host library: the data.js world is recorded through
 * [CoreBridge]'s CoreIngest, then the same [CoreContracts] that `:core-fake` passes run against it.
 */
class CoreBridgeTest {
    companion object {
        private lateinit var dir: File
        private lateinit var core: CoreBridge
        private val sessions = linkedMapOf<String, String>()
        private val products = HashMap<String, String?>()
        private val pageOfProduct = HashMap<String, String>()
        private lateinit var categories: List<String>

        private fun resource(name: String) = File(System.getProperty("cluetooth.fake.resources"), name).readText()

        private val env = ScanEnvironment("Pixel 8", "15", 35, "0.1.0", true, true, 1650, "all_supported", false)

        private fun raw(structures: List<String>): ByteArray = structures.flatMap { s ->
            val (type, hex) = s.split(":")
            val data = hex.chunked(2).map { it.toInt(16).toByte() }
            listOf((data.size + 1).toByte(), type.toInt(16).toByte()) + data
        }.toByteArray()

        @BeforeClass @JvmStatic
        fun seed() {
            dir = Files.createTempDirectory("core-bridge-test").toFile()
            val bundle = resource("fake-bundle.json")
            val b = Json.parseToJsonElement(bundle).jsonObject
            categories = b.getValue("vocabularies").jsonObject.getValue("categories").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            for (p in b.getValue("pages").jsonArray) {
                val o = p.jsonObject
                o["fake_product"]?.jsonPrimitive?.contentOrNull?.let { pageOfProduct[it] = o.getValue("id").jsonPrimitive.content }
            }
            core = CoreBridge.open(
                CoreConfig(dataDirectory = dir.path, recipientPublicKey = edu.ucsd.sysnet.cluetoothscanner.core.generated.defaultCoreConfig().recipientPublicKey),
                CoreAssets(bundle, "company\t0057\tHarman International\n"),
            )
            val fixture = Json.parseToJsonElement(resource("data-js-fixture.json")).jsonObject
            val devices: Map<String, JsonObject> = fixture.getValue("allDevices").jsonArray
                .associate { it.jsonObject.getValue("addr").jsonPrimitive.content to it.jsonObject }
            devices.forEach { (addr, d) -> products[addr] = d["product"]?.jsonPrimitive?.contentOrNull }
            var nanos = 0L
            for (id in listOf("r6", "r5", "r4", "r3", "r2", "r1")) {
                val uuid = core.startRecording(env)
                val now = System.currentTimeMillis()
                for ((i, p) in fixture.getValue("recordings").jsonObject.getValue(id).jsonArray.withIndex()) {
                    val a = p.jsonArray
                    val addr = a[0].jsonPrimitive.content
                    nanos += 1_000_000_000
                    core.ingestLocation(LocationFix(a[2].jsonPrimitive.double, a[1].jsonPrimitive.double, 8.0, now + i, nanos))
                    val d = devices.getValue(addr)
                    val ack = core.ingestScans(listOf(ScanObservationInput(
                        addr, a[3].jsonPrimitive.int, now + i, nanos,
                        raw(d.getValue("structures").jsonArray.map { it.jsonPrimitive.content }),
                        d["name"]?.jsonPrimitive?.contentOrNull, -12, d["connectable"]?.jsonPrimitive?.booleanOrNull,
                        d["legacy"]?.jsonPrimitive?.booleanOrNull, 1, 0, null, null, null, 0, 1,
                    )))
                    check(ack.accepted == 1) { "rejected $addr" }
                }
                check(core.stopRecording() == uuid)
                sessions[id] = uuid
            }
        }

        @AfterClass @JvmStatic
        fun teardown() {
            core.close()
            dir.deleteRecursively()
        }
    }

    private fun session(id: String) = Scope.session(sessions.getValue(id))

    /** Lane D's D-3a contract, the same check `FakeCoreTest` runs. */
    @Test
    fun facetCountsEqualListLength() = runBlocking {
        assertEquals(emptyList<String>(), CoreContracts.facetCountsEqualListLength(core, listOf(Scope.All, session("r1")), categories))
    }

    @Test
    fun filtersOnlyHideAndSearchKeepsOrder() = runBlocking {
        assertEquals(emptyList<String>(), CoreContracts.filtersOnlyHide(core, listOf(Scope.All, session("r1"), session("r3"))))
        assertEquals(emptyList<String>(), CoreContracts.searchKeepsOrder(core, Scope.All, "govee"))
    }

    /** Matching reproduces data.js's product of every device. */
    @Test
    fun pagesMatchTheFake() = runBlocking {
        val order = core.deviceOrder(Scope.All).keys
        val rows = order.toList().chunked(200).flatMap { core.deviceRows(Scope.All, it.toLongArray()) }
        assertEquals(order.size, rows.size)
        for (r in rows) assertEquals(r.addr, products[r.addr]?.let { pageOfProduct.getValue(it) }, r.page?.id)
    }

    /** L-2 newest first without the empty r4; §2.1 a union of two sessions draws faint routes. */
    @Test
    fun sessionsAndViews() = runBlocking {
        assertEquals(listOf("r1", "r2", "r3", "r5", "r6").map { sessions.getValue(it) }, core.sessions().map { it.id })
        assertEquals(5, core.allSessionsSummary().sessionCount)
        val two = Scope(SessionScope.Sessions(listOf(sessions.getValue("r1"), sessions.getValue("r3"))))
        val view = core.sessionView(two)
        assertTrue(view.routesFaint)
        assertEquals(2, view.sessionIds.size)
        assertTrue(core.sessionView(session("r1")).route.size > 1)
        assertTrue(core.routes(Scope.All, null, 14.0).contains("LineString"))
    }

    /** §2.5: map points carry the uppercase colon MAC; the display filter reaches every count (G-12). */
    @Test
    fun mapPointsAndDisplayFilter() = runBlocking {
        val data = core.mapPoints(Scope.All, DeviceFilter(), Bbox(-117.236, 32.878, -117.232, 32.882), 16.0)
        assertEquals(MapMode.Points, data.mode)
        val addrs = Regex("\"addr\":\"([^\"]*)\"").findAll(data.geojson).map { it.groupValues[1] }.toList()
        assertEquals(data.count, addrs.size)
        assertTrue(addrs.all { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) })
        val shown = core.deviceOrder(Scope.All).keys.size
        val events = async { core.events.first { it is CoreEvent.DataInvalidated } }
        kotlinx.coroutines.yield()
        core.setDisplayFilter(DisplayFilter(hideUnnamedApple = false))
        events.await()
        try {
            assertTrue(core.deviceOrder(Scope.All).keys.size > shown)
        } finally {
            core.setDisplayFilter(DisplayFilter(hideUnnamedApple = true))
        }
    }

    @Test
    fun detailAndErrors() = runBlocking {
        val jbl = core.search(Scope.All, "JBL Flip 6", null)!!.keys.first()
        val d = core.device(Scope.All, jbl)!!
        assertEquals("Harman International", d.companyName)
        assertNull(d.decoded)
        assertTrue(core.deviceSightings(Scope.All, jbl).isNotEmpty())
        assertEquals(RecordingPhase.Idle, core.recordingState().phase)
        try { core.stopRecording(); fail() } catch (_: CoreException.NotRecording) {}
        assertTrue(core.matchDevice("4b4b", null, "72:CD:4C:4B:4B:6F"))
    }

    /** D-13 through the bindings: a target for a seen device, the stored run, and the device's last result. */
    @Test
    fun gattThroughTheBindings() = runBlocking {
        val jbl = core.search(Scope.All, "JBL Flip 6", null)!!.keys.first()
        val target = core.gattTarget(jbl)!!
        assertTrue(target.addr.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        assertEquals("random", target.addrType)
        assertTrue(target.triggerRaw.isNotEmpty())
        assertNull(core.gattTarget(0x010203040506L))
        val id = core.newGattDiscoveryId()
        val json = """{"schema":"gatt/v1","id":"$id","session_id":null,"app_version":"0.1.0",
            "device":{"model":"Pixel 8","android":"15"},
            "target":{"addr":"${target.addr}","addr_type":"random","name":"JBL Flip 6","trigger_adv":null},
            "started_at":"2026-10-28T19:00:01.000Z","ended_at":"2026-10-28T19:00:09.500Z","location":null,
            "outcome":"complete","stage_reached":"disconnect","error":null,
            "disconnect":{"initiator":"local","status":0},"mtu":247,"phy":{"tx":2,"rx":2},
            "timings_ms":{"connect":800,"mtu":120,"discover":1500,"read":4000},
            "services":[{"uuid":"180F","primary":true,"instance":12,"characteristics":[
              {"uuid":"2A19","instance":14,"properties":["read"],"value":{"hex":"4e","status":"ok"},"descriptors":[]}]}]}"""
        val view = core.ingestGatt(json)
        assertTrue(view.ok)
        assertEquals(jbl, view.key)
        assertEquals("78 %", view.services.single().characteristics.single().valueText)
        assertEquals(view, core.deviceGatt(jbl))
        try { core.ingestGatt("{}"); fail() } catch (_: CoreException.InvalidPayload) {}
    }

    /** AGENTS.md crash rules: every binding failure is typed; a Rust panic (InternalException) is Internal. */
    /** SPEC R-10 through the bindings: typed results (this shared fixture has no uploaded raw files). */
    @Test
    fun storageFullCallsReturnResults() = runBlocking {
        val r = core.reclaimable()
        assertTrue("$r", r is CoreResult.Ok && r.value.localDatabaseBytes >= 0 && r.value.uploadedRawBytes == 0L)
        assertEquals(CoreResult.Ok(0L), core.deleteUploadedRaw())
        val full = bindingFailure(edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.StorageFull(5uL))
        assertEquals(5L, (full as? CoreException.StorageFull)?.freeBytes)
        val failed = result<Unit> { throw GInternalException("x") }
        assertTrue("$failed", failed is CoreResult.Err && failed.error is CoreException.Internal)
    }

    @Test
    fun bindingFailuresAreTyped() {
        assertTrue(bindingFailure(GInternalException("boom")) is CoreException.Internal)
        assertTrue(bindingFailure(edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.NotRecording()) is CoreException.NotRecording)
        assertTrue(bindingFailure(edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Cancelled()) is kotlin.coroutines.cancellation.CancellationException)
        assertTrue(bindingFailure(IllegalStateException("x")) is CoreException.Internal)
        val c = kotlin.coroutines.cancellation.CancellationException("scope")
        assertTrue(bindingFailure(c) === c)
    }
}
