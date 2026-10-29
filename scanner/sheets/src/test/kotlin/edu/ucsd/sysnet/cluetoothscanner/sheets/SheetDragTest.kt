package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetDrag.Mode
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Collapsed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Dismissed
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Full
import edu.ucsd.sysnet.cluetoothscanner.sheets.SheetHeight.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Content stand-in: [max] px of scroll range. */
internal class FakeContent(var value: Float = 0f, private val max: Float = 5000f) : ContentScroll {
    val flings = mutableListOf<Float>()
    override val canScrollBackward get() = value > 0f
    override fun scrollBy(delta: Float): Float {
        val next = (value + delta).coerceIn(0f, max)
        val used = next - value
        value = next
        return used
    }
    override suspend fun fling(velocity: Float) {
        flings += velocity
    }
}

/** G-13, S-10, S-10a, S-13, S-14 on the plain gesture state. 1 px = 1 dp. */
class SheetDragTest {
    private val g = SheetGeometry(screenHeight = 915f, topZone = 68f, peek = 78f, bottomInset = 0f, halfFraction = 0.67f, dismissGap = 8f)
    private val half = g.offsetOf(Half)

    private fun drag(
        region: DragRegion,
        from: SheetHeight,
        content: ContentScroll? = null,
        pushed: Boolean = false,
        x: Float = 200f,
        y: Float = 600f,
        t: Long = 0L,
    ) = SheetDrag(region, g, pushed, from, g.offsetOf(from), content, slop = 8f, flick = 0.4f, samples = 5, x0 = x, y0 = y, t0 = t)

    @Test
    fun `G-13 under the slop is a tap, and a mostly horizontal move is not ours`() {
        val tap = drag(DragRegion.Header, Half)
        assertFalse(tap.move(205f, 605f, 16))
        assertEquals(Mode.Pending, tap.mode)
        assertEquals(half, tap.offset, 0f)
        assertTrue(tap.release().tap)

        val sideways = drag(DragRegion.Header, Half)
        assertFalse(sideways.move(220f, 605f, 16))
        assertEquals(Mode.Ignored, sideways.mode)
        assertFalse(sideways.move(200f, 400f, 32))
        assertEquals(half, sideways.offset, 0f)
        assertEquals(null, sideways.release().target)
    }

    @Test
    fun `S-10a the sheet moves exactly with the finger from the down point, slop included`() {
        val d = drag(DragRegion.Header, Half)
        var finger = 600f
        var last = d.offset
        for ((i, dy) in listOf(-3f, -3f, -3f, -7f, -20f, 11f, 2f, -40f).withIndex()) {
            finger += dy
            d.move(200f, finger, 16L * (i + 1))
            if (d.mode == Mode.Sheet) {
                assertEquals("move $i", finger - 600f, d.offset - half, 0.001f) // total since down: no jump at the slop
                last = d.offset
            }
        }
        assertEquals(Mode.Sheet, d.mode)
        assertEquals(half + (finger - 600f), last, 0.001f)
    }

    @Test
    fun `S-10 the header always drags, even at Full with content scrolled`() {
        val content = FakeContent(value = 300f)
        val d = drag(DragRegion.Header, Full, content)
        assertTrue(d.move(200f, 610f, 16))
        assertEquals(Mode.Sheet, d.mode)
        assertEquals(g.offsetOf(Full) + 10f, d.offset, 0f)
        assertEquals(300f, content.value, 0f)
    }

    @Test
    fun `S-13 not at Full the content gesture moves the sheet`() {
        val content = FakeContent(value = 120f) // scrolled earlier at Full, then lowered
        val down = drag(DragRegion.Content, Half, content)
        assertTrue(down.move(200f, 620f, 16))
        assertEquals(Mode.Sheet, down.mode)
        assertEquals(half + 20f, down.offset, 0f)
        assertEquals(120f, content.value, 0f)
    }

    @Test
    fun `S-13 at Full with the content at its top a downward move lowers the sheet`() {
        val content = FakeContent(value = 0f)
        val d = drag(DragRegion.Content, Full, content)
        assertTrue(d.move(200f, 612f, 16))
        assertEquals(Mode.Sheet, d.mode)
        assertEquals(g.offsetOf(Full) + 12f, d.offset, 0f)
    }

    @Test
    fun `S-13 at Full otherwise it is native scroll`() {
        val up = drag(DragRegion.Content, Full, FakeContent(value = 0f))
        assertFalse(up.move(200f, 590f, 16))
        assertEquals(Mode.Native, up.mode)

        val scrolledDown = drag(DragRegion.Content, Full, FakeContent(value = 50f))
        assertFalse(scrolledDown.move(200f, 610f, 16))
        assertEquals(Mode.Native, scrolledDown.mode)
        assertEquals(g.offsetOf(Full), scrolledDown.offset, 0f)
        assertEquals(null, scrolledDown.release().target)
    }

