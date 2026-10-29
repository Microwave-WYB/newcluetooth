package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The device sheet's GATT tab state (docs/v2-api.md §5.4). */
internal sealed interface GattUi {
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
 * [discover] is `GattRunner::discover` (:platform-api); the last view of its flow is final.
 */
internal class GattController(
    private val key: Long,
    private val scope: CoroutineScope,
    private val discover: (Long) -> Flow<GattView>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state by mutableStateOf<GattUi>(GattUi.Idle)
        private set

    private var run: Job? = null

    /** D-13.7: the stored result, when the sheet opens. A run already in progress wins. */
    fun showLast(last: GattView?) {
        if (state != GattUi.Idle || last == null) return
        state = classify(last, final = true)
    }

    fun onDiscover() {
        if (state == GattUi.Idle) state = GattUi.Confirm
    }

    fun onCancelPrompt() {
        if (state == GattUi.Confirm) state = GattUi.Idle
    }

    fun onTryAgain() {
        if (state is GattUi.Failed) state = GattUi.Confirm
    }

    fun onAuthorized() {
        if (state != GattUi.Confirm) return
        state = GattUi.Running(starting())
        run = scope.launch {
            var last: GattView? = null
            discover(key).collect { view ->
                last = view
                state = classify(view, final = false)
            }
            state = last?.let { classify(it, final = true) } ?: GattUi.Idle
        }
    }

    /** D-13.8: the sheet left the stack or the composition. */
    fun cancel() {
        val job = run ?: return
        run = null
        job.cancel()
        if (state is GattUi.Running) state = GattUi.Idle
    }

    private fun starting() = GattView(
        discoveryId = "", key = key, startedMs = clock(), ok = false,
        steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Running, null, null, null, null, null)),
        services = emptyList(),
    )

    private fun classify(view: GattView, final: Boolean): GattUi = when {
        view.steps.any { it.state == GattStepState.Failed } -> GattUi.Failed(view)
        view.ok -> GattUi.Done(view)
        final -> GattUi.Idle // ended without a result or a failure: cancelled, never shown
        else -> GattUi.Running(view)
    }
}
