package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot

// The sheet stack's logic (S-4..S-9, S-11, S-12, P-6, T-1, T-2, T-9) as one immutable value and a pure reducer. The
// runner (SheetStackController) executes the effects: animations, the map camera and the hooks. Offsets, frames and
// pointers never reach this file; the reducer only sees drag start, release and settle.

/** A stack level's id. */
@JvmInline
value class SheetId(val raw: Long)

/**
 * One level: what it shows, its saved phone height and tablet panel state (S-7), and the camera saved under it. The
 * kind is in the type, so the root is a `Level<SheetKind.Discover>` and a pushed level a `Level<PushedKind>`.
 */
data class Level<out K : SheetKind>(
    val id: SheetId,
    val kind: K,
    val height: RestHeight,
    val panel: PanelHeight,
    val camera: CameraSnapshot? = null,
)

/**
 * What is drawn besides the top level. The two cases exclude each other: a push ends any slide-out and a pop ends
 * any slide-in.
 */
sealed interface StackTransition {
    /** Only the top level is drawn (S-7). */
    data object Idle : StackTransition

    /** A pushed level is sliding in over [under], which stays drawn until the top settles. */
    data class SlidingIn(val under: SheetId) : StackTransition

    /** Popped levels sliding out over the revealed top, the most recent first in the list's draw order. */
    data class SlidingOut(val leaving: List<Level<PushedKind>>) : StackTransition
}

/**
 * What sits directly on the root when it is not a product: one device, or the Filter sheet (opened from the root's
 * header only, so there is at most one) with at most one device over it.
 */
sealed interface Base {
    data class Device(val device: Level<SheetKind.Device>) : Base
    data class Filter(val filter: Level<SheetKind.Filter>, val device: Level<SheetKind.Device>? = null) : Base
}

/** A product level with at most one device level over it. */
data class ProductFrame(val product: Level<SheetKind.Product>, val device: Level<SheetKind.Device>? = null)

/**
 * The stack, bottom to top: [root] (Discover, never closes), then [base], then [frames]. The shape is the rule: a
 * device level only ever sits on the root, the Filter sheet or a product (S-4: never on a device), and there is at
 * most one Filter level, directly on the root.
 */
data class StackState(
    val root: Level<SheetKind.Discover>,
    val base: Base? = null,
    val frames: List<ProductFrame> = emptyList(),
    val transition: StackTransition = StackTransition.Idle,
    val tablet: Boolean = false,
    val nextId: Long = root.id.raw + 1,
) {
    /** The pushed levels, bottom to top. */
    val pushed: List<Level<PushedKind>>
        get() = buildList {
            when (base) {
                null -> Unit
                is Base.Device -> add(base.device)
                is Base.Filter -> {
                    add(base.filter)
                    base.device?.let(::add)
                }
            }
            for (frame in frames) {
                add(frame.product)
                frame.device?.let(::add)
            }
        }

    val levels: List<Level<SheetKind>> get() = listOf(root) + pushed
    val top: Level<SheetKind> get() = pushed.lastOrNull() ?: root

    /** The level with [id] in the stack or sliding out; null once it has gone. */
    fun levelOf(id: SheetId): Level<SheetKind>? =
        levels.firstOrNull { it.id == id } ?: (transition as? StackTransition.SlidingOut)?.leaving?.firstOrNull { it.id == id }

    /** S-9: only while a pushed level is at Full, never on the tablet panel. */
    val scrimVisible: Boolean get() = !tablet && pushed.isNotEmpty() && top.height == RestHeight.Full

    companion object {
        fun initial(rootHeight: SheetHeight): StackState {
            val height = rootHeight.rest() ?: RestHeight.Collapsed // the root has no Dismissed
            return StackState(Level(SheetId(1), SheetKind.Discover, height, height.toPanel()))
        }
    }
}

/** Why a sheet pops (S-8). Every path pops exactly one level; only a drag release reveals without easing. */
enum class PopReason { Close, Back, Scrim, EmptyMap, Drag }

