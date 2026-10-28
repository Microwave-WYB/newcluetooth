package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
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
    fun rssiOf127IsAbsentAndKeepsTheLastValue() {
        val t = LiveDeviceTracker()
        t.onSample(sample(rssi = -70))
        t.onSample(sample(rssi = null, t = 1_001_000))
        assertEquals(-70, t.rssi("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun chartAveragesPerSecondAndHonoursTheWindow() {
        val t = LiveDeviceTracker()
        // Two samples in one second average to -55; an old sample leaves the 1 minute window.
        t.onSample(sample(rssi = -50, t = 1_000_000))
        t.onSample(sample(rssi = -60, t = 1_000_400))
        t.onSample(sample(rssi = -80, t = 1_100_000))
        val now = 1_100_500L
        assertEquals(listOf(-80), t.chart("AA:BB:CC:DD:EE:01", ChartWindow.OneMinute, now))
        assertEquals(listOf(-55, -80), t.chart("AA:BB:CC:DD:EE:01", ChartWindow.FiveMinutes, now))
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
        o.tick(2_900, rssi::get)
        assertEquals(listOf("a", "b", "c"), o.addrs)
    }

    @Test
    fun sortsOnceThreeSecondsAfterTheFirstDeviceThenStaysFixed() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500); o.onDevice("c", 900)
        o.tick(3_000, rssi::get)
        assertEquals(listOf("b", "c", "a"), o.addrs)
        // The numbers change; the order does not.
        val changed = rssi + ("a" to -30)
        o.tick(4_000, changed::get)
        assertEquals(listOf("b", "c", "a"), o.addrs)
    }

    @Test
    fun newDevicesAppendAtTheEndAfterTheSort() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500)
        o.tick(3_000, rssi::get)
        o.onDevice("d", 5_000)
        o.tick(6_000, rssi::get)
        assertEquals(listOf("b", "a", "d"), o.addrs)
    }

    @Test
    fun resortSortsEverythingAgain() {
        val o = ScanOrder(3_000)
        o.onDevice("a", 0); o.onDevice("b", 500)
        o.tick(3_000, rssi::get)
        o.onDevice("d", 5_000)
        o.resort(rssi::get)
        assertEquals(listOf("d", "b", "a"), o.addrs)
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
