package edu.ucsd.sysnet.cluetoothscanner.service

import kotlinx.coroutines.CompletableDeferred

enum class ScanOperatingState {
    STOPPED,
    SCANNING,
    PAUSED_BACKPRESSURE,
    BLUETOOTH_OFF,
    SCANNER_UNAVAILABLE,
    FAILED,
}

enum class ScannerStartOutcome {
    STARTED,
    DEFERRED_INTENT_RETAINED,
    REJECTED,
}

internal enum class ScanLifecycleEvent {
    DEFERRED_INTENT_RETAINED,
    RECOVERY_AVAILABLE,
    REJECTED,
}

data class ScanStatus(
    val state: ScanOperatingState = ScanOperatingState.STOPPED,
    val message: String? = null,
)

internal class CallbackFence(
    private val post: (Runnable) -> Boolean,
) {
    fun enqueue(afterFence: () -> Unit) {
        check(post(Runnable(afterFence))) { "Failed to post BLE callback fence" }
    }

    suspend fun await() {
        val drained = CompletableDeferred<Unit>()
        enqueue { drained.complete(Unit) }
        drained.await()
    }
}
