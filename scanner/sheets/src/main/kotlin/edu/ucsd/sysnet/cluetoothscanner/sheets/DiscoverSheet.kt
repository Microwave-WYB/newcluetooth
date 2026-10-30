package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategorySection
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ProductCard
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SegmentedSize
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SwapContent
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal object DiscoverTags {
    const val VIEW_SWITCH = "discover-view"
    const val SEGMENT = "discover-segment"
    const val FILTER = "discover-filter"
    const val LIST = "discover-list"
    const val CATEGORIES = "discover-categories"
    const val RETRY = "discover-retry"
}

/** The Discover sheet, the root (D-1..D-5; Q-5 in search state). */
@Composable
internal fun DiscoverSheet(state: SheetsState, entry: SheetEntry) {
    val d = state.discover
    val tablet = state.stack.tablet
    SheetFrame(
        state.stack, entry, state.metrics,
        header = { if (d.search != null) SearchHeader(d) else DiscoverHeader(state, tablet) },
    ) {
        // the content needs the first sort: nothing while it loads, and Try again if it failed (DiscoverPhase)
        when (val phase = d.phase) {
            is DiscoverPhase.Loading -> return@SheetFrame
            is DiscoverPhase.Failed -> {
                FailedNote(phase.message, onRetry = state::retry)
                return@SheetFrame
            }
            is DiscoverPhase.Ready -> Unit
        }
        when {
            d.search != null -> DeviceList(state, entry, d.rows, listKey = SEARCH_LIST, empty = {
                EmptyText(if (d.search?.area != null) Strings.NoResultsInArea else Strings.NoDevicesMatch)
            })
            else -> SwapContent(d.view, { it.ordinal }, crossfade = true) { view -> // D-1a: crossfade, height kept
                when (view) {
                    DiscoverView.List -> DeviceList(state, entry, d.rows, listKey = SheetEntry.MAIN_LIST, empty = {
                        EmptyText(Strings.NoMatchingDevices)
                    })
                    DiscoverView.Categories -> Categories(state, entry)
                }
            }
        }
    }
}

/** The first sort failed: why, and Try again (back to Loading). */
@Composable
private fun FailedNote(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyText(message)
        Button(Strings.TryAgain, onClick = onRetry, kind = ButtonKind.Tonal, modifier = Modifier.testTag(DiscoverTags.RETRY))
    }
}

/** D-1: the view switch, the segmented control and the filter button on one 40 dp row (S-2, S-3). */
@Composable
private fun DiscoverHeader(state: SheetsState, tablet: Boolean) {
    val d = state.discover
    val inset = Concentric.cornerButton(tablet)
    Box(Modifier.fillMaxWidth().height(Css.DiscTitle.height).padding(horizontal = inset)) {
        val toList = d.view == DiscoverView.Categories
        IconButton(
            if (toList) Glyphs.list else Glyphs.grid,
            if (toList) Strings.ShowDeviceList else Strings.ShowCategories,
            onClick = d::toggleView,
            modifier = Modifier.align(Alignment.CenterStart).testTag(DiscoverTags.VIEW_SWITCH),
        )
        val segments = segmentsFor(d.live)
        Segmented(
            segments.map { it.label() },
            segments.indexOf(d.segment),
            onSelect = { i -> state.selectSegment(segments[i]) },
            modifier = Modifier.align(Alignment.Center).width(Css.DiscHead_Seg.width).testTag(DiscoverTags.SEGMENT),
            size = SegmentedSize.Header,
        )
        val active = d.filter.activeFilterCount
        IconButton(
            Glyphs.filter,
            Strings.filterActive(active),
            onClick = state::openFilter,
            modifier = Modifier.align(Alignment.CenterEnd).testTag(DiscoverTags.FILTER),
            kind = if (active > 0) IconButtonKind.Selected else IconButtonKind.Neutral,
            badge = active.takeIf { it > 0 },
        )
    }
}

/** Q-5: "N results" or "N results in this area", centered on the header line. */
@Composable
private fun SearchHeader(d: DiscoverModel) {
    Box(Modifier.fillMaxWidth().height(Css.DiscTitle.height), contentAlignment = Alignment.Center) {
        Text(
            Strings.resultsHeader(d.rows.size, d.search?.area != null),
            style = CluetoothTheme.type.title.copy(fontSize = Css.DiscTitle.fontSize.sp, fontWeight = FontWeight(Css.DiscTitle.fontWeight.toInt())),
        )
    }
}

