package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * The sheets of one session view (lane D's entry point for :app and the gallery). A new session view makes a new
 * state (V-7: the stack back to the root, the segment, filters, area and search reset, the order sorted once).
 *
 * - [mapEvents] is what :app routes the map's events to (P-6, M-7, D-4, Q-6).
 * - [handleBack] is D's part of the G-14 back order: the top pushed sheet, then search state.
 * - [enterSearch] / [exitSearch] are Q-5 and Q-7, called by the search page and the search bar (lane C).
 * - The map follows the top sheet through [MapRequests] (S-17, D-8, D-14), and a running GATT discovery is cancelled
 *   when its device sheet leaves the stack (D-13.8).
 */
class SheetsState internal constructor(
    internal val scope: Scope,
    /** The view is the live recording (V-2): Nearby, sparklines, the chart. */
    val live: Boolean,
    private val routesFaint: Boolean,
    private val core: CoreQueries,
    private val gattRunner: GattRunner,
    private val map: MapRequests?,
    internal val coroutines: CoroutineScope,
    internal val nowMs: () -> Long,
    internal val metrics: SheetMetrics,
) {
    /**
     * Launches [block] on the sheets' scope with its failure handled: the core and GATT calls already come back as
     * [Outcome]s, and anything else that escapes moves Discover to Failed (Try again re-reads it) instead of
     * reaching the scope.
     */
    private fun launch(block: suspend CoroutineScope.() -> Unit) = coroutines.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discover.fault(failureMessage(e))
        }
    }

    private val gatt = HashMap<Long, GattController>()

    /** The stack (S-4..S-9): the cover geometry is `offsetOf(top)` and `top.peek`, for MapRequests.setCover. */
    val stack: SheetStackController = SheetStackController(
        scope = coroutines,
        animator = composeOffsetAnimator(metrics),
        metrics = metrics,
        hooks = SheetStackHooks(
            camera = { map?.camera() },
            restoreCamera = { map?.restore(it) },
            onTopChanged = { entry, opening -> followTop(entry, opening) },
            onRemoved = { gatt.remove(it.id)?.cancel() },
        ),
    )
    /** The Discover sheet's list, segment, filter, area and search state (G-9, D-1..D-4, Q-5..Q-7). */
    val discover = DiscoverModel(core.forScope(scope), live, stack)
    internal val rows = RowStore(core, scope, coroutines)

    /** Category ids to their vocabulary labels (the core's, G-8), for headers and the Filter sheet. */
    internal var categoryLabels by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Learns category labels from any core answer that carries them (category counts, product groups). The first read
     * at open can come back empty (a live session that has not seen a device yet), so labels are never a one-time
     * snapshot.
     */
    internal fun learnLabels(labels: List<Pair<String, String>>) {
        val merged = mergeLabels(categoryLabels, labels)
        if (merged !== categoryLabels) categoryLabels = merged
    }

    /** D-6: the display label of [category] for a product sheet's title; read from the product's counts if unknown. */
    internal suspend fun labelOf(category: String, pageId: String?): String {
        categoryLabels[category]?.let { return it }
        val filter = if (pageId == null) DeviceFilter(identification = Identification.Unidentified) else DeviceFilter(page = pageId)
        when (val counts = load(SheetQuery.Counts(filter))) {
            is Outcome.Ok -> learnLabels(counts.value.map { it.category to it.label })
            is Outcome.Failed -> Unit // the id stands in for the label
        }
        return categoryLabels[category] ?: category
    }
    /** The first sort has landed (S-7 content can show); derived from the model's phase, not a flag. */
    internal val opened: Boolean get() = discover.phase is DiscoverPhase.Ready

    private val sheetEvents = SheetMapEvents(stack) { byUser, viewport, zoom ->
        launch {
            discover.onCameraIdle(byUser, viewport, zoom)
            // D-4, S-17: This area followed the user's move; the map shows the new set
            if (byUser && discover.search == null && discover.segment == Segment.ThisArea) followDiscover()
        }
    }

    /** :app routes the map's events here (and to lane C for the "Search this area" chip). */
    val mapEvents: MapEvents = object : MapEvents by sheetEvents {
        override fun onDeviceTap(key: DeviceKey) {
            stack.openDevice(key) // D-14: from a map tap the camera stays put
        }
    }

    /** True when search state is on (Q-5): lane C shows the search bar instead of ☰ and search. */
    val searching: Boolean get() = discover.search != null

    /** "Search this area" is offered (Q-6). */
    val searchMovedAway: Boolean get() = discover.search?.movedAway == true

    /** G-14: pops the top pushed sheet, else leaves search state. False when there was nothing to close. */
    fun handleBack(): Boolean {
        if (stack.handleBack()) return true
        if (discover.search == null) return false
        launch { discover.exitSearch() }
        return true
    }

    /** Q-5: a picked search result. The Discover sheet lists the matches; the picked device opens at Half. */
    fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot, picked: DeviceKey?) {
        launch {
            discover.enterSearch(query, area, reference)
            followTop(stack.root, opening = false)
            if (picked != null) openDevice(picked, fromList = true)
        }
    }

    /** Q-7: the search bar's X. */
    fun exitSearch() {
        launch {
            discover.exitSearch()
            followTop(stack.root, opening = false)
        }
    }

    /** Q-6: the "Search this area" chip. */
    fun searchThisArea(viewport: Bbox, zoom: Double) {
        launch { discover.searchThisArea(viewport, zoom); followTop(stack.root, opening = false) }
    }

    /** S-4: open a device; from a list (and search results) the camera eases to it (D-14). */
    fun openDevice(key: DeviceKey, fromList: Boolean) {
        stack.openDevice(key) // selects it on the map (S-17, M-6)
        if (fromList) map?.focusDevice(key)
    }

    internal fun openProduct(pageId: String?) {
        stack.push(SheetKind.Product(pageId))
    }

    internal fun openFilter() {
        stack.push(SheetKind.Filter)
    }

    /** D-3a: the Filter sheet applies changes live. */
    internal fun setFilter(filter: DeviceFilter) {
        launch { discover.setFilter(filter) }
    }

    /** D-13.1: Try again after the first sort failed. */
    internal fun retry() {
        launch {
            discover.retry()
            followDiscover()
        }
    }

    /** Runs one of the pushed sheets' reads: every screen reads the core through here, never directly. */
    internal suspend fun <T> load(query: SheetQuery<T>): Outcome<T> = attempt { query.run(core, scope) }

    /** D-10: the live charts of [key] from the deltas; the flow ends quietly if the live feed fails. */
    internal fun chartUpdates(key: DeviceKey): Flow<LiveChart> = core.live.outcomes().transformWhile { delta ->
        when (delta) {
            is Outcome.Ok -> {
                delta.value.chart?.takeIf { it.key == key }?.let { emit(it) }
                true
            }
            is Outcome.Failed -> false
        }
    }

    internal fun gattFor(entry: SheetEntry, key: DeviceKey): GattController =
        gatt.getOrPut(entry.id) { GattController(key, coroutines, gattRunner, nowMs) }

    /** The filter of a product sheet: its page (or the unidentified card), within This area (D-7.6). */
    internal fun productFilter(pageId: String?): DeviceFilter {
        val base = discover.queryFilter().copy(inRangeOnly = false)
        return if (pageId == null) base.copy(identification = Identification.Unidentified) else base.copy(page = pageId)
    }

    /** The point set of the Discover sheet now: the filtered session, or only the search matches (Q-5). */
    internal fun discoverMapFilter(): DeviceFilter =
        discover.search?.let { searchQueryFilter(it.query, it.area) } ?: discover.queryFilter()

    /**
     * §2.6: subscribe to the live deltas first, then read the first sort, so no delta between the two is lost. A
     * delta that arrives while the model is Loading is kept by the model's state and applied when it is Ready
     * ([DiscoverPhase.Loading.pending]); nothing depends on which one finishes first.
     */
    internal suspend fun start() = coroutineScope {
        if (live) launch(start = CoroutineStart.UNDISPATCHED) { rows.followLive(discover) }
        open()
    }

    /** D-1: a segment switch; S-17: the map shows the new point set. */
    internal fun selectSegment(segment: Segment) {
        launch {
            discover.selectSegment(segment)
            followDiscover()
        }
    }

    /** S-17: re-show the Discover point set if the Discover sheet is on top. */
    private fun followDiscover() {
        if (stack.top === stack.root) followTop(stack.root, opening = false)
    }

    /** Called once by [start]: the labels (cosmetic: a failed read leaves the ids), then the model's first sort. */
    internal suspend fun open() {
        when (val counts = attempt { core.categoryCounts(scope, DeviceFilter()) }) {
            is Outcome.Ok -> learnLabels(counts.value.map { it.category to it.label })
            is Outcome.Failed -> Unit // the Filter sheet and product titles fall back to the category ids
        }
        discover.open()
        followTop(stack.root, opening = false)
    }

    /** S-17: the map shows what the top sheet is about. Called on push, replace and reveal. */
    private fun followTop(entry: SheetEntry, opening: Boolean) {
        val map = map ?: return
        when (val kind = entry.kind) {
            SheetKind.Discover -> {
                map.setSelection(null)
                map.show(scope, discoverMapFilter(), routesFaint)
            }
            is SheetKind.Product -> {
                map.setSelection(null)
                val filter = productFilter(kind.pageId)
                map.show(scope, filter, routesFaint)
                if (opening) launch { (productFrame(filter) as? Outcome.Ok)?.value?.let(map::frame) } // D-8
            }
            is SheetKind.Device -> map.setSelection(kind.key) // M-6; D-14's ease is openDevice's (from a list only)
            SheetKind.Filter -> Unit // the map stays as it is
        }
    }

    /** D-8: the bounding box of a product's devices' strongest points (none, or a failure: the camera stays). */
    private suspend fun productFrame(filter: DeviceFilter): Outcome<Bbox?> = attempt {
        val keys = core.deviceKeys(scope, filter).keys
        val points = keys.toList().chunked(RowStore.PAGE).flatMap { chunk -> core.deviceRows(scope, chunk.toLongArray()).mapNotNull { it.best } }
        boundsOf(points)
    }
}

