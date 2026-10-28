package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSpot
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiBuckets
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ScanOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.SigNumbers
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdField
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDeviceTrackerTest {
    private val flagsAndName = byteArrayOf(2, 1, 6, 5, 9, 'T'.code.toByte(), 'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte())
    private val other = byteArrayOf(2, 1, 6, 3, 0xFF.toByte(), 0x4C, 0x00)

    private fun sample(
        addr: String = "AA:BB:CC:DD:EE:01",
        rssi: Int? = -60,
        t: Long = 1_000_000,
        raw: ByteArray = flagsAndName,
        connectable: Boolean? = true,
        legacy: Boolean? = true,
        addrType: Int? = 1,
    ) = LiveSample(addr, rssi, t, raw, null, connectable, legacy, null, null, null, addrType)

    @Test
    fun aggregatesPerMacAndReportsNewDevices() {
        val t = LiveDeviceTracker()
        assertTrue(t.onSample(sample()))
        assertFalse(t.onSample(sample(t = 1_000_500, rssi = -50)))
        assertTrue(t.onSample(sample(addr = "AA:BB:CC:DD:EE:02")))
        assertEquals(2, t.size)
        val row = t.row("AA:BB:CC:DD:EE:01", 1_001_000)!!
        assertEquals(-50, row.rssi)
        assertEquals("Tile", row.name)
        assertTrue(row.connectable)
        assertEquals(AdvKind.Legacy, row.advKind)
    }

    @Test
    fun theSparklineAndChartHoldTheNewestSampleInTheirNewestBuckets() {
        val t = LiveDeviceTracker()
        // Two seconds ago strong, now weak, with the weak sample in a fresh second.
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -82, t = 1_001_900))
        val now = 1_002_000L
        val row = t.row("AA:BB:CC:DD:EE:01", now)!!
        assertEquals(-82, row.rssi)
        assertEquals(30, row.history.size) // 60 s in 2 s buckets
        // the newest bucket holds the strongest sample of its second(s); the displayed number is the latest
        assertEquals(-82, row.history.last())
        assertEquals(-82, t.chart("AA:BB:CC:DD:EE:01", now).buckets[58])
        assertEquals(-82, RssiBuckets.plan(row.history, row.rssi).end!!.rssi)
    }

    @Test
    fun rssiOf127IsAbsentAndKeepsTheLastValue() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -70))
        t.onSample(sample(rssi = null, t = 1_001_000))
        assertEquals(-70, t.rssi("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun chartTakesTheStrongestPerBucketAndHonoursTheWindow() {
        val t = LiveDeviceTracker()
        // An old sample leaves the one-minute window.
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -60, t = 1_000_400))
        t.onSample(sample(rssi = -80, t = 1_100_000))
        val now = 1_100_500L
        assertEquals(listOf(-80), t.chart("AA:BB:CC:DD:EE:01", now).buckets.filterNotNull())
    }

    @Test
    fun variantsAreOnePerDistinctAdvertisementContent() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -60))
        t.onSample(sample(rssi = -70, t = 1_001_000))
        t.onSample(sample(raw = other, rssi = -55, t = 1_002_000))
        // Same bytes, other flags: the same content, shown once.
        t.onSample(sample(raw = other, rssi = -65, t = 1_003_000, legacy = false, connectable = false))
        val v = t.variants("AA:BB:CC:DD:EE:01")
        assertEquals(2, v.size)
        assertEquals(listOf("01", "09"), v[0].structures.map { it.type })
        assertEquals("54696c65", v[0].structures[1].data)
        assertEquals(listOf(AdField("Name", "Tile")), v[0].structures[1].fields)
        assertEquals(5, v[0].structures[1].length)
    }

    @Test
    fun theSpotIsWhereThePhoneHeardTheDeviceTheStrongest() {
        val t = LiveDeviceTracker()
        fun at(rssi: Int?, lat: Double?, ms: Long) = LiveSample(
            "AA:BB:CC:DD:EE:01", rssi, ms, byteArrayOf(2, 1, 6), null, true, true, 1, 0, null, 1, lat = lat, lon = lat?.let { -117.0 },
        )
        t.onSample(at(-80, null, 1_000))
        assertNull(t.spot("AA:BB:CC:DD:EE:01"))
        t.onSample(at(-70, 32.1, 2_000))
        t.onSample(at(-60, 32.2, 3_000))
        t.onSample(at(-75, 32.3, 4_000)) // weaker: the spot stays
        t.onSample(at(null, 32.4, 5_000)) // no RSSI: ignored
        assertEquals(LiveSpot(32.2, -117.0, -60, 3_000), t.spot("AA:BB:CC:DD:EE:01"))
        assertEquals(5_000L, t.lastSeenMs("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun aDeviceKeepsTheNameOfItsNamedAdvertisementWhenItAlsoSendsNamelessOnes() {
        val t = LiveDeviceTracker()
        val nameless = byteArrayOf(2, 1, 6, 3, 0xFF.toByte(), 0x88.toByte(), 0xEC.toByte())
        val named = byteArrayOf(5, 9, 'G'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte())
        t.onSample(sample(raw = nameless, t = 1_000_000))
        assertNull(t.name("AA:BB:CC:DD:EE:01"))
        t.onSample(sample(raw = named, t = 1_001_000))
        t.onSample(sample(raw = nameless, t = 1_002_000))
        t.onSample(sample(raw = byteArrayOf(2, 1, 4), t = 1_003_000)) // a new nameless one
        assertEquals("Gove", t.name("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun theSigListNamesCompaniesServicesAppearancesAndTypes() {
        val sig = SigNumbers.parse(
            sequenceOf(
                "# header",
                "company\t004C\tApple, Inc.",
                "uuid16\t180D\tHeart Rate",
                "uuid16\tFEA0\tGoogle LLC",
                "appearance\t0080\tComputer",
                "appearance\t0083\tComputer: Laptop",
                "adtype\tFF\tManufacturer Specific Data",
                "broken line",
            ),
        )
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertEquals(listOf(AdField("Company", "Apple, Inc. (0x004C)"), AdField("Data", "12 02", bytes = true)), LiveDeviceTracker.fields(0xFF, b(0x4C, 0, 0x12, 2), sig))
        assertEquals(listOf(AdField("Company", "0x0999")), LiveDeviceTracker.fields(0xFF, b(0x99, 0x09), sig))
        assertEquals(listOf(AdField("Services", "Heart Rate (180D)\nFE0E")), LiveDeviceTracker.fields(0x03, b(0x0D, 0x18, 0x0E, 0xFE), sig))
        assertEquals(listOf(AdField("Service", "Google LLC (FEA0)"), AdField("Data", "01", bytes = true)), LiveDeviceTracker.fields(0x16, b(0xA0, 0xFE, 1), sig))
        assertEquals(listOf(AdField("Appearance", "Computer: Laptop (0x0083)")), LiveDeviceTracker.fields(0x19, b(0x83, 0x00), sig))
        // An unknown subcategory falls back to its category.
        assertEquals(listOf(AdField("Appearance", "Computer (0x0085)")), LiveDeviceTracker.fields(0x19, b(0x85, 0x00), sig))
        assertEquals("Manufacturer Specific Data", LiveDeviceTracker.adStructures(b(3, 0xFF, 0x4C, 0), sig).single().typeName)
    }

    @Test
    fun structuresReadAsTableRows() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        fun f(type: Int, vararg x: Int) = LiveDeviceTracker.fields(type, b(*x))
        assertEquals(listOf(AdField("Services", "FE0E\n180D")), f(0x03, 0x0E, 0xFE, 0x0D, 0x18))
        assertEquals(listOf(AdField("Service", "FEA0"), AdField("Data", "01 02", bytes = true)), f(0x16, 0xA0, 0xFE, 1, 2))
        assertEquals(listOf(AdField("TX power", "-12 dBm")), f(0x0A, 0xF4))
        assertEquals(
            listOf(AdField("Services", "0000fe9f-0000-1000-8000-00805f9b34fb")),
            f(0x07, 0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80, 0x00, 0x10, 0x00, 0x00, 0x9f, 0xfe, 0x00, 0x00),
        )
        assertEquals(listOf(AdField("Company", "0x004C"), AdField("Data", "12 02", bytes = true)), LiveDeviceTracker.adStructures(b(5, 0xFF, 0x4C, 0x00, 0x12, 0x02)).single().fields)
        assertEquals(listOf(AdField("Company", "0x0075")), f(0xFF, 0x75, 0x00))
        assertEquals(listOf(AdField("Flags", "LE General Discoverable\nBR/EDR Not Supported")), f(0x01, 6))
        // Malformed or unknown: the bytes under the type.
        assertEquals(listOf(AdField("Type 0x03", "0e", bytes = true)), f(0x03, 0x0E))
        assertEquals(listOf(AdField("Type 0x2A", "01", bytes = true)), f(0x2A, 1))
    }

    @Test
    fun theChartKeepsGapsAsNulls() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -60, t = 1_030_000))
        val series = t.chart("AA:BB:CC:DD:EE:01", 1_030_500).buckets
        assertEquals(60, series.size)
        assertEquals(-60, series.last())
        assertEquals(-50, series[29])
        assertTrue(series.subList(30, 59).all { it == null })
    }

    @Test
    fun variantIdsAreStableAndTheLeastRecentlyHeardVariantIsEvicted() {
        val t = LiveDeviceTracker()
        t.onSample(sample(raw = byteArrayOf(2, 1, 1), t = 1_000_000))
        val firstId = t.variants("AA:BB:CC:DD:EE:01").single().id
        repeat(LiveDeviceTracker.MAX_VARIANTS - 1) { i -> t.onSample(sample(raw = byteArrayOf(2, 1, (i + 10).toByte()), t = 1_000_100L + i)) }
        t.onSample(sample(raw = byteArrayOf(2, 1, 1), t = 1_001_000)) // the first one is heard again
        t.onSample(sample(raw = byteArrayOf(2, 1, 99), t = 1_002_000)) // forces one eviction
        val ids = t.variants("AA:BB:CC:DD:EE:01").map { it.id }
        assertEquals(LiveDeviceTracker.MAX_VARIANTS, ids.size)
        assertTrue(firstId in ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theRssiRingGrowsPastItsInitialSizeAndKeepsTheLast900Seconds() {
        val t = LiveDeviceTracker()
        // More seconds than the ring holds: the oldest are overwritten, the chart still sees the newest.
        for (i in 0 until 1_000) t.onSample(sample(rssi = -40 - (i % 50), t = 1_000_000L + i * 1_000L))
        val now = 1_000_000L + 999 * 1_000L
        val series = t.chart("AA:BB:CC:DD:EE:01", now).buckets
        assertEquals(60, series.size) // the last minute in 1 s buckets
        assertTrue(series.all { it != null })
        assertEquals(-40 - (999 % 50), series.last())
    }

    @Test
    fun variantsAreListedInTheOrderTheyFirstAppearedWhateverWasHeardLast() {
        val t = LiveDeviceTracker()
        val a = byteArrayOf(2, 1, 1)
        val b = byteArrayOf(2, 1, 2)
        t.onSample(sample(raw = a, t = 1_000_000))
        t.onSample(sample(raw = b, t = 1_001_000))
        val first = t.variants("AA:BB:CC:DD:EE:01").map { it.id }
        t.onSample(sample(raw = a, t = 1_002_000)) // a is heard again; the table must not reorder
        t.onSample(sample(raw = b, t = 1_003_000))
        assertEquals(first, t.variants("AA:BB:CC:DD:EE:01").map { it.id })
    }

    @Test
    fun variantsAreBoundedPerDevice() {
        val t = LiveDeviceTracker()
        repeat(LiveDeviceTracker.MAX_VARIANTS + 10) { i -> t.onSample(sample(raw = byteArrayOf(2, 1, i.toByte()), t = 1_000_000L + i)) }
        assertEquals(LiveDeviceTracker.MAX_VARIANTS, t.variants("AA:BB:CC:DD:EE:01").size)
    }

    @Test
    fun staleAndPrune() {
        val t = LiveDeviceTracker()
        t.onSample(sample(t = 1_000_000))
        assertFalse(t.row("AA:BB:CC:DD:EE:01", 1_010_000)!!.stale)
        assertTrue(t.row("AA:BB:CC:DD:EE:01", 1_000_000 + LiveDeviceTracker.STALE_AFTER_MS + 1)!!.stale)
        assertTrue(t.prune(1_000_000 + 60_000).isEmpty())
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), t.prune(1_000_000 + LiveDeviceTracker.FORGET_AFTER_MS + 1))
        assertNull(t.row("AA:BB:CC:DD:EE:01", 0))
    }

}

