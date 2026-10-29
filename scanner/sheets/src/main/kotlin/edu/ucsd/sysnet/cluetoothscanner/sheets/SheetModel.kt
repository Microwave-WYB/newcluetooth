package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey

// Lane D's own state types (docs/v2-api.md §5.4). They stay internal until the sheet contents are wired.

/** Phone snap states (S-5). Dismissed exists only for pushed sheets as a drag target; reaching it pops the sheet. */
enum class SheetHeight { Collapsed, Half, Full, Dismissed }

/** The heights a level rests at (S-5): every snap state but Dismissed, which is only a target that pops. */
enum class RestHeight {
    Collapsed, Half, Full;

    val sheet: SheetHeight
        get() = when (this) {
            Collapsed -> SheetHeight.Collapsed
            Half -> SheetHeight.Half
            Full -> SheetHeight.Full
        }
}

/** The rest height of [this]; null for Dismissed. */
fun SheetHeight.rest(): RestHeight? = when (this) {
    SheetHeight.Collapsed -> RestHeight.Collapsed
    SheetHeight.Half -> RestHeight.Half
    SheetHeight.Full -> RestHeight.Full
    SheetHeight.Dismissed -> null
}

/** Tablet panel states (T-2). */
enum class PanelHeight { Open, Partial }

/** T-2: a saved or restored phone height on the tablet. No level is ever header-only, so Collapsed is Partial. */
fun SheetHeight.toPanel(): PanelHeight = if (this == SheetHeight.Collapsed) PanelHeight.Partial else PanelHeight.Open

fun RestHeight.toPanel(): PanelHeight = sheet.toPanel()

sealed interface SheetKind {
    /** The root's kind; never pushed. */
    data object Discover : SheetKind
    data class Product(val pageId: String?) : PushedKind
    data class Device(val key: DeviceKey) : PushedKind
    data object Filter : PushedKind
}

/** The kinds a pushed level has: every kind but Discover. */
sealed interface PushedKind : SheetKind

/** A sheet's content scroll position, kept while the sheet is hidden (S-7) and restored on pop (S-8, G-10). */
data class ScrollSnapshot(val index: Int = 0, val offset: Int = 0)
