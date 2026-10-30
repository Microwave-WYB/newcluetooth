package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.focusRing
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.SpecValues
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Chip
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Icon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SearchField
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.plainClickable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.CssEnv
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyph
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys

/** docs/v2-api.md §5.4: the search page (Q-3, Q-4); [results] null while the query is blank. */
data class SearchPageState(val query: String, val area: Bbox?, val results: OrderedKeys?, val searchedEverywhere: Boolean)

/** The icon morph of the menu and search buttons: `rotate(±90deg | ±180deg) scale(0.6)` with a fade (`--t-std`). */
private const val MorphScale = 0.6f

/** One glyph layer of a morphing button: shown, or turned [angle] degrees and shrunk while hidden. */
@Composable
private fun MorphLayer(glyph: Glyph, shown: Boolean, angle: Float, size: Dp, durationStd: Boolean = true) {
    val m = CluetoothTheme.motion
    val spec = if (durationStd) m.std<Float>() else m.fade()
    val a by animateFloatAsState(if (shown) 1f else 0f, spec, label = "morphA")
    val r by animateFloatAsState(if (shown) 0f else angle, spec, label = "morphR")
    val s by animateFloatAsState(if (shown) 1f else MorphScale, spec, label = "morphS")
    if (a > 0f) Icon(glyph, null, Modifier.alpha(a).rotate(r).scale(s), size = size, tint = CluetoothTheme.colors.t1)
}

/**
 * L-1 / T-6: ☰. Closed it is the menu glyph; open it turns into "collapse sidebar" (phone) or an X (tablet, the
 * sessions list in the panel). A 44 dp FloatButton (C-1).
 */
@Composable
fun MenuButton(open: Boolean, tablet: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Tokens.rb)
            .tokenShadow(CluetoothTheme.shadows.shadowFloat, CircleShape)
            .clip(CircleShape)
            .background(c.fill1)
            .plainClickable(source, label = if (open) (if (tablet) Strings.CloseSessions else Strings.CloseSidebar) else Strings.SessionsAndSettings, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MorphLayer(Glyphs.menu, !open, MenuTurn, Tokens.rbIcon)
        if (tablet) MorphLayer(Glyphs.close, open, -MenuTurn, Tokens.rbIcon)
        else MorphLayer(Glyphs.panelClose, open, -MenuTurn, Tokens.rbIcon)
    }
}

private const val MenuTurn = 90f
private const val SearchTurn = 180f

/** What the top chrome shows (P-2, L-1, Q-3 … Q-7, R-4, V-6). :app builds it from its state. */
data class TopChromeState(
    /** V-6 first run: no session yet, so no search button. */
    val hasSession: Boolean,
    val sidebarOpen: Boolean = false,
    /** R-4: the open recorder panel covers the row; ☰ and search fade out. */
    val recorderPanelOpen: Boolean = false,
    /** Q-6: while recording, the chip sits below the island. */
    val recording: Boolean = false,
    /** The search page is open (Q-3) with this state. */
    val page: SearchPageState? = null,
    /** Search state (Q-5): the bar's query, null outside search state. */
    val searchQuery: String? = null,
    /** Q-6: the user moved the map away from the searched area. */
    val showSearchThisArea: Boolean = false,
)

class TopChromeActions(
    val onMenu: () -> Unit,
    val onSearchOpen: () -> Unit,
    /** Q-3: Back or the arrow closes the page (back to search state when it was opened from the bar, Q-7). */
    val onSearchBack: () -> Unit,
    val onQueryChange: (String) -> Unit,
    /** Q-5: picking a result enters search state. */
    val onPick: (DeviceKey) -> Unit,
    /** Q-4: re-runs the query with no bbox. */
    val onSearchEverywhere: () -> Unit,
    /** Q-7: the bar's text reopens the page. */
    val onBarEdit: () -> Unit,
    /** Q-7: the bar's X leaves search state. */
    val onBarExit: () -> Unit,
    /** Q-6. */
    val onSearchThisArea: () -> Unit,
)