class ScanOrderTest {
    private val rssi = mapOf("a" to -80, "b" to -50, "c" to -65, "d" to -40)

    @Test
    fun listsInArrivalOrderUntilTheInitialSort() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500); o.onDevice("c", 900)
        o.tick(2_900, rssi::get) { false }
        assertEquals(listOf("a", "b", "c"), o.addrs)
    }

    @Test
    fun sortsOnceThreeSecondsAfterTheFirstDeviceThenStaysFixed() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500); o.onDevice("c", 900)
        o.tick(3_000, rssi::get) { false }
        assertEquals(listOf("b", "c", "a"), o.addrs)
        // The numbers change; the order does not.
        val changed = rssi + ("a" to -30)
        o.tick(4_000, changed::get) { false }
        assertEquals(listOf("b", "c", "a"), o.addrs)
    }

    @Test
    fun newDevicesAppendAtTheEndAfterTheSort() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500)
        o.tick(3_000, rssi::get) { false }
        o.onDevice("d", 5_000)
        o.tick(6_000, rssi::get) { false }
        assertEquals(listOf("b", "a", "d"), o.addrs)
    }

    @Test
    fun resortSortsEverythingAgain() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500)
        o.tick(3_000, rssi::get) { false }
        o.onDevice("d", 5_000)
        o.resort(rssi::get) { false }
        assertEquals(listOf("d", "b", "a"), o.addrs)
    }

    @Test
    fun devicesNoLongerHeardSortAfterLiveOnesInTheInitialSortAndInRefresh() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 100); o.onDevice("c", 200); o.onDevice("d", 300)
        val stale = setOf("d") // the strongest, but gone
        o.tick(3_000, rssi::get) { it in stale }
        assertEquals(listOf("b", "c", "a", "d"), o.addrs)
        val stale2 = setOf("b")
        o.resort(rssi::get) { it in stale2 }
        assertEquals(listOf("d", "c", "a", "b"), o.addrs)
    }

    @Test
    fun removedDevicesLeaveTheOrder() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500)
        o.remove(listOf("a"))
        assertEquals(listOf("b"), o.addrs)
        o.onDevice("a", 700)
        assertEquals(listOf("b", "a"), o.addrs)
    }
}
