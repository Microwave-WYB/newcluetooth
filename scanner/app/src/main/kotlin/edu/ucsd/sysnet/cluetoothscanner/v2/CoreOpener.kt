package edu.ucsd.sysnet.cluetoothscanner.v2

import android.util.Log
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's core lifecycle (§1.2.2), as an explicit state machine: Opening → Ready(core) | Failed(error). */
sealed interface CoreState {
    data object Opening : CoreState

    data class Ready(val core: CoreHandle) : CoreState

    /**
     * The core could not open, or a coroutine of :app failed. [core] is the already-open handle when the failure
     * came after opening; Retry then returns to it instead of opening the database a second time.
     */
    data class Failed(val error: Throwable, val core: CoreHandle? = null) : CoreState
}

/** What moves the [CoreState]. */
sealed interface CoreTransition {
    data class Opened(val core: CoreHandle) : CoreTransition
    data class Error(val error: Throwable) : CoreTransition
    data object Retry : CoreTransition
}

/** The transition function: the only place the core state changes. */
fun reduce(state: CoreState, t: CoreTransition): CoreState = when (t) {
    is CoreTransition.Opened -> if (state is CoreState.Opening) CoreState.Ready(t.core) else state
    is CoreTransition.Error -> CoreState.Failed(t.error, (state as? CoreState.Ready)?.core ?: (state as? CoreState.Failed)?.core)
    CoreTransition.Retry -> if (state is CoreState.Failed) CoreState.Opening else state
}

/**
 * Drives [CoreState]: opens the core on [io] (a throw is an Error transition, never a crash) and handles Retry.
 * [handler] is the CoroutineExceptionHandler every top-level scope of :app uses: it logs and moves to Failed.
 * [ingest] completes only with an opened core (never exceptionally), for :platform's `Deferred<CoreIngest>`.
 */
class CoreOpener(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: (Throwable) -> Unit = { Log.e(TAG, "core lifecycle", it) },
    private val open: () -> CoreHandle,
) {
    private val _state = MutableStateFlow<CoreState>(CoreState.Opening)
    val state: StateFlow<CoreState> = _state.asStateFlow()

    private val ingestReady = CompletableDeferred<CoreIngest>()
    val ingest: Deferred<CoreIngest> get() = ingestReady

    val handler = CoroutineExceptionHandler { _, e -> report(e) }

    private var started = false

    /** Starts the first attempt (idempotent). */
    fun start() {
        if (started) return
        started = true
        attempt()
    }

    /** Failed → Opening; opens again only when no core has opened yet. */
    fun retry() {
        val known = (_state.value as? CoreState.Failed)?.core ?: if (_state.value is CoreState.Failed) null else return
        move(CoreTransition.Retry)
        if (known != null) move(CoreTransition.Opened(known)) else attempt()
    }

    /** A failure anywhere in :app: logged, then the state is Failed. */
    fun report(e: Throwable) {
        if (e is CancellationException) return
        log(e)
        move(CoreTransition.Error(e))
    }

    /** Suspends until the core is open (it never throws; it keeps waiting while the state is Failed). */
    suspend fun ready(): CoreHandle = state.filterIsInstance<CoreState.Ready>().first().core

    private fun move(t: CoreTransition) = _state.update { reduce(it, t) }

    private fun attempt() {
        scope.launch(handler) {
            val core = withContext(io) { open() } // a throw reaches [handler]: Error → Failed
            move(CoreTransition.Opened(core))
            ingestReady.complete(core.ingest)
        }
    }

    private companion object {
        const val TAG = "CoreOpener"
    }
}
