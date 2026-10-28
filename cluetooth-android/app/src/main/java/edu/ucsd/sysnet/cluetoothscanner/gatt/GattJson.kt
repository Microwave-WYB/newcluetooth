package edu.ucsd.sysnet.cluetoothscanner.gatt

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes one finished discovery as the `gatt/v1` object of docs/payload-schema-v3.md.
 * The Rust core validates and stores these exact bytes, so this is the only writer of the
 * format; keys keep a fixed order and every nullable member is present as `null`.
 */
object GattJson {
    const val SCHEMA = "gatt/v1"

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `YYYY-MM-DDTHH:MM:SS.mmmZ`, always UTC with millisecond precision. */
    fun timestamp(epochMs: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(epochMs))

    fun encode(result: GattDiscoveryResult): String = toJson(result).toString()

    fun toJson(result: GattDiscoveryResult): JsonObject {
        val request = result.request
        return buildJsonObject {
            put("schema", SCHEMA)
            put("id", request.id)
            put("session_id", nullable(request.sessionId))
            put("app_version", request.appVersion)
            put(
                "device",
                buildJsonObject {
                    put("model", request.deviceModel)
                    put("android", request.androidVersion)
                },
            )
            put("target", target(request.target))
            put("started_at", timestamp(result.startedAtMs))
            put("ended_at", timestamp(result.endedAtMs))
            put(
                "location",
                request.location?.let {
                    buildJsonObject {
                        put("lat", it.lat)
                        put("lon", it.lon)
                        put("accuracy", it.accuracy)
                    }
                } ?: JsonNull,
            )
            put("outcome", result.outcome.wire)
            put("stage_reached", result.stageReached.wire)
            put(
                "error",
                result.error?.let {
                    buildJsonObject {
                        put("stage", it.stage.wire)
                        put("status", it.status)
                        put("name", it.name)
                    }
                } ?: JsonNull,
            )
            put(
                "disconnect",
                result.disconnect?.let {
                    buildJsonObject {
                        put("initiator", it.initiator.wire)
                        put("status", nullable(it.status))
                    }
                } ?: JsonNull,
            )
            put("mtu", nullable(result.mtu))
            put(
                "phy",
                result.phy?.let {
                    buildJsonObject {
                        put("tx", it.tx)
                        put("rx", it.rx)
                    }
                } ?: JsonNull,
            )
            put(
                "timings_ms",
                buildJsonObject {
                    put("connect", nullable(result.timings.connectMs))
                    put("mtu", nullable(result.timings.mtuMs))
                    put("discover", nullable(result.timings.discoverMs))
                    put("read", nullable(result.timings.readMs))
                },
            )
            put("services", JsonArray(result.services.map(::service)))
        }
    }

    private fun target(target: GattTarget) = buildJsonObject {
        put("addr", target.addr)
        put("addr_type", nullable(target.addrType))
        put("name", nullable(target.name))
        put(
            "trigger_adv",
            target.triggerAdv?.let {
                buildJsonObject {
                    put("raw", it.raw.toHex())
                    put("rssi", nullable(it.rssi))
                    put("scanned_at", timestamp(it.scannedAtMs))
                    put("is_legacy", nullable(it.isLegacy))
                    put("primary_phy", nullable(it.primaryPhy))
                    put("secondary_phy", nullable(it.secondaryPhy))
                    put("adv_sid", nullable(it.advSid))
                }
            } ?: JsonNull,
        )
    }

    private fun service(service: GattServiceResult) = buildJsonObject {
        put("uuid", service.uuid)
        put("primary", service.primary)
        put("instance", service.instance)
        put("characteristics", JsonArray(service.characteristics.map(::characteristic)))
    }

    private fun characteristic(characteristic: GattCharacteristicResult) = buildJsonObject {
        put("uuid", characteristic.uuid)
        put("instance", characteristic.instance)
        put("properties", buildJsonArray { characteristic.properties.forEach { add(JsonPrimitive(it)) } })
        put("value", value(characteristic.value))
        put(
            "descriptors",
            JsonArray(
                characteristic.descriptors.map { descriptor ->
                    buildJsonObject {
                        put("uuid", descriptor.uuid)
                        // Android exposes no descriptor instance id.
                        put("instance", JsonNull)
                        put("value", value(descriptor.value))
                    }
                },
            ),
        )
    }

    private fun value(value: GattReadValue) = buildJsonObject {
        put("hex", nullable(value.hex))
        put("status", value.status)
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Int?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Boolean?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}
