package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** §5.4: the full-screen pages over the main screen; each carries only its own state. */
sealed interface Page {
    data object Settings : Page

    /** Q-3 / Q-4: the query, and the area it searches (the viewport when the page opened, null once everywhere). */
    data class SearchPage(val query: String, val area: Bbox?, val searchedEverywhere: Boolean) : Page
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

/** V-6: what the app opens on launch. */
sealed interface LaunchTarget {
    /** A recording survived (process restart) or the view restores to it. */
    data class Session(val id: String) : LaunchTarget
    data object StartRecording : LaunchTarget
    data object AllSessions : LaunchTarget
    data object FirstRun : LaunchTarget
}

/** V-6: a live recording first; else "Record when the app opens"; else All sessions if any exist; else first run. */
fun launchTarget(liveSessionId: String?, recordOnOpen: Boolean, sessionCount: Int): LaunchTarget = when {
    liveSessionId != null -> LaunchTarget.Session(liveSessionId)
    recordOnOpen -> LaunchTarget.StartRecording
    sessionCount > 0 -> LaunchTarget.AllSessions
    else -> LaunchTarget.FirstRun
}

/** The bounding box of points (R-5: fit the main map to the route), or null without any. */
fun bboxOf(points: List<GeoPoint>): Bbox? = if (points.isEmpty()) null else Bbox(
    west = points.minOf { it.lon }, south = points.minOf { it.lat }, east = points.maxOf { it.lon }, north = points.maxOf { it.lat },
)

/**
 * P-3 / P-5: what the FAB layer (the FAB, locate and the warning) sits above on phone: the root sheet's peek plus the
 * navigation bar, or the navigation bar alone without a sheet. The tablet's layers are already inside the safe area.
 */
fun fabLayerCover(tablet: Boolean, sheetPeek: Dp?, navigationBar: Dp): Dp = when {
    tablet -> 0.dp
    sheetPeek != null -> sheetPeek + navigationBar
    else -> navigationBar
}

/** R-3 / R-4 / R-9: the phone island and panel sit on the ☰/search row, which starts below the status bar. */
fun recorderTopInset(tablet: Boolean, statusBar: Dp): Dp = if (tablet) 0.dp else statusBar