/** A read of the pushed sheets (their effects), run by [SheetsState.load]; the result comes back as an [Outcome]. */
internal sealed interface SheetQuery<T> {
    suspend fun run(core: CoreQueries, scope: Scope): T

    /** D-9: the device's detail. */
    data class Device(val key: DeviceKey) : SheetQuery<DeviceDetail?> {
        override suspend fun run(core: CoreQueries, scope: Scope) = core.device(scope, key)
    }

    /** D-13.7: the device's stored GATT result. */
    data class LastGatt(val key: DeviceKey) : SheetQuery<GattView?> {
        override suspend fun run(core: CoreQueries, scope: Scope) = core.deviceGatt(key)
    }

    /** D-10: focus the live chart on [key] and read its chart so far. */
    data class Chart(val key: DeviceKey) : SheetQuery<LiveChart?> {
        override suspend fun run(core: CoreQueries, scope: Scope): LiveChart? {
            core.setLiveFocus(key)
            return core.deviceChart(key)
        }
    }

    /** D-6, D-7: the product's detail. */
    data class Product(val pageId: String?, val filter: DeviceFilter) : SheetQuery<ProductDetail?> {
        override suspend fun run(core: CoreQueries, scope: Scope) = core.product(scope, pageId, filter)
    }

    /** D-2: the product cards. */
    data class Products(val filter: DeviceFilter) : SheetQuery<List<CategoryGroup>> {
        override suspend fun run(core: CoreQueries, scope: Scope) = core.products(scope, filter)
    }

