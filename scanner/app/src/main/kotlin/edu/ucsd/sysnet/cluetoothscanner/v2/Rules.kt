package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** §5.4 / G-2: one layout mode from the window width (Material window size classes). */
enum class LayoutMode {
    Compact, Medium, Expanded;

    /** Medium and expanded are the tablet layout (section T). */
    val tablet: Boolean get() = this != Compact

    companion object {
        fun of(widthDp: Float): LayoutMode = when {
            widthDp < Tokens.bpMedium.value -> Compact
            widthDp < Tokens.bpExpanded.value -> Medium
            else -> Expanded
        }
    }
}

/** §5.4: the full-screen pages over the main screen. */
sealed interface Page {
    data object Settings : Page
    data object SearchPage : Page
}

/** G-14: what System Back closes, topmost first; [Leave] lets the system finish the activity. */
enum class BackTarget { LocationPopover, Page, PushedSheet, Sidebar, RecorderPanel, SearchState, Leave }

/** The layers open right now, as the back dispatcher sees them. */
data class BackLayers(
    val locationPopover: Boolean = false,
    val page: Boolean = false,
    val pushedSheet: Boolean = false,
    val sidebar: Boolean = false,
    val recorderPanel: Boolean = false,
    val searchState: Boolean = false,
)

/** G-14: popover, then a page, the top pushed sheet, the sidebar, the recorder panel, search state; else leave. */
fun backTarget(l: BackLayers): BackTarget = when {
    l.locationPopover -> BackTarget.LocationPopover
    l.page -> BackTarget.Page
    l.pushedSheet -> BackTarget.PushedSheet
    l.sidebar -> BackTarget.Sidebar
    l.recorderPanel -> BackTarget.RecorderPanel
    l.searchState -> BackTarget.SearchState
    else -> BackTarget.Leave
}

/** The bounding box of points (R-5: fit the main map to the route), or null without any. */
fun bboxOf(points: List<GeoPoint>): Bbox? = if (points.isEmpty()) null else Bbox(
    west = points.minOf { it.lon }, south = points.minOf { it.lat }, east = points.maxOf { it.lon }, north = points.maxOf { it.lat },
)