/** What happens to the stack. */
sealed interface StackEvent {
    /**
     * S-4, S-6: [camera] is the map's camera now, saved under the current top. A device pushed while a device level
     * is on top replaces it (S-4); the Filter sheet opens only on the root alone (its button is the root's).
     */
    data class Push(val kind: PushedKind, val camera: CameraSnapshot?) : StackEvent
    data class Pop(val reason: PopReason) : StackEvent
    /** V-7. */
    data object Reset : StackEvent
    data class SetHeight(val id: SheetId, val height: SheetHeight) : StackEvent
    data class SetPanel(val id: SheetId, val panel: PanelHeight) : StackEvent
    /** S-11, T-2. */
    data class TapHeader(val id: SheetId) : StackEvent
    /** S-12. */
    data object RootHeaderControl : StackEvent
    /** P-6, T-2; [dragging]: the top level is under the finger right now. */
    data class MapUserGesture(val dragging: Boolean) : StackEvent
    /** T-2: the Partial panel's chevron. */
    data object ExpandPanel : StackEvent
    /** T-9 and back. */
    data class SetLayout(val tablet: Boolean) : StackEvent
    /** S-7: the top level started moving under the finger. */
    data class DragStarted(val id: SheetId) : StackEvent
    /** S-14: the finger went up; [target] null is a tap; [resettle]: it interrupted an animation or moved the sheet. */
    data class Released(val id: SheetId, val target: SheetHeight?, val resettle: Boolean) : StackEvent
    /** An ease, slide-in or reveal of [id] finished. */
    data class Settled(val id: SheetId) : StackEvent
    /** A popped level finished sliding out. */
    data class SlidOut(val id: SheetId) : StackEvent
}

/** What the runner does; executed in list order. */
sealed interface StackEffect {
    /** Cancel [id]'s running animation (its offset stays where it is). */
    data class Stop(val id: SheetId) : StackEffect
    /** [id] rests at its height (no live offset). */
    data class Rest(val id: SheetId) : StackEffect
    /** [id] keeps its current offset as a live offset (before it leaves or is covered). */
    data class Pin(val id: SheetId) : StackEffect
    /** [id] slides up from below the screen to its height, then [StackEvent.Settled]. */
    data class SlideIn(val id: SheetId) : StackEffect
    /** [id] eases from where it is to its height, then [StackEvent.Settled]. */
    data class Ease(val id: SheetId) : StackEffect
    /** S-8: [id] starts at [from]'s current offset and eases to its height, then [StackEvent.Settled]. */
    data class Reveal(val id: SheetId, val from: SheetId) : StackEffect
    /** [id] slides out of the screen, then [StackEvent.SlidOut]. */
    data class SlideOut(val id: SheetId) : StackEffect
    /** S-4: [to] replaces [from] at once: it takes over [from]'s measured header. */
    data class Inherit(val from: SheetId, val to: SheetId) : StackEffect
    /** S-8: the map gets back the camera saved under the revealed level. */
    data class RestoreCamera(val camera: CameraSnapshot) : StackEffect
    /** S-17: the map follows the new top; [opening] is false when it was revealed by a pop. */
    data class TopChanged(val id: SheetId, val opening: Boolean) : StackEffect
    /** D-13.8: a level left the stack. */
    data class Removed(val level: Level<PushedKind>) : StackEffect
}

/** The reducer's result. */
data class StackStep(val state: StackState, val effects: List<StackEffect> = emptyList())

/** The pure stack logic: the next state and the effects for [event]. */
fun reduceStack(state: StackState, event: StackEvent): StackStep = when (event) {
    is StackEvent.Push -> push(state, event)
    is StackEvent.Pop -> pop(state, event.reason)
    StackEvent.Reset -> reset(state)
    is StackEvent.SetHeight -> setHeight(state, event.id, event.height)
    is StackEvent.SetPanel -> StackStep(state.update(event.id) { it.copy(panel = event.panel) })
    is StackEvent.TapHeader -> tapHeader(state, event.id)
    StackEvent.RootHeaderControl ->
        if (!state.tablet && state.pushed.isEmpty() && state.root.height == RestHeight.Collapsed) setHeight(state, state.root.id, SheetHeight.Half)
        else StackStep(state)
    is StackEvent.MapUserGesture -> when {
        state.tablet -> StackStep(state.update(state.top.id) { it.copy(panel = PanelHeight.Partial) })
        event.dragging || state.top.height == RestHeight.Collapsed -> StackStep(state)
        else -> setHeight(state, state.top.id, SheetHeight.Collapsed)
    }
    StackEvent.ExpandPanel -> StackStep(state.update(state.top.id) { it.copy(panel = PanelHeight.Open) })
    is StackEvent.SetLayout -> setLayout(state, event.tablet)
    is StackEvent.DragStarted ->
        if (event.id == state.top.id && state.transition is StackTransition.SlidingIn) StackStep(state.copy(transition = StackTransition.Idle))
        else StackStep(state)
    is StackEvent.Released -> {
        val level = state.levelOf(event.id)
        when {
            level == null -> StackStep(state)
            event.target != null -> setHeight(state, event.id, event.target)
            event.resettle -> setHeight(state, event.id, level.height.sheet)
            else -> StackStep(state)
        }
    }
    is StackEvent.Settled -> {
        // S-7: the top came to rest; whatever path settled it, nothing below it stays drawn
        val next = if (event.id == state.top.id && state.transition is StackTransition.SlidingIn) state.copy(transition = StackTransition.Idle) else state
        StackStep(next, listOf(StackEffect.Rest(event.id)))
    }
    is StackEvent.SlidOut -> {
        val t = state.transition
        if (t !is StackTransition.SlidingOut) StackStep(state)
        else {
            val rest = t.leaving.filter { it.id != event.id }
            StackStep(state.copy(transition = if (rest.isEmpty()) StackTransition.Idle else StackTransition.SlidingOut(rest)))
        }
    }
}

