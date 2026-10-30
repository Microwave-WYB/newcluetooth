package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Connecting
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.Disconnected
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.DiscoveringServices
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind.ReadingCharacteristics
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState.Done
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState.Failed
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState.Running
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-13 and D-13.8 (cancel on pop) on the plain state machine, with a scripted runner. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattControllerTest {
    private fun step(kind: GattStepKind, state: GattStepState, failure: GattFailure? = null) =
        GattStep(kind, state, durationMs = 800, services = null, read = null, readable = null, failure = failure)

    private fun view(ok: Boolean, vararg steps: GattStep, services: List<GattServiceView> = emptyList()) =
        GattView("d1", key = 7, startedMs = 0, ok = ok, steps = steps.toList(), services = services)

    private val battery = GattServiceView("180F", "Battery", listOf(GattCharView("2A19", "Battery Level", listOf("read"), "78 %")))

    private val success = listOf(
        view(false, step(Connecting, Running)),
        view(false, step(Connecting, Done), step(DiscoveringServices, Running)),
        view(false, step(Connecting, Done), step(DiscoveringServices, Done), step(ReadingCharacteristics, Running)),
        view(true, step(Connecting, Done), step(DiscoveringServices, Done), step(ReadingCharacteristics, Done), step(Disconnected, Done), services = listOf(battery)),
    )
    private val timedOut = listOf(
        view(false, step(Connecting, Running)),
        view(false, step(Connecting, Failed, GattFailure.TimedOut)),
    )

    private fun runner(block: (Long) -> Flow<GattView>) = object : GattRunner {
        override fun discover(key: Long): Flow<GattView> = block(key)
    }

    private fun script(views: List<GattView>) = runner { flow { views.forEach { delay(100); emit(it) } } }

    private fun TestScope.controller(views: List<GattView>) = GattController(7, this, script(views), clock = { 0 })

    @Test
    fun `D-13 idle, the prompt every time, cancel back to idle`() = runTest {
        val g = controller(success)
        assertEquals(GattUi.Idle, g.state)
        g.onDiscover()
        assertEquals(GattUi.Confirm, g.state)
        g.onCancelPrompt()
        assertEquals(GattUi.Idle, g.state)
        g.onDiscover()
        assertEquals(GattUi.Confirm, g.state) // asked again, never remembered
    }

    @Test
    fun `D-13 steps appear one by one, then the services`() = runTest {
        val g = controller(success)
        g.onDiscover()
        g.onAuthorized()
        assertTrue(g.state is GattUi.Running) // Connecting shows at once
        assertEquals(listOf(Connecting), (g.state as GattUi.Running).view.steps.map { it.kind })
        runCurrent()
        advanceTimeBy(201)
        assertEquals(listOf(Connecting, DiscoveringServices), (g.state as GattUi.Running).view.steps.map { it.kind })
        advanceUntilIdle()
        val done = g.state as GattUi.Done
        assertEquals(listOf(battery), done.view.services)
    }

    @Test
    fun `D-13 a failure stops at the failed row, and Try again goes straight to the prompt`() = runTest {
        val g = controller(timedOut)
        g.onDiscover()
        g.onAuthorized()
        advanceUntilIdle()
        val failed = g.state as GattUi.Failed
        // D-13.5: the failed row, then "Disconnected" once the link is closed
        assertEquals(GattFailure.TimedOut, failed.view.steps.single { it.state == Failed }.failure)
        assertEquals(Disconnected to Done, failed.view.steps.last().let { it.kind to it.state })
        g.onTryAgain()
        assertEquals(GattUi.Confirm, g.state)
    }

    @Test
    fun `D-13 a device already read opens on its last result`() = runTest {
        val g = controller(success)
        g.showLast(success.last())
        assertTrue(g.state is GattUi.Done)
        val f = controller(timedOut)
        f.showLast(timedOut.last())
        assertTrue(f.state is GattUi.Failed)
        val none = controller(success)
        none.showLast(null)
        assertEquals(GattUi.Idle, none.state)
    }

    @Test
    fun `D-13-8 popping the sheet cancels a running discovery`() = runTest {
        var collecting = false
        var cancelled = false
        val g = GattController(7, this, runner {
            flow {
                collecting = true
                try {
                    emit(view(false, step(Connecting, Running)))
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        g.onDiscover()
        g.onAuthorized()
        runCurrent()
        assertTrue(collecting)
        g.cancel()
        runCurrent()
        assertTrue(cancelled) // the runner's collector is gone, so the runner cancels the discovery
        assertEquals(GattUi.Idle, g.state)
    }

    @Test
    fun `D-13-8 through the stack - popping or replacing a device sheet cancels its discovery`() = runTest {
        val controllers = mutableMapOf<Long, GattController>()
        val stack = SheetStackController(
            scope = this,
            animator = { _, to, _, onValue -> onValue(to) },
            hooks = SheetStackHooks(onRemoved = { controllers[it.id]?.cancel() }),
        ).also { it.setScreen(915f, 1f, 0f) }
        val never = runner { flow { emit(view(false, step(Connecting, Running))); awaitCancellation() } }
        val d1 = stack.openDevice(1)
        controllers[d1.id] = GattController(1, this, never).apply { onDiscover(); onAuthorized() }
        runCurrent()
        val d2 = stack.openDevice(2) // replaces d1
        assertEquals(GattUi.Idle, controllers.getValue(d1.id).state)
        controllers[d2.id] = GattController(2, this, never).apply { onDiscover(); onAuthorized() }
        runCurrent()
        stack.pop(PopReason.Back)
        assertEquals(GattUi.Idle, controllers.getValue(d2.id).state)
        advanceUntilIdle() // nothing is left running
    }

    @Test
    fun `a run that ends without a result or a failure is not shown`() = runTest {
        val g = controller(listOf(view(false, step(Connecting, Running))))
        g.onDiscover()
        g.onAuthorized()
        advanceUntilIdle()
        assertEquals(GattUi.Idle, g.state)
    }
}
