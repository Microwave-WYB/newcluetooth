package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.stringResource
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import edu.ucsd.sysnet.cluetoothscanner.R
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.offset
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiBuckets
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.Signal
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.signalOf
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.TabularNumbers
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.signal

@Composable
fun Signal.color(): Color = when (this) {
    Signal.Strong -> MaterialTheme.signal.strong
    Signal.Fair -> MaterialTheme.signal.fair
    Signal.Weak -> MaterialTheme.signal.weak
}

@Composable
fun signalColor(rssi: Int): Color = signalOf(rssi).color()

/** Small filled dot in the signal color. */
@Composable
fun SignalDot(rssi: Int, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(signalColor(rssi)))
}

/** RSSI number in the signal color, body font with tabular digits. */
@Composable
fun RssiText(rssi: Int, modifier: Modifier = Modifier, textAlign: TextAlign? = TextAlign.End) {
    Text(
        text = rssi.toString(),
        modifier = modifier,
        textAlign = textAlign,
        color = signalColor(rssi),
        style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
    )
}

/**
 * Draws bucketed RSSI the one way for the sparkline and the chart: consecutive buckets with data are
 * joined (the line breaks only across a long gap, [RssiBuckets.MAX_GAP_BUCKETS]), a lone bucket is a
 * small dot in the line color, and the single end marker is the current reading at now, in its band's color.
 *
 * Bucket `i` of `n` sits at `(i + 1) / n` of the plot width, so the last bucket (the one that
 * contains now) is at the right edge and x only ever increases with time.
 */
private fun DrawScope.drawBuckets(
    buckets: List<Int?>,
    current: Int?,
    xOf: (Int) -> Float,
    yOf: (Int) -> Float,
    line: Color,
    strokeWidth: Float,
    dotRadius: Float,
    endRadius: Float,
    endColor: Color,
) {
    val plan = RssiBuckets.plan(buckets, current)
    plan.lines.forEach { run ->
        val path = Path()
        run.forEachIndexed { k, p -> if (k == 0) path.moveTo(xOf(p.index), yOf(p.rssi)) else path.lineTo(xOf(p.index), yOf(p.rssi)) }
        drawPath(path, line, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    plan.dots.forEach { drawCircle(line, dotRadius, Offset(xOf(it.index), yOf(it.rssi))) }
    plan.end?.let { drawCircle(endColor, endRadius, Offset(xOf(it.index), yOf(it.rssi))) }
}

/** About 56x20 dp: the last 60 s in 2 s buckets, gray line, the last bucket colored by its band. */
@Composable
fun Sparkline(history: List<Int?>, current: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val endColor = signalColor(current)
    Canvas(modifier.size(width = 56.dp, height = 20.dp)) {
        val pad = 3.dp.toPx()
        val lo = -110f
        val hi = -35f
        val n = history.size
        drawBuckets(
            history, current,
            xOf = { i -> pad + (size.width - 2 * pad) * (i + 1) / n },
            yOf = { v -> pad + (size.height - 2 * pad) * (1f - (v.coerceIn(-110, -35) - lo) / (hi - lo)) },
            line = line, strokeWidth = 1.25.dp.toPx(), dotRadius = 1.5.dp.toPx(), endRadius = 2.5.dp.toPx(), endColor = endColor,
        )
    }
}

/** The chart's fixed value range (readings go below -100, so the floor is -110); -65 and -80 are the band edges. */
private const val CHART_LO = -110f
private const val CHART_HI = -30f

private fun chartFraction(rssi: Float) = (CHART_HI - rssi) / (CHART_HI - CHART_LO)

/**
 * The detail chart: always the last minute, ending at now on the right edge ("−1 min … now"), labelled on
 * the left, 60 buckets of the strongest RSSI, faint -65 / -80 lines with labels, and one end marker.
 * A device heard for less than a tenth of the minute gets a "Heard for ..." caption.
 */
@Composable
fun RssiChart(
    samples: List<Int?>,
    /** The reading shown as the big number; the end marker is this value at now. */
    current: Int,
    heardSeconds: Int?,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp,
) {
    val bands = MaterialTheme.signal
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val endColor = signalColor(current)
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                // Points at the range edges are drawn whole: the line lives inside a small vertical inset.
                val inset = 5.dp.toPx()
                fun y(v: Float) = inset + (size.height - 2 * inset) * chartFraction(v.coerceIn(CHART_LO, CHART_HI))
                val y65 = y(-65f)
                val y80 = y(-80f)
                // Everything is drawn inside the plot area.
                clipRect {
                    drawRect(bands.strong.copy(alpha = 0.08f), Offset(0f, 0f), Size(size.width, y65))
                    drawRect(bands.fair.copy(alpha = 0.08f), Offset(0f, y65), Size(size.width, y80 - y65))
                    drawRect(bands.weak.copy(alpha = 0.08f), Offset(0f, y80), Size(size.width, size.height - y80))
                    drawLine(grid, Offset(0f, y65), Offset(size.width, y65), 1.dp.toPx())
                    drawLine(grid, Offset(0f, y80), Offset(size.width, y80), 1.dp.toPx())
                    val pad = 5.dp.toPx() // room for the end marker at the right edge
                    val n = samples.size
                    drawBuckets(
                        samples, current,
                        xOf = { i -> pad + (size.width - 2 * pad) * (i + 1) / n },
                        yOf = { v -> y(v.toFloat()) },
                        line = line, strokeWidth = 1.75.dp.toPx(), dotRadius = 2.25.dp.toPx(), endRadius = 4.dp.toPx(), endColor = endColor,
                    )
                }
            }
            Text("-65", style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp).offset(y = chartHeight * chartFraction(-65f) - 16.dp))
            Text("-80", style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp).offset(y = chartHeight * chartFraction(-80f) - 16.dp))
            // What the bands mean, at the right edge just inside the top of each band.
            Text(stringResource(R.string.chart_band_close), style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = 2.dp))
            Text(stringResource(R.string.chart_band_nearby), style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = chartHeight * chartFraction(-65f) + 2.dp))
            Text(stringResource(R.string.chart_band_far), style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = chartHeight * chartFraction(-80f) + 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chart_axis_start), style = MaterialTheme.typography.labelSmall, color = label)
            if (RssiBuckets.isShortLived(heardSeconds, RssiBuckets.CHART_WINDOW_SEC)) {
                Text(
                    stringResource(R.string.chart_heard_for, formatDuration(heardSeconds ?: 0, LocalTexts.current)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = label,
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(Modifier.weight(1f))
            }
            Text(stringResource(R.string.chart_axis_now), style = MaterialTheme.typography.labelSmall, color = label)
        }
    }
}
