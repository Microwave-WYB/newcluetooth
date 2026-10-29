package edu.ucsd.sysnet.cluetoothscanner.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FormatTest {
    /** G-4 bands. */
    @Test fun bands() {
        assertEquals(RssiBand.Strong, Format.band(-30))
        assertEquals(RssiBand.Strong, Format.band(-65))
        assertEquals(RssiBand.Fair, Format.band(-66))
        assertEquals(RssiBand.Fair, Format.band(-80))
        assertEquals(RssiBand.Weak, Format.band(-81))
    }

    /** C-5 / D-9: the typographic minus. */
    @Test fun minus() {
        assertEquals("−48", Format.signed(-48))
        assertEquals("12", Format.signed(12))
        assertEquals("−56 dBm", Format.dbm(-56))
    }

    /** L-2: durations from 60 min read "1 h 5 min"; the distance is omitted when 0. */
    @Test fun sessionSummary() {
        assertEquals("23 min", Format.duration(23))
        assertEquals("1 h", Format.duration(60))
        assertEquals("1 h 5 min", Format.duration(65))
        assertEquals("1.8 km", Format.distance(1800.0))
        assertEquals("300 m", Format.distance(300.0))
        assertEquals("23 min · 1.8 km · 214 devices", Format.sessionSummary(23 * 60_000L, 1800.0, 214))
        assertEquals("8 min · 41 devices", Format.sessionSummary(8 * 60_000L, 0.0, 41))
    }

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    /** L-2 day heads. */
    @Test fun dayLabels() {
        val now = at(2026, 10, 28, 20)
        assertEquals("Today · Oct 28", Format.dayLabel(at(2026, 10, 28, 0, 5), now, utc))
        assertEquals("Yesterday · Oct 27", Format.dayLabel(at(2026, 10, 27, 23, 59), now, utc))
        assertEquals("Oct 25", Format.dayLabel(at(2026, 10, 25), now, utc))
        assertEquals(20261028, Format.dayKey(now, utc))
    }

    @Test fun times() {
        assertEquals("7:12 PM", Format.time(at(2026, 10, 28, 19, 12), utc))
        assertEquals("12:34", Format.elapsed((12 * 60 + 34) * 1000L))
        assertEquals("0:05", Format.elapsed(5_400))
        assertEquals("1:02:03", Format.elapsed((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("just now", Format.ago(0, 59_000))
        assertEquals("4 min ago", Format.ago(0, 4 * 60_000L))
        assertEquals("2 h ago", Format.ago(0, 2 * 3_600_000L))
        assertEquals("3 d ago", Format.ago(0, 3 * 86_400_000L))
    }

    @Test fun misc() {
        assertEquals("32.88012, -117.23421 ±8 m", Format.location(32.88012, -117.23421, 8.2))
        assertEquals("0x09", Format.adType(9))
        assertEquals("0x16", Format.adType(0x16))
        assertEquals("02010a", Format.hex(byteArrayOf(2, 1, 10)))
        assertEquals("AA:BB:CC:DD:EE:FF", Format.mac(0xAABBCCDDEEFFL))
        assertEquals("812 KB", Format.bytes(812_000))
        assertEquals("4.2 MB", Format.bytes(4_200_000))
        assertEquals("340 MB", Format.bytes(340_000_000))
        assertEquals("1.3 GB", Format.bytes(1_300_000_000))
    }
}
