package edu.ucsd.sysnet.cluetoothscanner.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapAssets
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTheme
import edu.ucsd.sysnet.cluetoothscanner.map.style.MapTokens

/** Where the locate button and the location warning sit (P-4, P-5, T-8). */
sealed interface LocatePlacement {
    /** Phone: bottom-left, centre level with the FAB's. recLift: the Discover sheet's collapsed peek (or 0). */
    data class Phone(val recLift: Dp) : LocatePlacement
    /** Tablet: bottom-right, 12 dp above the recorder, moving up with its panel. recorderHeight: FAB or panel. */
    data class Tablet(val recorderHeight: Dp) : LocatePlacement
}

/**
 * P-4 / P-5 / T-8: the locate button, or the location warning in its spot. Both live in the FAB's layer (above
 * the map, below every sheet). [MapUiState.showWarning] overrides [MapUiState.showLocate].
 *
 * TEMPORARY look: until :designsystem (C1) merges this draws a plain 44 dp FloatButton from tokens.json without
 * the float shadow, with the prototype's locate / locateOff glyphs; the labels move to :designsystem strings (G-8).
 */
@Composable
fun BoxScope.LocateControl(
    ui: MapUiState,
    placement: LocatePlacement,
    onLocate: () -> Unit,
    onWarning: () -> Unit,
    dark: Boolean = isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    val tokens = remember { MapAssets.tokens(context) }
    val theme = if (dark) MapTheme.Dark else MapTheme.Light
    val edge = tokens.dp("--edge").dp
    val rb = tokens.dp("--rb").dp
    val fab = tokens.dp("--fab").dp
    val position = when (placement) {
        // components.css .locate-btn: left --edge, bottom --rec-lift + --edge + 12 + (--fab − --rb) / 2
        is LocatePlacement.Phone -> Modifier.align(Alignment.BottomStart)
            .padding(start = edge, bottom = placement.recLift + edge + 12.dp + (fab - rb) / 2)
        // .phone.tablet .locate-btn: right --edge + (--fab − --rb) / 2, bottom --edge + 12 + --rec-h + 12
        is LocatePlacement.Tablet -> Modifier.align(Alignment.BottomEnd)
            .padding(end = edge + (fab - rb) / 2, bottom = edge + 12.dp + placement.recorderHeight + 12.dp)
    }
    val fade = tokens.ms("--t-std")
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    AnimatedVisibility(
        visible = ui.showLocate && !ui.showWarning,
        modifier = position,
        enter = fadeIn(tween(fade, easing = ease)),
        exit = fadeOut(tween(fade, easing = ease)),
    ) {
        FloatButtonStub(tokens, theme, LOCATE, Color(tokens.argb("--t1", theme)), "Show my location", onLocate)
    }
    if (ui.showWarning) {
        Box(position) { FloatButtonStub(tokens, theme, LOCATE_OFF, Color(tokens.argb("--warn", theme)), "Location problem", onWarning) }
    }
}

@Composable
private fun FloatButtonStub(tokens: MapTokens, theme: MapTheme, icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    val rb = tokens.dp("--rb").dp
    Box(
        Modifier.size(rb).clip(CircleShape).background(Color(tokens.argb("--fill1", theme)))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
    }
}

/** app.js ICONS (24 grid, 1.8 stroke, round caps and joins); circles written as two arcs. */
private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList()) = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    for (d in strokes) addPath(
        PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    for (d in fills) addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
}.build()

private const val RING = "M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0"
private const val TICKS = "M12 2v3M12 19v3M2 12h3M19 12h3"
private val LOCATE = icon("locate", listOf(RING, TICKS), fills = listOf("M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"))
private val LOCATE_OFF = icon("locateOff", listOf(RING, TICKS, "M4 4l16 16"))
