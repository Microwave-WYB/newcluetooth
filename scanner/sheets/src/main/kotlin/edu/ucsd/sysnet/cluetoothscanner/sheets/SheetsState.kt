package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapEvents
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
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
    internal val core: CoreQueries,
    private val gattRunner: GattRunner,
    private val map: MapRequests?,
    internal val coroutines: CoroutineScope,
    internal val nowMs: () -> Long,
    internal val metrics: SheetMetrics,
) {
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
    /** The first sort has landed (S-7 content can show); derived from the model's phase, not a flag. */
    internal val opened: Boolean get() = discover.phase is DiscoverPhase.Ready

    private val sheetEvents = SheetMapEvents(stack) { byUser, viewport, zoom ->
        coroutines.launch {
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
        coroutines.launch { discover.exitSearch() }
        return true
    }

    /** Q-5: a picked search result. The Discover sheet lists the matches; the picked device opens at Half. */
    fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot, picked: DeviceKey?) {
        coroutines.launch {
            discover.enterSearch(query, area, reference)
            followTop(stack.root, opening = false)
            if (picked != null) openDevice(picked, fromList = true)
        }
    }

    /** Q-7: the search bar's X. */
    fun exitSearch() {
        coroutines.launch {
            discover.exitSearch()
            followTop(stack.root, opening = false)
        }
    }

    /** Q-6: the "Search this area" chip. */
    fun searchThisArea(viewport: Bbox, zoom: Double) {
        coroutines.launch { discover.searchThisArea(viewport, zoom); followTop(stack.root, opening = false) }
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
        coroutines.launch {
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
        try {
            categoryLabels = core.categoryCounts(scope, DeviceFilter()).associate { it.category to it.label }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the Filter sheet and product titles fall back to the category ids
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
                if (opening) coroutines.launch { productFrame(filter)?.let(map::frame) } // D-8
            }
            is SheetKind.Device -> map.setSelection(kind.key) // M-6; D-14's ease is openDevice's (from a list only)
            SheetKind.Filter -> Unit // the map stays as it is
        }
    }

    /** D-8: the bounding box of a product's devices' strongest points. */
    private suspend fun productFrame(filter: DeviceFilter): Bbox? {
        val keys = core.deviceKeys(scope, filter).keys
        val points = keys.toList().chunked(RowStore.PAGE).flatMap { chunk -> core.deviceRows(scope, chunk.toLongArray()).mapNotNull { it.best } }
        return boundsOf(points)
    }
}

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

/** The loaded device rows, which keys are loading, and the last live delta applied (§2.6). */
internal data class RowCache(
    val rows: Map<Long, DeviceRow> = emptyMap(),
    val loading: Set<Long> = emptySet(),
    val seq: Long = -1,
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
}

internal data class RowStep(val cache: RowCache, val effects: List<RowEffect> = emptyList())

/** The pure row-cache logic: what to load, and how a live delta changes the loaded rows in place. */
internal fun reduceRows(cache: RowCache, event: RowEvent): RowStep = when (event) {
    is RowEvent.Requested -> {
        val missing = event.keys.filter { it !in cache.rows && it !in cache.loading }
        if (missing.isEmpty()) RowStep(cache) else RowStep(cache.copy(loading = cache.loading + missing), listOf(RowEffect.Load(missing)))
    }
    is RowEvent.ChunkLoaded -> RowStep(cache.copy(rows = cache.rows + event.rows.associateBy { it.key }))
    is RowEvent.LoadEnded -> RowStep(cache.copy(loading = cache.loading - event.keys.toSet()))
    is RowEvent.Live -> {
        val delta = event.delta
        if (delta.seq <= cache.seq) RowStep(cache)
        else {
            val changed = delta.changed.mapNotNull { c ->
                cache.rows[c.key]?.let { row ->
                    c.key to row.copy(
                        rssi = c.rssi, inRange = c.inRange, spark = c.spark,
                        name = c.name ?: row.name, page = c.page ?: row.page, best = c.best ?: row.best,
                    )
                }
            }
            val rows = cache.rows + changed + delta.appended.associateBy { it.key }
            RowStep(
                cache.copy(rows = rows, seq = delta.seq),
                if (delta.appended.isEmpty()) emptyList() else listOf(RowEffect.AppendToOrder(delta.appended.map { it.key }.toLongArray())),
            )
        }
    }
}

/**
 * Device rows by key, loaded on demand in pages of ≤ 200 (§2.5) and kept current by the live deltas (§2.6): values
 * update in place, new devices are appended to the session order (G-9). The runner of [reduceRows]: a failed page
 * read leaves those keys unloaded (their rows stay placeholders) instead of throwing.
 */
internal class RowStore(private val core: CoreQueries, private val scope: Scope, private val coroutines: CoroutineScope) {
    private var cache by mutableStateOf(RowCache())

    val rows: Map<Long, DeviceRow> get() = cache.rows

    /** Loads the rows of [keys] that are not loaded yet. */
    fun ensure(keys: List<Long>) {
        val effects = apply(RowEvent.Requested(keys))
        for (effect in effects) if (effect is RowEffect.Load) load(effect.keys)
    }

    /** §2.6: applies live deltas to the loaded rows and appends new devices to the order. */
    suspend fun followLive(discover: DiscoverModel) {
        core.live.collect { delta ->
            for (effect in apply(RowEvent.Live(delta))) {
                when (effect) {
                    is RowEffect.AppendToOrder -> discover.appendLive(effect.keys)
                    is RowEffect.Load -> load(effect.keys)
                }
            }
        }
    }

    private fun apply(event: RowEvent): List<RowEffect> {
        val step = reduceRows(cache, event)
        cache = step.cache
        return step.effects
    }

    private fun load(keys: List<Long>) {
        coroutines.launch {
            try {
                for (chunk in keys.chunked(PAGE)) apply(RowEvent.ChunkLoaded(core.deviceRows(scope, chunk.toLongArray())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the keys stay unloaded; a later scroll asks again
            } finally {
                apply(RowEvent.LoadEnded(keys))
            }
        }
    }

    companion object {
        const val PAGE = 200
    }
}
