package edu.ucsd.sysnet.cluetoothscanner.fake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC Q-1 with the docs/v2-api.md §2.11 vectors (the Rust core runs the same table). */
class MatchDeviceTest {
    @Test
    fun q1GalleryVectors() {
        val vectors = listOf(
            listOf("qt", "QT-Sensor", "AA:BB:CC:00:11:22", true),
            listOf("qt", "SmartQt", "AA:BB:CC:00:11:22", true),
            listOf("qt", "aqtb", "AA:BB:CC:00:11:22", true),
            listOf("qt", "Other", "AA:BB:CC:00:11:22", false),
            listOf("4b4b", null, "72:CD:4C:4B:4B:6F", true),
            listOf("4B:4B", null, "72:CD:4C:4B:4B:6F", true),
            listOf("4b-4b", null, "72:CD:4C:4B:4B:6F", true),
            listOf("7d3f", null, "40:E7:7D:3F:7A:CD", true),
            listOf("ab", "Sensor", "AB:CD:EF:01:02:03", false),
            listOf("ab", "Abbey Road", "11:22:33:44:55:66", true),
            listOf("  ", "QT-Sensor", "AA:BB:CC:00:11:22", false),
            listOf("no", null, "11:22:33:44:55:66", false),
        )
        for ((q, name, addr, expected) in vectors) {
            assertEquals("$q $name $addr", expected, MatchDevice.matches(q as String, name as String?, addr as String))
        }
    }

    @Test
    fun jsTrimAndUnicodeLowercase() {
        assertTrue(MatchDevice.matches("  qt\uFEFF", "QT-Sensor", "AA:BB:CC:00:11:22"))
        assertTrue(MatchDevice.matches("ÄPFEL", "äpfel 1", "AA:BB:CC:00:11:22"))
        // U+001C is whitespace to Java but not to JS trim, so it stays part of the query.
        assertFalse(MatchDevice.matches("\u001Cqt", "QT-Sensor", "AA:BB:CC:00:11:22"))
        assertTrue(MatchDevice.isBlank(" \u00A0\u3000"))
    }
}
