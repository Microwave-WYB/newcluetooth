package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.SpecValues
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** docs/v2-api.md §5.4: the recorder's UI state (R-1: one control in three forms); the panel shows [discovery] (R-4a). */
data class RecorderUi(
    val form: Form,
    val paused: Boolean,
    val elapsedMs: Long,
    val discovery: DiscoveryBins = DiscoveryBins.Empty,
) {
    enum class Form { Fab, Capsule, Panel }
}

/**
 * Where the recorder is and how its row is laid out, in dp within the screen (R-2 … R-4, R-9, T-7). Positions are
 * the top-left of the recorder's box. The row (dot, time, Pause, Stop) is [rowHeight] tall at [rowTop] inside it;
 * the dot-and-time group starts at [groupX] and Stop's right edge is [buttonsInset] from the box's right edge.
 */
data class RecorderGeometry(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
    val radius: Dp,
    val rowTop: Dp,
    val rowHeight: Dp,
    val groupX: Dp,
    val buttonsInset: Dp,
    val buttonSize: Dp,
    val buttonGap: Dp,
    /** The panel's extra content (the discovery-rate chart, R-4a) sits below the row (phone) or above it (tablet). */
    val extrasBelowRow: Boolean,
) {
    val right: Dp get() = x + width
    val bottom: Dp get() = y + height

    companion object {
        private val CapsuleSlack = SpecValues.recorderCapsuleSlack

        /** The width of the Pause–Stop pair. */
        fun buttonsWidth(tablet: Boolean): Dp =
            if (tablet) Tokens.iconBtn * 2 + Tokens.recGapTablet else Tokens.islandIb * 2 + Tokens.recGapPhone

        /**
         * The panel's height, from its content: the row and the discovery-rate chart (R-4a), which takes the place
         * and the padding of the old meta line (`.rec-meta`). On phone the panel ends `--edge` below the chart.
         */
        fun panelHeight(tablet: Boolean): Dp = if (tablet) {
            chartTop(true) + SpecValues.recorderChartHeight + Tokens.fab
        } else {
            Tokens.islandH + chartTop(false) + SpecValues.recorderChartHeight + Tokens.edge
        }

        /** The chart's top padding within the panel's extras: `.rec-meta`'s 14 on tablet, `.recorder.top`'s 0 on phone. */
        fun chartTop(tablet: Boolean): Dp = if (tablet) Css.RecMeta.padding.top else Css.RecorderTop_RecMeta.paddingTop

        /**
         * @param screenWidth, screenHeight the screen (the map's box), dp
         * @param groupWidth the measured dot-and-time group (dot, 8 dp, the time text)
         * @param bottomCover phone: what covers the screen bottom under the FAB (the collapsed sheet's peek plus the
         *   navigation bar); the FAB sits `--edge` + 12 dp above it (P-3)
         * @param searching phone search state: the island sits below the search bar (R-9)
         */
        fun compute(
            form: RecorderUi.Form,
            tablet: Boolean,
            screenWidth: Dp,
            screenHeight: Dp,
            groupWidth: Dp,
            bottomCover: Dp = Dp(0f),
            searching: Boolean = false,
        ): RecorderGeometry {
            val edge = Tokens.edge
            val fab = Tokens.fab
            // `.recorder { bottom: calc(var(--rec-lift) + var(--edge) + 12px) }`, the lift being the sheet's cover on phone
            val fabBottom = screenHeight - (if (tablet) Dp(0f) else bottomCover) - Css.PhoneTablet_Recorder.bottom
            val padL = if (tablet) Tokens.recorderPad else Tokens.islandPad
            val gap = if (tablet) Tokens.recGapTablet else Tokens.recGapPhone
            val inset = Concentric.recorderButtonInset(tablet)
            val btn = if (tablet) Tokens.iconBtn else Tokens.islandIb
            val btns = buttonsWidth(tablet)
            val capsuleW = padL + groupWidth + gap + CapsuleSlack + btns + inset
            val base = RecorderGeometry(
                x = screenWidth - edge - fab, y = fabBottom - fab, width = fab, height = fab, radius = fab / 2,
                rowTop = Dp(0f), rowHeight = fab, groupX = padL, buttonsInset = inset, buttonSize = btn, buttonGap = gap,
                extrasBelowRow = !tablet,
            )
            if (form == RecorderUi.Form.Fab) return base
            if (tablet) {
                // T-7: the FAB widens in place into a capsule of FAB height; the panel grows up and to the left
                return when (form) {
                    RecorderUi.Form.Capsule -> base.copy(x = screenWidth - edge - capsuleW, width = capsuleW)
                    else -> {
                        val w = minOf(Tokens.recorderTabletW, screenWidth - Tokens.panelW - edge * 3)
                        val h = panelHeight(true)
                        base.copy(
                            x = screenWidth - edge - w, y = fabBottom - h, width = w, height = h,
                            radius = Concentric.recorderPanel(true), rowTop = h - fab, groupX = (w - groupWidth) / 2,
                        )
                    }
                }
            }
            // R-3 / R-9: the island at the top center, on the ☰/search row or below the search bar
            val top = if (searching) Css.PhoneSearching_RecorderTop.top else edge + (Tokens.rb - Tokens.islandH) / 2
            val island = base.copy(
                x = (screenWidth - capsuleW) / 2, y = top, width = capsuleW, height = Tokens.islandH,
                radius = Tokens.islandH / 2, rowHeight = Tokens.islandH,
            )
            if (form == RecorderUi.Form.Capsule) return island
            // R-4: the panel widens symmetrically to the full width minus 2 × `--edge` and grows downward
            val w = screenWidth - edge * 2
            return island.copy(x = edge, width = w, height = panelHeight(false), radius = Concentric.recorderPanel(false), groupX = (w - groupWidth) / 2)
        }
    }
}
