package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Full
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * S-16a: every height change the finger isn't driving animates (`--t-page`): right after the change nothing shown has
 * moved, and the sheet then reaches its new place through one animation of the page duration. Only a drag in
 * progress moves a sheet directly; with reduced motion the animation takes no time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeightsAnimateTest {
    private class Recorder {
        val calls = mutableListOf<Triple<Float, Float, Int>>()
        val animator = OffsetAnimator { from, to, ms, onValue ->
            calls += Triple(from, to, ms)
            onValue(from)
            delay(ms.toLong())
            onValue(to)
        }
    }

    private fun TestScope.stack(rec: Recorder, reduced: Boolean = false, rootHeight: SheetHeight = Collapsed) =
        SheetStackController(this, rec.animator, SheetMetrics(reducedMotion = reduced), rootHeight = rootHeight)
            .also { it.setScreen(915f, 1f, 0f) }

    /** Runs [change]; checks that nothing shown jumped and that the top moved only through an animation. */
    private fun TestScope.assertAnimates(s: SheetStackController, rec: Recorder, what: String, ms: Int, change: () -> Unit) {
        advanceUntilIdle()
        val before = s.rendered.associateWith { s.offsetOf(it) }
        val topBefore = s.offsetOf(s.top)
        rec.calls.clear()
        change()
        // right after the change, before any frame: nothing shown before and still shown has moved (no jump)
        for ((e, was) in before) if (e in s.rendered) assertEquals("$what: ${e.kind} jumped", was, s.offsetOf(e), 0.5f)
        runCurrent()
        advanceUntilIdle()
        val top = s.top
        val target = s.geometry(top).offsetOf(top.height)
        assertEquals("$what: the top rests at its target", target, s.offsetOf(top), 0.5f)
        val start = before[top] ?: topBefore
        if (abs(target - start) > 0.5f) {
            assertTrue(
                "$what: no ${ms} ms animation from $start to $target in ${rec.calls}",
                rec.calls.any { (from, to, d) -> abs(to - target) <= 0.5f && d == ms && (abs(from - start) <= 0.5f || top !in before) },
            )
        }
    }

    private val product = SheetKind.Product("p")

    private fun scenarios(s: SheetStackController): List<Pair<String, () -> Unit>> = listOf(
        "snap after release (setHeight)" to { s.setHeight(s.top, Half) },
        "a tap on the handle" to { s.tapHeader(s.top) },
        "raising the root (S-12)" to { s.setHeight(s.root, Collapsed); s.onRootHeaderControl() },
        "opening a sheet" to { s.push(product) },
        "popping by X (reveal below)" to { s.pop(PopReason.Close) },
        "a map gesture" to { s.onMapUserGesture() },
        "the keyboard (the screen shrinks)" to { s.setScreen(600f, 1f, 0f) },
        "the keyboard closing" to { s.setScreen(915f, 1f, 0f) },
        "rotation (the screen changes)" to { s.setScreen(412f, 1f, 0f) },
        "back" to { s.setScreen(915f, 1f, 0f) },
        "a header remeasure (peek)" to { s.setHeight(s.top, Collapsed); s.setPeek(s.top, 160f) },
        "to tablet and back (layout mode)" to { s.setLayout(true); s.setLayout(false) },
        "a device replacing a device" to { s.openDevice(1); s.openDevice(2) },
        "search returning (reset, then the saved height)" to { s.push(product); s.reset(); s.setHeight(s.root, Full) },
    )

    @Test
    fun `S-16a every height change the finger isn't driving animates for --t-page`() = runTest {
        val rec = Recorder()
        val s = stack(rec, rootHeight = Half)
        val pageMs = SheetMetrics().pageMs
        for ((what, change) in scenarios(s)) assertAnimates(s, rec, what, pageMs, change)
    }

    @Test
    fun `S-16a with reduced motion the same paths take no time, and still nothing jumps mid-frame`() = runTest {
        val rec = Recorder()
        val s = stack(rec, reduced = true, rootHeight = Half)
        for ((what, change) in scenarios(s)) assertAnimates(s, rec, what, 0, change)
    }

    @Test
    fun `S-16a search returning - the root shows where the top was, then eases to its height`() = runTest {
        val rec = Recorder()
        val s = stack(rec, rootHeight = Collapsed)
        val p = s.push(product)
        s.setHeight(p, Full)
        advanceUntilIdle()
        val topWas = s.offsetOf(p)
        s.reset()
        runCurrent()
        assertEquals(topWas, s.offsetOf(s.root), 0.5f) // under the top: nothing visibly changes
        advanceUntilIdle()
        assertEquals(s.geometry(s.root).offsetOf(Collapsed), s.offsetOf(s.root), 0.5f)
    }

    @Test
    fun `S-10a only a drag in progress moves the sheet directly`() = runTest {
        val rec = Recorder()
        val s = stack(rec, rootHeight = Half)
        advanceUntilIdle()
        val root = s.root
        val start = s.offsetOf(root)
        s.beginDrag(root, DragRegion.Header, 200f, start + 10f, 0)
        rec.calls.clear()
        assertTrue(s.dragMove(root, 200f, start + 80f, 16))
        assertEquals(start + 70f, s.offsetOf(root), 0.5f) // followed the finger, no animation
        assertTrue(rec.calls.isEmpty())
        s.endDrag(root)
        runCurrent()
        assertTrue(rec.calls.isNotEmpty()) // the snap after release animates
    }

    /** Moves halfway at half the duration, so a sheet can be caught mid-animation; logs each write by call. */
    private class Stepped {
        val writes = mutableListOf<Pair<Int, Float>>()
        var started = 0
        val animator = OffsetAnimator { from, to, ms, onValue ->
            val call = ++started
            val write = { v: Float -> writes += call to v; onValue(v) }
            write(from)
            delay(ms / 2L)
            write((from + to) / 2)
            delay(ms - ms / 2L)
            write(to)
        }
    }

    private val pageMs = SheetMetrics().pageMs

    @Test
    fun `S-16a a device replacing a device still sliding in starts where the old one is, then eases to its height`() = runTest {
        val rec = Stepped()
        val s = SheetStackController(this, rec.animator, SheetMetrics(), rootHeight = Half).also { it.setScreen(915f, 1f, 0f) }
        advanceUntilIdle()
        val old = s.openDevice(1)
        runCurrent()
        advanceTimeBy(pageMs / 2L + 1)
        val mid = s.offsetOf(old)
        val target = s.geometry(old).offsetOf(old.height)
        assertTrue("caught mid-slide: $mid vs $target", abs(mid - target) > 0.5f && abs(mid - s.geometry(old).dismissed) > 0.5f)
        val new = s.openDevice(2)
        assertEquals("the new sheet shows where the old one was", mid, s.offsetOf(new), 0.5f)
        runCurrent()
        assertEquals(mid, s.offsetOf(new), 0.5f)
        advanceUntilIdle()
        assertEquals(s.geometry(new).offsetOf(new.height), s.offsetOf(new), 0.5f)
    }

    @Test
    fun `S-16a a new animation of a sheet cancels the running one, so two never move it`() = runTest {
        val rec = Stepped()
        val s = SheetStackController(this, rec.animator, SheetMetrics(), rootHeight = Collapsed).also { it.setScreen(915f, 1f, 0f) }
        advanceUntilIdle()
        val root = s.root
        s.setHeight(root, Full)
        runCurrent()
        advanceTimeBy(pageMs / 4L)
        val first = rec.started
        s.setHeight(root, Half)
        runCurrent()
        assertTrue("the second change animates", rec.started > first)
        val after = rec.writes.size
        advanceUntilIdle()
        val late = rec.writes.drop(after).filter { it.first == first }
        assertTrue("the first animation still wrote $late", late.isEmpty())
        assertEquals(s.geometry(root).offsetOf(Half), s.offsetOf(root), 0.5f)
    }

    @Test
    fun `S-16a a closed sheet leaves no offset behind`() = runTest {
        val rec = Recorder()
        val s = stack(rec, rootHeight = Half)
        advanceUntilIdle()
        // popped by X: it slides out and ends at the bottom
        val p = s.push(product)
        advanceUntilIdle()
        s.pop(PopReason.Close)
        advanceUntilIdle()
        assertNull(s.liveOf(p.id))
        // a device replaced while it slides in
        val d = s.openDevice(1)
        runCurrent()
        s.openDevice(2)
        advanceUntilIdle()
        assertNull(s.liveOf(d.id))
    }
}
