package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Dismissed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Full
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-5 snap states. 1 px = 1 dp here (density 1), a 412 × 915 phone. */
class SheetGeometryTest {
    private val g = SheetGeometry(screenHeight = 915f, topZone = 68f, peek = 78f, bottomInset = 24f, halfFraction = 0.67f, dismissGap = 8f)

    @Test
    fun `S-5 offsets - Full below the top row, Half 67 percent, Collapsed the peek above the nav bar`() {
        assertEquals(68f, g.offsetOf(Full), 0f)                 // --top-zone = 12 + 44 + 12
        assertEquals(915f * 0.33f, g.offsetOf(Half), 0.01f)     // 67 % of the screen covered
        assertEquals(915f - 78f - 24f, g.offsetOf(Collapsed), 0f) // the peek excludes the navigation bar inset (S-1)
        assertEquals(915f + 8f, g.offsetOf(Dismissed), 0f)      // below the screen
    }

    @Test
    fun `S-5 only pushed sheets have Dismissed`() {
        assertEquals(listOf(Collapsed, Half, Full), g.ladder(pushed = false))
        assertEquals(listOf(Dismissed, Collapsed, Half, Full), g.ladder(pushed = true))
        assertEquals(g.offsetOf(Collapsed), g.maxOffset(pushed = false), 0f)
        assertEquals(g.offsetOf(Dismissed), g.maxOffset(pushed = true), 0f)
    }

    @Test
    fun `S-5 a tiny screen never inverts the order`() {
        val tiny = g.copy(screenHeight = 120f)
        val offsets = tiny.ladder(pushed = true).map(tiny::offsetOf)
        assertTrue(offsets.zipWithNext().all { (low, high) -> low >= high })
    }

    @Test
    fun `S-14 nearest state and one-state steps that never skip`() {
        assertEquals(Half, g.nearest(320f, pushed = false))
        assertEquals(Full, g.nearest(100f, pushed = false))
        assertEquals(Collapsed, g.nearest(2000f, pushed = false))
        assertEquals(Dismissed, g.nearest(915f, pushed = true))
        assertEquals(Half, g.step(Collapsed, up = true, pushed = false))
        assertEquals(Full, g.step(Half, up = true, pushed = false))
        assertEquals(Full, g.step(Full, up = true, pushed = false))
        assertEquals(Collapsed, g.step(Collapsed, up = false, pushed = false))
        assertEquals(Dismissed, g.step(Collapsed, up = false, pushed = true))
    }
}
