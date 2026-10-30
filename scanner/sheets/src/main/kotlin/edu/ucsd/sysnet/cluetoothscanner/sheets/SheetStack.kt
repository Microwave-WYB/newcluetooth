package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

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
class SheetEntry internal constructor(val id: Long, val kind: SheetKind, private val owner: SheetStackController, private val initial: Level<SheetKind>) {
    private val level: Level<SheetKind> get() = owner.state.levelOf(SheetId(id)) ?: initial

    /** The saved phone height (S-5, S-7). */
    val height: SheetHeight get() = level.height.sheet

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

    /**
     * The top edge while dragged or animating; null while resting at [height]. S-16a: read-only here; only the runner
     * writes it, through its animate-to-target path or its drag-follow path.
     */
    internal val liveOffset: Float? get() = owner.liveOf(id)

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
    rootHeight: SheetHeight = SheetHeight.Collapsed,
) {
    /** The stack's whole logical state. */
    var state by mutableStateOf(StackState.initial(rootHeight))
        private set

    private val handles = HashMap<Long, SheetEntry>()

    val entries: List<SheetEntry> get() = state.levels.map(::handle)
    val below: SheetEntry? get() = (state.transition as? StackTransition.SlidingIn)?.under?.let(state::levelOf)?.let(::handle)
    val leaving: List<SheetEntry> get() = (state.transition as? StackTransition.SlidingOut)?.leaving.orEmpty().map(::handle)
    val tablet: Boolean get() = state.tablet

    /**
     * Screen facts, px, as one value (set by the host on every layout). [topInset] is the status bar the phone's top
     * row sits below (the sheets are drawn edge to edge), so Full stays below that row (S-5).
     */
    private data class Screen(val height: Float = 0f, val density: Float = 1f, val bottomInset: Float = 0f, val topInset: Float = 0f)

    private var screen by mutableStateOf(Screen())

    val screenHeight: Float get() = screen.height

    val top: SheetEntry get() = handle(state.top)
    val root: SheetEntry get() = handle(state.root)
    val rendered: List<SheetEntry> get() = listOfNotNull(below) + top + leaving

    /** S-9: only while a pushed sheet is at Full, never on the tablet panel. */
    val scrimVisible: Boolean get() = state.scrimVisible

    /**
     * The sheets' live offsets by id (S-16a): private to the runner. Every write is [place], called only by the
     * animate-to-target path ([animate], its start and end) and the drag-follow path ([dragMove]); a resting sheet has
     * none and shows its target.
     */
    private val live = mutableStateMapOf<Long, Float>()

    internal fun liveOf(id: Long): Float? = live[id]

    private fun place(entry: SheetEntry, offset: Float?) {
        if (offset == null) live.remove(entry.id) else live[entry.id] = offset
    }

    /** Screen facts (the keyboard, rotation, insets): a resting sheet whose target moves animates there (S-16a). */
    fun setScreen(heightPx: Float, density: Float, bottomInsetPx: Float, topInsetPx: Float = 0f) {
        val next = Screen(heightPx, density, bottomInsetPx, topInsetPx)
        if (next == screen) return
        val first = screen.height == 0f // the first layout places the sheets; nothing moves yet
        retarget { screen = next }.takeUnless { first }?.let(::easeMoved)
    }

    /** S-5 peek: the measured header; a Collapsed sheet whose target moves animates there (S-16a). */
    internal fun setPeek(entry: SheetEntry, px: Float) {
        if (entry.peek == px) return
        val first = entry.peek.isNaN()
        retarget { entry.peek = px }.takeUnless { first }?.let(::easeMoved)
    }

    /** The resting sheets' offsets before [change], to animate from (S-16a). */
    private inline fun retarget(change: () -> Unit): Map<SheetEntry, Float> {
        val resting = rendered.filter { it.drag == null && it.animation?.isActive != true && live[it.id] == null }
        val before = resting.associateWith { offsetOf(it) }
        change()
        return before
    }

    /** Each resting sheet whose target moved eases there from where it was, instead of jumping. */
    private fun easeMoved(before: Map<SheetEntry, Float>) {
        for ((e, was) in before) {
            if (abs(offsetOf(e) - was) <= SAME_PX) continue
            place(e, was)
            animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(SheetId(e.id)))
        }
    }

    /** Tests only: puts a sheet where an animation frame would. */
    internal fun placeForTest(entry: SheetEntry, offset: Float?) = place(entry, offset)

    private val density: Float get() = screen.density

    internal fun geometry(entry: SheetEntry): SheetGeometry {
        val peek = if (entry.peek.isNaN()) defaultPeek() else entry.peek
        return SheetGeometry(
            screenHeight = screen.height,
            topZone = screen.topInset + metrics.topZone.value * density, // S-5: Full below the status bar and the top row
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
    fun push(kind: PushedKind): SheetEntry {
        val under = top
        under.scroll = under.scrollReader?.invoke() ?: under.scroll
        dispatch(StackEvent.Push(kind, hooks.camera()))
        return top
    }

    /** S-4: a device opened while a device sheet is on top replaces it, at the same level and height. Only a device
     *  replaces a device; any other [kind] leaves the stack as it is. */
    fun replaceTop(kind: PushedKind): SheetEntry =
        if (kind is SheetKind.Device && state.top.kind is SheetKind.Device) push(kind) else top

    /** Opens a device: replacing a device sheet on top, otherwise pushing one (S-4). */
    fun openDevice(key: DeviceKey): SheetEntry = push(SheetKind.Device(key))

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
        place(entry, drag.offset) // S-10a: the drag-follow path, the only direct move
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
        // S-16a: every shown sheet stays where it is drawn while the state changes, so the effects animate from
        // there (an Ease of a resting sheet used to start at its new target: a jump)
        val shown = rendered.filter { it.drag == null }
        for (e in shown) if (live[e.id] == null) place(e, offsetOf(e))
        val step = reduceStack(state, event)
        if (step.state !== state) state = step.state
        step.effects.forEach(::execute)
        prune()
        // then each one no effect moved rests: in place if it is at its target, else it eases there
        for (e in shown) if (e.id in handles && e.drag == null && e.animation?.isActive != true) rest(e)
    }

    private fun handle(level: Level<SheetKind>): SheetEntry = handles.getOrPut(level.id.raw) { SheetEntry(level.id.raw, level.kind, this, level) }

    private fun handleOf(id: SheetId): SheetEntry? = handles[id.raw] ?: state.levelOf(id)?.let(::handle)

    /** Handles and offsets of levels that are neither in the stack nor sliding out are no longer needed. */
    private fun prune() {
        val kept = (state.levels + (state.transition as? StackTransition.SlidingOut)?.leaving.orEmpty()).map { it.id.raw }.toSet()
        handles.keys.retainAll(kept)
        live.keys.retainAll(kept)
    }

    private fun execute(effect: StackEffect) {
        when (effect) {
            is StackEffect.Stop -> handles[effect.id.raw]?.let(::stop)
            is StackEffect.Rest -> handles[effect.id.raw]?.let(::rest)
            is StackEffect.Pin -> handleOf(effect.id)?.let { place(it, offsetOf(it)) } // where it is: nothing moves
            is StackEffect.SlideIn -> handleOf(effect.id)?.let { e ->
                place(e, geometry(e).dismissed) // below the screen: the slide's start
                animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(effect.id))
            }
            is StackEffect.Ease -> handleOf(effect.id)?.let { e -> animate(e, geometry(e).offsetOf(e.height), StackEvent.Settled(effect.id)) }
            is StackEffect.Reveal -> handleOf(effect.id)?.let { e ->
                handles[effect.from.raw]?.let { from -> place(e, offsetOf(from)) } // under it: nothing moves
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

    /** S-16a: rest at the target; a sheet not there yet animates there instead of jumping. */
    private fun rest(entry: SheetEntry) {
        val at = live[entry.id] ?: return
        val target = geometry(entry).offsetOf(entry.height)
        if (abs(at - target) <= SAME_PX) place(entry, null)
        else animate(entry, target, StackEvent.Settled(SheetId(entry.id)))
    }

    /** S-16a: the animate-to-target path (`--t-page`, `--ease`; instant only with reduced motion, G-7). */
    private fun animate(entry: SheetEntry, to: Float, done: StackEvent) {
        stop(entry)
        val from = offsetOf(entry)
        place(entry, from)
        val ms = if (metrics.reducedMotion) 0 else metrics.pageMs
        entry.animation = scope.launch {
            animator.animate(from, to, ms) { place(entry, it) }
            place(entry, to)
            dispatch(done)
        }
    }
}

/** Offsets within half a pixel are the same place. */
private const val SAME_PX = 0.5f
