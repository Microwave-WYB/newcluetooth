package edu.ucsd.sysnet.cluetoothscanner.data

import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encodeToString(value, Base64.NO_WRAP))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.decode(decoder.decodeString(), Base64.NO_WRAP)
    }
}

@Serializable
data class BleRecord(
    val mac: String,
    val rssi: Int?,
    val timestamp: String,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    @Serializable(with = Base64ByteArraySerializer::class)
    val raw: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BleRecord

        if (mac != other.mac) return false
        if (rssi != other.rssi) return false
        if (timestamp != other.timestamp) return false
        if (lat != other.lat) return false
        if (lon != other.lon) return false
        if (accuracy != other.accuracy) return false
        if (!raw.contentEquals(other.raw)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mac.hashCode()
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (lat?.hashCode() ?: 0)
        result = 31 * result + (lon?.hashCode() ?: 0)
        result = 31 * result + (accuracy?.hashCode() ?: 0)
        result = 31 * result + raw.contentHashCode()
        return result
    }

    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromJson(json: String): BleRecord {
            return Json.decodeFromString(json)
        }
    }
}
