package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.service.ScannerStartOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScanTransitionState {
    STOPPED,
    STARTING,
    SCANNING,
    DEFERRED,
    FINALIZING,
    FINALIZATION_FAILED,
    ;

    val isRequested: Boolean
        get() = this == STARTING || this == SCANNING || this == DEFERRED
}

/** Serializes explicit user stop/start across the durable Rust session boundary. */
internal class ScanTransitionCoordinator(
    private val startSession: suspend () -> Boolean,
    private val startScanner: () -> ScannerStartOutcome,
    private val stopScannerAndFence: suspend () -> Unit,
    private val finishSession: suspend () -> Boolean,
    private val onRequestRejected: () -> Unit = {},
) {
    private val transitionMutex = Mutex()
    private val _state = MutableStateFlow(ScanTransitionState.STOPPED)
    val state: StateFlow<ScanTransitionState> = _state.asStateFlow()

    suspend fun start(): Boolean = transitionMutex.withLock {
        when (_state.value) {
            ScanTransitionState.SCANNING -> return@withLock true
            ScanTransitionState.DEFERRED -> return@withLock applyStartOutcome(startScanner())
            ScanTransitionState.FINALIZATION_FAILED -> return@withLock false
            ScanTransitionState.STOPPED,
            ScanTransitionState.STARTING,
            ScanTransitionState.FINALIZING,
            -> Unit
        }

        _state.value = ScanTransitionState.STARTING
        if (!startSession()) {
            _state.value = ScanTransitionState.STOPPED
            return@withLock false
        }
        applyStartOutcome(startScanner())
    }

    suspend fun scannerDeferred() = transitionMutex.withLock {
        if (_state.value == ScanTransitionState.SCANNING) {
            _state.value = ScanTransitionState.DEFERRED
        }
    }

    suspend fun resumeDeferred(): Boolean = transitionMutex.withLock {
        if (_state.value != ScanTransitionState.DEFERRED) return@withLock false
        applyStartOutcome(startScanner())
    }

    suspend fun scannerRejected(): Boolean = transitionMutex.withLock {
        if (!_state.value.isRequested) return@withLock false
        onRequestRejected()
        finalizeSession()
    }

    suspend fun stop(): Boolean = transitionMutex.withLock {
        if (_state.value == ScanTransitionState.STOPPED) return@withLock true
        finalizeSession()
    }

    private suspend fun applyStartOutcome(outcome: ScannerStartOutcome): Boolean = when (outcome) {
        ScannerStartOutcome.STARTED -> {
            _state.value = ScanTransitionState.SCANNING
            true
        }
        ScannerStartOutcome.DEFERRED_INTENT_RETAINED -> {
            _state.value = ScanTransitionState.DEFERRED
            true
        }
        ScannerStartOutcome.REJECTED -> {
            onRequestRejected()
            finalizeSession()
            false
        }
    }

    private suspend fun finalizeSession(): Boolean {
        _state.value = ScanTransitionState.FINALIZING
        stopScannerAndFence()
        return finishSessionAndUpdateState()
    }

    private suspend fun finishSessionAndUpdateState(): Boolean {
        val finished = finishSession()
        _state.value = if (finished) {
            ScanTransitionState.STOPPED
        } else {
            ScanTransitionState.FINALIZATION_FAILED
        }
        return finished
    }
}
