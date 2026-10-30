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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** What the sheets tell :app. */
sealed interface SheetsEvent {
    /** D-1b: the user switched the Discover view; :app remembers it (UI state, not a setting). */
    data class ViewChosen(val view: DiscoverView) : SheetsEvent
}

/** The Discover sheet's views (D-2, D-3) and segments (D-1); §5.4 `SheetContentState.Discover`. */
enum class DiscoverView { Categories, List }
enum class Segment { Nearby, All, ThisArea }

/** D-4: the area and its own order (kept rows first, then new ones in session order). */
data class AreaState(val bbox: Bbox, val order: KeyOrder)

/** D-3a item 1, the scope: [Nearby | All] while recording, [This area | All] for history and All sessions. */
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
 * - [Failed]: the first sort could not be read (or a failure escaped a runner); the sheet shows no rows, nothing
 *   appends, and Try again goes back to [Loading].
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

/** The selected segment (D-1). Only This area has an area (D-4). */
internal sealed interface SegmentState {
    val segment: Segment

    data object Nearby : SegmentState {
        override val segment: Segment get() = Segment.Nearby
    }

    data object All : SegmentState {
        override val segment: Segment get() = Segment.All
    }

    /** D-4: the loaded area (none before the first answer), and the number of the area query it waits for. */
    data class ThisArea(val area: AreaState? = null, val asked: Long = 0) : SegmentState {
        override val segment: Segment get() = Segment.ThisArea
    }

    companion object {
        fun of(segment: Segment): SegmentState = when (segment) {
            Segment.Nearby -> Nearby
            Segment.All -> All
            Segment.ThisArea -> ThisArea()
        }
    }
}

/** What a search query is for (Q-4..Q-6): its answer is applied by purpose. */
internal sealed interface SearchPurpose {
    /** Q-5: a picked result enters search state. */
    data class Enter(val reference: CameraSnapshot, val back: SearchReturn) : SearchPurpose
    /** Q-6: "Search this area". */
    data class Area(val zoom: Double) : SearchPurpose
    /** Q-4: "Search everywhere". */
    data object Everywhere : SearchPurpose
    /** Q-5, G-9: new live devices that match are appended. */
    data object Append : SearchPurpose
}

/**
 * Whether the session view is the live recording (V-2). :app reads the view after the sheets exist, so it starts
 * [Pending]; a [Live] view ends at Stop (R-7); a [Frozen] one never turns live (a new recording is a new scope).
 */
sealed interface Liveness {
    /** The view has not been read yet: the frozen segments, no chart; the session's own appends are kept. */
    data object Pending : Liveness
    data object Live : Liveness
    data object Frozen : Liveness

    companion object {
        fun of(live: Boolean?): Liveness = when (live) {
            null -> Pending
            true -> Live
            false -> Frozen
        }
    }
}

/**
 * The Discover sheet's whole state (G-9, D-1..D-4, Q-5..Q-7). The versions number the queries: an answer is applied
 * only if it answers the newest query of its kind, so a stale answer never wins.
 */
internal data class DiscoverState(
    val liveness: Liveness,
    val phase: DiscoverPhase = DiscoverPhase.Loading(),
    val view: DiscoverView = DiscoverView.Categories,
    val segment: SegmentState = SegmentState.of(defaultSegment(liveness == Liveness.Live)),
    val filter: DeviceFilter = DeviceFilter(),
    val searching: Searching? = null,
    val rows: LongArray = LongArray(0),
    val rowsVersion: Long = 0,
    val areaVersion: Long = 0,
    val searchVersion: Long = 0,
    /** D-1c: the number of the newest re-sort asked for. */
    val resortVersion: Long = 0,
    /** D-1c: re-sorts applied so far (the list crossfades on each). */
    val resorts: Int = 0,
    val liveAppends: Int = 0,
    val viewport: Bbox? = null,
) {
    /** The view is the live recording now (Nearby, the chart). */
    val live: Boolean get() = liveness == Liveness.Live

    /** D-4: the area, only in This area. */
    val area: AreaState? get() = (segment as? SegmentState.ThisArea)?.area

    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = filter.copy(inRangeOnly = segment == SegmentState.Nearby, area = area?.bbox)

    /** D-1: the filter button's badge: the Filter sheet's filters, and a scope other than the default as one more. */
    val activeFilters: Int get() = filter.activeFilterCount + if (segment.segment != defaultSegment(live)) 1 else 0
}

