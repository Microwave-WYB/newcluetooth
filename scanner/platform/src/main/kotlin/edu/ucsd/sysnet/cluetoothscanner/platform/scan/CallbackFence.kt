package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import kotlinx.coroutines.CompletableDeferred

/** Posts a marker behind every scan callback already queued on the main looper; [await] returns once it ran. */
internal class CallbackFence(
    private val post: (Runnable) -> Boolean,
) {
    fun enqueue(afterFence: () -> Unit) {
        // A looper that refuses the post is quitting: nothing queued will run any more, so the fence is passed.
        if (!post(Runnable(afterFence))) afterFence()
    }

    suspend fun await() {
        val drained = CompletableDeferred<Unit>()
        enqueue { drained.complete(Unit) }
        drained.await()
    }
}
