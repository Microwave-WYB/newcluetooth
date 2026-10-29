package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SwapContent
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.bandColor
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.sparkRssi
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import java.util.Locale

internal object DeviceTags {
    const val TABS = "device-tabs"
    const val CHART = "device-chart"
    const val DISCOVER = "gatt-discover"
    const val CANCEL = "gatt-cancel"
    const val TRY_AGAIN = "gatt-try-again"
    const val NOTE = "gatt-note"
    fun step(kind: GattStepKind) = "gatt-step-${kind.name}"
}


/** The device sheet (D-9..D-13). */
@Composable
internal fun DeviceSheet(state: SheetsState, entry: SheetEntry, key: Long) {
    var content by remember { mutableStateOf(DeviceContent()) }
    val gatt = remember(entry.id) { state.gattFor(entry, key) }
    LaunchedEffect(key) {
        content = content.withDetail(state.load(SheetQuery.Device(key)))
        // D-13.7; a failed read shows the tab as never read
        if (gatt.state == GattUi.Idle) (state.load(SheetQuery.LastGatt(key)) as? Outcome.Ok)?.let { gatt.showLast(it.value) }
    }
    if (state.live) {
        LaunchedEffect(key) { // D-10: the chart rides on the deltas of the focused key
            content = content.withChart(state.load(SheetQuery.Chart(key)))
            state.chartUpdates(key).collect { content = content.withChart(it) }
        }
    }
    val row = state.rows[key]
    val detail = content.detail
    SheetFrame(state.stack, entry, state.metrics, header = {
        PushedHeader(state) { detail.valueOrNull()?.let { DeviceHead(it, row?.rssi ?: it.rssi) } }
    }) {
        when (detail) {
            Loaded.Pending, Loaded.Missing -> Unit
            is Loaded.Failed -> EmptyText(detail.message)
            is Loaded.Found -> DeviceBody(state, entry, content, detail.value, row?.rssi ?: detail.value.rssi, gatt) { content = content.withTab(it) }
        }
    }
}

@Composable
private fun DeviceBody(state: SheetsState, entry: SheetEntry, content: DeviceContent, d: DeviceDetail, rssi: Int?, gatt: GattController, onTab: (DeviceTab) -> Unit) {
    val list = rememberSheetListState(entry)
    LazyColumn(state = list) {
        item {
            val chart = content.chart
            if (state.live && chart is ChartState.Live) {
                Box(Modifier.padding(start = Css.ChartWrap.padding.left, top = Css.ChartWrap.padding.top, end = Css.ChartWrap.padding.right)) {
                    RssiChart(chart.chart, rssi)
                }
            }
            Segmented(
                listOf(Strings.Advertising, Strings.Gatt),
                content.tab.ordinal,
                onSelect = { onTab(DeviceTab.entries[it]) }, // D-11: the height stays (S-16)
                modifier = Modifier.fillMaxWidth().padding(
                    start = Css.DevTabs.padding.left, top = Css.DevTabs.padding.top,
                    end = Css.DevTabs.padding.right, bottom = Css.DevTabs.padding.bottom,
                ).testTag(DeviceTags.TABS),
            )
            SwapContent(content.tab, { it.ordinal }) { t ->
                when (t) {
                    DeviceTab.Advertising -> Advertising(d)
                    DeviceTab.Gatt -> GattTab(gatt)
                }
            }
        }
    }
}

/** D-9: the name (or "No name"), the product link, the MAC and "−56 dBm" in the band color. */
@Composable
private fun DeviceHead(d: DeviceDetail, rssi: Int?) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    Column(Modifier.padding(start = Css.DevHead.padding.left, bottom = Css.DevHead.padding.bottom).defaultMinSize(minHeight = Tokens.sheetHeaderH)) {
        Box(Modifier.defaultMinSize(minHeight = Tokens.sheetHeaderH), contentAlignment = Alignment.CenterStart) {
            Text(d.name ?: Strings.NoName, style = t.title, color = if (d.name == null) c.t3 else c.t1)
        }
        val page = d.page
        val url = d.wikiUrl
        if (page != null && url != null) WikiLink(page.title, url)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.addr, style = t.sub.tabular(), color = c.t2)
            if (rssi != null) {
                Text(
                    Format.dbm(rssi),
                    Modifier.padding(start = Css.DevRssi.marginLeft),
                    style = t.sub.tabular().copy(fontWeight = FontWeight(Css.DevRssi.fontWeight.toInt())),
                    color = bandColor(rssi),
                )
            }
        }
    }
}

