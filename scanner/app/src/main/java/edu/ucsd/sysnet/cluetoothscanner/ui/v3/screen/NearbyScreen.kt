package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.AppShell
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordControlBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SheetLayer
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.SignalDot
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.StatusLine
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.V3TopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyCategoryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductItemState

private fun CategoryIcon.vector(): ImageVector = when (this) {
    CategoryIcon.Headphones -> Icons.Filled.Headphones
    CategoryIcon.Watch -> Icons.Filled.Watch
    CategoryIcon.Beacon -> Icons.Filled.Sensors
    CategoryIcon.Home -> Icons.Filled.Home
    CategoryIcon.Car -> Icons.Filled.DirectionsCar
    CategoryIcon.Medical -> Icons.Filled.MonitorHeart
    CategoryIcon.Tracker -> Icons.Filled.LocationSearching
    CategoryIcon.Unknown -> Icons.Filled.QuestionMark
}

/** Rounded-square placeholder image; one icon per category until products have artwork. */
@Composable
private fun ProductImage(icon: CategoryIcon, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size / 4.5f).dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon.vector(), null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size((size * 0.42f).dp))
    }
}

/** Subtitle line; in-range products lead with a dot for their strongest RSSI. */
@Composable
private fun ProductSubtitle(item: ProductItemState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.strongestRssi != null) {
            SignalDot(item.strongestRssi, 7.dp, Modifier.padding(end = 6.dp))
        }
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProductTile(item: ProductItemState, icon: CategoryIcon, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        ProductImage(icon, 104)
        Text(
            item.name,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProductSubtitle(item)
    }
}

/** Title with a right arrow, then a horizontally scrolling row; in-range products first. */
@Composable
private fun CategorySection(
    category: NearbyCategoryState,
    onOpenCategory: () -> Unit,
    onOpenProduct: (ProductItemState) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenCategory).padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看全部", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(category.items, key = { it.id }) { item -> ProductTile(item, category.icon) { onOpenProduct(item) } }
        }
    }
}

@Composable
fun NearbyPage(
    state: NearbyUiState,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onOpenCategory: (NearbyCategoryState) -> Unit = {},
    onOpenProduct: (ProductItemState) -> Unit = {},
) {
    val inRange = (state.categories + state.unrecognized).sumOf { c -> c.items.count { it.strongestRssi != null } }
    Column(modifier.fillMaxSize()) {
        V3TopBar(
            title = "附近",
            status = {
                RecorderStatus(state.recorder, "$inRange 个产品在范围内")
                HeaderNotice(notice)
            },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.categories, key = { it.id }) { c ->
                CategorySection(c, { onOpenCategory(c) }, onOpenProduct)
            }
            if (state.unrecognized.items.isNotEmpty()) {
                item(key = state.unrecognized.id) {
                    CategorySection(state.unrecognized, { onOpenCategory(state.unrecognized) }, onOpenProduct)
                }
            }
            if (state.categories.isEmpty() && state.unrecognized.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有识别出附近的产品。",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The arrow's destination: every product of one category as a vertical list. */
@Composable
fun CategoryListPage(
    category: NearbyCategoryState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenProduct: (ProductItemState) -> Unit = {},
) {
    val now = category.items.filter { it.strongestRssi != null }
    val before = category.items.filter { it.strongestRssi == null }
    Column(modifier.fillMaxSize()) {
        V3TopBar(
            title = category.title,
            navigation = { BackButton(onBack) },
            status = { StatusLine("${now.size} 个在范围内 · 共 ${category.items.size} 个") },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (now.isNotEmpty()) {
                item { ListLabel("在范围内") }
                items(now, key = { it.id }) { ProductListRow(it, category.icon) { onOpenProduct(it) } }
            }
            if (before.isNotEmpty()) {
                item { ListLabel("曾经见过") }
                items(before, key = { it.id }) { ProductListRow(it, category.icon) { onOpenProduct(it) } }
            }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProductListRow(item: ProductItemState, icon: CategoryIcon, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(icon, 52)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(1.dp))
            ProductSubtitle(item)
        }
    }
}
