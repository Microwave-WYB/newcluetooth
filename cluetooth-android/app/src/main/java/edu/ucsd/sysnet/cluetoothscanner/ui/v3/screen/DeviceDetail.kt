package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.BackButton
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.appendMac
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiChart
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RssiText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.V3TopBar
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatCount
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.signalColor
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdStructureRow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdVariantState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DetailTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattCharState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattServiceState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.StepStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.WikiUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra

// The one device-detail component. The half-height sheet and the expanded page share their
// pieces and state; the scan page, the recording map and the product map all host it.

@Composable
private fun MacLine(detail: DeviceDetailUiState) {
    Text(
        text = buildAnnotatedString {
            appendMac(detail.device.mac)
            append(" · ${detail.device.addressType.label}")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DeviceName(name: String?, style: androidx.compose.ui.text.TextStyle) {
    if (name != null) Text(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else Text("未命名", style = style, color = MaterialTheme.colorScheme.outline)
}

/** Half-height sheet content. */
@Composable
fun DeviceDetailHalf(
    detail: DeviceDetailUiState,
    onWindowChange: (ChartWindow) -> Unit,
    onDiscoverGatt: () -> Unit,
    onShowOnMap: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = detail.device
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                DeviceName(device.name, MaterialTheme.typography.titleLarge)
                MacLine(detail)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (device.connectable) "可连接" else "不可连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${detail.variants.size} 种广播变体 ›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onExpand),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    device.rssi.toString(),
                    fontFamily = MonoFamily,
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Medium,
                    color = signalColor(device.rssi),
                )
                Text("dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RssiChart(detail.chartSamples, detail.chartWindow, onWindowChange, Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onDiscoverGatt,
                enabled = detail.gattDisabledReason == null,
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("发现 GATT") }
            OutlinedButton(onClick = onShowOnMap, modifier = Modifier.weight(1f).height(46.dp)) { Text("在地图上查看") }
        }
        if (detail.gattDisabledReason != null) {
            Text(
                detail.gattDisabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Expanded, full-screen detail: 广播 / GATT / Wiki. */
@Composable
fun DeviceDetailFull(
    detail: DeviceDetailUiState,
    tab: DetailTab,
    expandedVariantId: String?,
    onTab: (DetailTab) -> Unit,
    onToggleVariant: (String) -> Unit,
    onDiscoverGatt: () -> Unit,
    onCancelGatt: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when a sheet already pads for the status bar. */
    statusBarInset: Boolean = true,
) {
    val device = detail.device
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        V3TopBar(
            title = device.name ?: "未命名",
            navigation = { BackButton(onCollapse) },
            statusBarInset = statusBarInset,
            status = {
                Text(
                    buildAnnotatedString {
                        appendMac(device.mac)
                        append(" · ${device.addressType.label}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            },
            actions = {
                RssiText(device.rssi, Modifier.padding(end = 12.dp), size = 22)
            },
        )
        val tabs = DetailTab.entries.filter { it != DetailTab.Wiki || detail.wiki != null }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            tabs.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.clickable { onTab(t) }.padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        t.label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier.height(2.dp).width(28.dp)
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (tab) {
                DetailTab.Advertising -> AdvertisingTab(detail.variants, expandedVariantId, onToggleVariant)
                DetailTab.Gatt -> GattTab(detail, onDiscoverGatt, onCancelGatt)
                DetailTab.Wiki -> detail.wiki?.let { WikiTab(it) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- 广播 ----------------------------------------------------------------------------------

@Composable
private fun AdvertisingTab(variants: List<AdVariantState>, expandedId: String?, onToggle: (String) -> Unit) {
    variants.forEach { v -> VariantRow(v, expanded = v.id == expandedId, onToggle = { onToggle(v.id) }) }
}

@Composable
private fun VariantRow(v: AdVariantState, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(v.pduType).append(" · ").append(v.phy)
                        if (v.sid != null) append(" · SID ").append(v.sid)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = MonoFamily,
                )
                Text(
                    "${formatCount(v.count)} 次 · ${v.firstSeen} – ${v.lastSeen}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${v.rssiMin} … ${v.rssiMax}",
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) AdStructureTable(v.structures)
    }
}

/** One quiet block per AD structure, set off by a thin rule on the left. */
@Composable
private fun AdStructureTable(structures: List<AdStructureRow>) {
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
            TableHeader("类型", Modifier.width(34.dp))
            TableHeader("名称", Modifier.weight(1f))
            TableHeader("长度", Modifier.width(36.dp), TextAlign.End)
        }
        structures.forEach { s ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.fillMaxHeight().width(2.dp).background(rule))
                Column(Modifier.padding(start = 10.dp, top = 5.dp, bottom = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.type, Modifier.width(34.dp), fontFamily = MonoFamily, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Text(s.typeName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(s.length.toString(), Modifier.width(36.dp), fontFamily = MonoFamily, fontSize = 13.sp, textAlign = TextAlign.End)
                    }
                    Text(
                        s.data.chunked(2).joinToString(" "),
                        fontFamily = MonoFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeader(text: String, modifier: Modifier, align: TextAlign? = null) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = align)
}

// ---- GATT ----------------------------------------------------------------------------------

@Composable
private fun GattTab(detail: DeviceDetailUiState, onDiscover: () -> Unit, onCancel: () -> Unit) {
    when (val g = detail.gatt) {
        GattUiState.NotStarted -> Column(Modifier.padding(20.dp)) {
            Text("还没有读取过这个设备的 GATT。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDiscover, enabled = detail.gattDisabledReason == null, modifier = Modifier.padding(top = 12.dp)) {
                Text("发现 GATT")
            }
            detail.gattDisabledReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        is GattUiState.InProgress -> {
            GattSteps(g.steps)
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) { Text("取消") }
        }
        is GattUiState.Done -> {
            GattSteps(g.steps)
            if (g.summary != null) {
                Text(
                    g.summary,
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            g.services.forEach { GattServiceBlock(it) }
            OutlinedButton(
                onClick = onDiscover,
                enabled = detail.gattDisabledReason == null,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp),
            ) { Text("再次发现 GATT") }
        }
    }
}

@Composable
private fun GattSteps(steps: List<GattStepState>) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        steps.forEach { step ->
            Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { StepGlyph(step.status) }
                Text(
                    step.label,
                    Modifier.padding(start = 12.dp).weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (step.status) {
                        StepStatus.Pending -> MaterialTheme.colorScheme.outline
                        StepStatus.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (step.detail != null) {
                    Text(step.detail, fontFamily = MonoFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepGlyph(status: StepStatus) {
    when (status) {
        StepStatus.Done -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.extra.signalGreen, modifier = Modifier.size(18.dp))
        StepStatus.Failed -> Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        StepStatus.Active -> CircularProgressIndicator(
            progress = { 0.65f },
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        StepStatus.Pending -> {
            val c = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.size(12.dp)) { drawCircle(c, size.minDimension / 2 - 1.dp.toPx(), Offset(size.width / 2, size.height / 2), style = Stroke(1.5.dp.toPx())) }
        }
    }
}

@Composable
private fun GattServiceBlock(service: GattServiceState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(service.name ?: "未知服务", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(service.uuid, fontFamily = MonoFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        service.characteristics.forEach { GattCharRow(it) }
    }
}

@Composable
private fun GattCharRow(c: GattCharState) {
    Row(Modifier.height(IntrinsicSize.Min).padding(start = 4.dp, top = 8.dp)) {
        Box(Modifier.fillMaxHeight().width(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name ?: "未知特征", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  ${c.properties}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(c.uuid, fontFamily = MonoFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                c.error != null -> Text(c.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
                c.value != null -> Text(c.value, fontFamily = MonoFamily, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                else -> Text("无值", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ---- Wiki ----------------------------------------------------------------------------------

@Composable
private fun WikiTab(wiki: WikiUiState) {
    Column(Modifier.padding(20.dp)) {
        Text(wiki.family, style = MaterialTheme.typography.titleMedium)
        Text(wiki.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        wiki.facts.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(k, Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, fontFamily = MonoFamily, fontSize = 13.sp)
            }
        }
        Text(wiki.confidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 14.dp))
    }
}