/** D-10: the last minute, y −100..−30, ticks at −65 and −80, the end marker at the current reading. */
@Composable
private fun RssiChart(chart: LiveChart, rssi: Int?) {
    val c = CluetoothTheme.colors
    val measurer = rememberTextMeasurer()
    val label = CluetoothTheme.type.small.copy(fontSize = Css.Chart_Text.fontSize.sp, color = c.t3)
    val band = rssi?.let { bandColor(it) } ?: c.t3
    Canvas(Modifier.fillMaxWidth().height(Css.Chart.height).testTag(DeviceTags.CHART)) {
        val u = density
        val plot = size.height - CHART_AXIS * u
        val x0 = CHART_LEFT * u
        val y = { r: Int -> CHART_PAD * u + ((-30f - r.coerceIn(-100, -30)) / 70f) * (plot - 2 * CHART_PAD * u) }
        val n = chart.samples.size
        val xs = { k: Int -> x0 + k * (size.width - x0 - CHART_RIGHT * u) / (n - 1).coerceAtLeast(1) }
        for (tick in intArrayOf(-65, -80)) {
            drawLine(c.div, Offset(x0, y(tick)), Offset(size.width, y(tick)), u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 3 * u)))
            drawText(measurer, Format.signed(tick), Offset(0f, y(tick) - label.fontSize.toPx() / 2 - u), label)
        }
        val path = Path()
        var pen = false
        var last = -1
        chart.samples.forEachIndexed { k, b ->
            val r = sparkRssi(b)
            if (r == null) { pen = false; return@forEachIndexed }
            if (pen) path.lineTo(xs(k), y(r)) else path.moveTo(xs(k), y(r))
            pen = true
            last = k
        }
        drawPath(path, c.t2, style = Stroke(Css.Chart_Line.strokeWidth * u))
        if (last >= 0 && rssi != null) drawCircle(band, CHART_MARKER * u, Offset(xs(last), y(rssi)))
        drawText(measurer, Strings.ChartMinusOneMin, Offset(x0, size.height - label.fontSize.toPx() - 2 * u), label)
        val now = measurer.measure(Strings.ChartNow, label)
        drawText(now, topLeft = Offset(size.width - now.size.width, size.height - label.fontSize.toPx() - 2 * u))
    }
}

/** D-12: the Details list and the raw Len / Type / Data table. */
@Composable
private fun Advertising(d: DeviceDetail) {
    Column {
        SheetSect(Strings.Details)
        val kv = buildList {
            d.connectable?.let { add(Strings.Connectable to if (it) Strings.Yes else Strings.No) }
            when (d.format) {
                AdvFormat.Legacy -> add(Strings.FormatKey to Strings.Legacy)
                AdvFormat.Extended -> add(Strings.FormatKey to Strings.Extended)
                AdvFormat.Unknown -> Unit
            }
            d.companyName?.let { add(Strings.Company to it) }
            d.serviceUuid?.let { add(Strings.Service to it) }
            d.txPower?.let { add(Strings.TxPower to Format.dbm(it)) }
        }
        Column(Modifier.padding(horizontal = Css.Kv.padding.left)) {
            for ((k, v) in kv) {
                Row(Modifier.fillMaxWidth().padding(vertical = Css.Kv_Div.padding.top), horizontalArrangement = Arrangement.spacedBy(Css.Kv_Div.gap)) {
                    val style = CluetoothTheme.type.body.copy(fontSize = Css.Kv_Div.fontSize.sp, lineHeight = Css.Kv_Div.lineHeight.sp)
                    Text(k, style = style, color = CluetoothTheme.colors.t2)
                    Text(v, Modifier.weight(1f), style = style.tabular().copy(fontWeight = FontWeight(Css.Kv_Dd.fontWeight.toInt())), textAlign = TextAlign.End)
                }
            }
        }
        SheetSect(Strings.RawData)
        RawTable(d)
    }
}

@Composable
private fun SheetSect(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(
            start = Css.SheetSect.padding.left, top = Css.SheetSect.padding.top,
            end = Css.SheetSect.padding.right, bottom = Css.SheetSect.padding.bottom,
        ),
        style = CluetoothTheme.type.small,
        color = CluetoothTheme.colors.t3,
    )
}

@Composable
private fun RawTable(d: DeviceDetail) {
    val c = CluetoothTheme.colors
    val shape = RoundedCornerShape(Css.Raw.borderRadius)
    val style = CluetoothTheme.type.sub.copy(fontSize = Css.Raw.fontSize.sp, lineHeight = Css.Raw.lineHeight.sp)
    Column(
        Modifier
            .padding(horizontal = Css.Raw.margin.left)
            .fillMaxWidth()
            .clip(shape)
            .border(Css.Raw.borderLengths[0], c.div, shape),
    ) {
        RawRow(Strings.Len, Strings.Type, Strings.Data, style.copy(fontWeight = FontWeight(Css.Raw_Th.fontWeight.toInt())), header = true)
        for (s in d.structures) {
            RawRow(s.len.toString(), Format.adType(s.adType), Format.hex(s.data), style, header = false)
        }
        d.tail?.let { RawRow("", "", Format.hex(it), style, header = false) }
    }
}

