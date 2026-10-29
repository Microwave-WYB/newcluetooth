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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-4, S-6, S-7, S-8, S-9, S-11, S-12, P-6, T-1, T-2, T-9, V-7, G-14 on the plain stack. 1 px = 1 dp. */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetStackTest {
    private val cameraA = CameraSnapshot(-117.2, 32.9, 15.0)
    private val cameraB = CameraSnapshot(-117.1, 32.8, 18.2)

    private class Log {
        var camera: CameraSnapshot? = null
        val restored = mutableListOf<CameraSnapshot>()
        val removed = mutableListOf<SheetKind>()
        val tops = mutableListOf<Pair<SheetKind, Boolean>>()
    }

    /** Animations take their duration in virtual time: the start value at once, the end after the delay. */
    private val stepAnimator = OffsetAnimator { from, to, ms, onValue ->
        onValue(from)
        delay(ms.toLong())
        onValue(to)
    }

    private fun TestScope.stack(log: Log = Log(), rootHeight: SheetHeight = Collapsed) = SheetStackController(
        scope = this,
        animator = stepAnimator,
        hooks = SheetStackHooks(
            camera = { log.camera },
            restoreCamera = { log.restored += it },
            onTopChanged = { e, opening -> log.tops += e.kind to opening },
            onRemoved = { log.removed += it.kind },
        ),
        rootHeight = rootHeight,
    ).also { it.setScreen(915f, 1f, 0f) }

    private val product = SheetKind.Product("device--karr-bt")

    @Test
    fun `S-6 a pushed sheet slides up to Half, or to Full over a Full sheet`() = runTest {
        val s = stack()
        val p = s.push(product)
        assertEquals(Half, p.height)
        assertEquals(s.geometry(p).dismissed, s.offsetOf(p), 0f) // starts below the screen
        advanceUntilIdle()
        assertEquals(s.geometry(p).half, s.offsetOf(p), 0f)

        s.setHeight(p, Full)
        advanceUntilIdle()
        assertEquals(Full, s.push(SheetKind.Device(1)).height)
    }

    @Test
    fun `S-7 only the top sheet is drawn once the pushed sheet has slid in`() = runTest {
        val s = stack()
        val root = s.root
        val p = s.push(product)
        assertEquals(listOf(root, p), s.rendered) // sliding in over the sheet below
        advanceUntilIdle()
        assertEquals(listOf(p), s.rendered)
        assertEquals(Collapsed, root.height)      // its state is kept while hidden
    }

    @Test
    fun `S-7 dragging the incoming sheet hides the sheet below at once, and nothing below moves`() = runTest {
        val s = stack()
        val root = s.root
        val p = s.push(product)
        runCurrent()
        s.beginDrag(p, DragRegion.Header, 200f, 600f, 0)
        assertTrue(s.dragMove(p, 200f, 640f, 16))
        assertEquals(listOf(p), s.rendered)
        assertNull(root.liveOffset)
        assertEquals(s.geometry(root).collapsed, s.offsetOf(root), 0f)
    }

    @Test
    fun `S-8 pop by X, Back, scrim or empty map - revealed at the popped offset, then eases to its saved height`() {
        for (reason in listOf(PopReason.Close, PopReason.Back, PopReason.Scrim, PopReason.EmptyMap)) runTest {
            val log = Log()
            val s = stack(log, rootHeight = Collapsed)
            val root = s.root
            root.scrollReader = { ScrollSnapshot(7, 30) }
            log.camera = cameraA
            val p = s.push(product)
            advanceUntilIdle()
            root.scrollReader = null // hidden: not composed, so only the snapshot saved at the push is left
            val at = s.offsetOf(p)

            assertTrue(s.pop(reason))
            assertEquals(reason.name, at, s.offsetOf(root), 0f)  // nothing visibly changes
            assertEquals(listOf(root, p), s.rendered)          // the popped sheet slides out over it
            runCurrent()
            assertEquals(at, s.offsetOf(root), 0f)
            advanceUntilIdle()
            assertEquals(s.geometry(root).collapsed, s.offsetOf(root), 0f) // its saved height
            assertEquals(s.geometry(p).dismissed, p.liveOffset!!, 0f)
            assertEquals(listOf(root), s.rendered)
            assertEquals(ScrollSnapshot(7, 30), root.scroll)
            assertEquals(listOf(cameraA), log.restored)
            assertEquals(root.kind to false, log.tops.last())
        }
    }

    @Test
    fun `S-8 pop by a drag past Collapsed - the revealed sheet shows at its saved height at once`() = runTest {
        val log = Log()
        val s = stack(log, rootHeight = Full)
        log.camera = cameraB
        val p = s.push(product)
        advanceUntilIdle()
        s.setHeight(p, Collapsed)
        advanceUntilIdle()
        s.beginDrag(p, DragRegion.Header, 200f, 850f, 0)
        for (i in 1..5) s.dragMove(p, 200f, 850f + 15f * i, 8L * i)
        val release = s.endDrag(p)!!
        assertEquals(SheetHeight.Dismissed, release.target)
        assertEquals(1, s.entries.size)
        assertNull(s.root.liveOffset)
        assertEquals(s.geometry(s.root).full, s.offsetOf(s.root), 0f)
        assertEquals(listOf(s.root, p), s.rendered) // still sliding out
        advanceUntilIdle()
        assertEquals(listOf(s.root), s.rendered)
        assertEquals(listOf(cameraB), log.restored)
    }

    @Test
    fun `S-8 the pop restores the height, scroll and camera saved at each level`() = runTest {
        val log = Log()
        val s = stack(log, rootHeight = Half)
        val root = s.root
        root.scrollReader = { ScrollSnapshot(12, 4) }
        log.camera = cameraA
        val p = s.push(product)
        advanceUntilIdle()
        s.setHeight(p, Full)
        p.scrollReader = { ScrollSnapshot(3, 0) }
        log.camera = cameraB
        val d = s.push(SheetKind.Device(42))
        advanceUntilIdle()
        assertEquals(Full, d.height)

        s.pop(PopReason.Back)
        advanceUntilIdle()
        assertSame(p, s.top)
        assertEquals(Full, p.height)
        assertEquals(ScrollSnapshot(3, 0), p.scroll)
        s.pop(PopReason.Close)
        advanceUntilIdle()
        assertEquals(Half, root.height)
        assertEquals(s.geometry(root).half, s.offsetOf(root), 0f)
        assertEquals(ScrollSnapshot(12, 4), root.scroll)
        assertEquals(listOf(cameraB, cameraA), log.restored)
        assertEquals(listOf<SheetKind>(SheetKind.Device(42), product), log.removed)
    }

    @Test
    fun `S-4 the root never closes, depth is unlimited, a device replaces a device on top`() = runTest {
        val log = Log()
        val s = stack(log)
        assertFalse(s.pop(PopReason.Back))
        assertFalse(s.handleBack())
        s.push(product)
        val d1 = s.openDevice(1)
        advanceUntilIdle()
        s.setHeight(d1, Full)
        advanceUntilIdle()
        val d2 = s.openDevice(2)
        assertEquals(3, s.entries.size)
        assertEquals(SheetKind.Device(2), s.top.kind)
        assertEquals(Full, d2.height)                             // same level and height
        assertEquals(listOf<SheetKind>(SheetKind.Device(1)), log.removed) // D-13.8 hook: GATT is cancelled
        assertTrue(s.handleBack())
        assertTrue(s.handleBack())
        assertFalse(s.handleBack())
    }

    @Test
    fun `S-9 the scrim shows only while a pushed sheet is at Full, never on tablet`() = runTest {
        val s = stack(rootHeight = Full)
        assertFalse(s.scrimVisible) // the root alone, even at Full
        val p = s.push(product)
        assertTrue(s.scrimVisible)
        s.setHeight(p, Half)
        assertFalse(s.scrimVisible)
        s.setHeight(p, Full)
        s.setLayout(tablet = true)
        assertFalse(s.scrimVisible)
    }

    @Test
    fun `S-11 a header tap raises one state from Collapsed, otherwise collapses`() = runTest {
        val s = stack()
        s.tapHeader(s.root)
        assertEquals(Half, s.root.height)
        s.tapHeader(s.root)
        assertEquals(Collapsed, s.root.height)
        s.setHeight(s.root, Full)
        s.tapHeader(s.root)
        assertEquals(Collapsed, s.root.height)
    }

    @Test
    fun `S-12 a root header control used while Collapsed also raises it to Half`() = runTest {
        val s = stack()
        s.onRootHeaderControl()
        assertEquals(Half, s.root.height)
        s.setHeight(s.root, Full)
        s.onRootHeaderControl()
        assertEquals(Full, s.root.height)
        s.setHeight(s.root, Collapsed)
        s.push(product)
        s.onRootHeaderControl()
        assertEquals(Collapsed, s.root.height) // only the root, only while it is the top
    }

    @Test
    fun `P-6 a user map gesture collapses the top sheet only`() = runTest {
        val s = stack(rootHeight = Half)
        val p = s.push(product)
        advanceUntilIdle()
        s.onMapUserGesture()
        assertEquals(Collapsed, p.height)
        assertEquals(Half, s.root.height)
        advanceUntilIdle()
        assertEquals(s.geometry(p).collapsed, s.offsetOf(p), 0f)
    }

    @Test
    fun `S-16 heights depend on geometry only, a resting sheet follows a changed peek`() = runTest {
        val s = stack()
        val before = s.offsetOf(s.root)
        s.root.peek = 120f // the header wrapped
        assertEquals(before - 42f, s.offsetOf(s.root), 0f)
        assertEquals(Collapsed, s.root.height)
    }

    @Test
    fun `T-2 on tablet a user pan moves Open to Partial, the header toggles, the chevron opens`() = runTest {
        val s = stack(rootHeight = Half)
        s.setLayout(tablet = true)
        assertEquals(PanelHeight.Open, s.root.panel)
        s.onMapUserGesture()
        assertEquals(PanelHeight.Partial, s.root.panel)
        s.onMapUserGesture()
        assertEquals(PanelHeight.Partial, s.root.panel)
        s.tapHeader(s.root)
        assertEquals(PanelHeight.Open, s.root.panel)
        s.tapHeader(s.root)
        assertEquals(PanelHeight.Partial, s.root.panel)
        s.expandPanel()
        assertEquals(PanelHeight.Open, s.root.panel)
        assertNull(s.beginDrag(s.root, DragRegion.Header, 0f, 0f, 0)) // no drag on the panel
        assertFalse(s.onWheel(s.root, 1f, 0))                          // no wheel rule either
    }

    @Test
    fun `T-1 T-2 a pushed level opens Open and only the top is drawn, popping restores the state below`() = runTest {
        val s = stack()
        s.setLayout(tablet = true)
        s.onMapUserGesture()
        val p = s.push(product)
        assertEquals(PanelHeight.Open, p.panel)
        assertEquals(listOf(p), s.rendered)
        s.pop(PopReason.Close)
        assertEquals(listOf(s.root), s.rendered)
        assertEquals(PanelHeight.Partial, s.root.panel)
    }

    @Test
    fun `T-2 a restored Collapsed maps to Partial, never header-only`() {
        assertEquals(PanelHeight.Partial, Collapsed.toPanel())
        assertEquals(PanelHeight.Open, Half.toPanel())
        assertEquals(PanelHeight.Open, Full.toPanel())
    }

    @Test
    fun `T-9 phone to tablet opens every level, Collapsed and Half alike`() = runTest {
        val s = stack(rootHeight = Collapsed)
        s.root.panel = PanelHeight.Partial
        val p = s.push(product)
        advanceUntilIdle()
        s.setLayout(tablet = true)
        assertEquals(PanelHeight.Open, s.root.panel)
        assertEquals(PanelHeight.Open, p.panel)
        s.onMapUserGesture()
        s.setLayout(tablet = false)
        assertEquals(Collapsed, p.height) // Partial comes back Collapsed
        assertEquals(Full, s.root.height) // Open comes back Full
    }

    @Test
    fun `V-7 reset goes back to the root alone`() = runTest {
        val log = Log()
        val s = stack(log)
        s.push(product)
        s.openDevice(5)
        s.reset()
        assertEquals(listOf(s.root), s.entries)
        assertEquals(listOf(s.root), s.rendered)
        assertEquals(listOf(product, SheetKind.Device(5)), log.removed)
    }

    @Test
    fun `S-14 and S-8 a flick down from Collapsed on a pushed sheet pops it, the root stops at Collapsed`() = runTest {
        val s = stack()
        s.beginDrag(s.root, DragRegion.Header, 200f, 850f, 0)
        for (i in 1..5) s.dragMove(s.root, 200f, 850f + 15f * i, 8L * i)
        assertEquals(Collapsed, s.endDrag(s.root)!!.target)
        assertEquals(1, s.entries.size)
    }

    @Test
    fun `a touch that interrupts a snap and turns out a tap settles the sheet again`() = runTest {
        val s = stack()
        s.setHeight(s.root, Full)
        runCurrent()
        s.beginDrag(s.root, DragRegion.Content, 200f, 600f, 0)
        val release = s.endDrag(s.root)!!
        assertTrue(release.tap)
        advanceUntilIdle()
        assertEquals(s.geometry(s.root).full, s.offsetOf(s.root), 0f)
    }

    @Test
    fun `S-15 the wheel over the top sheet`() = runTest {
        val s = stack()
        val content = FakeContent()
        s.root.content = content
        assertTrue(s.onWheel(s.root, 1f, 0))
        assertEquals(Full, s.root.height)
        assertFalse(s.onWheel(s.root, 1f, 500))  // at Full the content scrolls
        content.value = 10f
        assertFalse(s.onWheel(s.root, -1f, 600)) // not at its top yet
        content.value = 0f
        assertTrue(s.onWheel(s.root, -1f, 700))
        assertEquals(Half, s.root.height)
    }

    @Test
    fun `S-10a through the controller - per move the top edge moves with the finger, ratio 1`() = runTest {
        val s = stack(rootHeight = Half)
        val e = s.root
        val top0 = s.offsetOf(e)
        s.beginDrag(e, DragRegion.Header, 200f, 600f, 0)
        var finger = 0f
        var t = 0L
        for (dy in listOf(-2f, -2f, -3f, -5f, -11f, 6f, -40f, 17f, -60f, -1f)) {
            finger += dy
            t += 16
            s.dragMove(e, 200f, 600f + finger, t)
            val moved = s.offsetOf(e) - top0
            if (e.drag!!.mode == SheetDrag.Mode.Sheet) assertEquals("finger $finger", finger, moved, 0.001f)
            else assertEquals(0f, moved, 0f)
        }
        assertEquals(1f, (s.offsetOf(e) - top0) / finger, 0.0001f)
        s.endDrag(e)
    }

    @Test
    fun `S-13 through the controller - a content drag from Half reaches Full then scrolls, sheet plus scroll equals finger`() = runTest {
        val s = stack(rootHeight = Half)
        val e = s.root
        val content = FakeContent()
        e.content = content
        val top0 = s.offsetOf(e)
        val travel = top0 - s.geometry(e).full
        s.beginDrag(e, DragRegion.Content, 200f, 800f, 0)
        var finger = 0f
        var t = 0L
        while (-finger < travel + 400f) {
            finger -= 13f
            t += 16
            s.dragMove(e, 200f, 800f + finger, t)
            assertEquals(-finger, (top0 - s.offsetOf(e)) + content.value, 0.001f)
        }
        assertEquals(s.geometry(e).full, s.offsetOf(e), 0f)
        assertTrue(content.value > 300f)
        s.endDrag(e)
        advanceUntilIdle()
        assertEquals(Full, e.height)
        assertEquals(1, content.flings.size) // released moving up at Full: the content keeps the momentum
    }
}
