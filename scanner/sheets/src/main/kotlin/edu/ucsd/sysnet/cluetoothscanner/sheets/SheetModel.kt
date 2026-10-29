package edu.ucsd.sysnet.cluetoothscanner.sheets

// Lane D's own state types (docs/v2-api.md §5.4). They stay internal until the sheet contents are wired; the
// shared types (CameraSnapshot, DeviceKey) are local stand-ins for :ui-contract and :core-api until those merge.

/** Phone snap states (S-5). Dismissed exists only for pushed sheets; reaching it pops the sheet. */
internal enum class SheetHeight { Collapsed, Half, Full, Dismissed }

/** Tablet panel states (T-2). */
internal enum class PanelHeight { Open, Partial }

/** T-2: a saved or restored phone height on the tablet. No level is ever header-only, so Collapsed is Partial. */
internal fun SheetHeight.toPanel(): PanelHeight = if (this == SheetHeight.Collapsed) PanelHeight.Partial else PanelHeight.Open

internal typealias DeviceKey = Long

internal sealed interface SheetKind {
    data object Discover : SheetKind
    data class Product(val pageId: String?) : SheetKind
    data class Device(val key: DeviceKey) : SheetKind
    data object Filter : SheetKind
}

/** A sheet's content scroll position, kept while the sheet is hidden (S-7) and restored on pop (S-8, G-10). */
internal data class ScrollSnapshot(val index: Int = 0, val offset: Int = 0)

/** Stand-in for :ui-contract's CameraSnapshot (§5.3). */
internal data class CameraSnapshot(val lon: Double, val lat: Double, val zoom: Double, val bearing: Double = 0.0)