/**
 * The phone's top chrome, drawn above the sheets and the sidebar: ☰ (riding the sidebar's edge, L-1), the search
 * button that expands in place into the field while the search page fades in under it (Q-3), the results or the
 * Q-4 empty text, search state's bar (Q-5) and the "Search this area" chip (Q-6).
 *
 * @param rows the result rows of [TopChromeState.page] in its fixed order (G-9), resolved by the caller
 * @param live sparklines in the rows (a live session)
 */
@Composable
fun PhoneTopChrome(
    state: TopChromeState,
    actions: TopChromeActions,
    rows: List<DeviceRow>,
    live: Boolean,
    modifier: Modifier = Modifier,
    statusInset: Dp = 0.dp,
) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val sideW = Tokens.sideW(CssEnv(containerWidth = w.value))
        val edge = Tokens.edge
        val pageOpen = state.page != null
        val searching = state.searchQuery != null
        val rowAlpha by animateFloatAsState(if (state.recorderPanelOpen) Css.PhoneRecTopOpen_MenuBtn.opacity else 1f, m.fast(), label = "rowA")

        // Q-3: the search page fades in place under the expanding field (`--t-fade`, no slide)
        val pageAlpha by animateFloatAsState(if (pageOpen) 1f else 0f, m.fade(), label = "pageA")
        if (pageAlpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(pageAlpha).background(c.bg).testTag("searchPage")
                .plainClickable(remember { MutableInteractionSource() }, role = null) {}) {
                SearchResults(state.page, rows, live, actions, Modifier.padding(top = statusInset + Css.ScrollSearchResults.top))
            }
        }

        // ☰: at the top-left; with the sidebar out it rides the panel's edge, 4 dp in (L-1)
        if (!searching || state.sidebarOpen) {
            val menuX by animateDpAsState(if (state.sidebarOpen) sideW - Tokens.rb - SpecValues.menuRide else edge, m.std(), label = "menuX")
            MenuButton(
                state.sidebarOpen, false, actions.onMenu,
                Modifier.offset { IntOffset(menuX.roundToPx(), (statusInset + edge).roundToPx()) }.alpha(if (pageOpen) 0f else rowAlpha),
            )
        }

        // the search button, expanding into the field (Q-3); it moves with the main screen while the sidebar is out
        if (state.hasSession && (!searching || pageOpen)) {
            val shift by animateDpAsState(if (state.sidebarOpen) sideW else 0.dp, m.std(), label = "searchShift")
            SearchBox(
                open = pageOpen,
                query = state.page?.query.orEmpty(),
                onOpen = actions.onSearchOpen,
                onBack = actions.onSearchBack,
                onQueryChange = actions.onQueryChange,
                openWidth = w - edge * 2,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset((shift - edge).roundToPx(), (statusInset + edge).roundToPx()) }
                    .alpha(rowAlpha),
            )
        }

        // Q-5: search state's bar replaces ☰ and search
        if (searching && !pageOpen) {
            SearchBar(
                state.searchQuery.orEmpty(), actions.onBarEdit, actions.onBarExit,
                Modifier.padding(start = edge, end = edge, top = statusInset + Css.SearchBar.top).fillMaxWidth(),
            )
        }

        // Q-6: centered below the search bar, or below the island while recording
        if (searching && !pageOpen && state.showSearchThisArea) {
            val top = if (state.recording) Css.PhoneRecording_SearchArea.top else Css.SearchArea.top
            SearchThisAreaChip(actions.onSearchThisArea, Modifier.align(Alignment.TopCenter).padding(top = statusInset + top))
        }
    }
}


/**
 * Q-3: the search button (a 44 dp `--fill1` FloatButton) that expands in place into the full-width field on `--fill2`,
 * its search icon morphing into a back arrow (`--t-fade`); the keyboard opens with it. Focused, the field is `--field`
 * with the inset focus ring.
 */
