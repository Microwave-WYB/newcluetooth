package edu.ucsd.sysnet.cluetoothscanner.chrome.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.chrome.DayGroup
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.LocationPopover
import edu.ucsd.sysnet.cluetoothscanner.chrome.MenuButton
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderActions
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderGeometry
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderLayer
import edu.ucsd.sysnet.cluetoothscanner.chrome.RecorderUi
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchBar
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchBox
import edu.ucsd.sysnet.cluetoothscanner.chrome.SearchThisAreaChip
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsList
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.chrome.rememberRecorderGroupWidth
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.FloatButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.GalleryCell
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import java.util.Calendar
import java.util.TimeZone

/*
 * The :chrome part of the component gallery. It mirrors the prototype's #gallery cells for the chrome (Search,
 * MenuButton, Location warning, Recorder, Sidebar) with the same sections, labels, cell widths and data.js samples,
 * so designsystem/tools/prototype-shots.mjs crops can be compared file by file. Widths copy the gallery's inline
 * styles (review layout, not design values).
 */

private val utc: TimeZone = TimeZone.getTimeZone("UTC")
private fun at(h: Int, m: Int) = Calendar.getInstance(utc).apply { clear(); set(2026, 9, 28, h, m) }.timeInMillis
private val noRecorder = RecorderActions({}, {}, {}, {}, {}, {})

/** A recorder form as the prototype's static gallery cell draws it: `.recorder.static`, the non-island variant. */
@Composable
private fun StaticRecorder(
    form: RecorderUi.Form,
    paused: Boolean,
    boxWidth: Dp,
    discovery: DiscoveryBins = DiscoverySamples.rising,
    elapsedMs: Long = DiscoverySamples.ELAPSED_MS,
) {
    val ui = RecorderUi(form, paused, elapsedMs, discovery)
    val groupW = rememberRecorderGroupWidth(ui.elapsedMs)
    val g0 = RecorderGeometry.compute(form, true, boxWidth, Tokens.fab, groupW)
    val w = if (form == RecorderUi.Form.Panel) boxWidth - Tokens.edge * 2 else g0.width
    val g = g0.copy(x = 0.dp, y = 0.dp, width = w, groupX = if (form == RecorderUi.Form.Panel) (w - groupW) / 2 else g0.groupX)
    Box(Modifier.width(g.width).height(g.height)) { RecorderLayer(ui, g, noRecorder, catchOutside = false) }
}

private val recorderSection = "Recorder (main map only: idle FAB bottom-right, recording = top-center island)"

/** Every :chrome gallery cell, in the prototype's order. */
@Composable
fun chromeCells(): List<GalleryCell> {
    val c = CluetoothTheme.colors
    val noop = {}
    val cells = mutableListOf<GalleryCell>()
    cells += GalleryCell("Search", "closed", 80.dp) { SearchBox(false, "", noop, noop, {}, 44.dp) }
    cells += GalleryCell("Search", "open", 388.dp) { SearchBox(true, "", noop, noop, {}, 364.dp) }
    cells += GalleryCell("Search", "search bar (search state)", 388.dp) { SearchBar("govee", noop, noop, Modifier.fillMaxWidth()) }
    cells += GalleryCell("Search", "Search this area") { SearchThisAreaChip(noop) }
    cells += GalleryCell("Search", "no results in this area", 300.dp) {
        EmptyText(Strings.NoResultsInArea) { TextButton(Strings.SearchEverywhere, noop) }
    }
    cells += GalleryCell("MenuButton", "closed") { MenuButton(false, false, noop) }
    cells += GalleryCell("MenuButton", "open") { MenuButton(true, false, noop) }
    listOf(
        "permission off" to LocationStatus(LocationStatus.State.Denied, true, action = LocationStatus.Action.Allow),
        "location turned off" to LocationStatus(LocationStatus.State.Off, true, action = LocationStatus.Action.TurnOn),
        "stale fix (waiting reads \"Waiting for location…\")" to LocationStatus(LocationStatus.State.Stale, true, ageMin = 5),
    ).forEach { (label, status) ->
        cells += GalleryCell("Location warning", label, 260.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocationPopover(status, {})
                FloatButton(Glyphs.locateOff, Strings.LocationProblem, noop, tint = c.warn)
            }
        }
    }
    cells += GalleryCell(recorderSection, "idle", 120.dp) { StaticRecorder(RecorderUi.Form.Fab, false, 96.dp) }
    cells += GalleryCell(recorderSection, "capsule", 240.dp) { StaticRecorder(RecorderUi.Form.Capsule, false, 216.dp) }
    cells += GalleryCell(recorderSection, "capsule, paused", 280.dp) { StaticRecorder(RecorderUi.Form.Capsule, true, 256.dp) }
    cells += GalleryCell(recorderSection, "panel", 412.dp) { StaticRecorder(RecorderUi.Form.Panel, false, 388.dp) }
    // R-4a: the discovery-rate chart's other cases (the prototype has no cells for them)
    cells += GalleryCell(recorderSection, "panel, just started", 412.dp) { StaticRecorder(RecorderUi.Form.Panel, false, 388.dp, DiscoverySamples.empty, DiscoverySamples.EMPTY_ELAPSED_MS) }
    cells += GalleryCell(recorderSection, "panel, standing still", 412.dp) { StaticRecorder(RecorderUi.Form.Panel, false, 388.dp, DiscoverySamples.stationary) }
    cells += GalleryCell(recorderSection, "panel, after a pause", 412.dp) { StaticRecorder(RecorderUi.Form.Panel, false, 388.dp, DiscoverySamples.pauseGap) }
    val day = DayGroup(
        "Today · Oct 28",
        listOf(
            SessionSummary("a", SessionStatus.Completed, at(18, 5), at(18, 28), 23 * 60_000L, 1800.0, 214, UploadState.Uploaded),
            SessionSummary("b", SessionStatus.Completed, at(12, 10), at(12, 18), 8 * 60_000L, 0.0, 41, UploadState.Uploaded),
        ),
    )
    val none = AllSessionsSummary(0, 0, null)
    val current = SessionSummary("c", SessionStatus.Recording, at(19, 12), null, 12 * 60_000L, 640.0, 26, UploadState.Pending)
    cells += GalleryCell("Sidebar", "recording, current session selected", 338.dp, padded = false) {
        Box(Modifier.height(420.dp)) { SessionsList(SessionsListState(current, none, listOf(day), Scope.session("c")), {}, {}, zone = utc) }
    }
    cells += GalleryCell("Sidebar", "not recording, history session selected", 338.dp, padded = false) {
        Box(Modifier.height(420.dp)) { SessionsList(SessionsListState(null, none, listOf(day), Scope.session("a")), {}, {}, zone = utc) }
    }
    return cells
}