@Composable
private fun RawRow(len: String, type: String, data: String, style: androidx.compose.ui.text.TextStyle, header: Boolean) {
    val c = CluetoothTheme.colors
    val pad = Css.Raw_Td.padding
    Column {
        if (!header) Box(Modifier.fillMaxWidth().height(Css.Raw_Td.borderTopLengths[0]).background(c.div))
        Row(Modifier.fillMaxWidth().background(if (header) c.fill2 else c.panel)) {
            Text(len, Modifier.width(Css.Raw_ColLen.width).padding(horizontal = pad.left, vertical = pad.top), style = style.tabular())
            Text(type, Modifier.width(Css.Raw_ColType.width).padding(horizontal = pad.left, vertical = pad.top), style = style.tabular())
            // G-3: the raw data column is the one place with the code font; it breaks anywhere
            val dataStyle = if (header) style else CluetoothTheme.type.code.copy(fontSize = style.fontSize, lineHeight = style.lineHeight)
            Text(data.breakAnywhere(), Modifier.weight(1f).padding(horizontal = pad.left, vertical = pad.top), style = dataStyle, color = if (header) c.t1 else c.t2)
        }
    }
}

/** Zero-width spaces between characters let hex wrap anywhere (`word-break: break-all`). */
private fun String.breakAnywhere(): String = toCharArray().joinToString("​")

/** D-13.1: "Only connect to devices you own…", above the button area whenever a button is present. */
@Composable
private fun GattNote(modifier: Modifier = Modifier) {
    Text(
        Strings.GattNote,
        modifier.padding(bottom = Css.GattNote.margin.bottom).testTag(DeviceTags.NOTE),
        style = CluetoothTheme.type.sub,
        color = CluetoothTheme.colors.t2,
    )
}

/** D-13: the note and buttons, the steps, Try again, the services. */
@Composable
internal fun GattTab(gatt: GattController) {
    val c = CluetoothTheme.colors
    val pad = Css.GattFlow.padding
    Column(Modifier.fillMaxWidth().padding(horizontal = pad.left, vertical = pad.top)) {
        when (val s = gatt.state) {
            GattUi.Idle, GattUi.Confirm -> {
                GattNote()
                val confirm = s == GattUi.Confirm
                // D-13.3: the row turns into Cancel | I am authorized in place: Cancel grows from zero width to
                // half the row and fades in, with the gap opening to 8 dp; nothing else moves.
                val m = CluetoothTheme.motion
                val grow by animateFloatAsState(if (confirm) 1f else 0f, m.std(), label = "cancelGrow")
                val gap by animateDpAsState(if (confirm) Css.GattFlowConfirm_GattCtl.gap else Css.GattCtl.gap, m.std(), label = "gap")
                Row(Modifier.fillMaxWidth()) {
                    if (grow > 0f) {
                        Button(
                            Strings.Cancel, onClick = gatt::onCancelPrompt, kind = ButtonKind.Tonal, enabled = confirm,
                            modifier = Modifier.weight(grow).alpha(grow).testTag(DeviceTags.CANCEL),
                        )
                        Spacer(Modifier.width(gap))
                    }
                    Button(
                        if (confirm) Strings.IAmAuthorized else Strings.DiscoverGatt,
                        onClick = if (confirm) gatt::onAuthorized else gatt::onDiscover,
                        modifier = Modifier.weight(1f).testTag(DeviceTags.DISCOVER),
                    )
                }
            }
            is GattUi.Running -> Steps(s.view)
            is GattUi.Failed -> {
                Steps(s.view)
                GattNote(Modifier.padding(top = Css.GattNote.margin.bottom)) // D-13.1: with any button
                Button(Strings.TryAgain, onClick = gatt::onTryAgain, kind = ButtonKind.Tonal, modifier = Modifier.testTag(DeviceTags.TRY_AGAIN))
            }
            is GattUi.Done -> {
                Steps(s.view)
                Services(s.view)
            }
        }
    }
}

@Composable
private fun Steps(view: GattView) {
    Column {
        for (step in view.steps) StepRow(step)
    }
}

