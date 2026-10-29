package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot

// The sheet stack's logic (S-4..S-9, S-11, S-12, P-6, T-1, T-2, T-9) as one immutable value and a pure reducer. The
// runner (SheetStackController) executes the effects: animations, the map camera and the hooks. Offsets, frames and
// pointers never reach this file; the reducer only sees drag start, release and settle.

/** A stack level's id. */
@JvmInline
value class SheetId(val raw: Long)

/** One level: what it shows, its saved phone height and tablet panel state (S-7), and the camera saved under it. */
data class Level(
    val id: SheetId,
    val kind: SheetKind,
    val height: SheetHeight,
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
    data class SlidingOut(val leaving: List<Level>) : StackTransition
}

/**
 * The stack: [root] (Discover) never closes, so it is not part of [pushed]; [pushed] is ordered bottom to top.
 * There is at most one Filter level, and a device level is never directly over another device level (S-4).
 */
data class StackState(
    val root: Level,
    val pushed: List<Level> = emptyList(),
    val transition: StackTransition = StackTransition.Idle,
    val tablet: Boolean = false,
    val nextId: Long = root.id.raw + 1,
) {
    val levels: List<Level> get() = listOf(root) + pushed
    val top: Level get() = pushed.lastOrNull() ?: root

    /** The level with [id] in the stack or sliding out; null once it has gone. */
    fun levelOf(id: SheetId): Level? =
        levels.firstOrNull { it.id == id } ?: (transition as? StackTransition.SlidingOut)?.leaving?.firstOrNull { it.id == id }

    /** S-9: only while a pushed level is at Full, never on the tablet panel. */
    val scrimVisible: Boolean get() = !tablet && pushed.isNotEmpty() && top.height == SheetHeight.Full

    companion object {
        fun initial(root: SheetKind, rootHeight: SheetHeight) =
            StackState(Level(SheetId(1), root, rootHeight, rootHeight.toPanel()))
    }
}

/** Why a sheet pops (S-8). Every path pops exactly one level; only a drag release reveals without easing. */
enum class PopReason { Close, Back, Scrim, EmptyMap, Drag }

/** What happens to the stack. */
sealed interface StackEvent {
    /** S-4, S-6: [camera] is the map's camera now, saved under the current top. */
    data class Push(val kind: SheetKind, val camera: CameraSnapshot?) : StackEvent
    /** S-4: a device opened while a device level is on top replaces it. */
    data class ReplaceTop(val kind: SheetKind) : StackEvent
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
    data class Removed(val level: Level) : StackEffect
}

/** The reducer's result. */
data class StackStep(val state: StackState, val effects: List<StackEffect> = emptyList())

