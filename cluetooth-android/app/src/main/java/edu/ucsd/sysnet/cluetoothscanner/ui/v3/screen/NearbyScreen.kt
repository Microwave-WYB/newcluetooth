package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SignalDot
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DiscoverMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyCategoryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductItemState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts

/** Subtitle line; in-range products lead with a dot for their strongest RSSI. */
@Composable
private fun ProductSubtitle(item: ProductItemState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.strongestRssi != null) SignalDot(item.strongestRssi, 8.dp, Modifier.padding(end = 6.dp))
        Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A stock [Card] per product: the category icon, the name, the subtitle. */
@Composable
private fun ProductCard(item: ProductItemState, icon: CategoryIcon, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(140.dp)) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon.vector(), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProductSubtitle(item)
        }
    }
}

/** A stock [ListItem] header with an arrow, then a row of cards; in-range products first. */
@Composable
private fun CategorySection(category: NearbyCategoryState, onOpenCategory: () -> Unit, onOpenProduct: (ProductItemState) -> Unit, rowState: LazyListState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        ListItem(
            headlineContent = { Text(category.title, style = MaterialTheme.typography.titleMedium) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_view_all)) },
            modifier = Modifier.clickable(onClick = onOpenCategory),
        )
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(category.items, key = { it.id }) { item -> ProductCard(item, category.icon) { onOpenProduct(item) } }
        }
    }
}

@Composable
fun NearbyTopBar(state: NearbyUiState, @StringRes notice: Int?, mode: DiscoverMode = DiscoverMode.Nearby) {
    val texts = LocalTexts.current
    val inRange = (state.categories + state.unrecognized).sumOf { c -> c.items.count { it.strongestRssi != null } }
    val seen = state.categories.flatMap { c -> c.items.map { it.id } }.toSet().size + state.unrecognized.items.size
    PageTopBar(
        title = stringResource(R.string.tab_discover),
        subtitle = if (mode == DiscoverMode.Nearby) texts.plural(R.plurals.products_in_range, inRange, inRange)
        else texts.plural(R.plurals.products_seen, seen, seen),
        notice = notice?.let { stringResource(it) },
    )
}

@Composable
fun NearbyContent(
    state: NearbyUiState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    mode: DiscoverMode = DiscoverMode.Nearby,
    onMode: (DiscoverMode) -> Unit = {},
    /** Hoisted by the caller so a product page and back keeps where the page and each card row were. */
    listState: LazyListState = rememberLazyListState(),
    rowStates: MutableMap<String, LazyListState> = remember { HashMap() },
    onOpenCategory: (NearbyCategoryState) -> Unit = {},
    onOpenProduct: (ProductItemState) -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        // Discover's switch: Nearby (in range now) or All (also what was seen before), the same layout.
        item(key = "mode") { DiscoverModeSwitch(mode, onMode) }
        items(state.categories, key = { it.id }) { c -> CategorySection(c, { onOpenCategory(c) }, onOpenProduct, rowStates.getOrPut(c.id) { LazyListState() }) }
        if (state.unrecognized.items.isNotEmpty()) {
            item(key = state.unrecognized.id) {
                CategorySection(state.unrecognized, { onOpenCategory(state.unrecognized) }, onOpenProduct, rowStates.getOrPut(state.unrecognized.id) { LazyListState() })
            }
        }
        if (state.categories.isEmpty() && state.unrecognized.items.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.nearby_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CategoryTopBar(category: NearbyCategoryState, onBack: () -> Unit) {
    val now = category.items.count { it.strongestRssi != null }
    PageTopBar(
        title = category.title,
        subtitle = stringResource(R.string.category_in_range_total, now, category.items.size),
        navigation = { BackButton(onBack) },
    )
}

/** The arrow's destination: every product of one category as a list. */
@Composable
fun CategoryContent(
    category: NearbyCategoryState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenProduct: (ProductItemState) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val now = category.items.filter { it.strongestRssi != null }
    val before = category.items.filter { it.strongestRssi == null }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        if (now.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.label_in_range)) }
            items(now, key = { it.id }) { ProductListItem(it, category.icon) { onOpenProduct(it) } }
        }
        if (before.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.label_seen_before)) }
            items(before, key = { it.id }) { ProductListItem(it, category.icon) { onOpenProduct(it) } }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ProductListItem(item: ProductItemState, icon: CategoryIcon, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon.vector(), null) },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { ProductSubtitle(item) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverModeSwitch(mode: DiscoverMode, onMode: (DiscoverMode) -> Unit) {
    val modes = DiscoverMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        modes.forEachIndexed { i, m ->
            SegmentedButton(selected = m == mode, onClick = { onMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) {
                Text(stringResource(m.label))
            }
        }
    }
}

