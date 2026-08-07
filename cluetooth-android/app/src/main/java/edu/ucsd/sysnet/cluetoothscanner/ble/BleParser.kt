package edu.ucsd.sysnet.cluetoothscanner.ble

object BleParser {

    fun parseAdvertisementData(bytes: ByteArray): AdPacket {
        val structures = mutableListOf<AdStruct>()
        var offset = 0

        while (offset < bytes.size) {
            if (offset + 1 >= bytes.size) break

            val length = bytes[offset].toInt() and 0xFF
            if (length == 0) break

            if (offset + length >= bytes.size) break

            val type = bytes[offset + 1].toUByte()
            val data = bytes.copyOfRange(offset + 2, offset + 1 + length)

            structures.add(AdStruct(type, data))
            offset += length + 1
        }

        return AdPacket(structures)
    }

    fun getAdTypeDescription(type: UByte): String {
        return when (type) {
            0x01.toUByte() -> "Flags"
            0x02.toUByte() -> "Incomplete List of 16-bit Service Class UUIDs"
            0x03.toUByte() -> "Complete List of 16-bit Service Class UUIDs"
            0x04.toUByte() -> "Incomplete List of 32-bit Service Class UUIDs"
            0x05.toUByte() -> "Complete List of 32-bit Service Class UUIDs"
            0x06.toUByte() -> "Incomplete List of 128-bit Service Class UUIDs"
            0x07.toUByte() -> "Complete List of 128-bit Service Class UUIDs"
            0x08.toUByte() -> "Shortened Local Name"
            0x09.toUByte() -> "Complete Local Name"
            0x0A.toUByte() -> "Tx Power Level"
            0x0D.toUByte() -> "Class of Device"
            0x0E.toUByte() -> "Simple Pairing Hash C"
            0x0F.toUByte() -> "Simple Pairing Randomizer R"
            0x10.toUByte() -> "Device ID"
            0x11.toUByte() -> "Security Manager Out of Band Flags"
            0x12.toUByte() -> "Slave Connection Interval Range"
            0x14.toUByte() -> "List of 16-bit Service Solicitation UUIDs"
            0x15.toUByte() -> "List of 32-bit Service Solicitation UUIDs"
            0x16.toUByte() -> "Service Data - 16-bit UUID"
            0x17.toUByte() -> "Public Target Address"
            0x18.toUByte() -> "Random Target Address"
            0x19.toUByte() -> "Appearance"
            0x1A.toUByte() -> "Advertising Interval"
            0x1B.toUByte() -> "LE Bluetooth Device Address"
            0x1C.toUByte() -> "LE Role"
            0x1D.toUByte() -> "Simple Pairing Hash C-256"
            0x1E.toUByte() -> "Simple Pairing Randomizer R-256"
            0x1F.toUByte() -> "List of 32-bit Service Solicitation UUIDs"
            0x20.toUByte() -> "Service Data - 32-bit UUID"
            0x21.toUByte() -> "Service Data - 128-bit UUID"
            0x22.toUByte() -> "LE Secure Connections Confirmation Value"
            0x23.toUByte() -> "LE Secure Connections Random Value"
            0x24.toUByte() -> "URI"
            0x25.toUByte() -> "Indoor Positioning"
            0x26.toUByte() -> "Transport Discovery Data"
            0x27.toUByte() -> "LE Supported Features"
            0x28.toUByte() -> "Channel Map Update Indication"
            0x29.toUByte() -> "PB-ADV"
            0x2A.toUByte() -> "Mesh Message"
            0x2B.toUByte() -> "Mesh Beacon"
            0x2C.toUByte() -> "BIGInfo"
            0x2D.toUByte() -> "Broadcast_Code"
            0x2E.toUByte() -> "Resolvable Set Identifier"
            0x2F.toUByte() -> "Advertising Interval - long"
            0x30.toUByte() -> "Broadcast_Name"
            0x3D.toUByte() -> "3D Information Data"
            0xFF.toUByte() -> "Manufacturer Specific Data"
            else -> "Unknown (0x${type.toString(16).padStart(2, '0').uppercase()})"
        }
    }

    fun extractDeviceName(packet: AdPacket): String? {
        // Try complete local name first
        packet.getStructureByType(0x09.toUByte())?.let { struct ->
            return struct.data.toString(Charsets.UTF_8)
        }

        // Fall back to shortened local name
        packet.getStructureByType(0x08.toUByte())?.let { struct ->
            return struct.data.toString(Charsets.UTF_8)
        }

        return null
    }

    fun extractManufacturerData(packet: AdPacket): ByteArray? {
        return packet.getStructureByType(0xFF.toUByte())?.data
    }

    fun extractServiceData16(packet: AdPacket): List<Pair<Int, ByteArray>> {
        return packet.getStructuresByType(0x16.toUByte()).mapNotNull { struct ->
            if (struct.data.size >= 2) {
                val serviceUuid = (struct.data[0].toInt() and 0xFF) or
                                ((struct.data[1].toInt() and 0xFF) shl 8)
                val serviceData = struct.data.copyOfRange(2, struct.data.size)
                Pair(serviceUuid, serviceData)
            } else null
        }
    }
}
