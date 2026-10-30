package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * G-13a: an element inside a rounded container's corner region has the same inset on its top and sides, and its
 * radius is the container radius minus that inset. Every inner radius of the UI is one of these.
 */
object Concentric {
    /** The radius of an element inset [inset] inside a container of radius [outer]. */
    fun inner(outer: Dp, inset: Dp): Dp = outer - inset

    /** The radius of a container whose corner element of radius [elementRadius] sits [inset] in. */
    fun outer(elementRadius: Dp, inset: Dp): Dp = elementRadius + inset

    /** `--sheet-radius` = `--edge` + `--rb` / 2 (S-2): the phone sheet's top corners. */
    val sheetRadius: Dp get() = Tokens.sheetRadius

    /** `--panel-radius` = `--panel-inset` + `--rb` / 2 (T-1): the tablet panel. */
    val panelRadius: Dp get() = Tokens.panelRadius

    /** Tablet panel rows: 30 − 8 = 22 (T-3). */
    val panelRow: Dp get() = inner(Tokens.panelRadius, Tokens.panelPad)

    /** The sheet (phone) or panel (tablet) radius. */
    fun container(tablet: Boolean): Dp = if (tablet) Tokens.panelRadius else Tokens.sheetRadius

    /** The sheet body's own inset: 0 on phone, `--panel-pad` on tablet. */
    fun contentInset(tablet: Boolean): Dp = if (tablet) Tokens.panelPad else Dp(0f)

    /** C-7 `--card-r`: the card row is inset `--gutter` from the body (phone 34 − 16 = 18; tablet 30 − 8 − 16 = 6). */
    fun card(tablet: Boolean): Dp = inner(container(tablet), contentInset(tablet) + Tokens.gutter)

    /** D-7 `--hero-r`: the hero is inset `--edge` (phone 34 − 12 = 22; tablet 30 − 8 − 12 = 10). */
    fun hero(tablet: Boolean): Dp = inner(container(tablet), contentInset(tablet) + Tokens.edge)

    /** R-4: the recorder panel is concentric with its Stop button: phone 18 + 4 = 22, tablet 20 + 8 = 28. */
    fun recorderPanel(tablet: Boolean): Dp = if (tablet) Tokens.recPanelRTablet else Tokens.recPanelR

    /** The Stop button's inset from the panel's corner: phone 22 − 36 / 2 = 4, tablet 28 − 40 / 2 = 8. */
    fun recorderButtonInset(tablet: Boolean): Dp =
        recorderPanel(tablet) - (if (tablet) Tokens.iconBtn else Tokens.islandIb) / 2

    /** P-5 `--pop-r`: the location popover is concentric with its small pill button (32 / 2 + 12 = 28). */
    val popover: Dp get() = Tokens.popR

    /** S-2: the corner buttons' center depth and inset from the sheet's side; their box starts this far in. */
    fun cornerButton(tablet: Boolean): Dp = container(tablet) - Tokens.sheetHeaderH / 2
}
