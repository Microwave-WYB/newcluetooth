package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiChart
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatCount
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.signalColor
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdStructureRow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdVariantState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattCharState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattServiceState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.PositionEstimateState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.StepStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.TabularNumbers
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.signal
import kotlinx.coroutines.launch

// The one device-detail component, shown in the Scan page's modal sheet and in the map pages'
// persistent sheet. One scrolling column: header, chart, actions, then the Advertising / GATT / Wiki
// tabs; the sheet around it is only as tall as this content (up to its cap).

/** Stock list items on the sheet's own surface (the default container would paint a different band). */
private val InSheet @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** The wiki page for a matched device; the site's `/p/<id>` route uses the wiki directory name, as the bundle does. */
fun wikiUrl(pageId: String): String = "https://wirelesswiki.org/p/" + java.net.URLEncoder.encode(pageId, Charsets.UTF_8.name())

/** What the device detail can ask for; the screen owner decides what each does. */
class DeviceDetailActions(
    val onDiscoverGatt: () -> Unit = {},
    val onCancelGatt: () -> Unit = {},
    val onShowOnMap: () -> Unit = {},
)

/** "-101 dBm": the number in the signal color with the unit after it on the same line. */
@Composable
fun RssiReading(rssi: Int, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(MaterialTheme.typography.titleLarge.merge(TabularNumbers).toSpanStyle().copy(color = signalColor(rssi))) { append(rssi.toString()) }
            withStyle(MaterialTheme.typography.bodySmall.toSpanStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" dBm") }
        },
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
    )
}

