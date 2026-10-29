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
    var state by mutableStateOf<GattUi>(GattUi.Idle)
        private set

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
        val step = reduceGatt(state, event)
        state = step.state
        step.effects.forEach(::execute)
    }

    private fun execute(effect: GattEffect) {
        when (effect) {
            GattEffect.StartRun -> run = scope.launch {
                var last: GattView? = null
                runner.discover(key).collect { view ->
                    last = view
                    dispatch(GattEvent.Progress(view))
                }
                dispatch(GattEvent.Finished(last))
            }
            GattEffect.CancelRun -> {
                run?.cancel()
                run = null
            }
        }
    }
}

/** What happens to the GATT tab (D-13). */
internal sealed interface GattEvent {
    data class LastLoaded(val last: GattView?) : GattEvent
    data object Discover : GattEvent
    data object CancelPrompt : GattEvent
    data object TryAgain : GattEvent
    /** "I am authorized", at [startedMs] (the runner reads the clock). */
    data class Authorized(val key: DeviceKey, val startedMs: Long) : GattEvent
    data class Progress(val view: GattView) : GattEvent
    /** The runner's flow ended; [last] is its final view (null if it emitted none). */
    data class Finished(val last: GattView?) : GattEvent
    data object Cancel : GattEvent
}

/** What the runner does: start the collector of `GattRunner.discover`, or cancel it (D-13.8). */
internal sealed interface GattEffect {
    data object StartRun : GattEffect
    data object CancelRun : GattEffect
}

internal data class GattStepResult(val state: GattUi, val effects: List<GattEffect> = emptyList())

/** The pure D-13 logic. */
internal fun reduceGatt(state: GattUi, event: GattEvent): GattStepResult = when (event) {
    is GattEvent.LastLoaded -> {
        val last = event.last
        if (state != GattUi.Idle || last == null) GattStepResult(state) else GattStepResult(classify(last, final = true))
    }
    GattEvent.Discover -> GattStepResult(if (state == GattUi.Idle) GattUi.Confirm else state)
    GattEvent.CancelPrompt -> GattStepResult(if (state == GattUi.Confirm) GattUi.Idle else state)
    GattEvent.TryAgain -> GattStepResult(if (state is GattUi.Failed) GattUi.Confirm else state)
    is GattEvent.Authorized ->
        if (state != GattUi.Confirm) GattStepResult(state)
        else GattStepResult(GattUi.Running(starting(event.key, event.startedMs)), listOf(GattEffect.StartRun))
    is GattEvent.Progress -> GattStepResult(classify(event.view, final = false))
    is GattEvent.Finished -> GattStepResult(event.last?.let { classify(it, final = true) } ?: GattUi.Idle)
    GattEvent.Cancel -> GattStepResult(if (state is GattUi.Running) GattUi.Idle else state, listOf(GattEffect.CancelRun))
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
