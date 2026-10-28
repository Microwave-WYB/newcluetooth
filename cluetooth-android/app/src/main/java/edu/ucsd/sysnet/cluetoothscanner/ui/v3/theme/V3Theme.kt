package edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ResourceTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Texts

/** The one thing Material 3 has no slot for: the three signal-strength bands (> -65, -65..-80, < -80). */
@Immutable
class SignalColors(val strong: Color, val fair: Color, val weak: Color)

private val LightSignal = SignalColors(strong = Color(0xFF1E8E4E), fair = Color(0xFFB07900), weak = Color(0xFFC62828))
private val DarkSignal = SignalColors(strong = Color(0xFF5BC98A), fair = Color(0xFFE2B53E), weak = Color(0xFFFF7A70))

private val LocalSignalColors = compositionLocalOf { LightSignal }

/** Previews set this so infinite animations (pulse, blink) render as one stable frame. */
val LocalStaticRender = compositionLocalOf { false }

/** Words and dates for composables that format text from plain values; set by [V3Theme]. */
val LocalTexts = compositionLocalOf<Texts> { error("LocalTexts is provided by V3Theme") }

val MaterialTheme.signal: SignalColors
    @Composable @ReadOnlyComposable get() = LocalSignalColors.current

/** Tabular digits for numbers that change in place (RSSI, elapsed time), so they do not jitter. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * The stock Material 3 theme (default color scheme, typography and shapes), light or dark with the
 * system, plus the signal band colors.
 */
@Composable
fun V3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val texts = remember(context) { ResourceTexts(context) }
    CompositionLocalProvider(LocalSignalColors provides if (darkTheme) DarkSignal else LightSignal, LocalTexts provides texts) {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
    }
}
