package edu.ucsd.sysnet.cluetoothscanner.service

internal data class ActiveRequest<T>(
    val generation: Long,
    val callback: T,
)

/** Owns a callback and generation so stale async completions cannot stop a newer request. */
internal class ActiveRequestState<T> {
    private val generations = ActiveGeneration()
    private var activeRequest: ActiveRequest<T>? = null

    val isActive: Boolean
        get() = activeRequest != null

    fun begin(callbackFactory: (Long) -> T): ActiveRequest<T> {
        check(activeRequest == null) { "request is already active" }
        val generation = generations.activate()
        return ActiveRequest(generation, callbackFactory(generation)).also {
            activeRequest = it
        }
    }

    fun accepts(generation: Long): Boolean = generations.accepts(generation)

    fun fail(generation: Long): Boolean {
        if (!generations.accepts(generation)) return false
        generations.invalidate()
        activeRequest = null
        return true
    }

    fun stop(): T? {
        generations.invalidate()
        return activeRequest?.callback.also { activeRequest = null }
    }
}
