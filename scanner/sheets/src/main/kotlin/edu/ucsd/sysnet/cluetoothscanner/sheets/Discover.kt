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

/** The Discover sheet's views (D-2, D-3) and segments (D-1); §5.4 `SheetContentState.Discover`. */
internal enum class DiscoverView { Categories, List }
internal enum class Segment { Nearby, All, ThisArea }

/** D-4: the area and its own order (kept rows first, then new ones in session order). */
internal data class AreaState(val bbox: Bbox, val order: OrderedKeys)

/** D-1: [Nearby | All] while recording, [This area | All] for history and All sessions. */
internal fun segmentsFor(live: Boolean): List<Segment> =
    if (live) listOf(Segment.Nearby, Segment.All) else listOf(Segment.ThisArea, Segment.All)

/** D-1: Nearby while recording, All otherwise (and after every session change, V-7). */
internal fun defaultSegment(live: Boolean): Segment = if (live) Segment.Nearby else Segment.All

/** The queries the Discover sheet makes, for one scope; a subset of `CoreQueries`. */
internal interface DiscoverQueries {
    suspend fun deviceOrder(): DeviceKeys
    suspend fun deviceKeys(filter: DeviceFilter): DeviceKeys
    suspend fun search(query: String, area: Bbox?): DeviceKeys?
}

internal fun CoreQueries.forScope(scope: Scope) = object : DiscoverQueries {
    override suspend fun deviceOrder() = this@forScope.deviceOrder(scope)
    override suspend fun deviceKeys(filter: DeviceFilter) = this@forScope.deviceKeys(scope, filter)
    override suspend fun search(query: String, area: Bbox?) = this@forScope.search(scope, query, area)
}

/** What search state saves of the root sheet and restores when it ends (Q-7). */
internal data class SearchReturn(val view: DiscoverView, val height: SheetHeight, val panel: PanelHeight, val scroll: ScrollSnapshot)

/**
 * The Discover sheet's list state for one session view (G-9, D-1, D-3, D-3a, D-4, Q-5..Q-7). A new session view
 * makes a new instance (V-7): segment back to its default, filters cleared, order sorted once.
 *
 * - G-9: [order] is built once from `deviceOrder`; live appends only append; filters, Nearby, the area and search
 *   only hide ([OrderedKeys.visible]).
 * - D-4: This area re-filters only when the map comes to rest after the user's own move. Rows still in view keep
 *   their order, rows that leave hide, new ones are appended in session order.
 * - Q-5: search state shows only the matches in their session order, ignoring the filters (kept in
 *   [SearchState.savedFilter]); Q-6 re-runs on the viewport with the same order rule; Q-7 restores the view, the
 *   filters and the root sheet's height and scroll.
 */
internal class DiscoverModel(private val queries: DiscoverQueries, val live: Boolean, private val stack: SheetStackController) {
    var view by mutableStateOf(DiscoverView.Categories)
        private set
    var segment by mutableStateOf(defaultSegment(live))
        private set
    var filter by mutableStateOf(DeviceFilter())
        private set
    var area by mutableStateOf<AreaState?>(null)
        private set
    var search by mutableStateOf<SearchState?>(null)
        private set

    /** The rows shown now, in their fixed order. */
    var rows by mutableStateOf(LongArray(0))
        private set

    lateinit var order: OrderedKeys
        private set

    private var viewport: Bbox? = null
    private var searchReturn: SearchReturn? = null

    suspend fun open() {
        order = OrderedKeys(queries.deviceOrder().keys)
        refresh()
    }

    /** The filter the core queries use: the sheet's filters plus Nearby and This area. */
    fun queryFilter(): DeviceFilter = filter.copy(
        inRangeOnly = segment == Segment.Nearby,
        area = if (segment == Segment.ThisArea) area?.bbox else null,
    )