/** What happens to the Discover sheet; query results come back as events. */
internal sealed interface DiscoverEvent {
    data object OpenRequested : DiscoverEvent
    class OrderLoaded(val keys: LongArray) : DiscoverEvent
    data class OrderFailed(val message: String) : DiscoverEvent
    /** D-13.1: Try again after a failure. */
    data object RetryRequested : DiscoverEvent
    /** A failure escaped a runner (the scope's handler): the sheet fails, and can be retried. */
    data class Faulted(val message: String) : DiscoverEvent
    class LiveAppended(val keys: LongArray) : DiscoverEvent
    /** :app read the view: live or not. Live → frozen is R-7's Stop; only the live-only parts change. */
    data class LiveRead(val live: Boolean) : DiscoverEvent
    /** D-1: a tap on the view tabs [List | Categories] (D-1a crossfade, D-1b remembered). */
    data class ViewSelected(val view: DiscoverView) : DiscoverEvent
    /** D-1c: the re-sort button. */
    data object ResortRequested : DiscoverEvent
    /** D-1c: the core's order now (live RSSI while recording, the strongest seen otherwise), for re-sort [version]. */
    class Resorted(val version: Long, val keys: LongArray) : DiscoverEvent
    /** D-1b: the remembered view, from :app (a new session view, or the store read after a restart). */
    data class ViewRestored(val view: DiscoverView) : DiscoverEvent
    /** D-3a item 1: the scope, chosen in the Filter sheet. */
    data class SegmentSelected(val next: Segment) : DiscoverEvent
    data class FilterSet(val filter: DeviceFilter) : DiscoverEvent
    /** D-3a Clear: the sheet's filters and the scope back to their defaults. */
    data object FiltersCleared : DiscoverEvent
    data class CameraIdle(val byUser: Boolean, val viewport: Bbox, val zoom: Double) : DiscoverEvent
    class AreaLoaded(val version: Long, val bbox: Bbox, val inView: DeviceKeys) : DiscoverEvent
    data class AreaFailed(val version: Long) : DiscoverEvent
    data class SearchRequested(val query: String, val area: Bbox?, val reference: CameraSnapshot, val back: SearchReturn) : DiscoverEvent
    data class SearchAreaRequested(val viewport: Bbox, val zoom: Double) : DiscoverEvent
    data object SearchEverywhereRequested : DiscoverEvent
    /** The answer to [DiscoverEffect.LoadSearch] number [version]. */
    class SearchLoaded(val version: Long, val query: String, val area: Bbox?, val purpose: SearchPurpose, val hits: DeviceKeys, val appendsAsked: Int) :
        DiscoverEvent
    data object SearchExited : DiscoverEvent
    data object RefreshRequested : DiscoverEvent
    class RowsLoaded(val version: Long, val keys: DeviceKeys) : DiscoverEvent
    /** A rows or search query failed: what is shown stays, the next change asks again. */
    data class QueryFailed(val message: String) : DiscoverEvent
}

/** What the runner does: core queries, and requests to the sheet stack. */
internal sealed interface DiscoverEffect {
    /** Requests the runner carries out at once. */
    sealed interface Immediate : DiscoverEffect

    /** Effects that suspend: core queries and the scroll restore. */
    sealed interface Async : DiscoverEffect

    data object LoadOrder : Async
    data class LoadArea(val version: Long, val bbox: Bbox) : Async
    /** [appendsAsked]: the live appends seen when asked; appends after it are searched again when it lands. */
    data class LoadSearch(val version: Long, val query: String, val area: Bbox?, val purpose: SearchPurpose, val appendsAsked: Int) : Async
    /** Re-read the rows: the runner sends [DiscoverEvent.RefreshRequested], which yields [LoadRows]. */
    data object Refresh : Async
    data class LoadRows(val version: Long, val query: RowsQuery) : Async
    /** Q-7: the root sheet's height, panel and scroll from before the search. */
    data class RestoreRoot(val back: SearchReturn) : Async
    /** D-1c: read the order the core would sort now, for re-sort [version]. */
    data class LoadResort(val version: Long) : Async
    /** D-1c: the Discover lists start at the top. */
    data object ScrollToTop : Async
    /** S-12. */
    data object RaiseRoot : Immediate
    /** Q-5, Q-7: the stack goes back to the root. */
    data object ResetStack : Immediate
    /** D-1b: the user chose [view]; :app remembers it. */
    data class ViewChosen(val view: DiscoverView) : Immediate
}