@Composable
fun SearchBox(
    open: Boolean,
    query: String,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    openWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val width by animateDpAsState(if (open) openWidth else Tokens.rb, m.fade(), label = "sbxW")
    val shape = RoundedCornerShape(Css.SearchBox.borderRadius)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) { runCatching { focus.requestFocus() }; keyboard?.show() } else keyboard?.hide()
    }
    val inputAlpha by animateFloatAsState(if (open) 1f else 0f, m.fade(), label = "sbxInput")
    // C-9 / Q-3 (`.search-box.open:focus-within`): the open field turns `--field` with the inset ring while focused
    val input = remember { MutableInteractionSource() }
    val focused by input.collectIsFocusedAsState()
    val bg by animateColorAsState(
        when {
            !open -> c.fill1
            focused -> c.field
            else -> c.fill2
        },
        m.fade(), label = "sbxBg",
    )
    Box(
        modifier
            .width(width)
            .height(Tokens.rb)
            .tokenShadow(CluetoothTheme.shadows.shadowFloat, shape)
            .clip(shape)
            .background(bg)
            .focusRing(input, shape)
            .testTag("searchBox"),
    ) {
        if (open || inputAlpha > 0f) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = CluetoothTheme.type.control.copy(fontSize = Tokens.searchSize.sp, color = c.t1),
                cursorBrush = SolidColor(c.blue),
                interactionSource = input,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Tokens.rb, end = Tokens.gutter)
                    .fillMaxWidth()
                    .alpha(inputAlpha)
                    .focusRequester(focus)
                    .testTag("searchInput"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(Strings.SearchPlaceholder, style = CluetoothTheme.type.control, color = c.t3, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
        }
        Box(
            Modifier.size(Css.SbxBtn.width, Css.SbxBtn.height)
                .plainClickable(remember { MutableInteractionSource() }, label = if (open) Strings.Back else Strings.SearchDevices) {
                    if (open) onBack() else onOpen()
                },
            contentAlignment = Alignment.Center,
        ) {
            MorphLayer(Glyphs.search, !open, SearchTurn, Tokens.rbIcon, durationStd = false)
            MorphLayer(Glyphs.back, open, -SearchTurn, Tokens.rbIcon, durationStd = false)
        }
    }
}

/**
 * Q-5: search state's bar, a full-width `--fill1` pill (44 dp, `--shadow-float`) with a `--t3` search icon, the
 * query (ellipsized; a tap reopens the page, Q-7) and a 40 dp neutral X that leaves search state (the prototype's
 * `.ib.neutral` outranks `.search-bar .ib { background: transparent }`, so the X sits on `--fill2`).
 */
