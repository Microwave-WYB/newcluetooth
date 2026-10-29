package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import android.util.Log
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * §4.1: scan callbacks hand observations to [offer], which never blocks; one consumer batches up to [maxBatch]
 * observations or [maxDelayMs] after the first one and calls `core.ingestScans`.
 *
 * - The inbound queue holds [capacity] observations and drops the oldest on overflow; drops are counted in
 *   [dropped] (`RecorderState.dropped`).
 * - `Backpressure` keeps the batch and retries after [backpressureRetryMs]; nothing is lost while the core catches up.
 * - [flush] ingests everything offered before it; the recorder calls it after the scan-callback fence, before pause
 *   and stop, so no observation of the session arrives after the core stopped accepting them.
 * - A batch the core rejects for any other reason is dropped and counted in [rejected]; the scanner carries on.
 */
class ScanIngest(
    private val core: Deferred<CoreIngest>,
    scope: CoroutineScope,
    /** `Dispatchers.IO.limitedParallelism(1)` in production: the single `scan-batcher` thread. */
    dispatcher: CoroutineDispatcher,
    private val capacity: Int = 1_024,
    private val maxBatch: Int = 64,
    private val maxDelayMs: Long = 250,
    private val backpressureRetryMs: Long = 100,
    /** Monotonic milliseconds for the batch window (`SystemClock.elapsedRealtime` in production). */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _dropped = MutableStateFlow(0L)
    private val _rejected = MutableStateFlow(0L)

    /** Observations the full inbound queue dropped. */
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    /** Observations in batches the core refused (not backpressure). */
    val rejected: StateFlow<Long> = _rejected.asStateFlow()

    private val queue = Channel<ScanObservationInput>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { _dropped.update { it + 1 } },
    )
    private val flushes = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) { consume() }
    }

    /** From the scan callback (main thread): never blocks. */
    fun offer(observation: ScanObservationInput) {
        queue.trySend(observation)
    }

    /** Returns once everything [offer]ed before this call has reached the core (or was dropped as rejected). */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        flushes.send(done)
        done.await()
    }

    private sealed interface Next {
        data class Observation(val value: ScanObservationInput) : Next
        data class Flush(val done: CompletableDeferred<Unit>) : Next
        data object Timeout : Next
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun consume() {
        while (true) {
            val first = select {
                queue.onReceive { Next.Observation(it) }
                flushes.onReceive { Next.Flush(it) }
            }
            if (first is Next.Flush) {
                drain()
                first.done.complete(Unit)
                continue
            }
            val batch = ArrayList<ScanObservationInput>(maxBatch)
            when (first) {
                is Next.Observation -> batch += first.value
                is Next.Flush, Next.Timeout -> continue // a flush was handled above; a timeout needs a batch
            }
            val deadline = nowMs() + maxDelayMs
            var flush: CompletableDeferred<Unit>? = null
            while (batch.size < maxBatch && flush == null) {
                // Prefer what is already queued; wait only for the rest of the window.
                val queued = queue.tryReceive().getOrNull()
                if (queued != null) {
                    batch += queued
                    continue
                }
                val remaining = deadline - nowMs()
                if (remaining <= 0) break
                when (
                    val next = select {
                        queue.onReceive { Next.Observation(it) }
                        flushes.onReceive { Next.Flush(it) }
                        onTimeout(remaining) { Next.Timeout }
                    }
                ) {
                    is Next.Observation -> batch += next.value
                    is Next.Flush -> flush = next.done
                    Next.Timeout -> break
                }
            }
            ingest(batch)
            if (flush != null) {
                drain()
                flush.complete(Unit)
            }
        }
    }

    /** Everything queued now, in batches of [maxBatch]. */
    private suspend fun drain() {
        while (true) {
            val batch = ArrayList<ScanObservationInput>(maxBatch)
            while (batch.size < maxBatch) batch += queue.tryReceive().getOrNull() ?: break
            if (batch.isEmpty()) return
            ingest(batch)
        }
    }

    private suspend fun ingest(batch: List<ScanObservationInput>) {
        val ingest = core.await()
        while (true) {
            try {
                ingest.ingestScans(batch)
                return
            } catch (backpressure: CoreException.Backpressure) {
                delay(backpressureRetryMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _rejected.update { it + batch.size }
                log("Dropped a batch of ${batch.size} the core refused", error)
                return
            }
        }
    }

    private fun log(message: String, error: Exception) {
        try {
            Log.w(TAG, message, error)
        } catch (_: RuntimeException) {
            // JVM unit tests: android.util.Log is a stub.
        }
    }

    private companion object {
        const val TAG = "ScanIngest"
    }
}
