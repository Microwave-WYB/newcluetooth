package edu.ucsd.sysnet.cluetoothscanner.ble

import kotlinx.serialization.Serializable

@Serializable
data class AdStruct(
    val type: UByte,
    val data: ByteArray
) {
    val length: Int get() = data.size + 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AdStruct

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
