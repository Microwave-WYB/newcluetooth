package edu.ucsd.sysnet.cluetoothscanner.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** G-8: the vocabulary rules hold for every UI string. */
class StringsTest {
    /** Every constant of [Strings], plus every formatter with sample arguments. */
    private val all: List<String> = Strings::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.get(null) as String } + listOf(
        Strings.devices(1), Strings.devices(3), Strings.lastScanned("2 h ago"), Strings.locationAge(4),
        Strings.newPerMinute(12), Strings.resultsHeader(1, true),
        Strings.resultsHeader(3, false), Strings.filterActive(2), Strings.productStatus(false, 3, 0, "just now"),
        Strings.productStatus(true, 3, 2, "just now"), Strings.productStatus(true, 3, 0, "4 min ago"),
        Strings.seenAt(20, "just now"), Strings.dbm(Format.signed(-56)), Strings.startedAt("7:12 PM", 26),
        Strings.allSessionsSub(12, 1840), Strings.pendingSub(3, "4.2 MB"), Strings.storageBreakdown("12 MB", "340 MB", "48 MB"),
        Strings.exportReady(2, "12 MB"), Strings.deleteConfirmTitle("All sessions"), Strings.deleteLoseTitle(3),
        Strings.deletedSessions(2), Strings.deletedPendingKept(1), Strings.stepOf(1, 4), Strings.recordingNotification("12:34", 26),
        Strings.gattFailedStatus(133), Strings.ofCount(12, 18), Strings.uploadFailed(2), Strings.deleteRefused(Strings.StopRecordingFirst),
    )

    @Test fun neverHeard() = all.forEach { assertFalse(it, it.contains("heard", ignoreCase = true)) }

    @Test fun noCheckOrCrossSymbols() = all.forEach { s -> assertFalse(s, s.any { it in "✓✔✗✘✕✖×☑☒" }) }

    /** English only: ASCII plus the typographic marks the prototype uses. */
    @Test fun englishOnly() {
        val marks = "·…−↗±›’"
        all.forEach { s -> assertTrue(s, s.all { it.code in 0x20..0x7e || it in marks }) }
    }

    @Test fun noNameAndScanVocabulary() {
        assertEquals("No name", Strings.NoName)
        assertEquals("Scanned now", Strings.ScannedNow)
        assertEquals("Last scanned 2 h ago", Strings.lastScanned("2 h ago"))
    }

    /** The address type is never shown. */
    @Test fun noAddressType() = all.forEach { s ->
        assertFalse(s, Regex("""\b(public|random|static|resolvable)( address)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) && "address" in s.lowercase())
    }

    @Test fun pluralsAndHeaders() {
        assertEquals("1 device", Strings.devices(1))
        assertEquals("214 devices", Strings.devices(214))
        assertEquals("1 result in this area", Strings.resultsHeader(1, true))
        assertEquals("3 results", Strings.resultsHeader(3, false))
        assertEquals("In range · 1 device", Strings.productStatus(true, 2, 1, "just now"))
        assertEquals("Last scanned just now", Strings.productStatus(true, 1, 0, "just now"))
        assertEquals("2 devices", Strings.productStatus(false, 2, 0, "just now"))
        assertEquals("Started 7:12 PM · 26 devices", Strings.startedAt("7:12 PM", 26))
        assertEquals("12 new/min", Strings.newPerMinute(12))
    }

    /** No other module source of :designsystem spells a UI string outside [Strings] with "heard" either. */
    @Test fun sourcesNeverSayHeard() {
        val src = File(System.getProperty("cluetooth.moduleDir")!!).resolve("src/main/kotlin")
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") || it.trimStart().startsWith("//") }
            assertFalse(f.name, code.any { Regex("\"[^\"]*heard[^\"]*\"", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        }
    }
}
