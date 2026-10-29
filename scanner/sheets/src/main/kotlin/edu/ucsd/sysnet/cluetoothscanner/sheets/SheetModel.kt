package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey

// Lane D's own state types (docs/v2-api.md §5.4). They stay internal until the sheet contents are wired.

/** Phone snap states (S-5). Dismissed exists only for pushed sheets; reaching it pops the sheet. */
enum class SheetHeight { Collapsed, Half, Full, Dismissed }

/** Tablet panel states (T-2). */
enum class PanelHeight { Open, Partial }

/** T-2: a saved or restored phone height on the tablet. No level is ever header-only, so Collapsed is Partial. */
fun SheetHeight.toPanel(): PanelHeight = if (this == SheetHeight.Collapsed) PanelHeight.Partial else PanelHeight.Open

sealed interface SheetKind {
    data object Discover : SheetKind
    data class Product(val pageId: String?) : SheetKind
    data class Device(val key: DeviceKey) : SheetKind
    data object Filter : SheetKind
}

/** A sheet's content scroll position, kept while the sheet is hidden (S-7) and restored on pop (S-8, G-10). */
data class ScrollSnapshot(val index: Int = 0, val offset: Int = 0)
