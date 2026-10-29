package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.SearchState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.movedAway
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.viewportCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** The Discover sheet's views (D-2, D-3) and segments (D-1); §5.4 `SheetContentState.Discover`. */
enum class DiscoverView { Categories, List }
enum class Segment { Nearby, All, ThisArea }

/** D-4: the area and its own order (kept rows first, then new ones in session order). */
data class AreaState(val bbox: Bbox, val order: KeyOrder)

/** D-1: [Nearby | All] while recording, [This area | All] for history and All sessions. */
fun segmentsFor(live: Boolean): List<Segment> =
    if (live) listOf(Segment.Nearby, Segment.All) else listOf(Segment.ThisArea, Segment.All)

/** D-1: Nearby while recording, All otherwise (and after every session change, V-7). */
fun defaultSegment(live: Boolean): Segment = if (live) Segment.Nearby else Segment.All

/** The queries the Discover sheet makes, for one scope; a subset of `CoreQueries`. */
interface DiscoverQueries {
    suspend fun deviceOrder(): DeviceKeys
    suspend fun deviceKeys(filter: DeviceFilter): DeviceKeys
    suspend fun search(query: String, area: Bbox?): DeviceKeys?
}

fun CoreQueries.forScope(scope: Scope) = object : DiscoverQueries {
    override suspend fun deviceOrder() = this@forScope.deviceOrder(scope)
    override suspend fun deviceKeys(filter: DeviceFilter) = this@forScope.deviceKeys(scope, filter)
    override suspend fun search(query: String, area: Bbox?) = this@forScope.search(scope, query, area)
}

/** What search state saves of the root sheet and restores when it ends (Q-7). */
internal data class SearchReturn(val view: DiscoverView, val height: SheetHeight, val panel: PanelHeight, val scroll: ScrollSnapshot)

/**
 * Search state (Q-5) and what leaving it restores (Q-7): one value. [order] is the matches' own order (Q-6 keeps
 * present matches and appends new ones); [contract] is the :ui-contract view other lanes read.
 */
internal data class Searching(
    val query: String,
    val area: Bbox?,
    val reference: CameraSnapshot,
    val movedAway: Boolean,
    val order: KeyOrder,
    val savedFilter: DeviceFilter,
    val back: SearchReturn,
) {
    val contract: SearchState by lazy {
        SearchState(query, area, OrderedKeys(order.toLongArray()), reference, movedAway, savedFilter)
    }
}

/**
 * The Discover model's lifecycle. The first sort is loaded once; nothing that needs the order exists before it.
 * - [Loading]: live appends that arrive now are kept in [Loading.pending] and applied when the order is ready;
 *   [Loading.requested] is true once the first sort has been asked for.
 * - [Ready]: the order (G-9), sorted once and grown only by appends.
 * - [Failed]: the first sort could not be read; the sheet shows no rows and nothing appends.
 */
sealed interface DiscoverPhase {
    data class Loading(val pending: List<Long> = emptyList(), val requested: Boolean = false) : DiscoverPhase
    class Ready(val order: KeyOrder) : DiscoverPhase
    data class Failed(val message: String) : DiscoverPhase
}

/** What a rows query asks: the search matches (Q-5), or the devices passing the filter (D-3). */
internal sealed interface RowsQuery {
    data class Search(val query: String, val area: Bbox?) : RowsQuery
    data class Devices(val filter: DeviceFilter) : RowsQuery
}

/** The Discover sheet's whole state (G-9, D-1..D-4, Q-5..Q-7). [rowsVersion] numbers the rows queries. */
internal data class DiscoverState(
    val live: Boolean,
    val phase: DiscoverPhase = DiscoverPhase.Loading(),
    val view: DiscoverView = DiscoverView.Categories,
    val segment: Segment = defaultSegment(live),
    val filter: DeviceFilter = DeviceFilter(),
    val area: AreaState? = null,
    val searching: Searching? = null,
    val rows: LongArray = LongArray(0),
    val rowsVersion: Long = 0,
    val liveAppends: Int = 0,
    val viewport: Bbox? = null,
) {
    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = filter.copy(
        inRangeOnly = segment == Segment.Nearby,
        area = if (segment == Segment.ThisArea) area?.bbox else null,
    )
}

