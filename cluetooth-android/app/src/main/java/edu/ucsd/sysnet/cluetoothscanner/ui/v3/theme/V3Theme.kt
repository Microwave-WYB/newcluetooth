package edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ResourceTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Texts
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colors that have no Material 3 slot: signal strength, record red and the map placeholder. */
@Immutable
class ExtraColors(
    val signalGreen: Color,
    val signalYellow: Color,
    val signalRed: Color,
    val record: Color,
    val mapBackground: Color,
    val mapRoad: Color,
    val mapRoadCasing: Color,
    val mapBlock: Color,
)

private val LightExtra = ExtraColors(
    signalGreen = Color(0xFF1E9E55),
    signalYellow = Color(0xFFC98A00),
    signalRed = Color(0xFFD93A35),
    record = Color(0xFFE5392F),
    mapBackground = Color(0xFFE9EAEC),
    mapRoad = Color(0xFFFFFFFF),
    mapRoadCasing = Color(0xFFD5D7DB),
    mapBlock = Color(0xFFDFE1E4),
)

private val DarkExtra = ExtraColors(
    signalGreen = Color(0xFF4CC77F),
    signalYellow = Color(0xFFE8B93A),
    signalRed = Color(0xFFFF6B66),
    record = Color(0xFFFF4D43),
    mapBackground = Color(0xFF26272A),
    mapRoad = Color(0xFF3A3B3F),
    mapRoadCasing = Color(0xFF1F2022),
    mapBlock = Color(0xFF2D2E31),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0064D4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3EEFB),
    onPrimaryContainer = Color(0xFF00346B),
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1B1E),
    onSurfaceVariant = Color(0xFF62656B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF1F2F3),
    surfaceContainerHigh = Color(0xFFEBECEE),
    surfaceContainerHighest = Color(0xFFE4E5E8),
    outline = Color(0xFF9A9DA3),
    outlineVariant = Color(0xFFE4E5E8),
    error = Color(0xFFD93A35),
    inverseSurface = Color(0xFF2E2F32),
    inverseOnSurface = Color(0xFFF1F2F3),
    inversePrimary = Color(0xFF7DB8FF),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3B9BFF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF16314F),
    onPrimaryContainer = Color(0xFFD3E6FF),
    background = Color(0xFF181819),
    onBackground = Color(0xFFE8E9EB),
    surface = Color(0xFF181819),
    onSurface = Color(0xFFE8E9EB),
    onSurfaceVariant = Color(0xFF9A9DA3),
    surfaceContainerLowest = Color(0xFF131314),
    surfaceContainerLow = Color(0xFF1D1D1F),
    surfaceContainer = Color(0xFF222224),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF333437),
    outline = Color(0xFF6C6F75),
    outlineVariant = Color(0xFF2E2F32),
    error = Color(0xFFFF6B66),
    inverseSurface = Color(0xFFE8E9EB),
    inverseOnSurface = Color(0xFF2E2F32),
    inversePrimary = Color(0xFF0064D4),
    scrim = Color(0xFF000000),
)

private val V3Typography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** Monospace numerals for RSSI, MAC addresses, timers and hex. */
val MonoFamily: FontFamily = FontFamily.Monospace

private val LocalExtraColors = compositionLocalOf { LightExtra }

/** Previews set this so infinite animations (pulse, blink) render as one stable frame. */
val LocalStaticRender = compositionLocalOf { false }

/** Words and dates for composables that format text from plain values; set by [V3Theme]. */
val LocalTexts = compositionLocalOf<Texts> { error("LocalTexts is provided by V3Theme") }

val MaterialTheme.extra: ExtraColors
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current

/** A fixed palette, not dynamic color; follows the system light/dark setting. */
@Composable
fun V3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme
    val context = LocalContext.current
    val texts = remember(context) { ResourceTexts(context) }
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra, LocalTexts provides texts) {
        MaterialTheme(colorScheme = scheme, typography = V3Typography, content = content)
    }
}
