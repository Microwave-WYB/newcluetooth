package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

data class GattTimeouts(
    val connectMs: Long = 10_000,
    /** The design only says an MTU failure is fine; waiting longer than this adds nothing. */
    val mtuMs: Long = 3_000,
    val discoverMs: Long = 15_000,
    val readEachMs: Long = 3_000,
    val readTotalMs: Long = 30_000,
    val disconnectMs: Long = 2_000,
    val retryBackoffMs: Long = 1_000,
    /** How long a read that timed out may still answer before the read queue counts as stalled. */
    val readStallGraceMs: Long = 5_000,
    /** Tries when the stack refuses a read request, and the pause between them. */
    val readRefusedTries: Int = 3,
    val readRefusedBackoffMs: Long = 200,
    val requestedMtu: Int = 517,
)

/**
 * One manual GATT discovery: the runner of [GattMachine], which decides every stage.
 *
 * Link callbacks (tagged with their attempt) and the cancel marker arrive in one inbox; the runner samples the clocks
 * and the cancel signal into the [EngineEnv], executes each [EngineEffect] on the link it owns and feeds a call's
 * synchronous answer straight back, then waits for the next input until the stage's deadline. Any exception from a
 * link call (a revoked permission, a dead stack) is that call's refusal, so a discovery always ends with a result.
 *
 * The engine never writes, subscribes or bonds. A single engine instance runs one discovery.
 */
class GattEngine(
    private val linkFactory: GattLinkFactory,
    private val timeouts: GattTimeouts = GattTimeouts(),
    /** Wall clock, for `started_at` / `ended_at` only; it may step. */
    private val nowMs: () -> Long,
    private val onProgress: (GattProgress) -> Unit = {},
    /** Monotonic clock (`SystemClock.elapsedRealtime`) for every duration and the read budget. */
    private val elapsedMs: () -> Long = nowMs,
) {
    private val inbox = Channel<EngineInput>(Channel.UNLIMITED)

    /** The one-way cancel signal; the runner samples it into every reduction. Lock-free. */
    private val cancelSignal = AtomicBoolean(false)

    /** Safe from any thread; the discovery keeps what it has and ends as `cancelled`. */
    fun cancel() {
        cancelSignal.set(true)
        inbox.trySend(EngineInput.Cancel)
    }

    suspend fun run(request: GattRequest): GattDiscoveryResult {
        var link: GattLink? = null
        try {
            var step = GattMachine.start(timeouts, request, env())
            while (true) {
                val pending = ArrayDeque(step.effects)
                while (pending.isNotEmpty()) {
                    when (val effect = pending.removeFirst()) {
                        is EngineEffect.Finish -> return effect.result
                        is EngineEffect.Progress -> onProgress(effect.progress)
                        is EngineEffect.Connect -> {
                            val opened = (call(null) { linkFactory.connect { event -> inbox.trySend(EngineInput.Link(effect.attempt, event)) } } as? Call.Ok)?.value
                            link = opened
                            step = feed(step, if (opened == null) EngineInput.ConnectRejected else EngineInput.Linked, pending)
                        }
                        EngineEffect.ReadPhy -> when (call(Unit) { link?.readPhy() }) {
                            is Call.Ok -> Unit
                            Call.Crashed -> step = feed(step, EngineInput.LinkFailed, pending)
                        }
                        is EngineEffect.RequestMtu -> step = feed(step, answer(call(false) { link?.requestMtu(effect.mtu) ?: false }, EngineInput::MtuRequested), pending)
                        EngineEffect.DiscoverServices -> step = feed(step, answer(call(false) { link?.discoverServices() ?: false }, EngineInput::DiscoverRequested), pending)
                        EngineEffect.ListServices -> step = feed(step, answer(call(null) { link?.services() ?: emptyList() }) { services ->
                            // The service list has no refusal: any exception from it is a stack failure.
                            services?.let(EngineInput::Listed) ?: EngineInput.LinkFailed
                        }, pending)
                        is EngineEffect.Read -> step = feed(step, answer(call(false) { link?.read(effect.ref) ?: false }, EngineInput::ReadRequested), pending)
                        // A failing disconnect or close changes nothing: the link is gone either way.
                        EngineEffect.Disconnect -> call(Unit) { link?.disconnect() }
                        EngineEffect.Close -> {
                            call(Unit) { link?.close() }
                            link = null
                        }
                    }
                }
                // Every wait has a deadline; a stage without one would be a machine bug, and times out at once.
                val until = step.model.wait.until ?: elapsedMs()
                val input = withTimeoutOrNull((until - elapsedMs()).coerceAtLeast(0)) { inbox.receive() } ?: EngineInput.TimedOut
                step = GattMachine.reduce(step.model, input, env())
            }
        } catch (cancelled: CancellationException) {
            // The caller's coroutine was cancelled (app teardown): drop the connection too.
            link?.let {
                call(Unit) { it.disconnect() }
                call(Unit) { it.close() }
            }
            throw cancelled
        }
    }

    /** Reduces a call's synchronous answer; its effects run before the rest of the current batch. */
    private fun feed(step: EngineStep, input: EngineInput, pending: ArrayDeque<EngineEffect>): EngineStep {
        val next = GattMachine.reduce(step.model, input, env())
        pending.addAll(0, next.effects)
        return next
    }

    private fun env() = EngineEnv(now = elapsedMs(), wallMs = nowMs(), cancelled = cancelSignal.get())

    /** A link call's answer: a refusal (`SecurityException`) is [fallback]; any other exception is [Call.Crashed]. */
    private sealed interface Call<out T> {
        data class Ok<T>(val value: T) : Call<T>
        data object Crashed : Call<Nothing>
    }

    private inline fun <T> call(fallback: T, block: () -> T): Call<T> = try {
        Call.Ok(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        Call.Ok(fallback)
    } catch (_: Exception) {
        Call.Crashed
    }

    private inline fun <T> answer(call: Call<T>, toInput: (T) -> EngineInput): EngineInput = when (call) {
        is Call.Ok -> toInput(call.value)
        Call.Crashed -> EngineInput.LinkFailed
    }
}
