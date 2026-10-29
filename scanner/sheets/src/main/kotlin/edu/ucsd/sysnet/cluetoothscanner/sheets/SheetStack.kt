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

/**
 * One level of the stack, as the UI holds it: a stable handle whose logical state ([height], [panel], [camera]) is
 * read from the controller's immutable [StackState], plus the execution-layer state the runner and the composition
 * own: the animated offset, the drag session, the measured header and the composed lists with their saved scroll.
 */
class SheetEntry internal constructor(val id: Long, val kind: SheetKind, private val owner: SheetStackController, private val initial: Level) {
    private val level: Level get() = owner.state.levelOf(SheetId(id)) ?: initial

    /** The saved phone height (S-5, S-7). */
    val height: SheetHeight get() = level.height

    /** The tablet panel state (T-2). */
    var panel: PanelHeight
        get() = level.panel
        internal set(value) = owner.dispatch(StackEvent.SetPanel(SheetId(id), value))

    /** The camera saved when this level was covered (S-8). */
    val camera: CameraSnapshot? get() = level.camera

    /** The main list's saved scroll (S-8, G-10); other lists of the sheet keep theirs under their own key. */
    var scroll: ScrollSnapshot
        get() = scrolls[MAIN_LIST] ?: ScrollSnapshot()
        internal set(value) {
            scrolls[MAIN_LIST] = value
        }

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
 * The sheet stack's runner (S-4..S-9, S-11, S-12, P-6, T-1, T-2, T-9). The logic is [reduceStack] over one
 * immutable [state]; this class turns calls into [StackEvent]s, executes the [StackEffect]s (offset animations,
 * the hooks) and feeds animation ends back as events. It owns the per-level [SheetEntry] handles.
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
    /** The stack's whole logical state. */
    var state by mutableStateOf(StackState.initial(root, rootHeight))
        private set

    private val handles = HashMap<Long, SheetEntry>()

    val entries: List<SheetEntry> get() = state.levels.map(::handle)
    val below: SheetEntry? get() = (state.transition as? StackTransition.SlidingIn)?.under?.let(state::levelOf)?.let(::handle)
    val leaving: List<SheetEntry> get() = (state.transition as? StackTransition.SlidingOut)?.leaving.orEmpty().map(::handle)
    val tablet: Boolean get() = state.tablet

    /** Screen facts, px, as one value (set by the host on every layout). */
    private data class Screen(val height: Float = 0f, val density: Float = 1f, val bottomInset: Float = 0f)

    private var screen by mutableStateOf(Screen())

    val screenHeight: Float get() = screen.height

    val top: SheetEntry get() = handle(state.top)
    val root: SheetEntry get() = handle(state.root)
    val rendered: List<SheetEntry> get() = listOfNotNull(below) + top + leaving

    /** S-9: only while a pushed sheet is at Full, never on the tablet panel. */
    val scrimVisible: Boolean get() = state.scrimVisible

    fun setScreen(heightPx: Float, density: Float, bottomInsetPx: Float) {
        screen = Screen(heightPx, density, bottomInsetPx)
    }

    private val density: Float get() = screen.density

    internal fun geometry(entry: SheetEntry): SheetGeometry {
        val peek = if (entry.peek.isNaN()) defaultPeek() else entry.peek
        return SheetGeometry(
            screenHeight = screen.height,
            topZone = metrics.topZone.value * density,
            peek = peek,
            bottomInset = screen.bottomInset,
            halfFraction = metrics.halfFraction,
            dismissGap = metrics.dismissGap.value * density,
        )
    }

    /** The sheet's top edge now. */
    fun offsetOf(entry: SheetEntry): Float = entry.liveOffset ?: geometry(entry).offsetOf(entry.height)

    fun isPushed(entry: SheetEntry) = entry.id != state.root.id.raw

    // ---- Stack ----

    /** S-4, S-6: opens [kind] on top, at Half, or at Full when the sheet below is at Full. T-2: Open on tablet. */
    fun push(kind: SheetKind): SheetEntry {
        val under = top
        under.scroll = under.scrollReader?.invoke() ?: under.scroll
        dispatch(StackEvent.Push(kind, hooks.camera()))
        return top
    }

    /** S-4: a device opened while a device sheet is on top replaces it, at the same level and height. */
    fun replaceTop(kind: SheetKind): SheetEntry {
        dispatch(StackEvent.ReplaceTop(kind))
        return top
    }

    /** Opens a device: replacing a device sheet on top, otherwise pushing one (S-4). */
    fun openDevice(key: DeviceKey): SheetEntry =
        if (state.top.kind is SheetKind.Device) replaceTop(SheetKind.Device(key)) else push(SheetKind.Device(key))

    /**
     * S-8: pops exactly one level. The revealed sheet gets back its saved height, scroll, view and filters, and the
     * map its camera. By X, Back, the scrim or an empty-map tap, the revealed sheet first sits at the popped sheet's
     * current offset under it and eases to its saved height while the popped one slides out. After a drag past
     * Collapsed it shows at its saved height at once while the popped one slides out.
     */
    fun pop(reason: PopReason): Boolean {
        val before = state.pushed.size
        dispatch(StackEvent.Pop(reason))
        return state.pushed.size < before
    }

