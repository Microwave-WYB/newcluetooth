package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail

// The pushed sheets' content as immutable values with pure updates; the composables only hold one value each and
// replace it from their effects' results.

/** A value read from the core: not read yet, read, or not there (the core returned none). */
internal sealed interface Loaded<out T> {
    data object Pending : Loaded<Nothing>
    data class Found<T>(val value: T) : Loaded<T>
    data object Missing : Loaded<Nothing>
}

internal fun <T> loadedOf(value: T?): Loaded<T> = if (value == null) Loaded.Missing else Loaded.Found(value)

/** The value when [Loaded.Found]. */
internal fun <T> Loaded<T>.valueOrNull(): T? = when (this) {
    is Loaded.Found -> value
    Loaded.Pending, Loaded.Missing -> null
}

/** The product sheet (D-6, D-7): its detail, and its devices in the session order (G-9). */
internal data class ProductContent(val detail: Loaded<ProductDetail> = Loaded.Pending, val keys: LongArray = LongArray(0)) {
    fun withDetail(detail: ProductDetail?) = copy(detail = loadedOf(detail))
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
    fun withDetail(detail: DeviceDetail?) = copy(detail = loadedOf(detail))
    fun withChart(chart: LiveChart?) = copy(chart = chart?.let(ChartState::Live) ?: ChartState.None)
    fun withTab(tab: DeviceTab) = copy(tab = tab)
}
