package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification

// D-1, D-3a: the Filter sheet's part of a DeviceFilter is the three switches, Identification and the categories.
// The segment (Nearby, This area), a product page and a search query are set elsewhere and survive Clear.

/** D-1: the filter button's badge, one per active switch, a non-Any Identification and each checked category. */
internal val DeviceFilter.activeFilterCount: Int
    get() = listOf(readable, trackers, vulnerable).count { it } +
        (if (identification != Identification.Any) 1 else 0) + categories.size

/** D-3a Clear: the sheet's own choices only. */
internal fun DeviceFilter.clearSheetFilters(): DeviceFilter =
    copy(identification = Identification.Any, categories = emptySet(), readable = false, trackers = false, vulnerable = false)

/** D-3a: a category row toggled; categories combine any-of. */
internal fun DeviceFilter.toggleCategory(id: String): DeviceFilter =
    copy(categories = if (id in categories) categories - id else categories + id)

/** V-7: a new session view starts unfiltered. */
internal fun resetFilterForNewView(): DeviceFilter = DeviceFilter()

/** Q-5, D-3a: search ignores the filters; it queries with the query and the area only. */
internal fun searchQueryFilter(query: String, area: Bbox?): DeviceFilter = DeviceFilter(area = area, query = query)

/**
 * D-3a: the Filter sheet's rows. The order is fixed when the sheet opens; counts update in place; a row whose count
 * falls to 0 stays where it is (dimmed) and can still be unchecked. A category first seen while the sheet is open
 * (a live recording) is appended. A checked category with no devices still gets a row, so it can be unchecked.
 *
 * This is the contract's `SheetContentState.Filter(rowOrder, counts)` plus the labels to show.
 */
internal data class FilterSheetContent(
    val rowOrder: List<String>,
    val counts: Map<String, Int>,
    val labels: Map<String, String>,
) {
    fun count(id: String): Int = counts[id] ?: 0
    fun dimmed(id: String): Boolean = count(id) == 0

    /** New faceted counts (core `category_counts`, which ignores the categories facet itself). */
    fun update(next: List<CategoryCount>): FilterSheetContent {
        val known = rowOrder.toSet()
        val added = next.map { it.category }.filter { it !in known }
        return copy(
            rowOrder = rowOrder + added,
            counts = next.associate { it.category to it.deviceCount },
            labels = labels + next.associate { it.category to it.label },
        )
    }

    companion object {
        /** Opens the sheet with the core's order (device count desc, then label) and the checked categories. */
        fun open(counts: List<CategoryCount>, checked: Set<String>, labelOf: (String) -> String = { it }): FilterSheetContent {
            val order = counts.map { it.category }
            val extra = checked.filter { it !in order }.sorted()
            return FilterSheetContent(
                rowOrder = order + extra,
                counts = counts.associate { it.category to it.deviceCount },
                labels = counts.associate { it.category to it.label } + extra.associateWith(labelOf),
            )
        }
    }
}