/** The pure stack logic: the next state and the effects for [event]. */
fun reduceStack(state: StackState, event: StackEvent): StackStep = when (event) {
    is StackEvent.Push -> push(state, event)
    is StackEvent.ReplaceTop -> replaceTop(state, event.kind)
    is StackEvent.Pop -> pop(state, event.reason)
    StackEvent.Reset -> reset(state)
    is StackEvent.SetHeight -> setHeight(state, event.id, event.height)
    is StackEvent.SetPanel -> StackStep(state.update(event.id) { it.copy(panel = event.panel) })
    is StackEvent.TapHeader -> tapHeader(state, event.id)
    StackEvent.RootHeaderControl ->
        if (!state.tablet && state.pushed.isEmpty() && state.root.height == SheetHeight.Collapsed) setHeight(state, state.root.id, SheetHeight.Half)
        else StackStep(state)
    is StackEvent.MapUserGesture -> when {
        state.tablet -> StackStep(state.update(state.top.id) { it.copy(panel = PanelHeight.Partial) })
        event.dragging || state.top.height == SheetHeight.Collapsed -> StackStep(state)
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
            event.resettle -> setHeight(state, event.id, level.height)
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

private fun StackState.update(id: SheetId, f: (Level) -> Level): StackState = when (id) {
    root.id -> copy(root = f(root))
    else -> copy(pushed = pushed.map { if (it.id == id) f(it) else it })
}

private fun StackState.leavingLevels(): List<Level> = (transition as? StackTransition.SlidingOut)?.leaving.orEmpty()

private fun push(state: StackState, event: StackEvent.Push): StackStep {
    // at most one Filter level: the filter button is on the root's header only
    if (event.kind == SheetKind.Filter && state.levels.any { it.kind == SheetKind.Filter }) return StackStep(state)
    val under = state.top
    val level = Level(
        SheetId(state.nextId), event.kind,
        if (under.height == SheetHeight.Full) SheetHeight.Full else SheetHeight.Half,
        PanelHeight.Open,
    )
    val saved = state.update(under.id) { it.copy(camera = event.camera) }
    val next = saved.copy(
        pushed = saved.pushed + level,
        transition = if (state.tablet) StackTransition.Idle else StackTransition.SlidingIn(under.id),
        nextId = state.nextId + 1,
    )
    val effects = buildList {
        add(StackEffect.Stop(under.id))
        add(StackEffect.Rest(under.id))
        state.leavingLevels().forEach { add(StackEffect.Stop(it.id)) }
        if (!state.tablet) add(StackEffect.SlideIn(level.id))
        add(StackEffect.TopChanged(level.id, opening = true))
    }
    return StackStep(next, effects)
}

private fun replaceTop(state: StackState, kind: SheetKind): StackStep {
    val old = state.pushed.lastOrNull() ?: return StackStep(state) // the root is never replaced
    val level = Level(SheetId(state.nextId), kind, old.height, old.panel)
    val next = state.copy(
        pushed = state.pushed.dropLast(1) + level,
        // S-7: the replacement rests at once, whatever the slide-in was doing
        transition = if (state.transition is StackTransition.SlidingIn) StackTransition.Idle else state.transition,
        nextId = state.nextId + 1,
    )
    return StackStep(
        next,
        listOf(StackEffect.Stop(old.id), StackEffect.Inherit(old.id, level.id), StackEffect.Removed(old), StackEffect.TopChanged(level.id, opening = true)),
    )
}

private fun pop(state: StackState, reason: PopReason): StackStep {
    val out = state.pushed.lastOrNull() ?: return StackStep(state)
    val remaining = state.pushed.dropLast(1)
    val revealed = remaining.lastOrNull() ?: state.root
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
    return StackStep(state.copy(pushed = remaining, transition = transition), effects)
}

private fun reset(state: StackState): StackStep {
    val effects = buildList {
        (state.pushed + state.leavingLevels()).forEach { add(StackEffect.Stop(it.id)) }
        state.pushed.forEach { add(StackEffect.Removed(it)) }
        add(StackEffect.Rest(state.root.id))
    }
    return StackStep(state.copy(pushed = emptyList(), transition = StackTransition.Idle), effects)
}

private fun setHeight(state: StackState, id: SheetId, height: SheetHeight): StackStep {
    val level = state.levelOf(id) ?: return StackStep(state)
    if (state.tablet) {
        // the panel has its own state (T-2); the phone height is only kept for going back to phone (T-9)
        return if (height == SheetHeight.Dismissed) StackStep(state) else StackStep(state.update(id) { it.copy(height = height) })
    }
    val isTop = id == state.top.id
    if (height == SheetHeight.Dismissed) {
        return if (isTop && id != state.root.id) pop(state, PopReason.Drag) else setHeight(state, id, SheetHeight.Collapsed)
    }
    val next = state.update(id) { it.copy(height = height) }
    return StackStep(next, if (isTop && level in state.levels) listOf(StackEffect.Ease(id)) else emptyList())
}

private fun tapHeader(state: StackState, id: SheetId): StackStep {
    val top = state.top
    if (id != top.id) return StackStep(state)
    if (state.tablet) {
        return StackStep(state.update(id) { it.copy(panel = if (it.panel == PanelHeight.Open) PanelHeight.Partial else PanelHeight.Open) })
    }
    val next = if (top.height == SheetHeight.Collapsed) stepUp(top.height) else SheetHeight.Collapsed
    return setHeight(state, id, next)
}

/** S-11: one state up from [from] (Collapsed → Half → Full). */
private fun stepUp(from: SheetHeight): SheetHeight = when (from) {
    SheetHeight.Dismissed -> SheetHeight.Collapsed
    SheetHeight.Collapsed -> SheetHeight.Half
    SheetHeight.Half, SheetHeight.Full -> SheetHeight.Full
}

private fun setLayout(state: StackState, tablet: Boolean): StackStep {
    if (tablet == state.tablet) return StackStep(state)
    val stops = (state.levels + state.leavingLevels()).flatMap { listOf(StackEffect.Stop(it.id), StackEffect.Rest(it.id)) }
    // T-9: phone to tablet opens every level; back, Partial comes back Collapsed and Open comes back Full
    val map: (Level) -> Level =
        if (tablet) { l -> l.copy(panel = PanelHeight.Open) }
        else { l -> l.copy(height = if (l.panel == PanelHeight.Partial) SheetHeight.Collapsed else SheetHeight.Full) }
    return StackStep(
        state.copy(root = map(state.root), pushed = state.pushed.map(map), transition = StackTransition.Idle, tablet = tablet),
        stops,
    )
}
