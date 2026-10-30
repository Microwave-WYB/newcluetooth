package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** The §2.7 mapping from a gatt/v1 object to a [GattView], and the data.js GATT demo results. */
object FakeGatt {
    private val SERVICE_NAMES = mapOf("1800" to "Generic Access", "1801" to "Generic Attribute",
        "180A" to "Device Information", "180F" to "Battery Service")
    private val CHAR_NAMES = mapOf("2A00" to "Device Name", "2A01" to "Appearance", "2A19" to "Battery Level",
        "2A24" to "Model Number String", "2A26" to "Firmware Revision String", "2A29" to "Manufacturer Name String")

    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    /** §2.7: steps from `stage_reached`, `error` and `timings_ms` (MTU folded into Connecting). */
    fun view(json: String): GattView = parse(json).first

    /** The view and the gatt/v1 `outcome`. */
    fun parse(json: String): Pair<GattView, String> {
        val o = try { Json.parseToJsonElement(json).jsonObject } catch (e: Exception) {
            throw CoreException.InvalidPayload("gatt/v1 is not JSON: ${e.message}")
        }
        if (o["schema"].str() != "gatt/v1") throw CoreException.InvalidPayload("schema must be gatt/v1")
        val id = o["id"].str() ?: throw CoreException.InvalidPayload("id is missing")
        val addr = o["target"]["addr"].str() ?: throw CoreException.InvalidPayload("target.addr is missing")
        val outcome = o["outcome"].str() ?: throw CoreException.InvalidPayload("outcome is missing")
        val started = o["started_at"].str()?.let { Instant.parse(it).toEpochMilli() } ?: 0L
        val timings = o["timings_ms"]
        val services = (o["services"] as? JsonArray).orEmpty().map { s ->
            val uuid = s["uuid"].str().orEmpty()
            GattServiceView(uuid, SERVICE_NAMES[uuid], (s["characteristics"] as? JsonArray).orEmpty().map { c ->
                val cu = c["uuid"].str().orEmpty()
                val props = (c["properties"] as? JsonArray).orEmpty().mapNotNull { it.str() }
                val value = c["value"]
                GattCharView(cu, CHAR_NAMES[cu], props, valueText(cu, value["hex"].str().takeIf { value["status"].str() == "ok" }))
            })
        }
        val chars = services.flatMap { it.characteristics }
        val readable = (o["services"] as? JsonArray).orEmpty().sumOf { s ->
            (s["characteristics"] as? JsonArray).orEmpty().count { c ->
                (c["properties"] as? JsonArray).orEmpty().any { it.str() == "read" }
            }
        }
        val read = chars.count { it.valueText != null }
        val errorStage = o["error"]["stage"].str()
        val errorStatus = o["error"]["status"].int()
        val initiator = o["disconnect"]["initiator"].str()
        // §2.7, the core's table (#99): a known status (the error's, else the disconnect's; 0 is none) is Other
        val status = (errorStatus ?: o["disconnect"]["status"].int())?.takeIf { it != 0 }
        val failure: GattFailure? = when {
            outcome == "complete" -> null
            outcome == "cancelled" -> GattFailure.Cancelled
            initiator == "timeout" || status == -1 -> GattFailure.TimedOut
            status != null -> GattFailure.Other(status)
            errorStage == "connect" -> GattFailure.ConnectionFailed
            initiator == "remote" -> GattFailure.DisconnectedUnexpectedly
            else -> GattFailure.Other(133)
        }
        val failedAt = when (errorStage ?: o["stage_reached"].str()) {
            "connect", "mtu" -> 0
            "discover" -> 1
            "read" -> 2
            else -> 3
        }
        val connectMs = listOfNotNull(timings["connect"].int(), timings["mtu"].int()).takeIf { it.isNotEmpty() }?.sum()
        val all = listOf(
            GattStep(GattStepKind.Connecting, GattStepState.Done, connectMs, null, null, null, null),
            GattStep(GattStepKind.DiscoveringServices, GattStepState.Done, timings["discover"].int(), services.size, null, null, null),
            GattStep(GattStepKind.ReadingCharacteristics, GattStepState.Done, timings["read"].int(), null, read, readable, null),
            GattStep(GattStepKind.Disconnected, GattStepState.Done, null, null, null, null, null),
        )
        val steps = if (failure == null) all
        else all.take(failedAt) + all[failedAt].copy(state = GattStepState.Failed, failure = failure)
        return GattView(id, DeviceKeyCodec.key(addr), started, outcome == "complete", steps,
            if (outcome == "complete") services else emptyList()) to outcome  // D-13.6
    }

    /** Display-only value text (§2.7): Battery Level as "N %", printable UTF-8 as text, else hex. */
    fun valueText(uuid: String, hex: String?): String? {
        if (hex == null) return null
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (uuid == "2A19" && bytes.size == 1) return "${bytes[0].toInt() and 0xff} %"
        val text = bytes.toString(Charsets.UTF_8)
        return if (bytes.isNotEmpty() && text.none { it == '�' || it < ' ' }) text else hex
    }

    const val JBL_DISCOVERY_ID = "019a2d4e-5c00-7000-8000-000000000001"

    /** data.js `GATT`: the JBL Flip 6 discovery that already ran. */
    fun jblView(key: Long, startedMs: Long): GattView {
        val services = listOf(
            GattServiceView("1800", "Generic Access", listOf(
                GattCharView("2A00", "Device Name", listOf("read"), "JBL Flip 6"),
                GattCharView("2A01", "Appearance", listOf("read"), "Speaker"))),
            GattServiceView("180A", "Device Information", listOf(
                GattCharView("2A29", "Manufacturer Name String", listOf("read"), "Harman International"),
                GattCharView("2A24", "Model Number String", listOf("read"), "Flip 6"),
                GattCharView("2A26", "Firmware Revision String", listOf("read"), "3.1.4"))),
            GattServiceView("180F", "Battery Service", listOf(
                GattCharView("2A19", "Battery Level", listOf("read", "notify"), "78 %"))),
            GattServiceView("65786365-6c70-6f69-6e74-2e636f6d0000", null, listOf(
                GattCharView("65786365-6c70-6f69-6e74-2e636f6d0001", null, listOf("write", "notify"), null))),
        )
        return GattView(JBL_DISCOVERY_ID, key, startedMs, true, listOf(
            GattStep(GattStepKind.Connecting, GattStepState.Done, 800, null, null, null, null),
            GattStep(GattStepKind.DiscoveringServices, GattStepState.Done, 1500, services.size, null, null, null),
            GattStep(GattStepKind.ReadingCharacteristics, GattStepState.Done, 4000, null, 6, 6, null),
            GattStep(GattStepKind.Disconnected, GattStepState.Done, null, null, null, null, null),
        ), services)
    }
}
