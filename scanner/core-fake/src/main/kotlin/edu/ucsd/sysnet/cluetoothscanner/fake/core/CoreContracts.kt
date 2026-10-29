package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope

/**
 * Query contracts that any [CoreQueries] must keep, shared by the fake's tests and the real core's parity tests
 * (`:core-bridge`), so both run the same checks. Each returns the violations it found; empty means it holds.
 */
object CoreContracts {
    /** The switches lane D's Filter sheet combines with a category (D-3a). */
    val SWITCHES: Map<String, DeviceFilter> = linkedMapOf(
        "none" to DeviceFilter(), "readable" to DeviceFilter(readable = true),
        "trackers" to DeviceFilter(trackers = true), "vulnerable" to DeviceFilter(vulnerable = true),
    )

    /** Filters that only hide (G-9). */
    val FILTERS: List<DeviceFilter> = SWITCHES.values.toList() + listOf(
        DeviceFilter(identification = Identification.Identified),
        DeviceFilter(identification = Identification.Unidentified),
        DeviceFilter(area = Bbox(-117.236, 32.878, -117.231, 32.883)),
        DeviceFilter(inRangeOnly = true),
    )

    /**
     * D-3a: for every category in [categories] (present or not) and each switch, the facet count equals the length
     * of the list that filter plus the category produces (0 when the category is absent); every switch has a count.
     */
    suspend fun facetCountsEqualListLength(q: CoreQueries, scopes: List<Scope>, categories: List<String>): List<String> {
        val violations = mutableListOf<String>()
        for (scope in scopes) for ((name, base) in SWITCHES) {
            val counts = q.categoryCounts(scope, base).associate { it.category to it.deviceCount }
            if (counts.isEmpty()) violations += "$scope $name: no category counted"
            for (category in categories) {
                val list = q.deviceKeys(scope, base.copy(categories = setOf(category))).keys.size
                val count = counts[category] ?: 0
                if (list != count) violations += "$scope $name $category: count $count, list $list"
            }
        }
        return violations
    }

    /** G-9: `deviceKeys` is `deviceOrder` with rows hidden, never reordered; the order is RSSI desc, then key. */
    suspend fun filtersOnlyHide(q: CoreQueries, scopes: List<Scope>): List<String> {
        val violations = mutableListOf<String>()
        for (scope in scopes) {
            val order = q.deviceOrder(scope).keys.toList()
            val rows = order.chunked(200).flatMap { q.deviceRows(scope, it.toLongArray()) }
            if (rows.map { it.key } != order) violations += "$scope: rows differ from the order"
            rows.zipWithNext().forEach { (a, b) ->
                val ra = a.rssi ?: Int.MIN_VALUE
                val rb = b.rssi ?: Int.MIN_VALUE
                if (ra < rb || (ra == rb && a.key > b.key)) violations += "$scope: ${a.addr} before ${b.addr}"
            }
            for (f in FILTERS) {
                val keys = q.deviceKeys(scope, f).keys.toList()
                val passing = keys.toSet()
                if (order.filter { it in passing } != keys) violations += "$scope $f: reordered or not a subset"
            }
        }
        return violations
    }

    /** Q-1/Q-2: blank is no search; hits keep the order and are exactly the `matchDevice` rows. */
    suspend fun searchKeepsOrder(q: CoreQueries, scope: Scope, query: String): List<String> {
        val violations = mutableListOf<String>()
        if (q.search(scope, "  ", null) != null) violations += "a blank query searched"
        val hits = q.search(scope, query, null)?.keys?.toList() ?: return violations + "no result for $query"
        val order = q.deviceOrder(scope).keys.toList()
        val rows = order.chunked(200).flatMap { q.deviceRows(scope, it.toLongArray()) }
        val expected = rows.filter { q.matchDevice(query, it.name, it.addr) }.map { it.key }
        if (hits != expected) violations += "search $query: ${hits.size} hits, expected ${expected.size} in order"
        if (hits.isEmpty()) violations += "search $query found nothing"
        return violations
    }
}
