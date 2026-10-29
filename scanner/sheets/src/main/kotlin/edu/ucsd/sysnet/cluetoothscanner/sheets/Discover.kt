package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Discover sheet's views (D-2, D-3) and segments (D-1); §5.4 `SheetContentState.Discover`. */
enum class DiscoverView { Categories, List }
enum class Segment { Nearby, All, ThisArea }

/** D-4: the area and its own order (kept rows first, then new ones in session order). */
data class AreaState(val bbox: Bbox, val order: OrderedKeys)

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

/** Search state (Q-5) together with what leaving it restores (Q-7): one value, never one without the other. */
internal data class Searching(val state: SearchState, val back: SearchReturn)

/**
 * The Discover model's lifecycle. The first sort is loaded once; nothing that needs the order exists before it.
 * - [Loading]: live appends that arrive now are kept in [Loading.pending] and applied when the order is ready.
 * - [Ready]: the order (G-9), sorted once and grown only by appends.
 * - [Failed]: the first sort could not be read; the sheet shows no rows and nothing appends.
 */
sealed interface DiscoverPhase {
    data class Loading(val pending: List<Long> = emptyList()) : DiscoverPhase
    class Ready(val order: OrderedKeys) : DiscoverPhase
    data class Failed(val message: String) : DiscoverPhase
}

/**
 * The Discover sheet's list state for one session view (G-9, D-1, D-3, D-3a, D-4, Q-5..Q-7). A new session view
 * makes a new instance (V-7): segment back to its default, filters cleared, order sorted once.
 *
 * - G-9: the order is built once from `deviceOrder` ([DiscoverPhase.Ready]); live appends only append; filters,
 *   Nearby, the area and search only hide ([OrderedKeys.visible]).
 * - D-4: This area re-filters only when the map comes to rest after the user's own move. Rows still in view keep
 *   their order, rows that leave hide, new ones are appended in session order.
 * - Q-5: search state shows only the matches in their session order, ignoring the filters (kept in
 *   [SearchState.savedFilter]); Q-6 re-runs on the viewport with the same order rule; Q-7 restores the view, the
 *   filters and the root sheet's height and scroll.
 *
 * Every entry point acts on [phase]: the view's own choices (view, segment, filter) apply in any phase; anything
 * that needs the order waits for, or ignores, the phases without one.
 */
class DiscoverModel(private val queries: DiscoverQueries, val live: Boolean, private val stack: SheetStackController) {
    var phase by mutableStateOf<DiscoverPhase>(DiscoverPhase.Loading())
        private set
    var view by mutableStateOf(DiscoverView.Categories)
        private set
    var segment by mutableStateOf(defaultSegment(live))
        private set
    var filter by mutableStateOf(DeviceFilter())
        private set
    var area by mutableStateOf<AreaState?>(null)
        private set
    private var searching by mutableStateOf<Searching?>(null)

    /** Search state (Q-5..Q-7), null outside it. */
    val search: SearchState? get() = searching?.state

    /** The rows shown now, in their fixed order. */
    var rows by mutableStateOf(LongArray(0))
        private set

    /** Counts live appends, so views over the order (a product's devices) re-query and append too (G-9). */
    var liveAppends by mutableIntStateOf(0)
        private set

    private var viewport: Bbox? = null
    private val opening = Mutex()
    private val settled = CompletableDeferred<Unit>()