internal data class DiscoverStep(val state: DiscoverState, val effects: List<DiscoverEffect> = emptyList())

/** D-4: asks for the area of [viewport] in This area (the rows follow its answer); otherwise just the rows. */
private fun DiscoverState.askArea(viewport: Bbox?, vararg first: DiscoverEffect): DiscoverStep {
    val seg = segment as? SegmentState.ThisArea
    if (seg == null || viewport == null || phase !is DiscoverPhase.Ready) return DiscoverStep(this, first.toList() + DiscoverEffect.Refresh)
    val version = areaVersion + 1
    return DiscoverStep(copy(segment = seg.copy(asked = version), areaVersion = version), first.toList() + DiscoverEffect.LoadArea(version, viewport))
}

/** Asks a search query; a new one makes every earlier answer stale. */
private fun DiscoverState.askSearch(query: String, area: Bbox?, purpose: SearchPurpose): DiscoverStep {
    val version = searchVersion + 1
    return DiscoverStep(copy(searchVersion = version), listOf(DiscoverEffect.LoadSearch(version, query, area, purpose, liveAppends)))
}

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
            state.copy(phase = DiscoverPhase.Ready(order)).askArea(state.viewport)
        }
        is DiscoverEvent.OrderFailed ->
            if (state.phase is DiscoverPhase.Loading) DiscoverStep(state.copy(phase = DiscoverPhase.Failed(event.message)))
            else DiscoverStep(state)
        DiscoverEvent.RetryRequested ->
            if (state.phase is DiscoverPhase.Failed) DiscoverStep(state.copy(phase = DiscoverPhase.Loading(requested = true)), listOf(DiscoverEffect.LoadOrder))
            else DiscoverStep(state)
        is DiscoverEvent.Faulted -> DiscoverStep(
            // every pending answer goes stale; the segment keeps its choice but not its area
            state.copy(
                phase = DiscoverPhase.Failed(event.message), searching = null, rows = LongArray(0),
                segment = SegmentState.of(state.segment.segment),
                rowsVersion = state.rowsVersion + 1, searchVersion = state.searchVersion + 1,
            ),
        )
        // R-7: a frozen session's list stops appending (and its order is never touched again); before the view is
        // read the session's own appends are kept (they come only while it records)
        is DiscoverEvent.LiveAppended -> if (state.liveness == Liveness.Frozen) DiscoverStep(state) else when (val p = state.phase) {
            is DiscoverPhase.Loading -> DiscoverStep(state.copy(phase = p.copy(pending = p.pending + event.keys.asList())))
            is DiscoverPhase.Failed -> DiscoverStep(state)
            is DiscoverPhase.Ready -> {
                val next = state.copy(phase = DiscoverPhase.Ready(p.order.append(event.keys)), liveAppends = state.liveAppends + 1)
                val s = state.searching
                // Q-5: a new device that matches the search shows too (as on the map), after the present matches
                if (s == null) DiscoverStep(next, listOf(DiscoverEffect.Refresh))
                else DiscoverStep(
                    next,
                    listOf(DiscoverEffect.LoadSearch(state.searchVersion, s.query, s.area, SearchPurpose.Append, next.liveAppends), DiscoverEffect.Refresh),
                )
            }
        }
        is DiscoverEvent.LiveRead -> liveRead(state, event.live)
        // D-1a keeps the height; S-12: a tap on the tabs also raises a Collapsed root, the current tab included;
        // D-1b: the user's choice is remembered. Search shows only its list (Q-5), with no tabs: nothing changes
        is DiscoverEvent.ViewSelected -> when {
            state.searching != null -> DiscoverStep(state)
            event.view == state.view -> DiscoverStep(state, listOf(DiscoverEffect.RaiseRoot))
            else -> DiscoverStep(state.copy(view = event.view), listOf(DiscoverEffect.RaiseRoot, DiscoverEffect.ViewChosen(event.view)))
        }
        // D-1c: the explicit user action G-9 allows: a new order from the current values; S-12: it also raises a
        // Collapsed root. Not in search (hidden there)
        DiscoverEvent.ResortRequested -> when {
            state.searching != null -> DiscoverStep(state)
            ready == null -> DiscoverStep(state, listOf(DiscoverEffect.RaiseRoot)) // nothing to re-sort yet
            else -> (state.resortVersion + 1).let { v ->
                DiscoverStep(state.copy(resortVersion = v), listOf(DiscoverEffect.RaiseRoot, DiscoverEffect.LoadResort(v)))
            }
        }
        is DiscoverEvent.Resorted ->
            if (ready == null || state.searching != null || event.version != state.resortVersion) DiscoverStep(state)
            else resorted(state, ready, event.keys)
        // D-1b: during search it is the view search returns to (Q-7), never the forced list; nothing is remembered
        is DiscoverEvent.ViewRestored -> {
            val s = state.searching
            when {
                s != null -> if (s.back.view == event.view) DiscoverStep(state) else DiscoverStep(state.copy(searching = s.copy(back = s.back.copy(view = event.view))))
                state.view == event.view -> DiscoverStep(state)
                else -> DiscoverStep(state.copy(view = event.view))
            }
        }
        // D-3a item 1, in the Filter sheet (no longer a header control: no S-12 raise); search has no scope (Q-5)
        is DiscoverEvent.SegmentSelected -> when {
            state.searching != null -> DiscoverStep(state)
            event.next !in segmentsFor(state.live) -> DiscoverStep(state) // not offered in this view
            event.next == state.segment.segment -> DiscoverStep(state)
            else -> state.copy(segment = SegmentState.of(event.next)).askArea(state.viewport)
        }
        is DiscoverEvent.FilterSet -> DiscoverStep(state.copy(filter = event.filter), listOf(DiscoverEffect.Refresh))
        DiscoverEvent.FiltersCleared -> {
            val cleared = state.copy(filter = state.filter.clearSheetFilters())
            val scope = defaultSegment(state.live)
            when {
                state.searching != null -> DiscoverStep(state)
                state.segment.segment == scope -> DiscoverStep(cleared, listOf(DiscoverEffect.Refresh))
                else -> cleared.copy(segment = SegmentState.of(scope)).askArea(state.viewport)
            }
        }
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
                event.byUser && state.segment is SegmentState.ThisArea && ready != null -> next.askArea(event.viewport)
                else -> DiscoverStep(next)
            }
        }
        is DiscoverEvent.AreaLoaded -> {
            val seg = state.segment as? SegmentState.ThisArea
            if (ready == null || seg == null || event.version != seg.asked) DiscoverStep(state) // stale: a newer area is asked
            else {
                val kept = seg.area?.order?.visible(event.inView) ?: LongArray(0)
                val area = AreaState(event.bbox, KeyOrder.of(kept).append(ready.order.visible(event.inView)))
                DiscoverStep(state.copy(segment = seg.copy(area = area)), listOf(DiscoverEffect.Refresh))
            }
        }
        is DiscoverEvent.AreaFailed -> {
            val seg = state.segment as? SegmentState.ThisArea
            // the newest area failed: the rows follow the area as it was
            if (seg == null || event.version != seg.asked) DiscoverStep(state) else DiscoverStep(state, listOf(DiscoverEffect.Refresh))
        }
        is DiscoverEvent.SearchRequested ->
            if (ready == null) DiscoverStep(state)
            else state.askSearch(event.query, event.area, SearchPurpose.Enter(event.reference, event.back))
        is DiscoverEvent.SearchAreaRequested -> {
            val s = state.searching
            if (s == null) DiscoverStep(state) else state.askSearch(s.query, event.viewport, SearchPurpose.Area(event.zoom))
        }
        DiscoverEvent.SearchEverywhereRequested -> {
            val s = state.searching
            if (s == null) DiscoverStep(state) else state.askSearch(s.query, null, SearchPurpose.Everywhere)
        }
        is DiscoverEvent.SearchLoaded -> {
            if (ready == null || event.version != state.searchVersion) DiscoverStep(state) // stale: a newer search is asked
            else searchAnswered(state, ready, event)
        }
        DiscoverEvent.SearchExited -> {
            val s = state.searching ?: return DiscoverStep(state)
            // D-4: the camera may have moved during search; This area follows the current view again
            state.copy(searching = null, filter = s.savedFilter, view = s.back.view, searchVersion = state.searchVersion + 1)
                .askArea(state.viewport, DiscoverEffect.ResetStack, DiscoverEffect.RestoreRoot(s.back))
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
                val order = state.searching?.order ?: state.area?.order ?: ready.order
                DiscoverStep(state.copy(rows = order.visible(event.keys)))
            }
        }
        is DiscoverEvent.QueryFailed -> DiscoverStep(state)
    }
}

