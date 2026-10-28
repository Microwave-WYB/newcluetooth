package edu.ucsd.sysnet.cluetoothscanner.local

/** What a [StructureKey] is made of; the order is the order of preference. */
enum class StructureKind(internal val prefix: String) {
    /** The first manufacturer-data company field ([StructureKey.value] is its decimal number). */
    COMPANY("company"),

    /** The first 16-bit service UUID (service lists `02`/`03`, service data `16`), uppercase: `FEA0`. */
    SERVICE_UUID("uuid"),

    /** The leading letters of the local name (or its first characters when it starts with a non-letter). */
    LOCAL_NAME("name"),

    /** The AD types and data lengths in observed order, wiki notation: `01/1,09/17,FF/7`. */
    AD_SHAPE("shape"),
}

/**
 * A coarse key for advertisements no wiki page identifies, so they can be grouped and counted:
 * the company field if there is manufacturer data, else the first 16-bit service UUID, else a
 * local-name prefix, else the AD-type shape. Derived from the bytes alone ([of]); stored per
 * payload as [key].
 */
data class StructureKey(val kind: StructureKind, val value: String) {
    /** Stable text form, `company:76`, `uuid:FEA0`, `name:ELK`, `shape:01/1,FF/7`. */
    val key: String get() = "${kind.prefix}:$value"

    /** The company field when [kind] is [StructureKind.COMPANY]. */
    val companyId: Int? get() = if (kind == StructureKind.COMPANY) value.toIntOrNull() else null

    override fun toString() = key

    companion object {
        private const val MAX_SHAPE_ITEMS = 16
        private const val MAX_NAME_LETTERS = 12
        private const val NAME_FALLBACK_CHARS = 3

        fun parse(key: String): StructureKey? {
            val colon = key.indexOf(':')
            if (colon <= 0) return null
            val kind = StructureKind.entries.firstOrNull { it.prefix == key.substring(0, colon) } ?: return null
            return StructureKey(kind, key.substring(colon + 1))
        }

        /**
         * The key of one advertisement. AD structures are split like `ble_adv_split`: in order, stopping at a
         * zero length or a truncated field.
         */
        fun of(raw: ByteArray): StructureKey {
            var company: Int? = null
            var uuid: String? = null
            var complete: ByteArray? = null // first Complete Local Name (0x09) data
            var shortened: ByteArray? = null // first Shortened Local Name (0x08) data
            val shape = StringBuilder()
            var items = 0
            var i = 0
            while (i < raw.size) {
                val n = raw[i].toInt() and 0xFF
                if (n == 0 || i + 1 + n > raw.size) break
                val type = raw[i + 1].toInt() and 0xFF
                val start = i + 2
                val end = i + 1 + n // exclusive
                val length = end - start
                if (items < MAX_SHAPE_ITEMS) {
                    if (items > 0) shape.append(',')
                    shape.append(hex2(type)).append('/').append(length)
                } else if (items == MAX_SHAPE_ITEMS) {
                    shape.append(",…")
                }
                items++
                when (type) {
                    0xFF -> if (company == null && length >= 2) {
                        company = (raw[start].toInt() and 0xFF) or ((raw[start + 1].toInt() and 0xFF) shl 8)
                    }
                    0x02, 0x03, 0x16 -> if (uuid == null && length >= 2) {
                        uuid = hex2(raw[start + 1].toInt()) + hex2(raw[start].toInt()) // little endian on the air
                    }
                    0x09 -> if (complete == null) complete = raw.copyOfRange(start, end)
                    0x08 -> if (shortened == null) shortened = raw.copyOfRange(start, end)
                }
                i = end
            }
            company?.let { return StructureKey(StructureKind.COMPANY, it.toString()) }
            uuid?.let { return StructureKey(StructureKind.SERVICE_UUID, it) }
            (complete ?: shortened)?.let { utf8OrNull(it) }?.let { namePrefix(it) }?.let { return StructureKey(StructureKind.LOCAL_NAME, it) }
            return StructureKey(StructureKind.AD_SHAPE, if (items == 0) "-" else shape.toString())
        }

        /**
         * The database's local name (`ble_utf8_or_null`): trailing NULs removed, null when empty or not valid
         * UTF-8. Like `payload_fields`, the Complete Local Name (0x09) is preferred over the Shortened one
         * (0x08), and that structure's name is final: if it is invalid there is no name.
         */
        private fun utf8OrNull(data: ByteArray): String? {
            var end = data.size
            while (end > 0 && data[end - 1].toInt() == 0) end--
            if (end == 0) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data, 0, end)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        private fun namePrefix(name: String): String? {
            val letters = name.takeWhile { it.isLetter() }.take(MAX_NAME_LETTERS)
            val prefix = letters.ifEmpty { name.take(NAME_FALLBACK_CHARS) }
            return prefix.filterNot { it.isISOControl() }.takeIf { it.isNotBlank() }
        }

        private val HEX = "0123456789ABCDEF".toCharArray()

        private fun hex2(byte: Int) = "${HEX[(byte shr 4) and 0xF]}${HEX[byte and 0xF]}"
    }
}
