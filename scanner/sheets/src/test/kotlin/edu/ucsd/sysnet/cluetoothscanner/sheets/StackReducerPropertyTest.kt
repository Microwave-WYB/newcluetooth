package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Fixed-seed random event sequences through the pure [reduceStack], with invariants after every step. */
class StackReducerPropertyTest {
    private val kinds = listOf(SheetKind.Product("p1"), SheetKind.Product(null), SheetKind.Device(1), SheetKind.Device(2), SheetKind.Filter)
    private val heights = SheetHeight.entries
    private val reasons = PopReason.entries

    private fun randomEvent(rnd: Random, s: StackState): StackEvent {
        val any = (s.levels + ((s.transition as? StackTransition.SlidingOut)?.leaving.orEmpty())).random(rnd).id
        return when (rnd.nextInt(16)) {
            0, 1, 2 -> StackEvent.Push(kinds.random(rnd), CameraSnapshot(GeoPoint(rnd.nextDouble(), rnd.nextDouble()), 15.0))
            3 -> StackEvent.Push(SheetKind.Device(rnd.nextLong(1, 4)), null)
            4, 5 -> StackEvent.Pop(reasons.random(rnd))
            6 -> StackEvent.Reset
            7 -> StackEvent.SetHeight(any, heights.random(rnd))
            8 -> StackEvent.TapHeader(if (rnd.nextBoolean()) s.top.id else any)
            9 -> StackEvent.RootHeaderControl
            10 -> StackEvent.MapUserGesture(rnd.nextBoolean())
            11 -> StackEvent.ExpandPanel
            12 -> StackEvent.SetLayout(rnd.nextInt(5) == 0)
            13 -> StackEvent.DragStarted(s.top.id)
            14 -> StackEvent.Released(s.top.id, heights.random(rnd).takeIf { rnd.nextBoolean() }, rnd.nextBoolean())
            else -> if (rnd.nextBoolean()) StackEvent.Settled(any) else StackEvent.SlidOut(any)
        }
    }

    private fun checkInvariants(before: StackState, event: StackEvent, step: StackStep, rootId: SheetId) {
        val s = step.state
        val ctx = "after $event from $before"
        // the root never closes and is never pushed
        assertEquals(ctx, rootId, s.root.id)
        assertTrue(ctx, s.pushed.none { it.id == rootId })
        // ids are unique and new ids are fresh
        val ids = s.levels.map { it.id.raw }
        assertEquals(ctx, ids.size, ids.toSet().size)
        assertTrue(ctx, ids.all { it < s.nextId })
        // at most one Filter level; never a device directly over a device (S-4)
        assertTrue(ctx, s.levels.count { it.kind == SheetKind.Filter } <= 1)
        assertTrue(ctx, s.levels.zipWithNext().none { (a, b) -> a.kind is SheetKind.Device && b.kind is SheetKind.Device })
        // the transition is consistent with the levels
        when (val t = s.transition) {
            StackTransition.Idle -> Unit
            is StackTransition.SlidingIn -> {
                assertTrue(ctx, !s.tablet)
                assertEquals(ctx, s.levels[s.levels.size - 2].id, t.under)
            }
            is StackTransition.SlidingOut -> {
                assertTrue(ctx, t.leaving.isNotEmpty())
                assertTrue(ctx, t.leaving.none { l -> ids.contains(l.id.raw) })
            }
        }
        // S-9
        assertEquals(ctx, !s.tablet && s.pushed.isNotEmpty() && s.top.height == SheetHeight.Full, s.scrimVisible)
        // Back always pops exactly the top, and only a pushed level
        if (event is StackEvent.Pop) {
            if (before.pushed.isEmpty()) assertEquals(ctx, before, s)
            else assertEquals(ctx, before.pushed.dropLast(1), s.pushed)
        }
        // effects name levels that exist (or just left), and TopChanged names the new top
        step.effects.filterIsInstance<StackEffect.TopChanged>().forEach { assertEquals(ctx, s.top.id, it.id) }
        step.effects.filterIsInstance<StackEffect.Removed>().forEach { r -> assertTrue(ctx, ids.none { it == r.level.id.raw }) }
        // a removed level is announced exactly once
        val removed = step.effects.filterIsInstance<StackEffect.Removed>().map { it.level.id }
        assertEquals(ctx, removed.size, removed.toSet().size)
        assertEquals(ctx, (before.levels.map { it.id } - s.levels.map { it.id }.toSet()).toSet(), removed.toSet())
    }

    @Test
    fun `random event sequences keep the stack invariants`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            var s = StackState.initial(SheetKind.Discover, SheetHeight.Collapsed)
            val rootId = s.root.id
            repeat(500) {
                val event = randomEvent(rnd, s)
                val step = reduceStack(s, event)
                checkInvariants(s, event, step, rootId)
                s = step.state
            }
        }
    }

    @Test
    fun `G-14 Back pops the top until only the root is left, then does nothing`() {
        val rnd = Random(3)
        var s = StackState.initial(SheetKind.Discover, SheetHeight.Half)
        repeat(30) { s = reduceStack(s, StackEvent.Push(kinds.random(rnd), null)).state }
        var depth = s.pushed.size
        while (depth > 0) {
            val top = s.top
            val step = reduceStack(s, StackEvent.Pop(PopReason.Back))
            assertEquals(depth - 1, step.state.pushed.size)
            assertTrue(step.effects.contains(StackEffect.Removed(top)))
            s = step.state
            depth--
        }
        assertEquals(s, reduceStack(s, StackEvent.Pop(PopReason.Back)).state)
    }
}
