package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail

// The pushed sheets' content as immutable values, each a small state machine: async results enter it only as events
// through a pure reducer, and the one write is SheetMachine.dispatch, a non-suspending reduce of the *current* value
// (AGENTS.md). No effect can read the content before a suspension and write back a stale value.

/**
 * One sheet's state machine: an immutable [state] and a pure [reduce]. Effects only produce events; [dispatch] is the
 * only write and reduces the current state without suspending.
 */
@Stable
internal class SheetMachine<S, E>(initial: S, private val reduce: (S, E) -> S) {
    var state: S by mutableStateOf(initial)
        private set

    fun dispatch(event: E) {
        state = reduce(state, event)
    }
}

@Composable
internal fun <S, E> rememberSheetMachine(initial: () -> S, reduce: (S, E) -> S): SheetMachine<S, E> =
    remember { SheetMachine(initial(), reduce) }

/** A value read from the core: not read yet, read, not there (the core returned none), or the read failed. */
internal sealed interface Loaded<out T> {
    data object Pending : Loaded<Nothing>
    data class Found<T>(val value: T) : Loaded<T>
    data object Missing : Loaded<Nothing>
    data class Failed(val message: String) : Loaded<Nothing>
}

internal fun <T> loadedOf(read: Outcome<T?>): Loaded<T> = when (read) {
    is Outcome.Ok -> read.value?.let { Loaded.Found(it) } ?: Loaded.Missing
    is Outcome.Failed -> Loaded.Failed(read.message)
}

/** The value when [Loaded.Found]. */
internal fun <T> Loaded<T>.valueOrNull(): T? = when (this) {
    is Loaded.Found -> value
    Loaded.Pending, Loaded.Missing, is Loaded.Failed -> null
}

/** The product sheet (D-6, D-7): its detail, and its devices in the session order (G-9). */
internal data class ProductContent(
    val detail: Loaded<ProductDetail> = Loaded.Pending,
    val keys: LongArray = LongArray(0),
    /** D-6: the header's category label. */
    val title: String = "",
) {
    fun withDetail(detail: Outcome<ProductDetail?>) = copy(detail = loadedOf(detail))
    fun withTitle(title: String) = copy(title = title)
    fun withKeys(keys: LongArray) = copy(keys = keys)
}

/** The Filter sheet's category rows (D-3a): not counted yet, or shown with a fixed order. */
internal sealed interface FilterRows {
    data object Pending : FilterRows
    data class Shown(val content: FilterSheetContent) : FilterRows

    /** New faceted counts: the first ones fix the row order, later ones update in place. */
    fun withCounts(counts: List<CategoryCount>, checked: Set<String>, labelOf: (String) -> String): FilterRows = when (this) {
        Pending -> Shown(FilterSheetContent.open(counts, checked, labelOf))
        is Shown -> Shown(content.update(counts))
    }
}

/** The live chart of the device sheet (D-10): none (not live, or no samples yet), or the latest. */
internal sealed interface ChartState {
    data object None : ChartState
    data class Live(val chart: LiveChart) : ChartState
}

internal enum class DeviceTab { Advertising, Gatt }

/** The device sheet (D-9..D-12): its detail, the live chart and the tab (D-11). */
internal data class DeviceContent(
    val detail: Loaded<DeviceDetail> = Loaded.Pending,
    val chart: ChartState = ChartState.None,
    val tab: DeviceTab = DeviceTab.Advertising,
) {
    fun withDetail(detail: Outcome<DeviceDetail?>) = copy(detail = loadedOf(detail))
    fun withChart(chart: LiveChart?) = copy(chart = chart?.let(ChartState::Live) ?: ChartState.None)
    /** A failed chart read leaves the chart as it was. */
    fun withChart(chart: Outcome<LiveChart?>) = when (chart) {
        is Outcome.Ok -> withChart(chart.value)
        is Outcome.Failed -> this
    }
    fun withTab(tab: DeviceTab) = copy(tab = tab)
}

/** What happens to the device sheet's content (D-9..D-12). */
internal sealed interface DeviceEvent {
    data class DetailLoaded(val detail: Outcome<DeviceDetail?>) : DeviceEvent
    /** D-10: the chart so far, when the sheet opens while live. */
    data class ChartLoaded(val chart: Outcome<LiveChart?>) : DeviceEvent
    /** D-10: a live delta's chart for this key. */
    data class ChartDelta(val chart: LiveChart) : DeviceEvent
    /** D-11. */
    data class TabSelected(val tab: DeviceTab) : DeviceEvent
}

/** Each event changes only its own part, so any arrival order of detail and chart ends in the same content. */
internal fun reduceDevice(s: DeviceContent, e: DeviceEvent): DeviceContent = when (e) {
    is DeviceEvent.DetailLoaded -> s.withDetail(e.detail)
    is DeviceEvent.ChartLoaded -> s.withChart(e.chart)
    is DeviceEvent.ChartDelta -> s.withChart(e.chart)
    is DeviceEvent.TabSelected -> s.withTab(e.tab)
}

/** What happens to the product sheet's content (D-6, D-7). */
internal sealed interface ProductEvent {
    /** The detail, with the header's category label (D-6) read for it. */
    data class DetailLoaded(val detail: Outcome<ProductDetail?>, val title: String) : ProductEvent
    /** D-7.6, G-9: the product's devices in the session order. */
    class KeysLoaded(val keys: LongArray) : ProductEvent
}

internal fun reduceProduct(s: ProductContent, e: ProductEvent): ProductContent = when (e) {
    is ProductEvent.DetailLoaded -> s.withDetail(e.detail).withTitle(e.title)
    is ProductEvent.KeysLoaded -> s.withKeys(e.keys)
}

/** D-3a: new faceted counts, with the checked categories and the labels known when they were read. */
internal class FilterCounts(val counts: List<CategoryCount>, val checked: Set<String>, val labels: Map<String, String>)

internal fun reduceFilterRows(s: FilterRows, e: FilterCounts): FilterRows = s.withCounts(e.counts, e.checked) { e.labels[it] ?: it }

/** D-2: the product cards: the first read, then live refreshes (a failed refresh keeps the cards shown). */
internal sealed interface CategoriesEvent {
    data class Loaded(val groups: Outcome<List<CategoryGroup>>) : CategoriesEvent
    data class Refreshed(val groups: Outcome<List<CategoryGroup>>) : CategoriesEvent
}

internal fun reduceCategories(s: edu.ucsd.sysnet.cluetoothscanner.sheets.Loaded<List<CategoryGroup>>, e: CategoriesEvent) = when (e) {
    is CategoriesEvent.Loaded -> loadedOf(e.groups)
    is CategoriesEvent.Refreshed -> when (val g = e.groups) {
        is Outcome.Ok -> edu.ucsd.sysnet.cluetoothscanner.sheets.Loaded.Found(g.value)
        is Outcome.Failed -> s
    }
}
