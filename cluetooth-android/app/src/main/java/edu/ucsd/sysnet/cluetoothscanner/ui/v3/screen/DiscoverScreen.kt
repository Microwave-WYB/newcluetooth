package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.PageTopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DiscoverMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductHeaderState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import kotlin.math.roundToLong

fun CategoryIcon.vector(): ImageVector = when (this) {
    CategoryIcon.Headphones -> Icons.Filled.Headphones
    CategoryIcon.Watch -> Icons.Filled.Watch
    CategoryIcon.Beacon -> Icons.Filled.Sensors
    CategoryIcon.Home -> Icons.Filled.Home
    CategoryIcon.Light -> Icons.Filled.Lightbulb
    CategoryIcon.Sensor -> Icons.Filled.Thermostat
    CategoryIcon.Power -> Icons.Filled.Bolt
    CategoryIcon.Car -> Icons.Filled.DirectionsCar
    CategoryIcon.Medical -> Icons.Filled.MonitorHeart
    CategoryIcon.Tracker -> Icons.Filled.LocationSearching
    CategoryIcon.Unknown -> Icons.Filled.QuestionMark
}

// ---- Product page --------------------------------------------------------------------------------

@Composable
fun ProductTopBar(title: String, onBack: () -> Unit) {
    PageTopBar(title = title, navigation = { BackButton(onBack) })
}

/**
 * The product's image. The wiki has no images yet, so this is its category icon, large, on a stock
 * [Card]; pass [image] to show a real picture instead.
 */
@Composable
fun ProductImage(icon: CategoryIcon, modifier: Modifier = Modifier, image: (@Composable () -> Unit)? = null) {
    Card(modifier.fillMaxWidth().height(200.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image != null) image() else Icon(icon.vector(), null, Modifier.size(96.dp))
        }
    }
}

/**
 * One product: its image, full name and wiki description, a map preview of where it was heard (a card
 * that opens the full map), and its devices; a device opens the device sheet.
 *
 * @param map the preview map, drawn under a layer that takes every touch (the preview is not interactive)
 */
@Composable
fun ProductContent(
    header: ProductHeaderState,
    state: MapUiState?,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    onOpenMap: () -> Unit = {},
    onDevice: (MapListRowState) -> Unit = {},
    map: @Composable () -> Unit = {},
) {
    val texts = LocalTexts.current
    val rows = state?.rows.orEmpty()
    val points = state?.productPoints.orEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "image") { ProductImage(header.icon, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item(key = "about") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The full name, wrapped over as many lines as it needs; only the app bar title may be cut.
                Text(header.title, style = MaterialTheme.typography.headlineSmall)
                if (header.description.isNotBlank()) {
                    Text(header.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val uri = LocalUriHandler.current
                val url = wikiUrl(header.id)
                FilledTonalButton(onClick = { uri.openUri(url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.product_wiki))
                }
            }
        }
        item(key = "map") {
            Card(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (points.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.product_map_empty), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(180.dp)) {
                        map()
                        val label = stringResource(R.string.product_map_open)
                        Box(Modifier.matchParentSize().clickable(onClickLabel = label, onClick = onOpenMap))
                    }
                    // Places: distinct spots about ten metres apart.
                    val places = points.map { (it.at.lat * 1e4).roundToLong() to (it.at.lon * 1e4).roundToLong() }.toSet().size
                    val last = rows.maxOfOrNull { it.lastSeenMs }?.takeIf { it > 0 }
                    Text(
                        stringResource(
                            R.string.product_map_caption,
                            texts.plural(R.plurals.places, places, places),
                            last?.let { DiscoverMapper.ago(it, nowMs, texts) } ?: "",
                        ),
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item(key = "devices") { SectionLabel(stringResource(R.string.product_devices)) }
        items(rows, key = { it.id }) { row ->
            ListItem(
                headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        row.mac?.let { Text(it) }
                        if (row.detail.isNotEmpty()) Text(row.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingContent = row.rssi?.let { { RssiText(it) } },
                modifier = Modifier.clickable { onDevice(row) },
            )
        }
        if (state != null && rows.isEmpty()) {
            item(key = "no-devices") {
                Text(
                    stringResource(R.string.product_no_devices),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
