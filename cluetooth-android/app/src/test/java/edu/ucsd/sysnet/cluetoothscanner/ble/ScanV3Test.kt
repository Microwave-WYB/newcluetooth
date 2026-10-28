package edu.ucsd.sysnet.cluetoothscanner.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScanV3Test {
    private fun fields(
        isLegacy: Boolean = false,
        primaryPhy: Int = 1,
        secondaryPhy: Int = 2,
        sid: Int = 3,
        txPower: Int = -9,
        interval: Int = 0,
        dataStatus: Int = 0,
        addressType: Int? = 1,
    ) = ScanV3Fields.from(isLegacy, primaryPhy, secondaryPhy, sid, txPower, interval, dataStatus, addressType)

    @Test
    fun `reported values pass through unchanged`() {
        val mapped = fields(primaryPhy = 3, secondaryPhy = 3, sid = 15, txPower = -127, interval = 65_535, dataStatus = 2, addressType = 0)
        assertEquals(
            ScanV3Fields(false, 3, 3, 15, -127, 65_535, 2, 0),
            mapped,
        )
        assertEquals(true, fields(isLegacy = true).isLegacy)
    }

    @Test
    fun `platform not-present sentinels become null`() {
        val mapped = fields(sid = 255, txPower = 127, interval = 0)
        assertNull(mapped.advSid)
        assertNull(mapped.advTxPower)
        assertNull(mapped.periodicInterval)
        // Everything else is untouched.
        assertEquals(1, mapped.primaryPhy)
        assertEquals(2, mapped.secondaryPhy)
    }

    @Test
    fun `secondary phy unused is a value not a null`() {
        assertEquals(0, fields(secondaryPhy = 0).secondaryPhy)
    }

    @Test
    fun `values outside the documented ranges are dropped instead of failing the batch`() {
        val mapped = fields(
            primaryPhy = 2,
            secondaryPhy = 9,
            sid = 16,
            txPower = -128,
            interval = 65_536,
            dataStatus = 1,
            addressType = 65_535,
        )
        assertEquals(ScanV3Fields(false, null, null, null, null, null, null, null), mapped)
    }

    @Test
    fun `android truncated data status is stored as the platform value 2`() {
        assertEquals(2, fields(dataStatus = 2).dataStatus)
        assertNull(fields(dataStatus = 1).dataStatus)
    }

    @Test
    fun `address type is null when the platform does not report it`() {
        assertNull(fields(addressType = null).addrType)
        assertNull(fields(addressType = 255).addrType)
    }

    @Test
    fun `pre-O systems report none of the fields`() {
        assertEquals(ScanV3Fields(null, null, null, null, null, null, null, null), ScanV3Fields.ABSENT)
    }

    @Test
    fun `environment states the scan phy in force and capabilities`() {
        val on = buildScanEnvironment("Pixel 8", "15", 35, "0.1.0-debug", true, true, 1650, codedPhyEnabled = true)
        assertEquals("all_supported", on.scanPhy)
        assertEquals("15", on.androidRelease)
        assertEquals(35, on.androidSdk)
        assertEquals(1650, on.capMaxAdvLen)
        assertEquals(false, on.scanLegacyOnly)
        val off = buildScanEnvironment("Pixel 8", "15", 35, "0.1.0-debug", true, true, 1650, codedPhyEnabled = false)
        assertEquals("1m", off.scanPhy)
        val unsupported = buildScanEnvironment("Old", "7.0", 24, "0.1.0", false, false, 31, codedPhyEnabled = true)
        assertEquals(false, unsupported.capExtAdv)
        assertEquals(false, unsupported.capCodedPhy)
    }

    @Test
    fun `footer text is never empty and stays within the byte limit`() {
        val blank = buildScanEnvironment("  ", "", 30, "", false, false, -5, true)
        assertEquals("unknown", blank.deviceModel)
        assertEquals("unknown", blank.appVersion)
        assertEquals(0, blank.capMaxAdvLen)
        val long = buildScanEnvironment("é".repeat(300), "15", 35, "1", false, false, 31, true)
        assert(long.deviceModel.toByteArray(Charsets.UTF_8).size <= 256)
        assert(long.deviceModel.isNotEmpty())
    }

    @Test
    fun `trigger cache keeps the latest advertisement per address and is bounded`() {
        val cache = AdvertisementTriggerCache(capacity = 2)
        fun trigger(addr: String, rssi: Int) =
            AdvertisementTrigger(addr, null, null, null, byteArrayOf(1), rssi, 1L, ScanV3Fields.ABSENT)
        cache.put(trigger("A", -50))
        cache.put(trigger("B", -60))
        val newerA = trigger("A", -40)
        cache.put(newerA)
        cache.put(trigger("C", -70))
        assertSame(newerA, cache.latest("A"))
        assertNull(cache.latest("B"))
        assertEquals(-70, cache.latest("C")?.rssi)
    }
}
