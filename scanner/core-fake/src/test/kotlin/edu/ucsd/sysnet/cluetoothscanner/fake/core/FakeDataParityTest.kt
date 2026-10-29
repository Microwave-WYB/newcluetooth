package edu.ucsd.sysnet.cluetoothscanner.fake.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The port is exact: every value data.js generates (MACs, histories, AD structures, points, routes, trails) equals
 * `src/test/resources/data-js-fixture.json`, which `fixture-from-data-js.mjs` writes from data.js itself.
 */
class FakeDataParityTest {
    private val fixture = Json.parseToJsonElement(
        javaClass.getResource("/data-js-fixture.json")!!.readText()
    ).jsonObject
    private val world = FakeData.build()

    @Test
    fun liveDevicesMatchDataJs() {
        val devices = fixture.getValue("devices").jsonArray
        assertEquals(devices.size, world.devices.size)
        devices.forEachIndexed { i, d ->
            val o = d.jsonObject
            val ours = world.devices[i]
            assertEquals(o.getValue("addr").jsonPrimitive.content, ours.addr)
            val history = o.getValue("history").jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("history of ${ours.addr}", history, ours.history)
            val structures = o.getValue("structures").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(structures, ours.structures.map { "%02X".format(it.type) + ":" + it.dataHex })
        }
    }

    @Test
    fun recordingsAndRoutesMatchDataJs() {
        val recordings = fixture.getValue("recordings").jsonObject
        val routes = fixture.getValue("routes").jsonObject
        for ((id, points) in recordings) {
            val ours = world.recordings.first { it.id == id }
            val expected = points.jsonArray.map { p ->
                val a = p.jsonArray
                listOf(a[0].jsonPrimitive.content, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double, a[3].jsonPrimitive.int)
            }
            assertEquals("points of $id", expected, ours.points.map { listOf(it.addr, it.lon, it.lat, it.rssi) })
            val route = (routes.getValue(id) as JsonArray).map { pt -> pt.jsonArray.map { it.jsonPrimitive.double } }
            assertEquals("route of $id", route, ours.route.map { it.toList() })
        }
    }

    @Test
    fun sightingTrailsMatchDataJs() {
        fixture.getValue("sightings").jsonArray.forEachIndexed { i, trail ->
            val expected = trail.jsonArray.map { s -> s.jsonArray.let { listOf(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.int.toDouble()) } }
            val ours = FakeData.sightings(world, world.devices[i].addr).map { listOf(it.lon, it.lat, it.rssi.toDouble()) }
            assertEquals(expected, ours)
        }
    }

    @Test
    fun allSessionsUnionMatchesDataJs() {
        val all = fixture.getValue("all").jsonObject
        val stored = world.recordings.filter { !it.current && it.devices > 0 }
        assertEquals(all.getValue("sessions").jsonPrimitive.int, stored.size)
        assertEquals(all.getValue("devices").jsonPrimitive.int, stored.flatMap { r -> r.points.map { it.addr } }.toSet().size)
    }
}
