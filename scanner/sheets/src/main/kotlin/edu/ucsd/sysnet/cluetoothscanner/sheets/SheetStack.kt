package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Moves an offset over time; the Compose host eases it, tests apply it at once. */
fun interface OffsetAnimator {
    suspend fun animate(from: Float, to: Float, durationMs: Int, onValue: (Float) -> Unit)
}

/** What the stack asks of the rest of the UI (S-8, S-17, D-13.8). The integration wires these to MapRequests. */
class SheetStackHooks(
    val camera: () -> CameraSnapshot? = { null },
    val restoreCamera: (CameraSnapshot) -> Unit = {},
    /** The top sheet changed: the map follows it (S-17). [opening] is false when it was revealed by a pop. */
    val onTopChanged: (entry: SheetEntry, opening: Boolean) -> Unit = { _, _ -> },
    /** A sheet left the stack (pop, replace or reset): a running GATT discovery is cancelled (D-13.8). */
    val onRemoved: (SheetEntry) -> Unit = {},
)

/** Why a sheet pops (S-8). Every path pops exactly one level; only a drag release reveals without easing. */
enum class PopReason { Close, Back, Scrim, EmptyMap, Drag }

/** One level of the stack. [height] and [panel] are its saved states while it is hidden (S-7). */
class SheetEntry(val id: Long, val kind: SheetKind, height: SheetHeight, panel: PanelHeight) {
    var height by mutableStateOf(height)
        internal set
    var panel by mutableStateOf(panel)
        internal set
    /** The main list's saved scroll (S-8, G-10); other lists of the sheet keep theirs under their own key. */
    var scroll: ScrollSnapshot
        get() = scrolls[MAIN_LIST] ?: ScrollSnapshot()
        internal set(value) {
            scrolls[MAIN_LIST] = value
        }
    var camera: CameraSnapshot? = null
        internal set

    /** The measured bottom of the header (S-5 peek), px from the sheet's top; NaN until laid out. */
    var peek by mutableFloatStateOf(Float.NaN)
        internal set

    /** The top edge while dragged or animating; null while resting at [height]. */
    internal var liveOffset by mutableStateOf<Float?>(null)

    /** The y the sheet was last placed at: the frame its pointer positions are relative to. */
    internal var placedTop = 0f

    /** The list the sheet's gestures drive (S-13): the most recently composed one. */
    internal var content: ContentScroll? by mutableStateOf(null)

    /** The main list's live position while it is composed. */
    internal var scrollReader: (() -> ScrollSnapshot)? = null

    private val scrolls = mutableMapOf<Any, ScrollSnapshot>()
    private val lists = mutableMapOf<Any, LiveList>()

    private class LiveList(val content: ContentScroll, val reader: () -> ScrollSnapshot, val restore: suspend (ScrollSnapshot) -> Unit)

    /** The saved scroll of the list under [key]. */
    internal fun scrollOf(key: Any): ScrollSnapshot = scrolls[key] ?: ScrollSnapshot()

    /** A list of this sheet was composed. */
    internal fun attachList(key: Any, content: ContentScroll, reader: () -> ScrollSnapshot, restore: suspend (ScrollSnapshot) -> Unit) {
        lists[key] = LiveList(content, reader, restore)
        this.content = content
        if (key == MAIN_LIST) scrollReader = reader
    }

    /** A list left the composition: it saves its position under its own key only, and unregisters only itself. */
    internal fun detachList(key: Any, content: ContentScroll) {
        val list = lists[key]?.takeIf { it.content === content } ?: return
        scrolls[key] = list.reader()
        lists.remove(key)
        if (this.content === content) this.content = lists.values.lastOrNull()?.content
        if (key == MAIN_LIST) scrollReader = null
    }

    /** Q-7, S-8: sets the saved scroll of [key] and moves the list there if it is composed now. */
    internal suspend fun restoreScroll(key: Any, snapshot: ScrollSnapshot) {
        scrolls[key] = snapshot
        lists[key]?.restore?.invoke(snapshot)
    }
    internal var animation: Job? = null
    internal var drag: SheetDrag? = null
    internal var dragInterrupted = false

    override fun toString() = "SheetEntry($id, $kind, $height, $panel)"

