package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.Signal
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.signalOf
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra

@Composable
fun Signal.color(): Color = when (this) {
    Signal.Strong -> MaterialTheme.extra.signalGreen
    Signal.Fair -> MaterialTheme.extra.signalYellow
    Signal.Weak -> MaterialTheme.extra.signalRed
}

@Composable
fun signalColor(rssi: Int): Color = signalOf(rssi).color()

/** Small filled dot in the signal color. */
@Composable
fun SignalDot(rssi: Int, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(signalColor(rssi)))
}

/** RSSI number in the signal color, monospace. */
@Composable
fun RssiText(rssi: Int, modifier: Modifier = Modifier, size: Int = 15, textAlign: TextAlign? = null) {
    Text(
        text = rssi.toString(),
        modifier = modifier,
        textAlign = textAlign,
        color = signalColor(rssi),
        fontFamily = MonoFamily,
        fontSize = size.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
    )
}

/** About 56x20 dp: gray line, last point colored by signal. */
@Composable
fun Sparkline(history: List<Int>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val last = history.lastOrNull()
    val lastColor = if (last != null) signalColor(last) else line
    Canvas(modifier.size(width = 56.dp, height = 20.dp)) {
        if (history.size < 2) return@Canvas
        val pad = 3.dp.toPx()
        val lo = -100f
        val hi = -35f
        fun pt(i: Int, v: Int) = Offset(
            pad + (size.width - 2 * pad) * i / (history.size - 1),
            pad + (size.height - 2 * pad) * (1f - (v.coerceIn(-100, -35) - lo) / (hi - lo)),
        )
        val path = Path()
        history.forEachIndexed { i, v -> pt(i, v).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, line, style = Stroke(1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lastColor, 2.5.dp.toPx(), pt(history.lastIndex, history.last()))
    }
}

/**
 * The larger detail chart. Faint bands mark the -65 and -80 thresholds; the line is the
 * primary color with the last point in the signal color.
 */
@Composable
fun RssiChart(
    samples: List<Int>,
    window: ChartWindow,
    onWindowChange: (ChartWindow) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp,
) {
    val extra = MaterialTheme.extra
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信号强度", style = MaterialTheme.typography.labelMedium, color = label, modifier = Modifier.weight(1f))
            ChartWindow.entries.forEach { w ->
                val selected = w == window
                Text(
                    text = w.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else label,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onWindowChange(w) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                val lo = -100f
                val hi = -40f
                fun y(v: Number) = size.height * (1f - (v.toFloat().coerceIn(lo, hi) - lo) / (hi - lo))
                val strongTop = y(hi)
                val y65 = y(-65)
                val y80 = y(-80)
                drawRect(extra.signalGreen.copy(alpha = 0.07f), Offset(0f, strongTop), androidx.compose.ui.geometry.Size(size.width, y65 - strongTop))
                drawRect(extra.signalYellow.copy(alpha = 0.07f), Offset(0f, y65), androidx.compose.ui.geometry.Size(size.width, y80 - y65))
                drawRect(extra.signalRed.copy(alpha = 0.07f), Offset(0f, y80), androidx.compose.ui.geometry.Size(size.width, size.height - y80))
                drawLine(grid, Offset(0f, y65), Offset(size.width, y65), 1.dp.toPx())
                drawLine(grid, Offset(0f, y80), Offset(size.width, y80), 1.dp.toPx())
                if (samples.size >= 2) {
                    val path = Path()
                    samples.forEachIndexed { i, v ->
                        val x = size.width * i / (samples.size - 1)
                        if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                    }
                    drawPath(path, line, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val lastColor = when (signalOf(samples.last())) {
                        Signal.Strong -> extra.signalGreen
                        Signal.Fair -> extra.signalYellow
                        Signal.Weak -> extra.signalRed
                    }
                    drawCircle(lastColor, 4.dp.toPx(), Offset(size.width, y(samples.last())))
                }
            }
            Text("-65", fontFamily = MonoFamily, fontSize = 10.sp, color = label, modifier = Modifier.align(Alignment.TopStart).padding(top = chartHeight * (25f / 60f) - 14.dp))
            Text("-80", fontFamily = MonoFamily, fontSize = 10.sp, color = label, modifier = Modifier.align(Alignment.TopStart).padding(top = chartHeight * (40f / 60f) - 14.dp))
        }
    }
}
