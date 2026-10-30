package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Full
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
