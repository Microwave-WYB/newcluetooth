package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.ble.AdTraits
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hide unnamed Apple devices": the matching rule, and that it only filters what screens show. */
class UnnamedAppleFilterTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private fun text(s: String) = s.toByteArray(Charsets.ISO_8859_1).map { it.toInt() and 0xFF }.toIntArray()

    /** Flags, then manufacturer data for [company] carrying [marker] (what the fake matcher keys on). */
    private fun mfg(company: Int, marker: String) =
        b(2, 1, 6, 3 + marker.length, 0xFF, company and 0xFF, company shr 8, *text(marker))

    private val appleMatched = mfg(0x004C, "buds") // an AirPods-like advertisement a rule identifies
    private val appleUnmatched = mfg(0x004C, "zzzz") // Find My / Continuity noise
    private val appleNamed = mfg(0x004C, "yyyy") + adv("Mac")
    private val otherUnnamed = mfg(0x0075, "bulb")

    @Test
    fun theRuleHidesUnnamedAppleWhateverItMatchedAndNothingElse() {
        fun hidden(name: String?, vararg raws: ByteArray) = AdTraits.unnamedApple(name, raws.map(AdTraits::of))
        assertTrue("unnamed Apple, matched to a product", hidden(null, appleMatched))
        assertTrue("unnamed Apple, unmatched", hidden(null, appleUnmatched))
        assertFalse("named Apple", hidden(null, appleNamed))
        assertFalse("Apple with a name in another advertisement", hidden(null, appleUnmatched, adv("iPhone")))
        assertFalse("Apple named by the platform", hidden("Watch", appleUnmatched))
        assertFalse("unnamed, not Apple", hidden(null, otherUnnamed))
        assertFalse("nothing at all", hidden(null, b(2, 1, 6)))
    }

    @Test
    fun theLiveTrackerAppliesTheSameRulePerDevice() {
        val t = LiveDeviceTracker()
        fun feed(addr: String, raw: ByteArray, name: String? = null) =
            t.onSample(LiveSample(addr, -60, 1_000, raw, name, false, true, 1, 0, null, 1))
        feed("A1", appleMatched)
        feed("A2", appleUnmatched)
        feed("A3", appleNamed)
        feed("A4", appleUnmatched)
        feed("A4", adv("iPad")) // a second advertisement with a name
        feed("B1", otherUnnamed)
        assertEquals(listOf("A1", "A2"), listOf("A1", "A2", "A3", "A4", "B1").filter(t::isUnnamedApple))
    }

    @Test
    fun recordedRowsAreTheSameWithTheFilterOnAndOff() {
        val db = JdbcSqlDatabase()
        val store = LocalStore(db)
        val matcher = FakeMatcher("v1", mapOf("device--buds" to "buds", "device--bulb" to "bulb"))
        val ids = HashMap<String, Long>()
        val t0 = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L
        val devices = mapOf("AA:00:00:00:00:01" to appleMatched, "AA:00:00:00:00:02" to appleUnmatched, "AA:00:00:00:00:03" to appleNamed, "AA:00:00:00:00:04" to otherUnnamed)
        store.startRecording("r1", t0)
        store.flush(
            FlushBatch(
                devices.values.associate { sha256Hex(it) to PayloadDelta(it, t0, t0, 1) },
                devices.entries.associate { (addr, raw) ->
                    MinuteKey(addr, t0 / 60_000L, sha256Hex(raw)) to MinuteDelta(t0, t0, 0, MinuteBest(null, null)).also { it.add(t0, -60, GeoPoint(32.0, -117.0, 5.0)) }
                },
                "r1", emptyList(), 0.0,
            ),
            ids, "v1",
        ) { _, raw -> matcher.match(raw) }

        fun storedRows() = Triple(store.devicesInRecording("r1"), store.sightingsInRecording("r1", null, 1_000), store.seenProducts(WikiCatalog.parse(bundleJson("v1", *PAGES))))
        val before = storedRows()
        // The display filter, both ways, over what was read: it hides one device on screen and changes nothing stored.
        val shownOn = DisplayFilter.devices(before.first, hideUnnamedApple = true).map { it.addr }
        val shownOff = DisplayFilter.devices(before.first, hideUnnamedApple = false).map { it.addr }
        assertEquals(before, storedRows())
        assertEquals(devices.keys.sorted(), shownOff.sorted())
        assertEquals(listOf("AA:00:00:00:00:03", "AA:00:00:00:00:04"), shownOn.sorted())
        assertEquals(setOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), DisplayFilter.unnamedAppleAddrs(before.second))
        // Every row is there either way: four devices, four minute rows, both matched products.
        assertEquals(4, before.first.size)
        assertEquals(4, before.second.size)
        assertEquals(setOf("device--buds", "device--bulb"), before.third.map { it.page.id }.toSet())
        // The read-only views the screens use.
        assertEquals(mapOf("r1" to 2), store.unnamedAppleDevicesPerRecording())
        assertEquals(setOf("device--buds"), store.pagesOnlyFromUnnamedApple())
    }

    @Test
    fun nothingThatScansRecordsStoresOrUploadsReadsTheSetting() {
        val root = listOf("src/main/java", "app/src/main/java").map { File(it, "edu/ucsd/sysnet/cluetoothscanner") }.first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(root).path
                !rel.startsWith("ui/") && rel != "utils/AppSettings.kt" &&
                    f.readText().let { it.contains("HideUnnamedApple") || it.contains("hide_unnamed_apple") || it.contains("DisplayFilter") }
            }
            .map { it.relativeTo(root).path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