    /** V-7: a new session view goes back to the root alone. */
    fun reset() = dispatch(StackEvent.Reset)

    /** G-14: Back pops the top pushed sheet; false when only the root is left. */
    fun handleBack(): Boolean = pop(PopReason.Back)

    // ---- Heights ----

    /** Moves the top sheet to [height] (Dismissed pops it). On tablet it only records the phone height. */
    fun setHeight(entry: SheetEntry, height: SheetHeight) = dispatch(StackEvent.SetHeight(SheetId(entry.id), height))

    /** S-11, T-2: a tap on the handle or header background. */
    fun tapHeader(entry: SheetEntry) = dispatch(StackEvent.TapHeader(SheetId(entry.id)))

    /** S-12: a header control of the root used while it is Collapsed also raises it to Half. */
    fun onRootHeaderControl() = dispatch(StackEvent.RootHeaderControl)

    /** P-6, T-2: the user started panning or zooming the map. App camera moves never call this. */
    fun onMapUserGesture() = dispatch(StackEvent.MapUserGesture(dragging = top.drag?.mode == SheetDrag.Mode.Sheet))

    /** T-2: the Partial panel's chevron. */
    fun expandPanel() = dispatch(StackEvent.ExpandPanel)

    /**
     * T-9 and back. Phone to tablet opens every level. Tablet to phone keeps the panel's choice: Partial comes back
     * Collapsed and Open comes back Full, as the prototype does.
     */
    fun setLayout(tablet: Boolean) = dispatch(StackEvent.SetLayout(tablet))

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
        dispatch(StackEvent.DragStarted(SheetId(entry.id))) // S-7: once dragged, nothing below the top is drawn
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
        if (release.target != null && release.contentFling != 0f) {
            entry.content?.let { c -> scope.launch { c.fling(release.contentFling) } }
        }
        dispatch(StackEvent.Released(SheetId(entry.id), release.target, resettle = entry.dragInterrupted || entry.liveOffset != null))
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

    // ---- Runner ----

    /** Reduces [event] and executes its effects in order. */
    internal fun dispatch(event: StackEvent) {
        val step = reduceStack(state, event)
        if (step.state !== state) state = step.state
        step.effects.forEach(::execute)
        prune()
    }

    private fun handle(level: Level): SheetEntry = handles.getOrPut(level.id.raw) { SheetEntry(level.id.raw, level.kind, this, level) }

    private fun handleOf(id: SheetId): SheetEntry? = handles[id.raw] ?: state.levelOf(id)?.let(::handle)

    /** Handles of levels that are neither in the stack nor sliding out are no longer needed. */
    private fun prune() {
        val live = (state.levels + (state.transition as? StackTransition.SlidingOut)?.leaving.orEmpty()).map { it.id.raw }.toSet()
        handles.keys.retainAll(live)
    }

    private fun execute(effect: StackEffect) {
        when (effect) {
            is StackEffect.Stop -> handles[effect.id.raw]?.let(::stop)
            is StackEffect.Rest -> handles[effect.id.raw]?.liveOffset = null
            is StackEffect.Pin -> handleOf(effect.id)?.let { it.liveOffset = offsetOf(it) }
            is StackEffect.SlideIn -> handleOf(effect.id)?.let { e ->
                e.liveOffset = geometry(e).dismissed
                animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(effect.id))
            }
            is StackEffect.Ease -> handleOf(effect.id)?.let { e -> animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(effect.id)) }
            is StackEffect.Reveal -> handleOf(effect.id)?.let { e ->
                handles[effect.from.raw]?.let { from -> e.liveOffset = offsetOf(from) }
                animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(effect.id))
            }
            is StackEffect.SlideOut -> handleOf(effect.id)?.let { e -> animate(e, geometry(e).dismissed, StackEvent.SlidOut(effect.id)) }
            is StackEffect.Inherit -> {
                val from = handles[effect.from.raw]
                val to = handleOf(effect.to)
                if (from != null && to != null) to.peek = from.peek
            }
            is StackEffect.RestoreCamera -> hooks.restoreCamera(effect.camera)
            is StackEffect.TopChanged -> handleOf(effect.id)?.let { hooks.onTopChanged(it, effect.opening) }
            is StackEffect.Removed -> hooks.onRemoved(handles[effect.level.id.raw] ?: handle(effect.level))
        }
    }

    private fun defaultPeek() = (metrics.handleZone.value + metrics.headerHeight.value + 2 * metrics.edge.value) * density

    private fun stop(entry: SheetEntry) {
        entry.animation?.cancel()
        entry.animation = null
    }

    private fun animate(entry: SheetEntry, to: Float, done: StackEvent) {
        stop(entry)
        val from = offsetOf(entry)
        entry.liveOffset = from
        val ms = if (metrics.reducedMotion) 0 else metrics.pageMs
        entry.animation = scope.launch {
            animator.animate(from, to, ms) { entry.liveOffset = it }
            entry.liveOffset = to
            dispatch(done)
        }
    }
}