/** What happens to the Discover sheet; query results come back as events. */
internal sealed interface DiscoverEvent {
    data object OpenRequested : DiscoverEvent
    class OrderLoaded(val keys: LongArray) : DiscoverEvent
    data class OrderFailed(val message: String) : DiscoverEvent
    class LiveAppended(val keys: LongArray) : DiscoverEvent
    class SearchAppendLoaded(val hits: DeviceKeys) : DiscoverEvent
    data object ViewToggled : DiscoverEvent
    data class SegmentSelected(val next: Segment) : DiscoverEvent
    data class FilterSet(val filter: DeviceFilter) : DiscoverEvent
    data class CameraIdle(val byUser: Boolean, val viewport: Bbox, val zoom: Double) : DiscoverEvent
    class AreaLoaded(val bbox: Bbox, val inView: DeviceKeys) : DiscoverEvent
    class SearchLoaded(val query: String, val area: Bbox?, val reference: CameraSnapshot, val hits: DeviceKeys, val back: SearchReturn) : DiscoverEvent
    class SearchAreaLoaded(val viewport: Bbox, val zoom: Double, val hits: DeviceKeys) : DiscoverEvent
    class SearchEverywhereLoaded(val hits: DeviceKeys) : DiscoverEvent
    data object SearchExited : DiscoverEvent
    data object RefreshRequested : DiscoverEvent
    class RowsLoaded(val version: Long, val keys: DeviceKeys) : DiscoverEvent
}

/** What the runner does: core queries, and requests to the sheet stack. */
internal sealed interface DiscoverEffect {
    /** Requests the runner carries out at once. */
    sealed interface Immediate : DiscoverEffect

    /** Effects that suspend: core queries and the scroll restore. */
    sealed interface Async : DiscoverEffect

    data object LoadOrder : Async
    data class LoadArea(val bbox: Bbox) : Async
    data class LoadSearchAppend(val query: String, val area: Bbox?) : Async
    /** Re-read the rows: the runner sends [DiscoverEvent.RefreshRequested], which yields [LoadRows]. */
    data object Refresh : Async
    data class LoadRows(val version: Long, val query: RowsQuery) : Async
    /** Q-7: the root sheet's height, panel and scroll from before the search. */
    data class RestoreRoot(val back: SearchReturn) : Async
    /** S-12. */
    data object RaiseRoot : Immediate
    /** Q-5, Q-7: the stack goes back to the root. */
    data object ResetStack : Immediate
}

internal data class DiscoverStep(val state: DiscoverState, val effects: List<DiscoverEffect> = emptyList())