    companion object {
        /** The key of a sheet's main list; a second list (Discover's search results) uses its own key. */
        const val MAIN_LIST = "main"
    }
}

/**
 * The sheet stack (S-4..S-9, S-11, S-12, P-6, T-1, T-2, T-9). Level 1 is the root and never closes.
 *
 * Only the top sheet is composed (S-7), apart from two transitions: while a pushed sheet slides in, the sheet below
 * stays drawn under it ([below]); while a popped sheet slides out, it stays drawn over the revealed one ([leaving]).
 * Draw order is [rendered]; the scrim, when shown, goes directly under [top].
 */
class SheetStackController(
    private val scope: CoroutineScope,
    private val animator: OffsetAnimator,
    private val metrics: SheetMetrics = SheetMetrics(),
    private val hooks: SheetStackHooks = SheetStackHooks(),
    root: SheetKind = SheetKind.Discover,
    rootHeight: SheetHeight = SheetHeight.Collapsed,
) {
    private var nextId = 1L

    var entries by mutableStateOf(listOf(SheetEntry(nextId++, root, rootHeight, rootHeight.toPanel())))
        private set
    var below by mutableStateOf<SheetEntry?>(null)
        private set
    var leaving by mutableStateOf<List<SheetEntry>>(emptyList())
        private set
    var tablet by mutableStateOf(false)
        private set

    /** Screen facts, px (set by the host on every layout). */
    var screenHeight by mutableFloatStateOf(0f)
        private set
    private var density = 1f
    private var bottomInset = 0f

    val top: SheetEntry get() = entries.last()
    val root: SheetEntry get() = entries.first()
    val rendered: List<SheetEntry> get() = listOfNotNull(below) + top + leaving

    /** S-9: only while a pushed sheet is at Full, never on the tablet panel. */
    val scrimVisible: Boolean get() = !tablet && entries.size > 1 && top.height == SheetHeight.Full

    fun setScreen(heightPx: Float, density: Float, bottomInsetPx: Float) {
        screenHeight = heightPx
        this.density = density
        bottomInset = bottomInsetPx
    }

    internal fun geometry(entry: SheetEntry): SheetGeometry {
        val peek = if (entry.peek.isNaN()) defaultPeek() else entry.peek
        return SheetGeometry(
            screenHeight = screenHeight,
            topZone = metrics.topZone.value * density,
            peek = peek,
            bottomInset = bottomInset,
            halfFraction = metrics.halfFraction,
            dismissGap = metrics.dismissGap.value * density,
        )
    }

    /** The sheet's top edge now. */
    fun offsetOf(entry: SheetEntry): Float = entry.liveOffset ?: geometry(entry).offsetOf(entry.height)

    fun isPushed(entry: SheetEntry) = entry !== root

    // ---- Stack ----

    /** S-4, S-6: opens [kind] on top, at Half, or at Full when the sheet below is at Full. T-2: Open on tablet. */
    fun push(kind: SheetKind): SheetEntry {
        val under = top
        under.scroll = under.scrollReader?.invoke() ?: under.scroll
        under.camera = hooks.camera()
        stop(under)
        under.liveOffset = null
        val entry = SheetEntry(
            nextId++, kind,
            if (under.height == SheetHeight.Full) SheetHeight.Full else SheetHeight.Half,
            PanelHeight.Open,
        )
        leaving.forEach(::stop)
        leaving = emptyList()
        entries = entries + entry
        if (tablet) {
            below = null
        } else {
            below = under
            entry.liveOffset = geometry(entry).dismissed
            animate(entry, geometry(entry).offsetOf(entry.height), metrics.pageMs) { settled(entry) }
        }
        hooks.onTopChanged(entry, true)
        return entry
    }

    /** S-4: a device opened while a device sheet is on top replaces it, at the same level and height. */
    fun replaceTop(kind: SheetKind): SheetEntry {
        val old = top
        check(isPushed(old)) { "the root is never replaced" }
        stop(old)
        val entry = SheetEntry(nextId++, kind, old.height, old.panel)
        entry.peek = old.peek
        entries = entries.dropLast(1) + entry
        below = null // S-7: the replacement rests at once, whatever the slide-in was doing
        hooks.onRemoved(old)
        hooks.onTopChanged(entry, true)
        return entry
    }

    /** Opens a device: replacing a device sheet on top, otherwise pushing one (S-4). */
    fun openDevice(key: DeviceKey): SheetEntry =
        if (top.kind is SheetKind.Device) replaceTop(SheetKind.Device(key)) else push(SheetKind.Device(key))

    /**
     * S-8: pops exactly one level. The revealed sheet gets back its saved height, scroll, view and filters, and the
     * map its camera. By X, Back, the scrim or an empty-map tap, the revealed sheet first sits at the popped sheet's
     * current offset under it and eases to its saved height while the popped one slides out. After a drag past
     * Collapsed it shows at its saved height at once while the popped one slides out.
     */
    fun pop(reason: PopReason): Boolean {
        if (entries.size < 2) return false
        val out = top
        val at = offsetOf(out)
        stop(out)
        out.liveOffset = at
        entries = entries.dropLast(1)
        below = null
        val revealed = top
        hooks.onRemoved(out)
        if (tablet) {
            revealed.liveOffset = null
        } else {
            leaving = listOf(out) + leaving
            if (reason == PopReason.Drag) {
                revealed.liveOffset = null
            } else {
                revealed.liveOffset = at
                animate(revealed, geometry(revealed).offsetOf(revealed.height), metrics.pageMs) {
                    revealed.liveOffset = null
                }
            }
            animate(out, geometry(out).dismissed, metrics.pageMs) { leaving = leaving - out }
        }
        revealed.camera?.let(hooks.restoreCamera)
        hooks.onTopChanged(revealed, false)
        return true
    }

    /** V-7: a new session view goes back to the root alone. */
    fun reset() {
        val removed = entries.drop(1)
        (removed + leaving).forEach(::stop)
        entries = listOf(root)
        below = null
        leaving = emptyList()
        removed.forEach(hooks.onRemoved)
        root.liveOffset = null
    }

    /** G-14: Back pops the top pushed sheet; false when only the root is left. */
    fun handleBack(): Boolean = pop(PopReason.Back)

    // ---- Heights ----

    /** Moves the top sheet to [height] (Dismissed pops it). On tablet it only records the phone height. */
    fun setHeight(entry: SheetEntry, height: SheetHeight) {
        if (tablet) {
            // The panel has its own state (T-2); the phone height is only kept for going back to phone (T-9).
            if (height != SheetHeight.Dismissed) entry.height = height
            return
        }
        if (height == SheetHeight.Dismissed) {
            if (entry === top && isPushed(entry)) pop(PopReason.Drag) else setHeight(entry, SheetHeight.Collapsed)
            return
        }
        entry.height = height
        if (entry !== top) return
        animate(entry, geometry(entry).offsetOf(height), metrics.pageMs) { settled(entry) }
    }

    /** S-11, T-2: a tap on the handle or header background. */
    fun tapHeader(entry: SheetEntry) {
        if (entry !== top) return
        if (tablet) {
            entry.panel = if (entry.panel == PanelHeight.Open) PanelHeight.Partial else PanelHeight.Open
            return
        }
        val next =
            if (entry.height == SheetHeight.Collapsed) geometry(entry).step(entry.height, up = true, pushed = isPushed(entry))
            else SheetHeight.Collapsed
        setHeight(entry, next)
    }

    /** S-12: a header control of the root used while it is Collapsed also raises it to Half. */
    fun onRootHeaderControl() {
        if (!tablet && entries.size == 1 && root.height == SheetHeight.Collapsed) setHeight(root, SheetHeight.Half)
    }

    /** P-6, T-2: the user started panning or zooming the map. App camera moves never call this. */
    fun onMapUserGesture() {
        val entry = top
        if (tablet) {
            entry.panel = PanelHeight.Partial
            return
        }
        if (entry.drag?.mode == SheetDrag.Mode.Sheet) return
        if (entry.height != SheetHeight.Collapsed) setHeight(entry, SheetHeight.Collapsed)
    }

    /** T-2: the Partial panel's chevron. */
    fun expandPanel() {
        top.panel = PanelHeight.Open
    }

    /**
     * T-9 and back. Phone to tablet opens every level. Tablet to phone keeps the panel's choice: Partial comes back
     * Collapsed and Open comes back Full, as the prototype does.
     */
    fun setLayout(tablet: Boolean) {
        if (tablet == this.tablet) return
        (entries + leaving).forEach(::stop)
        below = null
        leaving = emptyList()
        entries.forEach { it.liveOffset = null }
        if (tablet) {
            entries.forEach { it.panel = PanelHeight.Open }
        } else {
            entries.forEach { it.height = if (it.panel == PanelHeight.Partial) SheetHeight.Collapsed else SheetHeight.Full }
        }
        this.tablet = tablet
    }

    // ---- Gestures (phone) ----

    /** A pointer went down on [entry] at screen ([x], [y]). Null when the sheet does not take gestures. */
    internal fun beginDrag(entry: SheetEntry, region: DragRegion, x: Float, y: Float, timeMs: Long): SheetDrag? {
        if (tablet || entry !== top) return null
        entry.dragInterrupted = entry.animation?.isActive == true
        stop(entry)
        val start = offsetOf(entry)
        val drag = SheetDrag(
            region = region,
            geometry = geometry(entry),
            pushed = isPushed(entry),
            from = entry.height,
            startOffset = start,
            content = entry.content,
            slop = metrics.slop.value * density,
            flick = metrics.flickDpPerMs * density,
            samples = metrics.velocitySamples,
            x0 = x, y0 = y, t0 = timeMs,
        )
        entry.drag = drag
        return drag
    }

    /** Feeds a move; true when it moved the sheet (the caller consumes the pointer change). */
    internal fun dragMove(entry: SheetEntry, x: Float, y: Float, timeMs: Long): Boolean {
        val drag = entry.drag ?: return false
        if (!drag.move(x, y, timeMs)) return false
        below = null // S-7: once the top sheet is dragged nothing below it is drawn
        entry.liveOffset = drag.offset
        return true
    }

    /** A child took a pending gesture. */
    internal fun dragYield(entry: SheetEntry) {
        entry.drag?.yield()
    }

    /** The pointer went up (or was cancelled). Returns the release so the caller can tell a tap. */
    internal fun endDrag(entry: SheetEntry): SheetDrag.Release? {
        val drag = entry.drag ?: return null
        entry.drag = null
        val release = drag.release()
        val target = release.target
        when {
            target != null -> {
                if (release.contentFling != 0f) entry.content?.let { c -> scope.launch { c.fling(release.contentFling) } }
                setHeight(entry, target)
            }
            entry.dragInterrupted || entry.liveOffset != null -> setHeight(entry, entry.height)
        }
        entry.dragInterrupted = false
        return release
    }

    /** S-15. True when the wheel event is consumed. */
    fun onWheel(entry: SheetEntry, deltaY: Float, nowMs: Long): Boolean {
        if (tablet || entry !== top || entry.drag != null) return false
        val step = wheel.onWheel(deltaY, entry.height, entry.content?.canScrollBackward != true, nowMs) ?: return false
        step.target?.let { setHeight(entry, it) }
        return true
    }

    private val wheel = WheelGate(metrics.wheelLockMs)

    // ---- Internals ----

    /**
     * S-7: the top sheet came to rest. Whatever path settled it (the slide-in, or a touch, P-6, the wheel or a
     * header tap that cancelled the slide-in), nothing below it stays drawn.
     */
    private fun settled(entry: SheetEntry) {
        entry.liveOffset = null
        if (entry === top) below = null
    }

    private fun defaultPeek() = (metrics.handleZone.value + metrics.headerHeight.value + 2 * metrics.edge.value) * density

    private fun stop(entry: SheetEntry) {
        entry.animation?.cancel()
        entry.animation = null
    }

    private fun animate(entry: SheetEntry, to: Float, durationMs: Int, done: () -> Unit) {
        stop(entry)
        val from = offsetOf(entry)
        entry.liveOffset = from
        val ms = if (metrics.reducedMotion) 0 else durationMs
        entry.animation = scope.launch {
            animator.animate(from, to, ms) { entry.liveOffset = it }
            entry.liveOffset = to
            done()
        }
    }
}