    /** G-9: new devices of the live view, in arrival order. */
    suspend fun appendLive(keys: LongArray) {
        order.append(keys)
        // Q-5: a new device that matches the search shows too (as on the map), appended after the present matches.
        search?.let { s -> s.order.append(order.visible(queries.search(s.query, s.area) ?: DeviceKeys(LongArray(0), 0))) }
        refresh()
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
        if (next == Segment.ThisArea) viewport?.let { applyArea(it) } else area = null
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
        val s = search
        if (s != null) {
            val now = viewportCamera(viewport, zoom)
            search = when {
                !byUser && !s.movedAway -> s.copy(reference = now) // Q-6: app moves carry the searched area along
                byUser -> s.copy(movedAway = movedAway(s.reference, now, viewport))
                else -> s
            }
            return
        }
        if (byUser && segment == Segment.ThisArea) {
            applyArea(viewport)
            refresh()
        }
    }

    // ---- Search (Q-5..Q-7) ----

    /** Q-5: a picked result enters search state over [area] (null: everywhere). */
    suspend fun enterSearch(query: String, area: Bbox?, reference: CameraSnapshot) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (search == null) {
            val root = stack.root
            searchReturn = SearchReturn(view, root.height, root.panel, root.scrollReader?.invoke() ?: root.scroll)
        }
        stack.reset()
        val hits = queries.search(q, area) ?: DeviceKeys(LongArray(0), 0)
        search = SearchState(
            query = q, area = area, order = OrderedKeys(order.visible(hits)), reference = reference,
            movedAway = false, savedFilter = search?.savedFilter ?: filter,
        )
        view = DiscoverView.List
        refresh()
    }

    /** Q-6: "Search this area": matches still present keep their order, new ones are appended, others hide. */
    suspend fun searchThisArea(viewport: Bbox, zoom: Double) {
        val s = search ?: return
        val hits = queries.search(s.query, viewport) ?: DeviceKeys(LongArray(0), 0)
        val next = OrderedKeys(s.order.visible(hits)).apply { append(order.visible(hits)) }
        search = s.copy(area = viewport, order = next, reference = viewportCamera(viewport, zoom), movedAway = false)
        refresh()
    }

    /** Q-4: "Search everywhere" re-runs without a bbox. */
    suspend fun searchEverywhere() {
        val s = search ?: return
        val hits = queries.search(s.query, null) ?: DeviceKeys(LongArray(0), 0)
        search = s.copy(area = null, order = OrderedKeys(order.visible(hits)), movedAway = false)
        refresh()
    }

    /** Q-7: the bar's X, or Back with only the root left. Restores the view, filters, height and scroll. */
    suspend fun exitSearch() {
        val s = search ?: return
        val back = searchReturn
        search = null
        searchReturn = null
        filter = s.savedFilter
        stack.reset()
        if (back != null) {
            view = back.view
            val root = stack.root
            // Both saved states come back; the one of the current layout shows (T-2 panel, S-5 height).
            root.panel = back.panel
            stack.setHeight(root, back.height)
            // The main list moves now if it is composed, else starts there; the search list keeps its own key.
            root.restoreScroll(SheetEntry.MAIN_LIST, back.scroll)
        }
        // D-4: the camera may have moved during search; This area follows the current view again.
        if (segment == Segment.ThisArea) viewport?.let { applyArea(it) }
        refresh()
    }

    /** G-14: pushed sheets first, search state last. */
    suspend fun handleBack(): Boolean {
        if (stack.handleBack()) return true
        if (search != null) {
            exitSearch()
            return true
        }
        return false
    }

    /** "N results in this area" / "N results" (Q-5), as numbers; the words are :designsystem's (G-8). */
    val resultCount: Int get() = if (search != null) rows.size else 0

    private suspend fun applyArea(bbox: Bbox) {
        val inView = queries.deviceKeys(DeviceFilter(area = bbox))
        val kept = area?.order?.visible(inView) ?: LongArray(0)
        area = AreaState(bbox, OrderedKeys(kept).apply { append(order.visible(inView)) })
    }

    private suspend fun refresh() {
        val s = search
        rows = if (s != null) {
            s.order.visible(queries.search(s.query, s.area) ?: DeviceKeys(LongArray(0), 0))
        } else {
            (area?.takeIf { segment == Segment.ThisArea }?.order ?: order).visible(queries.deviceKeys(queryFilter()))
        }
    }
}
