package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiBuckets
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ScanOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AddressType
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
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
        assertEquals(-82, t.chart("AA:BB:CC:DD:EE:01", ChartWindow.OneMinute, now).buckets[58])
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
        // Two samples in one second give the stronger, -50; an old sample leaves the 1 minute window.
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -60, t = 1_000_400))
        t.onSample(sample(rssi = -80, t = 1_100_000))
        val now = 1_100_500L
        assertEquals(listOf(-80), t.chart("AA:BB:CC:DD:EE:01", ChartWindow.OneMinute, now).buckets.filterNotNull())
        assertEquals(listOf(-50, -80), t.chart("AA:BB:CC:DD:EE:01", ChartWindow.FiveMinutes, now).buckets.filterNotNull())
    }

    @Test
    fun variantsAreOnePerDistinctAdvertisementWithCountsAndRange() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -60))
        t.onSample(sample(rssi = -70, t = 1_001_000))
        t.onSample(sample(raw = other, rssi = -55, t = 1_002_000))
        t.onSample(sample(raw = other, rssi = -65, t = 1_003_000, legacy = false, connectable = false))
        val v = t.variants("AA:BB:CC:DD:EE:01")
        assertEquals(3, v.size)
        assertEquals("ADV_IND", v[0].pduType)
        assertEquals(2, v[0].count)
        assertEquals(-70, v[0].rssiMin)
        assertEquals(-60, v[0].rssiMax)
        assertEquals("ADV_EXT_IND", v[2].pduType)
        assertEquals(listOf("01", "09"), v[0].structures.map { it.type })
        assertEquals("54696c65", v[0].structures[1].data)
        assertEquals(5, v[0].structures[1].length)
    }

    @Test
    fun theChartKeepsGapsAsNulls() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -60, t = 1_030_000))
        val series = t.chart("AA:BB:CC:DD:EE:01", ChartWindow.OneMinute, 1_030_500).buckets
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
        val series = t.chart("AA:BB:CC:DD:EE:01", ChartWindow.FifteenMinutes, now).buckets
        assertEquals(60, series.size) // 15 s buckets
        assertTrue(series.all { it != null })
        // the last bucket holds seconds 985..999: the strongest of them is i = 985 (985 % 50 = 35)
        assertEquals(-40 - 35, series.last())
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

    @Test
    fun addressClassification() {
        assertEquals(AddressType.Public, LiveDeviceTracker.classifyAddress("C8:0F:10:3A:91:5E", 0))
        assertEquals(AddressType.RandomStatic, LiveDeviceTracker.classifyAddress("C8:0F:10:3A:91:5E", 1))
        assertEquals(AddressType.ResolvablePrivate, LiveDeviceTracker.classifyAddress("5B:21:7C:E0:19:A4", 1))
        assertEquals(AddressType.NonResolvablePrivate, LiveDeviceTracker.classifyAddress("2B:21:7C:E0:19:A4", 1))
        assertEquals(AddressType.Unknown, LiveDeviceTracker.classifyAddress("C8:0F:10:3A:91:5E", null))
    }

    @Test
    fun phyLabels() {
        assertEquals("1M", LiveDeviceTracker.phyLabel(true, 1, 0))
        assertEquals("2M", LiveDeviceTracker.phyLabel(false, 1, 2))
        assertEquals("Coded", LiveDeviceTracker.phyLabel(false, 3, 3))
        assertEquals("1M", LiveDeviceTracker.phyLabel(false, 1, 0))
        assertEquals("—", LiveDeviceTracker.phyLabel(false, null, null))
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