@Composable
fun SearchBar(query: String, onEdit: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val shape = RoundedCornerShape(Css.SearchBar.borderRadius)
    Row(
        modifier
            .height(Css.SearchBar.height)
            .tokenShadow(CluetoothTheme.shadows.shadowFloat, shape)
            .clip(shape)
            .background(c.fill1)
            .testTag("searchBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxSize()
                .plainClickable(remember { MutableInteractionSource() }, onClick = onEdit)
                .padding(horizontal = Css.SbText.padding.left),
            horizontalArrangement = Arrangement.spacedBy(Css.SbText.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.search, null, tint = c.t3)
            Text(query, style = CluetoothTheme.type.control.copy(fontSize = Css.SbText.fontSize.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            Glyphs.close, Strings.ExitSearch, onExit,
            Modifier.padding(end = Css.SearchBar_Ib.marginRight), kind = IconButtonKind.Neutral, size = Css.SearchBar_Ib.width,
        )
    }
}

/** Q-6: the "Search this area" chip (C-3 with `--shadow-float`). */
@Composable
fun SearchThisAreaChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Chip(Strings.SearchThisArea, selected = false, onClick = onClick, modifier = modifier.testTag("searchThisArea"), floating = true)
}

/**
 * Q-3 / Q-4 / T-5 results: DeviceRows in the fixed order; a blank query shows the hint; no results in an area offer
 * "Search everywhere", and without an area read "No devices match".
 */
@Composable
fun SearchResults(
    page: SearchPageState?,
    rows: List<DeviceRow>,
    live: Boolean,
    actions: TopChromeActions,
    modifier: Modifier = Modifier,
    rowRadius: Dp = Css.Row.borderRadius,
) {
    when {
        page == null || page.query.isBlank() || page.results == null -> EmptyText(Strings.SearchHint, modifier)
        rows.isEmpty() && page.area != null && !page.searchedEverywhere ->
            EmptyText(Strings.NoResultsInArea, modifier) { TextButton(Strings.SearchEverywhere, actions.onSearchEverywhere) }
        rows.isEmpty() -> EmptyText(Strings.NoDevicesMatch, modifier)
        else -> LazyColumn(modifier.fillMaxSize().testTag("searchResults")) {
            items(rows, key = { it.key }) { d ->
                DeviceRow(
                    d.name, d.addr, d.rssi?.toInt(), if (live) d.spark else null, { actions.onPick(d.key) },
                    stale = live && !d.inRange, radius = rowRadius,
                )
            }
        }
    }
}

/**
 * T-4 / T-5: the tablet top row above the panel. ☰ (44 dp, ☰ ↔ X for the sessions list) and, 8 dp to its right,
 * a permanently expanded SearchField spanning the rest of the panel's width; both at `--edge` from the top. While
 * [query] is non-blank and [resultsOpen], the results show in a panel-shaped page over the stack (T-5).
 */
@Composable
fun TabletTopChrome(
    sessionsOpen: Boolean,
    query: String,
    resultsOpen: Boolean,
    page: SearchPageState?,
    rows: List<DeviceRow>,
    live: Boolean,
    actions: TopChromeActions,
    modifier: Modifier = Modifier,
    showSearchThisArea: Boolean = false,
    panelPartial: Boolean = false,
    panelHeightPartial: Dp = Dp.Unspecified,
) {
    val c = CluetoothTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val edge = Tokens.edge
        MenuButton(sessionsOpen, true, actions.onMenu, Modifier.offset(edge + Tokens.panelInset, edge))
        SearchField(
            query, actions.onQueryChange, Strings.SearchPlaceholder,
            Modifier.offset(Css.PhoneTablet_SearchBox.left, Css.PhoneTablet_SearchBox.top)
                .width(Css.PhoneTablet_SearchBox.width).height(Tokens.rb).testTag("tabletSearch"),
        )
        if (resultsOpen && query.isNotBlank()) {
            val shape = RoundedCornerShape(Concentric.panelRadius)
            val full = maxHeight - Tokens.topZone - edge
            val h = if (panelPartial && panelHeightPartial != Dp.Unspecified) panelHeightPartial else full
            Box(
                Modifier.offset(edge, Tokens.topZone).width(Tokens.panelW).height(h)
                    .tokenShadow(CluetoothTheme.shadows.shadowRaised, shape).clip(shape).background(c.panel)
                    .padding(Tokens.panelPad).testTag("tabletResults"),
            ) { SearchResults(page, rows, live, actions, rowRadius = Concentric.panelRow) }
        }
        if (showSearchThisArea) {
            // T-5: centered at the top of the map area to the right of the panel
            val mapLeft = edge + Tokens.panelW
            Box(
                Modifier.offset(x = mapLeft).width(maxWidth - mapLeft).padding(top = Css.PhoneTablet_SearchArea.top),
                contentAlignment = Alignment.TopCenter,
            ) { SearchThisAreaChip(actions.onSearchThisArea) }
        }
    }
}