    /** D-3a: the faceted category counts. */
    data class Counts(val filter: DeviceFilter) : SheetQuery<List<CategoryCount>> {
        override suspend fun run(core: CoreQueries, scope: Scope) = core.categoryCounts(scope, filter)
    }
}

/** [old] with [learned] added; [old] itself when nothing is new (no state write). */
internal fun mergeLabels(old: Map<String, String>, learned: List<Pair<String, String>>): Map<String, String> =
    if (learned.all { (id, label) -> old[id] == label }) old else old + learned

/** The bounding box of [points], or null when there are none. */
internal fun boundsOf(points: List<GeoPoint>): Bbox? =
    if (points.isEmpty()) null
    else Bbox(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })

/** The sheets for [scope]; keyed by the scope and [live], so a new session view starts fresh (V-7). */
@Composable
fun rememberSheetsState(
    scope: Scope,
    live: Boolean,
    routesFaint: Boolean,
    core: CoreQueries,
    gatt: GattRunner,
    map: MapRequests?,
    nowMs: () -> Long = System::currentTimeMillis,
    reducedMotion: Boolean = false,
): SheetsState {
    val coroutines = rememberCoroutineScope()
    val state = remember(scope, live) {
        SheetsState(scope, live, routesFaint, core, gatt, map, coroutines, nowMs, SheetMetrics(reducedMotion = reducedMotion))
    }
    LaunchedEffect(state) { state.start() }
    return state
}

/**
 * What the row logic tracks besides the rows: which keys are loading, the last live delta applied (§2.6), and
 * [version], which counts changes to the rows. The rows themselves live in [RowStore]'s table, written only by
 * [RowEffect.Put], so an update costs the changed rows, not a copy of every loaded one.
 */
internal data class RowState(
    val loading: Set<Long> = emptySet(),
    val seq: Long = -1,
    val version: Long = 0,
)

internal sealed interface RowEvent {
    data class Requested(val keys: List<Long>) : RowEvent
    data class ChunkLoaded(val rows: List<DeviceRow>) : RowEvent
    data class LoadEnded(val keys: List<Long>) : RowEvent
    data class Live(val delta: LiveDelta) : RowEvent
}

internal sealed interface RowEffect {
    /** Read [keys] in pages of ≤ 200 (§2.5). */
    data class Load(val keys: List<Long>) : RowEffect
    /** G-9: new devices of the live view are appended to the order. */
    class AppendToOrder(val keys: LongArray) : RowEffect
    /** Write [rows] into the table: new rows, or loaded rows updated in place. */
    class Put(val rows: List<DeviceRow>) : RowEffect
}