/** Name and reading on one line (the name ellipsizes only when it really is too long), the MAC under it. */
@Composable
fun DeviceHeader(name: String?, mac: String, rssi: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name ?: stringResource(R.string.unnamed),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RssiReading(rssi, Modifier.padding(start = 12.dp))
        }
        Text(mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The estimated position with its accuracy; the map shows it only as the ring and dot. */
@Composable
private fun PositionEstimateRow(estimate: PositionEstimateState) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (estimate.accuracyMeters != null) stringResource(R.string.map_estimated_position_accuracy, estimate.accuracyMeters)
            else stringResource(R.string.map_estimated_position),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            String.format(java.util.Locale.US, "%.5f, %.5f", estimate.at.lat, estimate.at.lon),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The whole device detail as one scrolling column. [tabState] and [expandedVariant] are hoisted so a
 * caller can start on a given tab (previews). "N variants ›" and "Discover GATT" switch the tab and
 * scroll it into view.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailContent(
    detail: DeviceDetailUiState,
    actions: DeviceDetailActions,
    modifier: Modifier = Modifier,
    /**
     * The last-minute "now" chart; on the Scan page only. A recording or product map is about the past,
     * where a chart ending at now would be wrong, so it shows no chart.
     */
    showLiveChart: Boolean = true,
    tabState: MutableState<DetailTab> = remember(detail.device.id) { mutableStateOf(DetailTab.Advertising) },
    expandedVariant: MutableState<String?> = remember(detail.device.id) { mutableStateOf(null) },
) {
    val device = detail.device
    val running = detail.gatt is GattUiState.InProgress
    val tabsInView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun showTab(tab: DetailTab) {
        tabState.value = tab
        scope.launch { tabsInView.bringIntoView() }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            DeviceHeader(device.name, device.mac, device.rssi)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (device.connectable) R.string.connectable else R.string.not_connectable) + " · ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    stringResource(R.string.variants_link, LocalTexts.current.plural(R.plurals.ad_variants, detail.variants.size, detail.variants.size)),
                    Modifier.clickable { showTab(DetailTab.Advertising) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (showLiveChart) RssiChart(detail.chartSamples, device.rssi, detail.chartHeardSeconds, Modifier.padding(top = 16.dp))
            detail.positionEstimate?.let { PositionEstimateRow(it) }
            // One row: the two actions share the width; a matched device adds the stock icon button that opens its
            // wiki page at the end. Narrower button padding keeps both labels on one line on a 360 dp phone.
            val compact = PaddingValues(horizontal = 12.dp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton(onClick = actions.onCancelGatt, modifier = Modifier.weight(1f), contentPadding = compact) {
                        Text(stringResource(R.string.gatt_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button(
                        onClick = { actions.onDiscoverGatt(); showTab(DetailTab.Gatt) },
                        enabled = detail.gattDisabledReason == null,
                        modifier = Modifier.weight(1f),
                        contentPadding = compact,
                    ) { Text(stringResource(R.string.gatt_discover), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                OutlinedButton(onClick = actions.onShowOnMap, modifier = Modifier.weight(1f), contentPadding = compact) {
                    Text(stringResource(R.string.view_on_map), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (detail.wikiPageId != null) {
                    val uri = LocalUriHandler.current
                    val url = wikiUrl(detail.wikiPageId)
                    OutlinedIconButton(onClick = { uri.openUri(url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_wiki))
                    }
                }
            }
            if (detail.gattDisabledReason != null && !running) {
                Text(
                    detail.gattDisabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        val tabs = DetailTab.entries
        PrimaryTabRow(
            selectedTabIndex = tabs.indexOf(tabState.value).coerceAtLeast(0),
            containerColor = Color.Transparent,
            modifier = Modifier.padding(top = 16.dp).bringIntoViewRequester(tabsInView),
        ) {
            tabs.forEach { t ->
                Tab(selected = t == tabState.value, onClick = { tabState.value = t }, text = { Text(stringResource(t.label)) })
            }
        }
        when (tabState.value) {
            DetailTab.Advertising -> detail.variants.forEach { v ->
                VariantItem(v, expanded = v.id == expandedVariant.value) {
                    expandedVariant.value = if (expandedVariant.value == v.id) null else v.id
                }
            }
            DetailTab.Gatt -> GattTab(detail, actions.onDiscoverGatt, actions.onCancelGatt)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** What a collapsed map sheet shows of a device: its header. */
@Composable
fun DeviceDetailPeek(detail: DeviceDetailUiState, modifier: Modifier = Modifier) {
    DeviceHeader(detail.device.name, detail.device.mac, detail.device.rssi, modifier.padding(horizontal = 24.dp))
}

// ---- Advertising ----------------------------------------------------------------------------

@Composable
private fun VariantItem(v: AdVariantState, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            colors = InSheet,
            headlineContent = {
                Text(buildString {
                    append(v.pduType).append(" · ").append(v.phy)
                    if (v.sid != null) append(" · SID ").append(v.sid)
                })
            },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.times_range,
                        LocalTexts.current.plural(R.plurals.times_heard, v.count, formatCount(v.count)), v.firstSeen, v.lastSeen,
                    ),
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${v.rssiMin} … ${v.rssiMax}", style = MaterialTheme.typography.labelMedium.merge(TabularNumbers))
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp))
                }
            },
            modifier = Modifier.clickable(onClick = onToggle),
        )
        if (expanded) v.structures.forEach { AdStructureItem(it) }
    }
}

/** One AD structure: type and its name, the length, and the bytes. */
@Composable
private fun AdStructureItem(s: AdStructureRow) {
    ListItem(
        colors = InSheet,
        overlineContent = { Text("0x${s.type} · ${stringResource(R.string.table_length)} ${s.length}") },
        headlineContent = { Text(s.typeName) },
        supportingContent = { Text(s.data.chunked(2).joinToString(" ")) },
        modifier = Modifier.padding(start = 16.dp),
    )
}

// ---- GATT ----------------------------------------------------------------------------------

@Composable
private fun GattTab(detail: DeviceDetailUiState, onDiscover: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        when (val g = detail.gatt) {
            GattUiState.NotStarted -> Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.gatt_not_read_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail.gattDisabledReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            is GattUiState.InProgress -> {
                g.steps.forEach { GattStepItem(it) }
                TextButton(onClick = onCancel, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
            is GattUiState.Done -> {
                g.steps.forEach { GattStepItem(it) }
                if (g.summary != null) {
                    Text(g.summary, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                g.services.forEach { GattServiceItems(it) }
                TextButton(onClick = onDiscover, enabled = detail.gattDisabledReason == null, modifier = Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.gatt_discover_again))
                }
            }
        }
    }
}

@Composable
private fun GattStepItem(step: GattStepState) {
    ListItem(
        colors = InSheet,
        leadingContent = {
            when (step.status) {
                StepStatus.Done -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.signal.strong)
                StepStatus.Failed -> Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error)
                StepStatus.Active -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                StepStatus.Pending -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
            }
        },
        headlineContent = {
            Text(
                step.label,
                color = when (step.status) {
                    StepStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                    StepStatus.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        },
        trailingContent = step.detail?.let { { Text(it, style = MaterialTheme.typography.labelMedium.merge(TabularNumbers)) } },
    )
}

@Composable
private fun GattServiceItems(service: GattServiceState) {
    ListItem(
        colors = InSheet,
        headlineContent = { Text(service.name ?: stringResource(R.string.gatt_unknown_service), style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(service.uuid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
    service.characteristics.forEach { GattCharItem(it) }
}

@Composable
private fun GattCharItem(c: GattCharState) {
    ListItem(
        colors = InSheet,
        overlineContent = { Text("${c.uuid} · ${c.properties}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = { Text(c.name ?: stringResource(R.string.gatt_unknown_characteristic), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                when {
                    c.error != null -> Text(c.error, color = MaterialTheme.colorScheme.error)
                    c.value != null -> Text(c.value)
                    else -> Text(stringResource(R.string.gatt_no_value))
                }
                c.descriptors.forEach { d ->
                    Text(
                        "${d.name ?: d.uuid}: ${d.error ?: d.value ?: stringResource(R.string.gatt_no_value)}",
                        color = if (d.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        modifier = Modifier.padding(start = 16.dp),
    )
}
