package edu.ucsd.sysnet.cluetoothscanner.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructureKeyTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun key(vararg v: Int) = StructureKey.of(bytes(*v)).key

    @Test
    fun companyFieldWinsAndIsDecimalLittleEndian() {
        // flags, complete 16-bit list FEA0, name "Lamp", manufacturer 0x004C = 76
        assertEquals(
            "company:76",
            key(2, 1, 6, 3, 3, 0xA0, 0xFE, 5, 9, 'L'.code, 'a'.code, 'm'.code, 'p'.code, 4, 0xFF, 0x4C, 0x00, 1),
        )
        // the first manufacturer structure counts
        assertEquals("company:1", key(3, 0xFF, 1, 0, 3, 0xFF, 2, 0))
        // manufacturer data too short for a company field falls through
        assertEquals("shape:FF/1", key(2, 0xFF, 1))
    }

    @Test
    fun firstSixteenBitServiceUuidIsUppercaseAndInAdOrder() {
        assertEquals("uuid:FEA0", key(3, 3, 0xA0, 0xFE, 3, 3, 0x0F, 0x18))
        assertEquals("uuid:FD6F", key(3, 2, 0x6F, 0xFD))
        // service data counts as well, in order of appearance
        assertEquals("uuid:FEAA", key(4, 0x16, 0xAA, 0xFE, 0))
        // 128-bit lists are not 16-bit UUIDs
        assertEquals("shape:07/16", StructureKey.of(byteArrayOf(17, 7) + ByteArray(16) { 1 }).key)
    }

    @Test
    fun localNamePrefixIsTheLeadingLetters() {
        fun named(name: String) = StructureKey.of(byteArrayOf((name.length + 1).toByte(), 9) + name.toByteArray()).key
        assertEquals("name:ELK", named("ELK-BLEDOM"))
        assertEquals("name:JBL", named("JBL Flip 5"))
        assertEquals("name:Tile", named("Tile"))
        assertEquals("name:123", named("123456"))
        assertEquals("name:LongNameWith", named("LongNameWithManyLetters"))
        // a shortened local name works the same
        assertEquals("name:Hue", StructureKey.of(byteArrayOf(4, 8, 'H'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte())).key)
        // blank names fall through to the shape
        assertEquals("shape:09/1", key(2, 9, 0x20))
    }

    @Test
    fun shapeIsTheLastResortInWikiNotation() {
        assertEquals("shape:01/1,2A/0", key(2, 1, 6, 1, 0x2A))
        assertEquals("shape:01/1,2A/2", key(2, 1, 6, 3, 0x2A, 1, 2))
        assertEquals("shape:-", key())
        assertEquals("shape:-", key(0, 0, 0))
    }

    @Test
    fun parsingStopsAtZeroLengthAndTruncationLikeTheDatabase() {
        // zero length: what follows is padding, even if it looks like a company field
        assertEquals("shape:01/1", key(2, 1, 6, 0, 3, 0xFF, 1, 0))
        // truncated field: not read
        assertEquals("shape:01/1", key(2, 1, 6, 9, 0xFF, 1, 0))
    }

    @Test
    fun longShapesAreCut() {
        val raw = ByteArray(40) { if (it % 2 == 0) 1 else 0x2A }
        assertEquals("shape:" + List(16) { "2A/0" }.joinToString(",") + ",…", StructureKey.of(raw).key)
    }

    @Test
    fun keysParseBack() {
        for (raw in listOf(bytes(3, 0xFF, 1, 0), bytes(3, 3, 0xA0, 0xFE), bytes(2, 9, 'A'.code), bytes(2, 1, 6))) {
            val key = StructureKey.of(raw)
            assertEquals(key, StructureKey.parse(key.key))
        }
        assertEquals(76, StructureKey.parse("company:76")?.companyId)
        assertNull(StructureKey.parse("bogus"))
        assertNull(StructureKey.parse("nope:1"))
    }
}