internal data class RowStep(val state: RowState, val effects: List<RowEffect> = emptyList())

/**
 * The pure row logic: what to load, and how a live delta changes the loaded rows in place. [rows] is the table as
 * it is now, read only; the writes are [RowEffect.Put]s, and every write bumps [RowState.version].
 */
internal fun reduceRows(state: RowState, rows: Map<Long, DeviceRow>, event: RowEvent): RowStep = when (event) {
    is RowEvent.Requested -> {
        val missing = event.keys.filter { it !in rows && it !in state.loading }.distinct()
        if (missing.isEmpty()) RowStep(state) else RowStep(state.copy(loading = state.loading + missing), listOf(RowEffect.Load(missing)))
    }
    is RowEvent.ChunkLoaded ->
        if (event.rows.isEmpty()) RowStep(state)
        else RowStep(state.copy(version = state.version + 1), listOf(RowEffect.Put(event.rows)))
    is RowEvent.LoadEnded -> RowStep(state.copy(loading = state.loading - event.keys.toSet()))
    is RowEvent.Live -> {
        val delta = event.delta
        if (delta.seq <= state.seq) RowStep(state)
        else {
            val changed = delta.changed.mapNotNull { c ->
                rows[c.key]?.let { row ->
                    row.copy(
                        rssi = c.rssi, inRange = c.inRange, spark = c.spark,
                        name = c.name ?: row.name, page = c.page ?: row.page, best = c.best ?: row.best,
                    )
                }
            }
            val put = changed + delta.appended
            RowStep(
                state.copy(seq = delta.seq, version = if (put.isEmpty()) state.version else state.version + 1),
                listOfNotNull(
                    RowEffect.Put(put).takeIf { put.isNotEmpty() },
                    RowEffect.AppendToOrder(delta.appended.map { it.key }.toLongArray()).takeIf { delta.appended.isNotEmpty() },
                ),
            )
        }
    }
}

/**
 * Device rows by key, loaded on demand in pages of ≤ 200 (§2.5) and kept current by the live deltas (§2.6): values
 * update in place, new devices are appended to the session order (G-9). The runner of [reduceRows]:
 * - the table is a plain map that only this runner writes (the [RowEffect.Put]s), on the UI thread;
 * - [state] is immutable and compared by reference, and [get] reads it, so a reader recomposes on every write
 *   without an equality check over the rows;
 * - a failed page read leaves those keys unloaded (their rows stay placeholders); a failed live feed stops updating.
 */
internal class RowStore(private val core: CoreQueries, private val scope: Scope, private val coroutines: CoroutineScope) {
    private val table = HashMap<Long, DeviceRow>()
    private var state by mutableStateOf(RowState(), referentialEqualityPolicy())

    /** The row of [key] if loaded; reading [state] first subscribes the caller to row writes. */
    operator fun get(key: Long): DeviceRow? = state.let { table[key] }

    /** The number of row writes so far (tests). */
    internal val version: Long get() = state.version

    /** Loads the rows of [keys] that are not loaded yet. */
    fun ensure(keys: List<Long>) {
        for (effect in apply(RowEvent.Requested(keys))) if (effect is RowEffect.Load) load(effect.keys)
    }

    /** §2.6: applies live deltas to the loaded rows and appends new devices to the order. */
    suspend fun followLive(discover: DiscoverModel) {
        core.live.outcomes().collect { delta ->
            when (delta) {
                is Outcome.Ok -> for (effect in apply(RowEvent.Live(delta.value))) run(effect, discover)
                is Outcome.Failed -> Unit // the feed ended: the rows keep their last values
            }
        }
    }

    private fun apply(event: RowEvent): List<RowEffect> {
        val step = reduceRows(state, table, event)
        for (effect in step.effects) if (effect is RowEffect.Put) for (row in effect.rows) table[row.key] = row
        state = step.state
        return step.effects
    }

    private suspend fun run(effect: RowEffect, discover: DiscoverModel) {
        when (effect) {
            is RowEffect.Put -> Unit // written by [apply], with the state
            is RowEffect.AppendToOrder -> discover.appendLive(effect.keys)
            is RowEffect.Load -> load(effect.keys)
        }
    }

    private fun load(keys: List<Long>) {
        coroutines.launch {
            for (chunk in keys.chunked(PAGE)) {
                when (val page = attempt { core.deviceRows(scope, chunk.toLongArray()) }) {
                    is Outcome.Ok -> apply(RowEvent.ChunkLoaded(page.value))
                    is Outcome.Failed -> break // the keys stay unloaded; a later scroll asks again
                }
            }
            apply(RowEvent.LoadEnded(keys))
        }
    }

    companion object {
        const val PAGE = 200
    }
}
