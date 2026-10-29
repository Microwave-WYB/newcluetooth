package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where opening the core stands (§1.2.2). */
sealed interface CoreState {
    data object Opening : CoreState
    data class Ready(val core: CoreHandle) : CoreState
    /** `CoreBridge.open` threw (a corrupt or too new local.db, an I/O error…); the process stays up and can retry. */
    data class Failed(val error: Throwable) : CoreState
}

/**
 * Opens the core on [io] and never lets a failure escape: a throw becomes [CoreState.Failed], and [retry] tries
 * again. [ingest] completes only with an opened core, so :platform's pieces wait rather than fail when it cannot
 * open; the UI awaits [ready].
 */
class CoreOpener(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val open: () -> CoreHandle,
) {
    private val _state = MutableStateFlow<CoreState>(CoreState.Opening)
    val state: StateFlow<CoreState> = _state.asStateFlow()

    private val ingestReady = CompletableDeferred<CoreIngest>()
    val ingest: Deferred<CoreIngest> get() = ingestReady

    private var started = false

    /** Starts the first attempt (idempotent). */
    fun start() {
        if (started) return
        started = true
        attempt()
    }

    /** After a failure: tries to open again. */
    fun retry() {
        if (_state.value is CoreState.Failed) attempt()
    }

    /** Suspends until the core is open (forever, while it keeps failing). */
    suspend fun ready(): CoreHandle = state.filterIsInstance<CoreState.Ready>().first().core

    private fun attempt() {
        _state.value = CoreState.Opening
        scope.launch {
            val result = runCatching { withContext(io) { open() } }
            result.onSuccess { core ->
                _state.value = CoreState.Ready(core)
                ingestReady.complete(core.ingest)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = CoreState.Failed(e)
            }
        }
    }
}