/**
 * :app's read of the view. Pending → Live: Nearby, the live default (D-1). Pending → Frozen: nothing else changes.
 * Live → Frozen is R-7's Stop: everything stays as it was (order, rows, view, filters, area, search, and nothing for
 * the stack) except what only exists while recording: Nearby becomes All (D-1), its rows re-read. A frozen view never
 * turns live, and a repeated read changes nothing.
 */
private fun liveRead(state: DiscoverState, live: Boolean): DiscoverStep = when (state.liveness) {
    Liveness.Pending ->
        if (live) DiscoverStep(state.copy(liveness = Liveness.Live, segment = SegmentState.Nearby), listOf(DiscoverEffect.Refresh))
        else DiscoverStep(state.copy(liveness = Liveness.Frozen))
    Liveness.Live -> when {
        live -> DiscoverStep(state)
        state.segment == SegmentState.Nearby ->
            DiscoverStep(state.copy(liveness = Liveness.Frozen, segment = SegmentState.All), listOf(DiscoverEffect.Refresh))
        else -> DiscoverStep(state.copy(liveness = Liveness.Frozen))
    }
    Liveness.Frozen -> DiscoverStep(state)
}

/**
 * D-1c: the new order is the core's answer, then any session key it lacks (in the old order); nothing is lost. This
 * area keeps its area and its keys, now in the new order; filters and the rows' own state stay. The lists go to the
 * top, and the rows are re-read in the new order.
 */
