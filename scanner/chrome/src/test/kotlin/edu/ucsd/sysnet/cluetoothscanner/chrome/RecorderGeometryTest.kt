package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder morph geometry (R-2 … R-4, R-9, P-3, T-7, G-13a). */
class RecorderGeometryTest {
    private val group = 64.dp
    private fun phone(f: Form, searching: Boolean = false, cover: Int = 0) =
        RecorderGeometry.compute(f, false, 412.dp, 915.dp, group, cover.dp, searching)
    private fun tablet(f: Form) = RecorderGeometry.compute(f, true, 1024.dp, 768.dp, group)

    /** P-3: a 56 dp FAB at the bottom-right, its bottom `--edge` + 12 above the sheet's peek. */
    @Test fun fab() {
        val g = phone(Form.Fab, cover = 78)
        assertEquals(56.dp, g.width); assertEquals(56.dp, g.height); assertEquals(28.dp, g.radius)
        assertEquals(412.dp - 12.dp, g.right)
        assertEquals(915.dp - 78.dp - 24.dp, g.bottom)
    }

    /** R-3: the 44 dp island centered between ☰ and search, its center on their row; buttons 36 dp, gap 6. */
    @Test fun island() {
        val g = phone(Form.Capsule)
        assertEquals(44.dp, g.height)
        assertEquals(12.dp + 22.dp, g.y + g.height / 2) // the ☰ row's center: --edge + 44 / 2
        assertEquals(412.dp / 2, g.x + g.width / 2)
        assertEquals(14.dp, g.groupX)
        assertEquals(36.dp, g.buttonSize); assertEquals(6.dp, g.buttonGap)
        // 14 + group + 6 + 4 + (36 + 6 + 36) + 4
        assertEquals(14.dp + group + 6.dp + 4.dp + 78.dp + 4.dp, g.width)
    }

    /** R-4: the panel keeps the row's top and height, widens to the full width − 2 × --edge, radius 22 and Stop
     *  4 dp from the corner; the group centers. */
    @Test fun phonePanel() {
        val cap = phone(Form.Capsule)
        val p = phone(Form.Panel)
        assertEquals(cap.y, p.y); assertEquals(cap.rowTop, p.rowTop); assertEquals(cap.rowHeight, p.rowHeight)
        assertEquals(12.dp, p.x); assertEquals(412.dp - 24.dp, p.width)
        assertEquals(22.dp, p.radius); assertEquals(4.dp, p.buttonsInset)
        assertEquals(22.dp, p.buttonsInset + p.buttonSize / 2) // concentric: 18 + 4
        assertEquals((p.width - group) / 2, p.groupX)
        assertTrue(p.extrasBelowRow)
        // its height from its content: row 44, meta 20, `--edge` 12 below it, and app.js's 2 (R-5's mini map is removed)
        assertEquals(78.dp, p.height)
    }

    /** R-9: in search state the island sits directly below the search bar. */
    @Test fun searchState() {
        assertEquals(12.dp + 44.dp + 8.dp, phone(Form.Capsule, searching = true).y)
        assertEquals(12.dp + 44.dp + 8.dp, phone(Form.Panel, searching = true).y)
    }

    /** T-7: the FAB widens in place (FAB height, 40 dp buttons, gap 8); the panel grows up and to the left,
     *  right-aligned, at most 420 wide; the row never moves; radius 28 with Stop 8 dp in. */
    @Test fun tabletMorph() {
        val f = tablet(Form.Fab); val c = tablet(Form.Capsule); val p = tablet(Form.Panel)
        assertEquals(f.right, c.right); assertEquals(f.bottom, c.bottom); assertEquals(56.dp, c.height)
        assertEquals(40.dp, c.buttonSize); assertEquals(8.dp, c.buttonGap); assertEquals(8.dp, c.buttonsInset)
        assertEquals(18.dp + group + 8.dp + 4.dp + 88.dp + 8.dp, c.width)
        assertEquals(c.right, p.right); assertEquals(c.bottom, p.bottom)
        assertEquals(c.y + c.rowTop, p.y + p.rowTop) // the row stays put
        assertEquals(420.dp, p.width); assertEquals(28.dp, p.radius)
        // meta 14 + 20 above the 56 dp row, and app.js's 2
        assertEquals(92.dp, p.height)
        assertTrue(!p.extrasBelowRow)
        // a narrow tablet: the panel leaves the floating panel and three edges free
        val narrow = RecorderGeometry.compute(Form.Panel, true, 700.dp, 768.dp, group)
        assertEquals(700.dp - 400.dp - 36.dp, narrow.width)
    }

    /** Collapsing returns exactly to the capsule layout. */
    @Test fun collapseIsExact() {
        assertEquals(phone(Form.Capsule), phone(Form.Capsule))
        val p = phone(Form.Panel)
        assertEquals(phone(Form.Capsule).copy(x = p.x, width = p.width, height = p.height, radius = p.radius, groupX = p.groupX), p)
    }
}