/** [f]'s height, panel and camera on this level; its id and kind stay. */
private fun <K : SheetKind> Level<K>.with(f: (Level<SheetKind>) -> Level<SheetKind>): Level<K> =
    f(this).let { copy(height = it.height, panel = it.panel, camera = it.camera) }

/** Every level through [f], keeping the stack's shape. */
private fun StackState.mapLevels(f: (Level<SheetKind>) -> Level<SheetKind>): StackState = copy(
    root = root.with(f),
    base = when (val b = base) {
        null -> null
        is Base.Device -> Base.Device(b.device.with(f))
        is Base.Filter -> Base.Filter(b.filter.with(f), b.device?.with(f))
    },
    frames = frames.map { ProductFrame(it.product.with(f), it.device?.with(f)) },
)

private fun StackState.update(id: SheetId, f: (Level<SheetKind>) -> Level<SheetKind>): StackState =
    mapLevels { if (it.id == id) f(it) else it }

private fun StackState.leavingLevels(): List<Level<PushedKind>> = (transition as? StackTransition.SlidingOut)?.leaving.orEmpty()

/** The device level on top, if the top is one. */
private fun StackState.topDevice(): Level<SheetKind.Device>? {
    val last = frames.lastOrNull()
    return if (last != null) last.device
    else when (val b = base) {
        null -> null
        is Base.Device -> b.device
        is Base.Filter -> b.device
    }
}

/** [device] on top: over the top product or Filter sheet, on the root, or in place of the device on top. */
private fun StackState.withTopDevice(device: Level<SheetKind.Device>): StackState {
    val last = frames.lastOrNull()
    if (last != null) return copy(frames = frames.dropLast(1) + last.copy(device = device))
    return when (val b = base) {
        null, is Base.Device -> copy(base = Base.Device(device))
        is Base.Filter -> copy(base = b.copy(device = device))
    }
}

/** The stack without its top pushed level; null when only the root is left. */
private fun StackState.withoutTop(): StackState? {
    val last = frames.lastOrNull()
    if (last != null) return copy(frames = frames.dropLast(1) + listOfNotNull(last.copy(device = null).takeIf { last.device != null }))
    return when (val b = base) {
        null -> null
        is Base.Device -> copy(base = null)
        is Base.Filter -> copy(base = if (b.device != null) b.copy(device = null) else null)
    }
}

private fun push(state: StackState, event: StackEvent.Push): StackStep {
    val under = state.top
    fun <K : PushedKind> opened(kind: K) = Level(
        SheetId(state.nextId), kind,
        if (under.height == RestHeight.Full) RestHeight.Full else RestHeight.Half,
        PanelHeight.Open,
    )
    val saved = state.update(under.id) { it.copy(camera = event.camera) }.copy(nextId = state.nextId + 1)
    val (next, level) = when (val kind = event.kind) {
        // the filter button is on the root's header only
        SheetKind.Filter -> if (state.pushed.isNotEmpty()) return StackStep(state) else opened(SheetKind.Filter).let { saved.copy(base = Base.Filter(it)) to it }
        is SheetKind.Device -> {
            // S-4: a device over a device replaces it
            val old = state.topDevice()
            if (old != null) return replaceDevice(state, old, kind)
            opened(kind).let { saved.withTopDevice(it) to it }
        }
        is SheetKind.Product -> opened(kind).let { saved.copy(frames = saved.frames + ProductFrame(it)) to it }
    }
    val effects = buildList {
        add(StackEffect.Stop(under.id))
        add(StackEffect.Rest(under.id))
        state.leavingLevels().forEach { add(StackEffect.Stop(it.id)) }
        if (!state.tablet) add(StackEffect.SlideIn(level.id))
        add(StackEffect.TopChanged(level.id, opening = true))
    }
    return StackStep(next.copy(transition = if (state.tablet) StackTransition.Idle else StackTransition.SlidingIn(under.id)), effects)
}