    @Test
    fun `S-13 and S-10a an upward drag that reaches Full scrolls the rest, sheet plus scroll equal to the finger`() {
        val content = FakeContent()
        val d = drag(DragRegion.Content, Half, content, y = 800f)
        var finger = 800f
        repeat(40) { i ->
            finger -= 13f
            d.move(200f, finger, 16L * (i + 1))
            val sheetMoved = half - d.offset
            assertEquals("move $i", 800f - finger, sheetMoved + content.value, 0.001f)
        }
        assertEquals(g.offsetOf(Full), d.offset, 0f)
        assertTrue(content.value > 0f)

        // back down in the same gesture: the content scrolls back first, then the sheet lowers
        val scrolled = content.value
        repeat(10) { i -> finger += 10f; d.move(200f, finger, 700L + 16 * i) }
        assertEquals(g.offsetOf(Full), d.offset, 0f)
        assertEquals(scrolled - 100f, content.value, 0.001f)
        finger += scrolled
        d.move(200f, finger, 900)
        assertEquals(0f, content.value, 0f)
        assertEquals(g.offsetOf(Full) + 100f, d.offset, 0.001f)
        assertEquals(800f - finger, (half - d.offset) + content.value, 0.001f)
    }

    @Test
    fun `S-14 a flick moves exactly one state from the start, never skipping`() {
        // a fast upward drag from Collapsed all the way past Full still ends at Half
        val d = drag(DragRegion.Header, Collapsed, y = 880f)
        var y = 880f
        for (i in 1..10) { y -= 80f; d.move(200f, y, 10L * i) }
        assertTrue(d.velocity() < -0.4f)
        assertEquals(Half, d.release().target)

        val down = drag(DragRegion.Header, Full, y = 100f)
        for (i in 1..5) down.move(200f, 100f + 30f * i, 10L * i)
        assertEquals(Half, down.release().target)
    }

    @Test
    fun `S-14 a slow release snaps to the nearest state`() {
        val d = drag(DragRegion.Header, Collapsed, y = 880f)
        var y = 880f
        for (i in 1..60) { y -= 10f; d.move(200f, y, 100L * i) } // 0.1 dp/ms
        assertTrue(kotlin.math.abs(d.velocity()) < 0.4f)
        assertEquals(g.nearest(d.offset, pushed = false), d.release().target)
    }

    @Test
    fun `S-14 velocity is measured over the last 5 samples only`() {
        // fast at the start, still at the end: not a flick
        val stopped = drag(DragRegion.Header, Half, y = 600f)
        for (i in 1..5) stopped.move(200f, 600f - 40f * i, 5L * i)
        for (i in 1..5) stopped.move(200f, 400f, 25L + 50 * i)
        assertEquals(0f, stopped.velocity(), 0f)
        assertEquals(g.nearest(stopped.offset, pushed = false), stopped.release().target)

        // slow for a long time, then a quick last stretch: a flick
        val late = drag(DragRegion.Header, Half, y = 600f)
        var y = 600f
        for (i in 1..20) { y -= 1f; late.move(200f, y, 100L * i) }
        for (i in 1..5) { y += 30f; late.move(200f, y, 2000L + 10 * i) }
        assertTrue(late.velocity() > 0.4f)
        assertEquals(Collapsed, late.release().target)
    }

    @Test
    fun `S-14 a pushed sheet flicked down from Collapsed is dismissed`() {
        val d = drag(DragRegion.Header, Collapsed, pushed = true, y = 850f)
        for (i in 1..5) d.move(200f, 850f + 12f * i, 8L * i)
        assertEquals(Dismissed, d.release().target)
    }

    @Test
    fun `S-13 releasing at Full while scrolling hands the momentum to the content`() {
        val content = FakeContent()
        val d = drag(DragRegion.Content, Full, content, y = 700f)
        // at Full, scroll 0, the finger goes down first (lowers), then fast up past Full: scrolls
        d.move(200f, 712f, 10)
        for (i in 1..6) d.move(200f, 712f - 60f * i, 10L + 10 * i)
        val release = d.release()
        assertEquals(Full, release.target)
        assertTrue(release.contentFling > 0f)
    }

    @Test
    fun `a child that takes a pending gesture keeps it`() {
        val d = drag(DragRegion.Content, Half, FakeContent())
        d.move(203f, 601f, 8)
        d.yield()
        assertFalse(d.move(200f, 500f, 16))
        assertEquals(Mode.Ignored, d.mode)
    }
}

/** S-15. */
class WheelGateTest {
    @Test
    fun `S-15 down below Full raises to Full, up at the top lowers one state, 400 ms apart`() {
        val w = WheelGate(400)
        assertEquals(WheelGate.Step(Full), w.onWheel(1f, Collapsed, contentAtTop = true, nowMs = 0))
        assertEquals(WheelGate.Step(null), w.onWheel(1f, Half, contentAtTop = true, nowMs = 100)) // taken, locked
        assertEquals(null, w.onWheel(1f, Full, contentAtTop = true, nowMs = 1000))              // native scroll
        assertEquals(null, w.onWheel(-1f, Full, contentAtTop = false, nowMs = 1000))            // scrolls back first
        assertEquals(WheelGate.Step(Half), w.onWheel(-1f, Full, contentAtTop = true, nowMs = 1000))
        assertEquals(WheelGate.Step(null), w.onWheel(-1f, Half, contentAtTop = true, nowMs = 1399))
        assertEquals(WheelGate.Step(Collapsed), w.onWheel(-1f, Half, contentAtTop = true, nowMs = 1400))
        assertEquals(null, w.onWheel(-1f, Collapsed, contentAtTop = true, nowMs = 3000))        // never below Collapsed
    }
}