private fun resorted(state: DiscoverState, ready: DiscoverPhase.Ready, keys: LongArray): DiscoverStep {
    val order = KeyOrder.of(keys).append(ready.order.toLongArray())
    val seg = state.segment
    val segment = if (seg is SegmentState.ThisArea && seg.area != null) {
        seg.copy(area = seg.area.copy(order = KeyOrder.of(order.visible(DeviceKeys(seg.area.order.toLongArray(), 0)))))
    } else seg
    return DiscoverStep(
        state.copy(phase = DiscoverPhase.Ready(order), segment = segment, resorts = state.resorts + 1),
        listOf(DiscoverEffect.ScrollToTop, DiscoverEffect.Refresh),
    )
}

/** Q-4..Q-6: the newest search answer. Appends that came in while it was asked are searched again. */
private fun searchAnswered(state: DiscoverState, ready: DiscoverPhase.Ready, event: DiscoverEvent.SearchLoaded): DiscoverStep {
    val s = state.searching
    val inOrder = ready.order.visible(event.hits)
    val next = when (val purpose = event.purpose) {
        is SearchPurpose.Enter -> Searching(
            query = event.query, area = event.area, reference = purpose.reference, movedAway = false,
            order = KeyOrder.of(inOrder), savedFilter = s?.savedFilter ?: state.filter, back = s?.back ?: purpose.back,
        )
        is SearchPurpose.Area -> s?.copy(
            area = event.area, order = KeyOrder.of(s.order.visible(event.hits)).append(inOrder),
            reference = event.area?.let { viewportCamera(it, purpose.zoom) } ?: s.reference, movedAway = false,
        )
        SearchPurpose.Everywhere -> s?.copy(area = null, order = KeyOrder.of(inOrder), movedAway = false)
        SearchPurpose.Append -> s?.copy(order = s.order.append(inOrder))
    } ?: return DiscoverStep(state)
    val stale = state.liveAppends != event.appendsAsked && event.purpose != SearchPurpose.Append
    val again = DiscoverEffect.LoadSearch(state.searchVersion, next.query, next.area, SearchPurpose.Append, state.liveAppends).takeIf { stale }
    val enter = event.purpose is SearchPurpose.Enter
    return DiscoverStep(
        state.copy(searching = next, view = if (enter) DiscoverView.List else state.view),
        listOfNotNull(DiscoverEffect.ResetStack.takeIf { enter }, again, DiscoverEffect.Refresh),
    )
}

