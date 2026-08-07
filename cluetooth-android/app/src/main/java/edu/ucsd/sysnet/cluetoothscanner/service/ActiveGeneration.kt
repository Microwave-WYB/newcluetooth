package edu.ucsd.sysnet.cluetoothscanner.service

/** Pure lifecycle token used to reject callbacks queued by an older/stopped request. */
internal class ActiveGeneration {
    private var generation: Long = 0
    private var active: Boolean = false

    fun activate(): Long {
        generation += 1
        active = true
        return generation
    }

    fun invalidate() {
        generation += 1
        active = false
    }

    fun accepts(candidate: Long): Boolean = active && candidate == generation
}
