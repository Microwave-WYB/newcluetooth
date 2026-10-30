package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** The device sheet's GATT tab state (docs/v2-api.md §5.4). */
sealed interface GattUi {
    data object Idle : GattUi
    data object Confirm : GattUi
    data class Running(val view: GattView) : GattUi
    data class Failed(val view: GattView) : GattUi
    data class Done(val view: GattView) : GattUi
}

/**
 * D-13 for one device sheet:
 * - Idle shows "Discover GATT"; tapping it asks every time ([Confirm]), never remembered (D-13.3).
 * - Cancel goes back to Idle; "I am authorized" starts the discovery and shows its steps as they arrive (D-13.4).
 * - A failed step stops the list there; "Try again" goes straight back to the prompt (D-13.5).
 * - Success shows the services (D-13.6); a device already read opens on its last result (D-13.7).
 * - Leaving or popping the sheet cancels a running discovery (D-13.8): collecting the runner's flow is the
 *   discovery, so cancelling the collector cancels it. A cancelled run is never shown.
 *
 * The last view of [runner]'s flow is final.
 *
 * Ownership (D-13.8): one controller per device sheet entry, owned by whoever composes the sheet stack (:app at
 * integration). That owner must call [cancel] when the entry leaves the stack, i.e. from `SheetStackHooks.onRemoved`
 * (pop, a device replacing a device, a reset); the stack itself does not know about GATT.
 */
class GattController(
    private val key: DeviceKey,
    private val scope: CoroutineScope,
    private val runner: GattRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var gatt by mutableStateOf(GattState())

    val state: GattUi get() = gatt.ui

    /** The running discovery: execution state, owned by this runner only. */
    private var run: Job? = null

    /** D-13.7: the stored result, when the sheet opens. A run already in progress wins. */
    fun showLast(last: GattView?) = dispatch(GattEvent.LastLoaded(last))

    fun onDiscover() = dispatch(GattEvent.Discover)

    fun onCancelPrompt() = dispatch(GattEvent.CancelPrompt)

    fun onTryAgain() = dispatch(GattEvent.TryAgain)

    fun onAuthorized() = dispatch(GattEvent.Authorized(key, clock()))

    /** D-13.8: the sheet left the stack or the composition. */
    fun cancel() = dispatch(GattEvent.Cancel)

    private fun dispatch(event: GattEvent) {
        val step = reduceGatt(gatt, event)
        gatt = step.state
        step.effects.forEach(::execute)
    }

    private fun execute(effect: GattEffect) {
        when (effect) {
            is GattEffect.StartRun -> {
                run?.cancel()
                run = scope.launch {
                    var last: GattView? = null
                    // the runner's flow, through [outcomes]: a throw becomes RunFailed, never reaches the scope
                    flow { emitAll(runner.discover(key)) }.outcomes().collect { view ->
                        when (view) {
                            is Outcome.Ok -> {
                                last = view.value
                                dispatch(GattEvent.Progress(effect.run, view.value))
                            }
                            is Outcome.Failed -> dispatch(GattEvent.RunFailed(effect.run))
                        }
                    }
                    dispatch(GattEvent.Finished(effect.run, last))
                }
            }
            GattEffect.CancelRun -> {
                run?.cancel()
                run = null
            }
        }
    }
}

/** The GATT tab's state: what it shows, and the number of the newest run (only its events count). */
internal data class GattState(val ui: GattUi = GattUi.Idle, val run: Long = 0)

/** What happens to the GATT tab (D-13). */
internal sealed interface GattEvent {
    data class LastLoaded(val last: GattView?) : GattEvent
    data object Discover : GattEvent
    data object CancelPrompt : GattEvent
    data object TryAgain : GattEvent
    /** "I am authorized", at [startedMs] (the runner reads the clock). */
    data class Authorized(val key: DeviceKey, val startedMs: Long) : GattEvent
    /** A view of run [run]. */
    data class Progress(val run: Long, val view: GattView) : GattEvent
    /** Run [run]'s flow ended; [last] is its final view (null if it emitted none). */
    data class Finished(val run: Long, val last: GattView?) : GattEvent
    /** Run [run]'s flow failed (the runner threw): the step that was running fails. */
    data class RunFailed(val run: Long) : GattEvent
    data object Cancel : GattEvent
}

/** What the runner does: start the collector of `GattRunner.discover` for run [StartRun.run], or cancel it (D-13.8). */
internal sealed interface GattEffect {
    data class StartRun(val run: Long) : GattEffect
    data object CancelRun : GattEffect
}

internal data class GattStepResult(val state: GattState, val effects: List<GattEffect> = emptyList())

/**
 * The pure D-13 logic. A run's events count only while that run shows ([GattUi.Running] with its number): a
 * cancelled or superseded run never changes the tab.
 */
internal fun reduceGatt(state: GattState, event: GattEvent): GattStepResult {
    val ui = state.ui
    fun show(next: GattUi) = GattStepResult(state.copy(ui = next))
    fun current(run: Long) = ui is GattUi.Running && run == state.run
    return when (event) {
        is GattEvent.LastLoaded -> {
            val last = event.last
            if (ui != GattUi.Idle || last == null) GattStepResult(state) else show(classify(last, final = true))
        }
        GattEvent.Discover -> show(if (ui == GattUi.Idle) GattUi.Confirm else ui)
        GattEvent.CancelPrompt -> show(if (ui == GattUi.Confirm) GattUi.Idle else ui)
        GattEvent.TryAgain -> show(if (ui is GattUi.Failed) GattUi.Confirm else ui)
        is GattEvent.Authorized ->
            if (ui != GattUi.Confirm) GattStepResult(state)
            else {
                val run = state.run + 1
                GattStepResult(GattState(GattUi.Running(starting(event.key, event.startedMs)), run), listOf(GattEffect.StartRun(run)))
            }
        is GattEvent.Progress -> if (current(event.run)) show(classify(event.view, final = false)) else GattStepResult(state)
        is GattEvent.Finished ->
            if (current(event.run)) show(event.last?.let { classify(it, final = true) } ?: GattUi.Idle) else GattStepResult(state)
        is GattEvent.RunFailed -> {
            val running = ui as? GattUi.Running
            if (running == null || !current(event.run)) GattStepResult(state) else show(GattUi.Failed(failRunning(running.view)))
        }
        GattEvent.Cancel -> GattStepResult(state.copy(ui = if (ui is GattUi.Running) GattUi.Idle else ui), listOf(GattEffect.CancelRun))
    }
}

/** The view with its running step failed (no reason claimed: the runner gave none). */
private fun failRunning(view: GattView): GattView {
    val failing = view.steps.indexOfLast { it.state == GattStepState.Running }.takeIf { it >= 0 } ?: view.steps.lastIndex
    return view.copy(
        ok = false,
        steps = view.steps.mapIndexed { i, step -> if (i == failing) step.copy(state = GattStepState.Failed) else step },
    )
}

private fun starting(key: DeviceKey, startedMs: Long) = GattView(
    discoveryId = "", key = key, startedMs = startedMs, ok = false,
    steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Running, null, null, null, null, null)),
    services = emptyList(),
)

private fun classify(view: GattView, final: Boolean): GattUi = when {
    view.steps.any { it.state == GattStepState.Failed } -> GattUi.Failed(view)
    view.ok -> GattUi.Done(view)
    final -> GattUi.Idle // ended without a result or a failure: cancelled, never shown
    else -> GattUi.Running(view)
}