/** The pure Discover logic. */
internal fun reduceDiscover(state: DiscoverState, event: DiscoverEvent): DiscoverStep {
    val ready = state.phase as? DiscoverPhase.Ready
    return when (event) {
        DiscoverEvent.OpenRequested -> {
            val loading = state.phase as? DiscoverPhase.Loading
            if (loading == null || loading.requested) DiscoverStep(state)
            else DiscoverStep(state.copy(phase = loading.copy(requested = true)), listOf(DiscoverEffect.LoadOrder))
        }
        is DiscoverEvent.OrderLoaded -> {
            val loading = state.phase as? DiscoverPhase.Loading ?: return DiscoverStep(state)
            val order = KeyOrder.of(event.keys).append(loading.pending.toLongArray())
            val area = state.viewport?.takeIf { state.segment == Segment.ThisArea }
            DiscoverStep(
                state.copy(phase = DiscoverPhase.Ready(order)),
                listOfNotNull(area?.let(DiscoverEffect::LoadArea), DiscoverEffect.Refresh),
            )
        }
        is DiscoverEvent.OrderFailed ->
            if (state.phase is DiscoverPhase.Loading) DiscoverStep(state.copy(phase = DiscoverPhase.Failed(event.message)))
            else DiscoverStep(state)
        is DiscoverEvent.LiveAppended -> when (val p = state.phase) {
            is DiscoverPhase.Loading -> DiscoverStep(state.copy(phase = p.copy(pending = p.pending + event.keys.asList())))
            is DiscoverPhase.Failed -> DiscoverStep(state)
            is DiscoverPhase.Ready -> DiscoverStep(
                state.copy(phase = DiscoverPhase.Ready(p.order.append(event.keys)), liveAppends = state.liveAppends + 1),
                // Q-5: a new device that matches the search shows too (as on the map), after the present matches
                listOfNotNull(state.searching?.let { DiscoverEffect.LoadSearchAppend(it.query, it.area) }, DiscoverEffect.Refresh),
            )
        }
        is DiscoverEvent.SearchAppendLoaded -> {
            val s = state.searching
            if (s == null || ready == null) DiscoverStep(state)
            else DiscoverStep(state.copy(searching = s.copy(order = s.order.append(ready.order.visible(event.hits)))))
        }
        DiscoverEvent.ViewToggled -> DiscoverStep(
            state.copy(view = if (state.view == DiscoverView.Categories) DiscoverView.List else DiscoverView.Categories),
            listOf(DiscoverEffect.RaiseRoot), // D-1a keeps the height; S-12 raises a Collapsed root
        )
        is DiscoverEvent.SegmentSelected -> when {
            event.next !in segmentsFor(state.live) -> DiscoverStep(state) // D-1: not offered in this view
            event.next == state.segment -> DiscoverStep(state, listOf(DiscoverEffect.RaiseRoot))
            else -> {
                val area = state.viewport?.takeIf { event.next == Segment.ThisArea && ready != null }
                DiscoverStep(
                    state.copy(segment = event.next, area = if (event.next == Segment.ThisArea) state.area else null),
                    listOfNotNull(DiscoverEffect.RaiseRoot, area?.let(DiscoverEffect::LoadArea), DiscoverEffect.Refresh),
                )
            }
        }
        is DiscoverEvent.FilterSet -> DiscoverStep(state.copy(filter = event.filter), listOf(DiscoverEffect.Refresh))
        is DiscoverEvent.CameraIdle -> {
            val next = state.copy(viewport = event.viewport)
            val s = state.searching
            when {
                s != null -> {
                    val now = viewportCamera(event.viewport, event.zoom)
                    val moved = when {
                        !event.byUser && !s.movedAway -> s.copy(reference = now) // Q-6: app moves carry the area
                        event.byUser -> s.copy(movedAway = movedAway(s.reference, now, event.viewport))
                        else -> s
                    }
                    DiscoverStep(next.copy(searching = moved))
                }
                ready != null && event.byUser && state.segment == Segment.ThisArea ->
                    DiscoverStep(next, listOf(DiscoverEffect.LoadArea(event.viewport), DiscoverEffect.Refresh))
                else -> DiscoverStep(next)
            }
        }
        is DiscoverEvent.AreaLoaded -> {
            if (ready == null) DiscoverStep(state)
            else {
                val kept = state.area?.order?.visible(event.inView) ?: LongArray(0)
                DiscoverStep(state.copy(area = AreaState(event.bbox, KeyOrder.of(kept).append(ready.order.visible(event.inView)))))
            }
        }
        is DiscoverEvent.SearchLoaded -> {
            if (ready == null) DiscoverStep(state)
            else {
                val s = state.searching
                val searching = Searching(
                    query = event.query, area = event.area, reference = event.reference, movedAway = false,
                    order = KeyOrder.of(ready.order.visible(event.hits)),
                    savedFilter = s?.savedFilter ?: state.filter, back = s?.back ?: event.back,
                )
                DiscoverStep(
                    state.copy(searching = searching, view = DiscoverView.List),
                    listOf(DiscoverEffect.ResetStack, DiscoverEffect.Refresh),
                )
            }
        }
        is DiscoverEvent.SearchAreaLoaded -> {
            val s = state.searching
            if (s == null || ready == null) DiscoverStep(state)
            else {
                val order = KeyOrder.of(s.order.visible(event.hits)).append(ready.order.visible(event.hits))
                val next = s.copy(area = event.viewport, order = order, reference = viewportCamera(event.viewport, event.zoom), movedAway = false)
                DiscoverStep(state.copy(searching = next), listOf(DiscoverEffect.Refresh))
            }
        }
        is DiscoverEvent.SearchEverywhereLoaded -> {
            val s = state.searching
            if (s == null || ready == null) DiscoverStep(state)
            else DiscoverStep(
                state.copy(searching = s.copy(area = null, order = KeyOrder.of(ready.order.visible(event.hits)), movedAway = false)),
                listOf(DiscoverEffect.Refresh),
            )
        }
        DiscoverEvent.SearchExited -> {
            val s = state.searching ?: return DiscoverStep(state)
            // D-4: the camera may have moved during search; This area follows the current view again
            val area = state.viewport?.takeIf { ready != null && state.segment == Segment.ThisArea }
            DiscoverStep(
                state.copy(searching = null, filter = s.savedFilter, view = s.back.view),
                listOfNotNull(
                    DiscoverEffect.ResetStack, DiscoverEffect.RestoreRoot(s.back),
                    area?.let(DiscoverEffect::LoadArea), DiscoverEffect.Refresh,
                ),
            )
        }
        DiscoverEvent.RefreshRequested -> {
            if (ready == null) DiscoverStep(state)
            else {
                val version = state.rowsVersion + 1
                val s = state.searching
                val query = if (s != null) RowsQuery.Search(s.query, s.area) else RowsQuery.Devices(state.queryFilter())
                DiscoverStep(state.copy(rowsVersion = version), listOf(DiscoverEffect.LoadRows(version, query)))
            }
        }
        is DiscoverEvent.RowsLoaded -> {
            if (ready == null || event.version != state.rowsVersion) DiscoverStep(state) // a newer query is on its way
            else {
                val s = state.searching
                val order = s?.order ?: state.area?.takeIf { state.segment == Segment.ThisArea }?.order ?: ready.order
                DiscoverStep(state.copy(rows = order.visible(event.keys)))
            }
        }
    }
}