/**
 * The Discover sheet's list state for one session view (G-9, D-1, D-3, D-3a, D-4, Q-5..Q-7). A new session view
 * makes a new instance (V-7): segment back to its default, filters cleared, order sorted once.
 *
 * This is the runner: the logic is [reduceDiscover] over one immutable [DiscoverState]. Each call sends an event,
 * executes the effects (core queries, stack requests) and sends their results back as events, so every transition
 * acts on the current state. Every query goes through [attempt]: a failure comes back as a typed event, never a
 * throw. Live appends while the first sort loads are kept by [DiscoverPhase.Loading].
 */
class DiscoverModel(
    private val queries: DiscoverQueries,
    live: Boolean?,
    private val stack: SheetStackController,
    /** D-1b: the remembered view this session view opens on. */
    view: DiscoverView = DiscoverView.Categories,
    /** What the sheets tell :app (D-1b: the user's view switch). */
    private val onEvent: (SheetsEvent) -> Unit = {},
) {
    private var state by mutableStateOf(DiscoverState(Liveness.of(live), view = view))

    /** The view is the live recording now (V-2): not before the view is read, never after Stop (R-7). */
    val live: Boolean get() = state.live

    /** Pending until :app has read the view; then live, or frozen (from the start, or after Stop). */
    val liveness: Liveness get() = state.liveness
    /** The phase, for [awaitReady] (a state flow, so a retry is awaited too). */
    private val phases = MutableStateFlow<DiscoverPhase>(state.phase)

    val phase: DiscoverPhase get() = state.phase
    val view: DiscoverView get() = state.view
    val segment: Segment get() = state.segment.segment
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

    /** D-13.1: Failed → Loading, and the first sort is read again. */
    suspend fun retry() = send(DiscoverEvent.RetryRequested)

    /** A failure escaped a runner: the sheet shows Failed, with Try again. */
    internal fun fault(message: String) = sendNow(DiscoverEvent.Faulted(message))

    /** The order's keys among [keys], in order; none before the order exists. */
    fun ordered(keys: DeviceKeys): LongArray = (state.phase as? DiscoverPhase.Ready)?.order?.visible(keys) ?: LongArray(0)

    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = state.queryFilter()

    /** :app read the view; live → frozen is R-7's Stop (the session stays shown, frozen). */
    suspend fun liveRead(live: Boolean) = send(DiscoverEvent.LiveRead(live))

    /** G-9: new devices of the live view, in arrival order. */
    suspend fun appendLive(keys: LongArray) = send(DiscoverEvent.LiveAppended(keys))

    /** D-1: the view tabs; D-1a keeps the height; S-12: from Collapsed it also raises the root to Half. */
    fun selectView(view: DiscoverView) = sendNow(DiscoverEvent.ViewSelected(view))

    /** D-1c: re-sort by the current values (not in search). */
    suspend fun resort() = send(DiscoverEvent.ResortRequested)

    /** D-1c: re-sorts applied so far (the list crossfades on each). */
    val resorts: Int get() = state.resorts

    /** D-1b: :app's remembered view (a store read that lands after the sheets exist); never re-remembered. */
    fun restoreView(view: DiscoverView) = sendNow(DiscoverEvent.ViewRestored(view))

    /** D-3a item 1; a segment not offered in this view is ignored. */
    suspend fun selectSegment(next: Segment) = send(DiscoverEvent.SegmentSelected(next))

    /** D-3a: the Filter sheet applies changes live. */
    suspend fun setFilter(next: DeviceFilter) = send(DiscoverEvent.FilterSet(next))

    /** D-3a Clear: the sheet's filters and the scope. */
    suspend fun clearFilters() = send(DiscoverEvent.FiltersCleared)

    /** D-1: the filter button's badge (a non-default scope counts as one). */
    val activeFilters: Int get() = state.activeFilters

    /** MapEvents.onCameraIdle. */
    suspend fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) = send(DiscoverEvent.CameraIdle(byUser, viewport, zoom))

    // ---- Search (Q-5..Q-7) ----

    /** Q-5: a picked result enters search state over [area] (null: everywhere); it waits for the first sort. */
    suspend fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot) {
        val q = query.trim()
        if (q.isEmpty()) return
        awaitReady() ?: return
        val back = stack.root.let { SearchReturn(state.view, it.height, it.panel, it.scrollReader?.invoke() ?: it.scroll) }
        send(DiscoverEvent.SearchRequested(q, area, reference, back))
    }

    /** Q-6: "Search this area": matches still present keep their order, new ones are appended, others hide. */
    suspend fun searchThisArea(viewport: Bbox, zoom: Double) = send(DiscoverEvent.SearchAreaRequested(viewport, zoom))

    /** Q-4: "Search everywhere" re-runs without a bbox. */
    suspend fun searchEverywhere() = send(DiscoverEvent.SearchEverywhereRequested)

    /** Q-7: the bar's X, or Back with only the root left. Restores the view, filters, height and scroll. */
    suspend fun exitSearch() = send(DiscoverEvent.SearchExited)

    /** G-9: the keys passing [filter] in the session order (a product's devices, D-7.6); none if a read failed. */
    suspend fun keysFor(filter: DeviceFilter): LongArray {
        awaitReady() ?: return LongArray(0)
        return when (val keys = attempt { queries.deviceKeys(filter) }) {
            is Outcome.Ok -> ordered(keys.value)
            is Outcome.Failed -> LongArray(0)
        }
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
    private suspend fun awaitReady(): DiscoverPhase.Ready? =
        phases.first { it !is DiscoverPhase.Loading } as? DiscoverPhase.Ready

    /** Applies [event] and runs its effects to the end (queries included). */
    private suspend fun send(event: DiscoverEvent) {
        for (effect in apply(event)) run(effect)
    }

    /** For events whose effects are all [DiscoverEffect.Immediate] (the view switch, a fault). */
    private fun sendNow(event: DiscoverEvent) {
        for (effect in apply(event)) if (effect is DiscoverEffect.Immediate) runNow(effect)
    }

    private fun apply(event: DiscoverEvent): List<DiscoverEffect> {
        val step = reduceDiscover(state, event)
        state = step.state
        phases.value = state.phase
        return step.effects
    }

    /** Runs a core query through [attempt] and sends its answer, or its failure, as an event. */
    private suspend fun <T> ask(query: suspend () -> T, failed: (String) -> DiscoverEvent, answered: (T) -> DiscoverEvent) =
        send(
            when (val outcome = attempt(query)) {
                is Outcome.Ok -> answered(outcome.value)
                is Outcome.Failed -> failed(outcome.message)
            },
        )

    private suspend fun run(effect: DiscoverEffect) {
        when (effect) {
            is DiscoverEffect.Immediate -> runNow(effect)
            DiscoverEffect.LoadOrder -> ask({ queries.deviceOrder() }, DiscoverEvent::OrderFailed) { DiscoverEvent.OrderLoaded(it.keys) }
            is DiscoverEffect.LoadArea -> ask(
                { queries.deviceKeys(DeviceFilter(area = effect.bbox)) },
                { DiscoverEvent.AreaFailed(effect.version) },
            ) { DiscoverEvent.AreaLoaded(effect.version, effect.bbox, it) }
            is DiscoverEffect.LoadSearch -> ask({ queries.search(effect.query, effect.area) ?: NONE }, DiscoverEvent::QueryFailed) {
                DiscoverEvent.SearchLoaded(effect.version, effect.query, effect.area, effect.purpose, it, effect.appendsAsked)
            }
            DiscoverEffect.Refresh -> send(DiscoverEvent.RefreshRequested)
            is DiscoverEffect.LoadRows -> ask(
                {
                    when (val q = effect.query) {
                        is RowsQuery.Search -> queries.search(q.query, q.area) ?: NONE
                        is RowsQuery.Devices -> queries.deviceKeys(q.filter)
                    }
                },
                DiscoverEvent::QueryFailed,
            ) { DiscoverEvent.RowsLoaded(effect.version, it) }
            is DiscoverEffect.LoadResort ->
                ask({ queries.deviceOrder() }, DiscoverEvent::QueryFailed) { DiscoverEvent.Resorted(effect.version, it.keys) }
            DiscoverEffect.ScrollToTop -> {
                stack.root.restoreScroll(SheetEntry.MAIN_LIST, ScrollSnapshot())
                stack.root.restoreScroll(CATEGORIES_LIST, ScrollSnapshot())
            }
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
            is DiscoverEffect.ViewChosen -> onEvent(SheetsEvent.ViewChosen(effect.view))
        }
    }

    private companion object {
        val NONE = DeviceKeys(LongArray(0), 0)
    }
}
