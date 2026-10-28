package edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.color
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.signalOf
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra

// A stand-in for the MapLibre map: a flat background, a few roads and the overlays. Points
// live in a unit square that is laid out in the part of the map not covered by the sheet.

private data class RoadPoint(val x: Float, val y: Float)

private val majorRoads = listOf(
    listOf(RoadPoint(0f, 0.22f), RoadPoint(0.4f, 0.24f), RoadPoint(1f, 0.20f)),
    listOf(RoadPoint(0f, 0.55f), RoadPoint(0.5f, 0.50f), RoadPoint(1f, 0.58f)),
    listOf(RoadPoint(0.16f, 0f), RoadPoint(0.15f, 0.5f), RoadPoint(0.20f, 1f)),
    listOf(RoadPoint(0.52f, 0f), RoadPoint(0.50f, 0.5f), RoadPoint(0.56f, 1f)),
    listOf(RoadPoint(0.82f, 0f), RoadPoint(0.80f, 0.6f), RoadPoint(0.86f, 1f)),
)

private val minorRoads = listOf(
    listOf(RoadPoint(0f, 0.04f), RoadPoint(1f, 0.05f)),
    listOf(RoadPoint(0f, 0.38f), RoadPoint(1f, 0.40f)),
    listOf(RoadPoint(0f, 0.80f), RoadPoint(1f, 0.78f)),
    listOf(RoadPoint(0.34f, 0f), RoadPoint(0.33f, 1f)),
    listOf(RoadPoint(0.68f, 0f), RoadPoint(0.67f, 1f)),
    listOf(RoadPoint(0f, 0.95f), RoadPoint(0.6f, 0.45f), RoadPoint(1f, 0.30f)),
)

private val blocks = listOf(
    listOf(0.20f, 0.26f, 0.12f, 0.10f), listOf(0.57f, 0.04f, 0.10f, 0.15f),
    listOf(0.37f, 0.42f, 0.12f, 0.06f), listOf(0.60f, 0.62f, 0.18f, 0.14f),
    listOf(0.02f, 0.58f, 0.12f, 0.18f), listOf(0.88f, 0.24f, 0.10f, 0.12f),
)

/**
 * @param bottomCovered fraction of the height hidden behind the sheet; overlays are laid out above it
 */
@Composable
fun PlaceholderMap(
    modifier: Modifier = Modifier,
    bottomCovered: Float = 0.5f,
    route: List<LatLon> = emptyList(),
    points: List<SightingPoint> = emptyList(),
    estimate: LatLon? = null,
    heat: List<LatLon> = emptyList(),
) {
    val extra = MaterialTheme.extra
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    val pointColors = points.map { signalOf(it.rssi).color() }
    Canvas(modifier.fillMaxSize()) {
        drawRect(extra.mapBackground)
        // Unit square -> visible area (below the top controls, above the sheet).
        val top = 120.dp.toPx()
        val bottom = size.height * (1f - bottomCovered) - 28.dp.toPx()
        // The geometry's bounding box fills the visible area (with a margin).
        val every = route + points.map { it.at } + listOfNotNull(estimate) + heat
        val minLat = every.minOfOrNull { it.lat } ?: 0.0
        val maxLat = every.maxOfOrNull { it.lat } ?: 1.0
        val minLon = every.minOfOrNull { it.lon } ?: 0.0
        val maxLon = every.maxOfOrNull { it.lon } ?: 1.0
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-6)
        fun at(p: LatLon): Offset {
            val x = 0.08f + 0.84f * ((p.lon - minLon) / spanLon).toFloat()
            val y = 0.08f + 0.84f * ((maxLat - p.lat) / spanLat).toFloat()
            return Offset(x * size.width, top + y * (bottom - top))
        }
        // Roads span the whole canvas, not just the visible area.
        fun full(p: RoadPoint) = Offset(p.x * size.width, p.y * size.height)

        blocks.forEach { (x, y, w, h) ->
            drawRoundRect(extra.mapBlock, full(RoadPoint(x, y)), Size(w * size.width, h * size.height), CornerRadius(6.dp.toPx()))
        }
        fun road(pts: List<RoadPoint>, width: Float, color: Color) {
            val path = Path()
            pts.forEachIndexed { i, p -> full(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        minorRoads.forEach { road(it, 7.dp.toPx(), extra.mapRoadCasing) }
        minorRoads.forEach { road(it, 5.dp.toPx(), extra.mapRoad) }
        majorRoads.forEach { road(it, 12.dp.toPx(), extra.mapRoadCasing) }
        majorRoads.forEach { road(it, 10.dp.toPx(), extra.mapRoad) }

        heat.forEach { p ->
            val c = at(p)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFF8A00).copy(alpha = 0.13f), Color(0xFFFF8A00).copy(alpha = 0f)),
                    center = c, radius = 26.dp.toPx(),
                ),
                radius = 26.dp.toPx(), center = c,
            )
        }

        if (route.size >= 2) {
            val path = Path()
            route.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, ring, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            marker(at(route.first()), extra.signalGreen, ring)
            marker(at(route.last()), extra.signalRed, ring)
        }

        points.forEachIndexed { i, p ->
            val r = (3f + (p.rssi.coerceIn(-100, -40) + 100) / 60f * 6f).dp.toPx()
            drawCircle(pointColors[i].copy(alpha = 0.85f), r, at(p.at))
            drawCircle(ring, r, at(p.at), style = Stroke(1.dp.toPx()))
        }

        if (estimate != null) {
            val c = at(estimate)
            drawCircle(primary.copy(alpha = 0.14f), 30.dp.toPx(), c)
            drawCircle(primary.copy(alpha = 0.5f), 30.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(ring, 8.dp.toPx(), c)
            drawCircle(primary, 5.5.dp.toPx(), c)
        }
    }
}

private fun DrawScope.marker(c: Offset, fill: Color, ring: Color) {
    drawCircle(ring, 9.dp.toPx(), c)
    drawCircle(fill, 6.dp.toPx(), c)
}

/** The placeholder drawn from a [MapUiState]: the route by default, a selected device's points otherwise. */
@Composable
fun PlaceholderMapFor(state: edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState, coveredFraction: Float) {
    val selecting = state.selected != null
    PlaceholderMap(
        bottomCovered = coveredFraction,
        route = if (selecting || state.mode == edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode.Product) emptyList() else state.route,
        points = if (selecting) state.selectedPoints else state.productPoints,
        estimate = state.estimate,
        heat = if (state.heatmap) state.heat else emptyList(),
    )
}
