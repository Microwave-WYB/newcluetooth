package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.ble.AdTraits
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DisplayFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A device row is titled with the device's own advertised name, live or from recordings, never the product's. */
class DeviceNameTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private fun shortName(name: String) = b(2, 1, 6, name.length + 1, 8) + name.toByteArray()

    @Test
    fun theAdvertisedNameIsReadCompleteOverShortened() {
        assertEquals("Govee_H6056_4828", AdTraits.of(adv("Govee_H6056_4828")).name)
        assertEquals("Gov", AdTraits.of(shortName("Gov")).name)
        assertEquals("Govee_H6056", AdTraits.of(shortName("Gov") + b(12, 9) + "Govee_H6056".toByteArray()).name)
        assertNull(AdTraits.of(b(2, 1, 6, 3, 0xFF, 0x88, 0xEC)).name)
    }

    @Test
    fun liveThenRecordedThenNothingAndNeverTheProductTitle() {
        assertEquals("Govee_H6056_4828", DisplayFilter.deviceName("Govee_H6056_4828", listOf("Old name")))
        assertEquals("Govee_H6056_4828", DisplayFilter.deviceName(null, listOf(null, "Govee_H6056_4828")))
        assertEquals("Govee_H6056_4828", DisplayFilter.deviceName(" ", listOf("Govee_H6056_4828")))
        assertNull(DisplayFilter.deviceName(null, listOf(null, null)))
    }

    @Test
    fun recordedDevicesAndRowsCarryTheNameTheyAdvertised() {
        val db = JdbcSqlDatabase()
        val store = LocalStore(db)
        val matcher = FakeMatcher("v1", mapOf("device--bulb" to "bulb"))
        val ids = HashMap<String, Long>()
        val t0 = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L
        val named = adv("bulb_H6056")
        val unnamedLater = b(2, 1, 6, 3, 0xFF, 0x88, 0xEC) // the same device later, without its name
        val neverNamed = b(2, 1, 6, 3, 0xFF, 0x75, 0x00)
        val rows = listOf(
            Triple("AA:00:00:00:00:01", t0, named),
            Triple("AA:00:00:00:00:01", t0 + 60_000, unnamedLater),
            Triple("AA:00:00:00:00:02", t0, neverNamed),
        )
        store.startRecording("r1", t0)
        store.flush(
            FlushBatch(
                rows.associate { (_, at, raw) -> sha256Hex(raw) to PayloadDelta(raw, at, at, 1) },
                rows.associate { (addr, at, raw) ->
                    MinuteKey(addr, at / 60_000L, sha256Hex(raw)) to MinuteDelta(at, at, 0, MinuteBest(null, null)).also { it.add(at, -60, null) }
                },
                "r1", emptyList(), 0.0,
            ),
            ids, "v1",
        ) { _, raw -> matcher.match(raw) }
        val devices = store.devicesInRecording("r1").associateBy { it.addr }
        assertEquals("bulb_H6056", devices.getValue("AA:00:00:00:00:01").name)
        assertNull(devices.getValue("AA:00:00:00:00:02").name)
        val sightings = store.sightingsInRecording("r1", null, 100)
        assertEquals("bulb_H6056", DisplayFilter.deviceName(null, sightings.filter { it.addr == "AA:00:00:00:00:01" }.map { it.name }))
        assertNull(DisplayFilter.deviceName(null, sightings.filter { it.addr == "AA:00:00:00:00:02" }.map { it.name }))
    }
}
