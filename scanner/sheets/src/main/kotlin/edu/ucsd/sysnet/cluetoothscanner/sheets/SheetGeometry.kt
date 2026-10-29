package edu.ucsd.sysnet.cluetoothscanner.sheets

import kotlin.math.abs
import kotlin.math.max

/**
 * S-5 snap offsets of one phone sheet, in px. An offset is the y of the sheet's top edge on screen, so a drag moves
 * the offset by exactly the finger's movement (S-10a).
 *
 * - Full: just below the top row (`--top-zone`).
 * - Half: 67 % of the screen height is covered.
 * - Collapsed: only the peek (the handle zone plus the header row, measured) shows above the navigation bar.
 * - Dismissed (pushed sheets only): just below the screen.
 *
 * Small screens never invert the order: Half is at least Full, and Collapsed at least Half.
 */
internal data class SheetGeometry(
    val screenHeight: Float,
    val topZone: Float,
    val peek: Float,
    val bottomInset: Float,
    val halfFraction: Float,
    val dismissGap: Float,
) {
    val full: Float get() = topZone
    val half: Float get() = max(full, screenHeight * (1f - halfFraction))
    val collapsed: Float get() = max(half, screenHeight - peek - bottomInset)
    val dismissed: Float get() = max(collapsed, screenHeight) + dismissGap

    fun offsetOf(height: SheetHeight): Float = when (height) {
        SheetHeight.Full -> full
        SheetHeight.Half -> half
        SheetHeight.Collapsed -> collapsed
        SheetHeight.Dismissed -> dismissed
    }

    /** The states a sheet can rest in, lowest (largest offset) first. */
    fun ladder(pushed: Boolean): List<SheetHeight> = if (pushed) PUSHED else ROOT

    /** The lowest offset a drag can reach: Collapsed for the root, Dismissed for a pushed sheet. */
    fun maxOffset(pushed: Boolean): Float = offsetOf(ladder(pushed).first())

    /** S-14: the state nearest to [offset]. */
    fun nearest(offset: Float, pushed: Boolean): SheetHeight =
        ladder(pushed).minBy { abs(offsetOf(it) - offset) }

    /** One state up (towards Full) or down from [from], never past the ends (S-11, S-14). */
    fun step(from: SheetHeight, up: Boolean, pushed: Boolean): SheetHeight {
        val ladder = ladder(pushed)
        val i = ladder.indexOf(from).coerceAtLeast(0)
        return ladder[if (up) minOf(i + 1, ladder.lastIndex) else maxOf(i - 1, 0)]
    }

    private companion object {
        val ROOT = listOf(SheetHeight.Collapsed, SheetHeight.Half, SheetHeight.Full)
        val PUSHED = listOf(SheetHeight.Dismissed, SheetHeight.Collapsed, SheetHeight.Half, SheetHeight.Full)
    }
}