/** S-4: a device level replaces the device level on top, at the same height. */
private fun replaceDevice(state: StackState, old: Level<SheetKind.Device>, kind: SheetKind.Device): StackStep {
    val level = Level(SheetId(state.nextId), kind, old.height, old.panel)
    val next = state.withTopDevice(level).copy(
        // S-7: the replacement rests at once, whatever the slide-in was doing
        transition = if (state.transition is StackTransition.SlidingIn) StackTransition.Idle else state.transition,
        nextId = state.nextId + 1,
    )
    return StackStep(
        next,
        listOf(
            StackEffect.Stop(old.id), StackEffect.Inherit(old.id, level.id),
            // S-16a: the new sheet starts where the old one is (it may be mid-animation) and eases to the height
            StackEffect.Reveal(level.id, from = old.id),
            StackEffect.Removed(old), StackEffect.TopChanged(level.id, opening = true),
        ),
    )
}

private fun pop(state: StackState, reason: PopReason): StackStep {
    val out = state.pushed.lastOrNull() ?: return StackStep(state)
    val remaining = state.withoutTop() ?: return StackStep(state)
    val revealed = remaining.top
    val effects = mutableListOf<StackEffect>(StackEffect.Stop(out.id))
    val transition = if (state.tablet) {
        effects += StackEffect.Removed(out)
        effects += StackEffect.Rest(revealed.id)
        StackTransition.Idle
    } else {
        effects += StackEffect.Pin(out.id)
        effects += StackEffect.Removed(out)
        effects += if (reason == PopReason.Drag) StackEffect.Rest(revealed.id) else StackEffect.Reveal(revealed.id, from = out.id)
        effects += StackEffect.SlideOut(out.id)
        StackTransition.SlidingOut(listOf(out) + state.leavingLevels())
    }
    revealed.camera?.let { effects += StackEffect.RestoreCamera(it) }
    effects += StackEffect.TopChanged(revealed.id, opening = false)
    return StackStep(remaining.copy(transition = transition), effects)
}

private fun reset(state: StackState): StackStep {
    val top = state.pushed.lastOrNull()
    val effects = buildList {
        (state.pushed + state.leavingLevels()).forEach { add(StackEffect.Stop(it.id)) }
        state.pushed.forEach { add(StackEffect.Removed(it)) }
        // S-16a: the root shows where the top was and eases to its own height (a new session view, search)
        if (top != null && !state.tablet) add(StackEffect.Reveal(state.root.id, from = top.id))
        else add(StackEffect.Rest(state.root.id))
    }
    return StackStep(state.copy(base = null, frames = emptyList(), transition = StackTransition.Idle), effects)
}

private fun setHeight(state: StackState, id: SheetId, height: SheetHeight): StackStep {
    val level = state.levelOf(id) ?: return StackStep(state)
    val isTop = id == state.top.id
    return when (val rest = height.rest()) {
        // the panel has its own state (T-2); Dismissed means nothing there
        null -> when {
            state.tablet -> StackStep(state)
            // S-5: past Collapsed a pushed top pops; the root stops at Collapsed
            isTop && id != state.root.id -> pop(state, PopReason.Drag)
            else -> setHeight(state, id, SheetHeight.Collapsed)
        }
        // the phone height is only kept for going back to phone (T-9)
        else -> if (state.tablet) StackStep(state.update(id) { it.copy(height = rest) })
        else StackStep(state.update(id) { it.copy(height = rest) }, if (isTop && level in state.levels) listOf(StackEffect.Ease(id)) else emptyList())
    }
}

private fun tapHeader(state: StackState, id: SheetId): StackStep {
    val top = state.top
    if (id != top.id) return StackStep(state)
    if (state.tablet) {
        return StackStep(state.update(id) { it.copy(panel = if (it.panel == PanelHeight.Open) PanelHeight.Partial else PanelHeight.Open) })
    }
    val next = if (top.height == RestHeight.Collapsed) stepUp(top.height) else RestHeight.Collapsed
    return setHeight(state, id, next.sheet)
}

/** S-11: one state up from [from] (Collapsed → Half → Full). */
private fun stepUp(from: RestHeight): RestHeight = when (from) {
    RestHeight.Collapsed -> RestHeight.Half
    RestHeight.Half, RestHeight.Full -> RestHeight.Full
}

private fun setLayout(state: StackState, tablet: Boolean): StackStep {
    if (tablet == state.tablet) return StackStep(state)
    val stops = (state.levels + state.leavingLevels()).flatMap { listOf(StackEffect.Stop(it.id), StackEffect.Rest(it.id)) }
    // T-9: phone to tablet opens every level; back, Partial comes back Collapsed and Open comes back Full
    val next = state.mapLevels { l ->
        if (tablet) l.copy(panel = PanelHeight.Open)
        else l.copy(height = if (l.panel == PanelHeight.Partial) RestHeight.Collapsed else RestHeight.Full)
    }
    return StackStep(next.copy(transition = StackTransition.Idle, tablet = tablet), stops)
}
