package edu.ucsd.sysnet.cluetoothscanner.ble

import kotlinx.serialization.Serializable

@Serializable
data class AdPacket(
    val structures: List<AdStruct>
) {
    fun getStructureByType(type: UByte): AdStruct? {
        return structures.firstOrNull { it.type == type }
    }

    fun getStructuresByType(type: UByte): List<AdStruct> {
        return structures.filter { it.type == type }
    }
}