    /** Loading → Ready (or Failed), once. Live appends kept while loading are applied to the new order. */
    suspend fun open() {
        opening.withLock {
            if (phase !is DiscoverPhase.Loading) return
            val next = try {
                val sorted = OrderedKeys(queries.deviceOrder().keys)
                val pending = (phase as DiscoverPhase.Loading).pending // appends that arrived during the query
                DiscoverPhase.Ready(sorted.apply { append(pending.toLongArray()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiscoverPhase.Failed(e.message ?: e.toString())
            }
            phase = next
            settled.complete(Unit)
            if (next is DiscoverPhase.Ready) {
                if (segment == Segment.ThisArea) viewport?.let { applyArea(next, it) }
                refresh()
            }
        }
    }

    /** The order's keys among [keys], in order; none before the order exists. */
    fun ordered(keys: DeviceKeys): LongArray = (phase as? DiscoverPhase.Ready)?.order?.visible(keys) ?: LongArray(0)

    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = filter.copy(
        inRangeOnly = segment == Segment.Nearby,
        area = if (segment == Segment.ThisArea) area?.bbox else null,
    )

    /** G-9: new devices of the live view, in arrival order. */
    suspend fun appendLive(keys: LongArray) {
        when (val p = phase) {
            is DiscoverPhase.Loading -> phase = p.copy(pending = p.pending + keys.asList()) // applied when Ready
            is DiscoverPhase.Failed -> Unit
            is DiscoverPhase.Ready -> {
                p.order.append(keys)
                liveAppends++
                // Q-5: a new device that matches the search shows too (as on the map), after the present matches
                searching?.state?.let { s -> s.order.append(p.order.visible(queries.search(s.query, s.area) ?: NONE)) }
                refresh()
            }
        }
    }

    /** D-1a: a view switch keeps the height; S-12: from Collapsed it also raises the root to Half. */
    fun toggleView() {
        view = if (view == DiscoverView.Categories) DiscoverView.List else DiscoverView.Categories
        stack.onRootHeaderControl()
    }

    suspend fun selectSegment(next: Segment) {
        require(next in segmentsFor(live)) { "$next is not offered here" }
        stack.onRootHeaderControl()
        if (next == segment) return
        segment = next
        val ready = phase as? DiscoverPhase.Ready
        if (next != Segment.ThisArea) area = null
        else if (ready != null) viewport?.let { applyArea(ready, it) }
        refresh()
    }

    /** D-3a: the Filter sheet applies changes live. */
    suspend fun setFilter(next: DeviceFilter) {
        filter = next
        refresh()
    }

    /** MapEvents.onCameraIdle. */
    suspend fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double) {
        this.viewport = viewport
        val s = searching
        if (s != null) {
            val now = viewportCamera(viewport, zoom)
            val st = s.state
            searching = when {
                !byUser && !st.movedAway -> s.copy(state = st.copy(reference = now)) // Q-6: app moves carry the area
                byUser -> s.copy(state = st.copy(movedAway = movedAway(st.reference, now, viewport)))
                else -> s
            }
            return
        }
        val ready = phase as? DiscoverPhase.Ready ?: return
        if (byUser && segment == Segment.ThisArea) {
            applyArea(ready, viewport)
            refresh()
        }
    }

    // ---- Search (Q-5..Q-7) ----

    /** Q-5: a picked result enters search state over [area] (null: everywhere); it waits for the first sort. */
    suspend fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot) {
        val q = query.trim()
        if (q.isEmpty()) return
        val ready = awaitReady() ?: return
        val back = searching?.back ?: stack.root.let { SearchReturn(view, it.height, it.panel, it.scrollReader?.invoke() ?: it.scroll) }
        stack.reset()
        val hits = queries.search(q, area) ?: NONE
        searching = Searching(
            SearchState(
                query = q, area = area, order = OrderedKeys(ready.order.visible(hits)), reference = reference,
                movedAway = false, savedFilter = searching?.state?.savedFilter ?: filter,
            ),
            back,
        )
        view = DiscoverView.List
        refresh()
    }

    /** Q-6: "Search this area": matches still present keep their order, new ones are appended, others hide. */
    suspend fun searchThisArea(viewport: Bbox, zoom: Double) {
        val s = searching ?: return
        val ready = phase as? DiscoverPhase.Ready ?: return
        val hits = queries.search(s.state.query, viewport) ?: NONE
        val next = OrderedKeys(s.state.order.visible(hits)).apply { append(ready.order.visible(hits)) }
        searching = s.copy(state = s.state.copy(area = viewport, order = next, reference = viewportCamera(viewport, zoom), movedAway = false))
        refresh()
    }

    /** Q-4: "Search everywhere" re-runs without a bbox. */
    suspend fun searchEverywhere() {
        val s = searching ?: return
        val ready = phase as? DiscoverPhase.Ready ?: return
        val hits = queries.search(s.state.query, null) ?: NONE
        searching = s.copy(state = s.state.copy(area = null, order = OrderedKeys(ready.order.visible(hits)), movedAway = false))
        refresh()
    }

    /** Q-7: the bar's X, or Back with only the root left. Restores the view, filters, height and scroll. */
    suspend fun exitSearch() {
        val (s, back) = searching ?: return
        searching = null
        filter = s.savedFilter
        stack.reset()
        view = back.view
        val root = stack.root
        // Both saved states come back; the one of the current layout shows (T-2 panel, S-5 height).
        root.panel = back.panel
        stack.setHeight(root, back.height)
        // The main list moves now if it is composed, else starts there; the search list keeps its own key.
        root.restoreScroll(SheetEntry.MAIN_LIST, back.scroll)
        // D-4: the camera may have moved during search; This area follows the current view again.
        val ready = phase as? DiscoverPhase.Ready
        if (ready != null && segment == Segment.ThisArea) viewport?.let { applyArea(ready, it) }
        refresh()
    }

    /** G-9: the keys passing [filter] in the session order (a product's devices, D-7.6); none if the sort failed. */
    suspend fun keysFor(filter: DeviceFilter): LongArray {
        val ready = awaitReady() ?: return LongArray(0)
        return ready.order.visible(queries.deviceKeys(filter))
    }

    /** G-14: pushed sheets first, search state last. */
    suspend fun handleBack(): Boolean {
        if (stack.handleBack()) return true
        if (searching != null) {
            exitSearch()
            return true
        }
        return false
    }

    /** "N results in this area" / "N results" (Q-5), as numbers; the words are :designsystem's (G-8). */
    val resultCount: Int get() = if (searching != null) rows.size else 0

    /** The order once the phase has left Loading; null if the first sort failed. */
    private suspend fun awaitReady(): DiscoverPhase.Ready? {
        settled.await()
        return phase as? DiscoverPhase.Ready
    }

    private suspend fun applyArea(ready: DiscoverPhase.Ready, bbox: Bbox) {
        val inView = queries.deviceKeys(DeviceFilter(area = bbox))
        val kept = area?.order?.visible(inView) ?: LongArray(0)
        area = AreaState(bbox, OrderedKeys(kept).apply { append(ready.order.visible(inView)) })
    }

    private suspend fun refresh() {
        val ready = phase as? DiscoverPhase.Ready ?: return
        val s = searching?.state
        rows = if (s != null) {
            s.order.visible(queries.search(s.query, s.area) ?: NONE)
        } else {
            (area?.takeIf { segment == Segment.ThisArea }?.order ?: ready.order).visible(queries.deviceKeys(queryFilter()))
        }
    }

    private companion object {
        val NONE = DeviceKeys(LongArray(0), 0)
    }
}