private fun Segment.label() = when (this) {
    Segment.Nearby -> Strings.Nearby
    Segment.All -> Strings.All
    Segment.ThisArea -> Strings.ThisArea
}

/** D-3, Q-5: DeviceRows in the fixed order (G-9), loaded a page ahead of what is visible. */
@Composable
internal fun DeviceList(
    state: SheetsState,
    entry: SheetEntry,
    keys: LongArray,
    listKey: String,
    empty: @Composable () -> Unit,
    header: (@Composable () -> Unit)? = null,
    onOpen: (Long) -> Unit = { state.openDevice(it, fromList = true) },
) {
    val listState = rememberSheetListState(entry, listKey)
    val tablet = state.stack.tablet
    LaunchedEffect(keys, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { first ->
            val from = maxOf(0, first - LOOK_BEHIND)
            state.rows.ensure(keys.asList().subList(minOf(from, keys.size), minOf(keys.size, from + RowStore.PAGE)))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(if (listKey == SEARCH_LIST) "search-list" else DiscoverTags.LIST),
        contentPadding = PaddingValues(horizontal = Concentric.contentInset(tablet)),
    ) {
        if (header != null) item(key = "header") { header() }
        if (keys.isEmpty()) item(key = "empty") { empty() }
        items(keys.asList(), key = { it }) { key ->
            val row = state.rows[key]
            if (row == null) Box(Modifier.fillMaxWidth().height(Css.Row.minHeight)) else Row(state, row, tablet) { onOpen(key) }
        }
    }
}

@Composable
private fun Row(state: SheetsState, row: DeviceRow, tablet: Boolean, onClick: () -> Unit) {
    DeviceRow(
        name = row.name,
        mac = row.addr,
        rssi = row.rssi,
        onClick = onClick,
        stale = !row.inRange,
        radius = if (tablet) Concentric.panelRow else Css.Row.borderRadius,
    )
}

/** D-2: categories of product cards; "Unidentified devices" last as one card. */
@Composable
private fun Categories(state: SheetsState, entry: SheetEntry) {
    val d = state.discover
    var groups by remember { mutableStateOf<Loaded<List<CategoryGroup>>>(Loaded.Pending) }
    val filter = d.queryFilter()
    LaunchedEffect(filter, d.rows) {
        // every answer also teaches the category labels (D-6: titles never show an id)
        suspend fun read() = state.load(SheetQuery.Products(filter)).also { o ->
            (o as? Outcome.Ok)?.let { state.learnLabels(it.value.map { g -> g.category to g.label }) }
        }
        groups = loadedOf(read())
        if (state.live) while (true) { // live cards update in place (§2.6); new ones are appended by the core order
            delay(LIVE_REFRESH_MS)
            // a failed refresh keeps the cards shown
            (read() as? Outcome.Ok)?.let { groups = Loaded.Found(it.value) }
        }
    }
    val listState = rememberSheetListState(entry, CATEGORIES_LIST)
    val tablet = state.stack.tablet
    val now = state.nowMs()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(DiscoverTags.CATEGORIES)) {
        (groups as? Loaded.Failed)?.let { failed -> item { EmptyText(failed.message) } }
        val g = groups.valueOrNull() ?: return@LazyColumn
        if (g.isEmpty()) item { EmptyText(Strings.NoProductsMatch) }
        items(g, key = { it.category }) { group ->
            CategorySection(group.label) {
                for (card in group.products) {
                    val unidentified = card.id == UNIDENTIFIED
                    ProductCard(
                        title = if (unidentified) Strings.UnidentifiedDevices else card.title,
                        status = Strings.productStatus(card.live, card.count, card.inRange, Format.ago(card.lastSeenMs, now)),
                        categoryId = if (unidentified) null else group.category,
                        onClick = { state.openProduct(if (unidentified) null else card.id) },
                        pageIcon = card.icon,
                        radius = Concentric.card(tablet),
                    )
                }
            }
        }
    }
}

internal const val UNIDENTIFIED = "unidentified"
internal const val SEARCH_LIST = "search"
private const val CATEGORIES_LIST = "categories"
private const val LOOK_BEHIND = 20
private const val LIVE_REFRESH_MS = 2_000L