/**
 * The Discover sheet's list state for one session view (G-9, D-1, D-3, D-3a, D-4, Q-5..Q-7). A new session view
 * makes a new instance (V-7): segment back to its default, filters cleared, order sorted once.
 *
 * This is the runner: the logic is [reduceDiscover] over one immutable [DiscoverState]. Each call sends an event,
 * executes the effects (core queries, stack requests) and sends their results back as events, so every transition
 * acts on the current state. Live appends while the first sort loads are kept by [DiscoverPhase.Loading].
 */
class DiscoverModel(private val queries: DiscoverQueries, val live: Boolean, private val stack: SheetStackController) {
    private var state by mutableStateOf(DiscoverState(live))
    private val settled = CompletableDeferred<Unit>()

    val phase: DiscoverPhase get() = state.phase
    val view: DiscoverView get() = state.view
    val segment: Segment get() = state.segment
    val filter: DeviceFilter get() = state.filter
    val area: AreaState? get() = state.area

    /** Search state (Q-5..Q-7), null outside it. */
    val search: SearchState? get() = state.searching?.contract

    /** The rows shown now, in their fixed order. */
    val rows: LongArray get() = state.rows

    /** Counts live appends, so views over the order (a product's devices) re-query and append too (G-9). */
    val liveAppends: Int get() = state.liveAppends

    /** Loading → Ready (or Failed), once. Live appends kept while loading are applied to the new order. */
    suspend fun open() = send(DiscoverEvent.OpenRequested)

    /** The order's keys among [keys], in order; none before the order exists. */
    fun ordered(keys: DeviceKeys): LongArray = (state.phase as? DiscoverPhase.Ready)?.order?.visible(keys) ?: LongArray(0)

    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = state.queryFilter()

    /** G-9: new devices of the live view, in arrival order. */
    suspend fun appendLive(keys: LongArray) = send(DiscoverEvent.LiveAppended(keys))

    /** D-1a: a view switch keeps the height; S-12: from Collapsed it also raises the root to Half. */
    fun toggleView() = sendNow(DiscoverEvent.ViewToggled)

    /** D-1; a segment not offered in this view is ignored. */
    suspend fun selectSegment(next: Segment) = send(DiscoverEvent.SegmentSelected(next))

    /** D-3a: the Filter sheet applies changes live. */
    suspend fun setFilter(next: DeviceFilter) = send(DiscoverEvent.FilterSet(next))

