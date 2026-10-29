package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** CSS px are dp; text sizes become sp so they follow the user's font scale. */
val Dp.sp: TextUnit get() = value.sp

/** The weights the prototype uses: 400, `--weight-medium`, `--weight-semi` and `--title-weight` (650). */
object Weights {
    val Regular = FontWeight.Normal
    val Medium = FontWeight(Tokens.weightMedium.toInt())
    val Semi = FontWeight(Tokens.weightSemi.toInt())
    val Title = FontWeight(Tokens.titleWeight.toInt())
    internal val all = listOf(Regular, Medium, Semi, Title)
}

/**
 * Inter, bundled as the variable font (wght and opsz axes). Browsers set the optical size to the font size
 * (`font-optical-sizing: auto`), so each text size gets its own family with `opsz` = size, clamped to Inter's 14–32.
 * Below API 26 the axes are ignored and the default instance is used.
 */
@OptIn(ExperimentalTextApi::class)
object Inter {
    private val families = HashMap<Float, FontFamily>()

    fun family(sizeDp: Float): FontFamily = synchronized(families) {
        val opsz = sizeDp.coerceIn(14f, 32f)
        families.getOrPut(opsz) {
            FontFamily(
                Weights.all.map { w ->
                    Font(
                        R.font.inter_variable,
                        weight = w,
                        variationSettings = FontVariation.Settings(FontVariation.weight(w.weight), FontVariation.Setting("opsz", opsz)),
                    )
                },
            )
        }
    }
}

/** CSS line boxes center the glyphs (half-leading above and below) and never add font padding. */
private val cssLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

private fun style(size: Dp, line: Dp, weight: FontWeight = Weights.Regular, tracking: Float = 0f) = TextStyle(
    fontFamily = Inter.family(size.value),
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    lineHeightStyle = cssLine,
    platformStyle = noFontPadding,
)

/** G-3 text styles. Numbers (RSSI, times, counts, MACs) use [tabular]. */
@Immutable
class CluetoothType(
    /** Body 15/24. */
    val body: TextStyle,
    /** Titles 17/650, −0.01em (page titles, sheet headers, category heads); the body line height. */
    val title: TextStyle,
    /** The product heading 24/30 at 650. */
    val head: TextStyle,
    /** Sub text 13/20 (MACs, status lines). */
    val sub: TextStyle,
    /** Small text 12.5/18 (section labels, the onboarding step). */
    val small: TextStyle,
    /** 14 sp controls: chips, segmented options, search text, buttons when small, the empty text. */
    val control: TextStyle,
    /** Code (`--font-code`), only for the raw advertisement data column (D-12). */
    val code: TextStyle,
) {
    companion object {
        val Default = CluetoothType(
            body = style(Tokens.bodySize, Tokens.bodyLine),
            title = style(Tokens.titleSize, Tokens.bodyLine, Weights.Title, Css.PageTitle.letterSpacingEm),
            head = style(Tokens.headSize, Tokens.headLine, Weights.Title, Css.ProdName.letterSpacingEm),
            sub = style(Tokens.subSize, Tokens.subLine),
            small = style(Tokens.smallSize, Tokens.smallLine),
            control = style(Css.Chip.fontSize, Css.LocPop_P.lineHeight),
            code = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = Tokens.codeSize.sp,
                lineHeight = Css.Raw.lineHeight.sp,
                lineHeightStyle = cssLine,
                platformStyle = noFontPadding,
            ),
        )
    }
}

/** `font-variant-numeric: tabular-nums`. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