@Composable
private fun StepRow(step: GattStep) {
    val c = CluetoothTheme.colors
    val failed = step.state == GattStepState.Failed
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = Css.Gstep.minHeight).testTag(DeviceTags.step(step.kind)),
        horizontalArrangement = Arrangement.spacedBy(Css.Gstep.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepMark(step.state)
        val style = CluetoothTheme.type.body.copy(fontSize = Css.Gstep.fontSize.sp)
        Text(step.label(), Modifier.weight(1f), style = style)
        Text(step.detail(), style = CluetoothTheme.type.sub.tabular().copy(fontSize = Css.Gdetail.fontSize.sp), color = if (failed) c.rssiWeak else c.t2)
    }
}

@Composable
private fun StepMark(state: GattStepState) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    Box(Modifier.size(Css.Gmark.width), contentAlignment = Alignment.Center) {
        when (state) {
            GattStepState.Running -> {
                val angle = if (m.reduced) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
                    0f, 360f, infiniteRepeatable(tween(m.spinMs, easing = LinearEasing), RepeatMode.Restart), label = "spin",
                ).value
                Canvas(Modifier.size(Css.Gmark.width).rotate(angle)) { // 2 dp `--div` ring with a `--blue` top
                    val w = 2 * density
                    drawCircle(c.div, (size.minDimension - w) / 2, style = Stroke(w))
                    drawArc(c.blue, -135f, 90f, false, Offset(w / 2, w / 2), size.copy(size.width - w, size.height - w), style = Stroke(w))
                }
            }
            GattStepState.Done -> Box(Modifier.size(Css.Gmark.width).clip(CircleShape).background(c.rssiStrong))
            GattStepState.Failed -> Box(Modifier.size(Css.Gmark.width).clip(CircleShape).background(c.rssiWeak))
        }
    }
}

private fun GattStep.label() = when (kind) {
    GattStepKind.Connecting -> Strings.Connecting
    GattStepKind.DiscoveringServices -> Strings.DiscoveringServices
    GattStepKind.ReadingCharacteristics -> Strings.ReadingCharacteristics
    GattStepKind.Disconnected -> Strings.Disconnected
}

/** D-13.4/5: "0.8 s", "N services", "m/n", or the failure reason. */
private fun GattStep.detail(): String {
    if (state == GattStepState.Failed) return when (val f = failure) {
        GattFailure.TimedOut -> Strings.TimedOut
        GattFailure.DisconnectedUnexpectedly -> Strings.DisconnectedUnexpectedly
        GattFailure.ConnectionFailed -> Strings.ConnectionFailed
        GattFailure.Cancelled -> Strings.Cancelled
        is GattFailure.Other -> Strings.gattFailedStatus(f.status)
        null -> Strings.Failed
    }
    return when (kind) {
        GattStepKind.Connecting -> if (state == GattStepState.Done) durationMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "" else ""
        GattStepKind.DiscoveringServices -> if (state == GattStepState.Done) services?.let(Strings::services) ?: "" else ""
        GattStepKind.ReadingCharacteristics -> {
            val n = readable ?: return ""
            Strings.ofCount(if (state == GattStepState.Done) n else read ?: 0, n)
        }
        GattStepKind.Disconnected -> ""
    }
}

/** D-13.6: the services, then their characteristics (name, UUID · properties, value). */
@Composable
private fun Services(view: GattView) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    Column(Modifier.padding(top = Css.GattSvc.padding.top)) {
        for (s in view.services) {
            Column(Modifier.padding(vertical = Css.GattSvc.padding.top)) {
                Text(s.name ?: s.uuid, style = t.body.copy(fontWeight = FontWeight(Css.GattSvc_B.fontWeight.toInt())))
                Text(s.uuid, style = t.sub, color = c.t2)
                for (ch in s.characteristics) {
                    val style = t.body.copy(fontSize = Css.GattChar.fontSize.sp, lineHeight = Css.GattChar.lineHeight.sp)
                    Column(Modifier.padding(start = Css.GattChar.padding.left, top = Css.GattChar.padding.top, bottom = Css.GattChar.padding.bottom)) {
                        Text(ch.name ?: ch.uuid, style = style)
                        Text(listOf(ch.uuid, ch.properties.joinToString(", ")).joinToString(" · "), style = style, color = c.t2)
                        ch.valueText?.let { Text(it, style = style, color = c.t2) }
                    }
                }
            }
        }
    }
}

private const val CHART_LEFT = 30f   // the prototype chart's x0 (the tick labels' column)
private const val CHART_RIGHT = 6f
private const val CHART_PAD = 4f
private const val CHART_AXIS = 20f   // 96 − 76: the plot is 76 of the 96 dp
private const val CHART_MARKER = 4.5f