    /** MapEvents.onCameraIdle. */
    suspend fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) = send(DiscoverEvent.CameraIdle(byUser, viewport, zoom))

    // ---- Search (Q-5..Q-7) ----

    /** Q-5: a picked result enters search state over [area] (null: everywhere); it waits for the first sort. */
    suspend fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot) {
        val q = query.trim()
        if (q.isEmpty()) return
        awaitReady() ?: return
        val back = stack.root.let { SearchReturn(state.view, it.height, it.panel, it.scrollReader?.invoke() ?: it.scroll) }
        val hits = queries.search(q, area) ?: NONE
        send(DiscoverEvent.SearchLoaded(q, area, reference, hits, back))
    }

    /** Q-6: "Search this area": matches still present keep their order, new ones are appended, others hide. */
    suspend fun searchThisArea(viewport: Bbox, zoom: Double) {
        val s = state.searching ?: return
        send(DiscoverEvent.SearchAreaLoaded(viewport, zoom, queries.search(s.query, viewport) ?: NONE))
    }

    /** Q-4: "Search everywhere" re-runs without a bbox. */
    suspend fun searchEverywhere() {
        val s = state.searching ?: return
        send(DiscoverEvent.SearchEverywhereLoaded(queries.search(s.query, null) ?: NONE))
    }

    /** Q-7: the bar's X, or Back with only the root left. Restores the view, filters, height and scroll. */
    suspend fun exitSearch() = send(DiscoverEvent.SearchExited)

    /** G-9: the keys passing [filter] in the session order (a product's devices, D-7.6); none if the sort failed. */
    suspend fun keysFor(filter: DeviceFilter): LongArray {
        val ready = awaitReady() ?: return LongArray(0)
        return ready.order.visible(queries.deviceKeys(filter))
    }

    /** G-14: pushed sheets first, search state last. */
    suspend fun handleBack(): Boolean {
        if (stack.handleBack()) return true
        if (state.searching == null) return false
        exitSearch()
        return true
    }

    /** "N results in this area" / "N results" (Q-5), as numbers; the words are :designsystem's (G-8). */
    val resultCount: Int get() = if (state.searching != null) rows.size else 0

    // ---- Runner ----

    /** The order once the phase has left Loading; null if the first sort failed. */
    private suspend fun awaitReady(): DiscoverPhase.Ready? {
        settled.await()
        return state.phase as? DiscoverPhase.Ready
    }

    /** Applies [event] and runs its effects to the end (queries included). */
    private suspend fun send(event: DiscoverEvent) {
        for (effect in apply(event)) run(effect)
    }

    /** For events whose effects are all [DiscoverEffect.Immediate] (the view switch). */
    private fun sendNow(event: DiscoverEvent) {
        for (effect in apply(event)) if (effect is DiscoverEffect.Immediate) runNow(effect)
    }

    private fun apply(event: DiscoverEvent): List<DiscoverEffect> {
        val step = reduceDiscover(state, event)
        state = step.state
        if (state.phase !is DiscoverPhase.Loading) settled.complete(Unit)
        return step.effects
    }

    private suspend fun run(effect: DiscoverEffect) {
        when (effect) {
            is DiscoverEffect.Immediate -> runNow(effect)
            DiscoverEffect.LoadOrder -> send(
                try {
                    DiscoverEvent.OrderLoaded(queries.deviceOrder().keys)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiscoverEvent.OrderFailed(e.message ?: e.toString())
                },
            )
            is DiscoverEffect.LoadArea -> send(DiscoverEvent.AreaLoaded(effect.bbox, queries.deviceKeys(DeviceFilter(area = effect.bbox))))
            is DiscoverEffect.LoadSearchAppend -> send(DiscoverEvent.SearchAppendLoaded(queries.search(effect.query, effect.area) ?: NONE))
            DiscoverEffect.Refresh -> send(DiscoverEvent.RefreshRequested)
            is DiscoverEffect.LoadRows -> send(
                DiscoverEvent.RowsLoaded(
                    effect.version,
                    when (val q = effect.query) {
                        is RowsQuery.Search -> queries.search(q.query, q.area) ?: NONE
                        is RowsQuery.Devices -> queries.deviceKeys(q.filter)
                    },
                ),
            )
            is DiscoverEffect.RestoreRoot -> {
                val root = stack.root
                // both saved states come back; the one of the current layout shows (T-2 panel, S-5 height)
                root.panel = effect.back.panel
                stack.setHeight(root, effect.back.height)
                // the main list moves now if it is composed, else starts there; the search list keeps its own key
                root.restoreScroll(SheetEntry.MAIN_LIST, effect.back.scroll)
            }
        }
    }

    private fun runNow(effect: DiscoverEffect.Immediate) {
        when (effect) {
            DiscoverEffect.RaiseRoot -> stack.onRootHeaderControl()
            DiscoverEffect.ResetStack -> stack.reset()
        }
    }

    private companion object {
        val NONE = DeviceKeys(LongArray(0), 0)
    }
}
