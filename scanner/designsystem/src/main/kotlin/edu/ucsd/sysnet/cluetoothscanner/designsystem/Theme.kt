package edu.ucsd.sysnet.cluetoothscanner.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.ColorTokens
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.ShadowTokens
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

internal val LocalColors = staticCompositionLocalOf { Tokens.lightColors }
internal val LocalShadows = staticCompositionLocalOf { Tokens.lightShadows }
internal val LocalType = staticCompositionLocalOf { CluetoothType.Default }
internal val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }
internal val LocalDark = staticCompositionLocalOf { false }

/** The content color for [Text] and [Icon] when they get none: `--t1` under the theme. */
val LocalContentColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * The Cluetooth v2 theme (G-1): light or dark colors and shadows from tokens.css, Inter type (G-3), and the motion
 * tokens (G-7). Reduced motion follows the system animator scale unless given.
 */
@Composable
fun CluetoothTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = systemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Tokens.darkColors else Tokens.lightColors
    // 44 dp touch targets (C-2, R-3): Compose extends the hit area of any smaller clickable to this size.
    val baseConfig = LocalViewConfiguration.current
    val viewConfig = remember(baseConfig) { TouchTargetViewConfiguration(baseConfig) }
    val selection = remember(colors) { TextSelectionColors(handleColor = colors.blue, backgroundColor = colors.blue.copy(alpha = 0.3f)) }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalShadows provides if (darkTheme) Tokens.darkShadows else Tokens.lightShadows,
        LocalType provides CluetoothType.Default,
        LocalMotion provides Motion(reducedMotion),
        LocalDark provides darkTheme,
        LocalContentColor provides colors.t1,
        LocalTextSelectionColors provides selection,
        LocalViewConfiguration provides viewConfig,
        content = content,
    )
}

/** Theme accessors, `CluetoothTheme.colors.fill2`. */
object CluetoothTheme {
    val colors: ColorTokens @Composable @ReadOnlyComposable get() = LocalColors.current
    val shadows: ShadowTokens @Composable @ReadOnlyComposable get() = LocalShadows.current
    val type: CluetoothType @Composable @ReadOnlyComposable get() = LocalType.current
    val motion: Motion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalDark.current
}

/** G-7: reduced motion is the system's "Remove animations" (animator duration scale 0). */
@Composable
fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The platform configuration with `--rb` (44 dp) as the minimum touch target. */
internal class TouchTargetViewConfiguration(base: ViewConfiguration) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize(Tokens.rb, Tokens.rb)
}
