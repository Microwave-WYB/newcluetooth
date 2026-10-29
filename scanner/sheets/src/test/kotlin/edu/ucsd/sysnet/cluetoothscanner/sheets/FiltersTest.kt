package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-1 badge, D-3a Filter sheet state, V-7 reset, Q-5 search ignoring filters. */
class FiltersTest {
    private val area = Bbox(-117.3, 32.8, -117.2, 32.9)

    @Test
    fun `D-1 the badge counts each switch, a non-Any Identification and each category`() {
        assertEquals(0, DeviceFilter().activeFilterCount)
        assertEquals(0, DeviceFilter(area = area, inRangeOnly = true, page = "p", query = "q").activeFilterCount)
        val f = DeviceFilter(readable = true, vulnerable = true, identification = Identification.Unidentified, categories = setOf("a", "b"))
        assertEquals(5, f.activeFilterCount)
    }

    @Test
    fun `D-3a Clear resets the sheet's choices and keeps the segment and area`() {
        val f = DeviceFilter(area = area, inRangeOnly = true, readable = true, trackers = true, identification = Identification.Identified, categories = setOf("audio-device"))
        assertEquals(DeviceFilter(area = area, inRangeOnly = true), f.clearSheetFilters())
    }

    @Test
    fun `D-3a categories toggle any-of`() {
        val f = DeviceFilter().toggleCategory("wearable").toggleCategory("audio-device")
        assertEquals(setOf("wearable", "audio-device"), f.categories)
        assertEquals(setOf("audio-device"), f.toggleCategory("wearable").categories)
    }

    @Test
    fun `D-3a the row order is fixed while open, counts update in place, zero rows stay dimmed`() {
        val open = FilterSheetContent.open(
            listOf(CategoryCount("wearable", "Wearable", 9), CategoryCount("audio-device", "Audio device", 4), CategoryCount("lighting-control", "Lighting control", 1)),
            checked = setOf("lighting-control"),
        )
        assertEquals(listOf("wearable", "audio-device", "lighting-control"), open.rowOrder)
        // a switch turned on: the counts change, audio now outnumbers wearables, lighting drops to 0
        val next = open.update(listOf(CategoryCount("audio-device", "Audio device", 3), CategoryCount("wearable", "Wearable", 1)))
        assertEquals(open.rowOrder, next.rowOrder)
        assertEquals(1, next.count("wearable"))
        assertEquals(3, next.count("audio-device"))
        assertTrue(next.dimmed("lighting-control"))
        assertFalse(next.dimmed("wearable"))
        assertEquals("Lighting control", next.labels["lighting-control"])
    }

    @Test
    fun `D-3a a category first seen while open is appended, a checked empty category still has a row`() {
        val open = FilterSheetContent.open(listOf(CategoryCount("wearable", "Wearable", 2)), checked = setOf("printer")) { "Printer" }
        assertEquals(listOf("wearable", "printer"), open.rowOrder)
        assertTrue(open.dimmed("printer"))
        val next = open.update(listOf(CategoryCount("camera", "Camera", 1), CategoryCount("wearable", "Wearable", 3)))
        assertEquals(listOf("wearable", "printer", "camera"), next.rowOrder)
    }

    @Test
    fun `V-7 a new session view starts unfiltered, Q-5 search queries without the filters`() {
        assertEquals(DeviceFilter(), resetFilterForNewView())
        val saved = DeviceFilter(trackers = true, categories = setOf("asset-tracker"))
        val search = searchQueryFilter("qt", area)
        assertEquals(DeviceFilter(area = area, query = "qt"), search)
        assertEquals(0, search.activeFilterCount)
        assertEquals(1 + 1, saved.activeFilterCount) // the saved filter is untouched, restored when search ends
    }
}
