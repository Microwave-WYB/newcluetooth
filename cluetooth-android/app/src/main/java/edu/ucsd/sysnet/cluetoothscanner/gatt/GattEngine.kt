package edu.ucsd.sysnet.cluetoothscanner.gatt

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
    val requestedMtu: Int = 517,
)

/**
 * One manual GATT discovery as an explicit stage machine:
 * connect, MTU, discover services, read every readable characteristic and every
 * descriptor, disconnect — always closing the client interface.
 *
 * The engine never writes, subscribes or bonds. Anything that goes wrong closes the
 * connection and keeps what was collected as a partial result. Status 133 before any
 * read is retried once after a backoff. A single engine instance runs one discovery.
 */
class GattEngine(
    private val linkFactory: GattLinkFactory,
    private val timeouts: GattTimeouts = GattTimeouts(),
    private val nowMs: () -> Long,
    private val onProgress: (GattProgress) -> Unit = {},
) {
    private class Tagged(val attempt: Int, val event: GattEvent?)

    private sealed interface Wait<out T> {
        data class Got<T>(val value: T) : Wait<T>
        data class Disconnected(val status: Int) : Wait<Nothing>
        data object TimedOut : Wait<Nothing>
        data object Cancelled : Wait<Nothing>
    }

    private class Run {
        var stage = GattStage.CONNECT
        var outcome = GattOutcome.FAILED
        var error: GattError? = null
        var disconnect: GattDisconnect? = null
        var mtu: Int? = null
        var connectMs: Long? = null
        var mtuMs: Long? = null
        var discoverMs: Long? = null
        var readMs: Long? = null
        var services: List<GattServiceResult> = emptyList()

        fun resetForRetry() {
            stage = GattStage.CONNECT
            error = null
            disconnect = null
            mtu = null
            connectMs = null
            mtuMs = null
            discoverMs = null
            readMs = null
            services = emptyList()
        }
    }

    private val inbox = Channel<Tagged>(Channel.UNLIMITED)

    @Volatile
    private var cancelRequested = false

    /** Safe from any thread; the discovery keeps what it has and ends as `cancelled`. */
    fun cancel() {
        cancelRequested = true
        inbox.trySend(Tagged(0, null))
    }

    suspend fun run(request: GattRequest): GattDiscoveryResult {
        val startedAt = nowMs()
        val run = Run()
        var attempt = 1
        while (true) {
            val retry = runAttempt(attempt, run)
            if (!retry) break
            // Android's generic status 133 is frequently transient; one fresh attempt.
            val backoff = await<Unit>(attempt = RETRY_WAIT, timeoutMs = timeouts.retryBackoffMs) { null }
            if (backoff is Wait.Cancelled) {
                run.outcome = GattOutcome.CANCELLED
                break
            }
            run.resetForRetry()
            observedPhy = null
            attempt++
        }
        return GattDiscoveryResult(
            request = request,
            startedAtMs = startedAt,
            // The wall clock may step back mid-run; the contract needs ended >= started.
            endedAtMs = maxOf(nowMs(), startedAt),
            outcome = run.outcome,
            stageReached = if (run.outcome == GattOutcome.COMPLETE) GattStage.DISCONNECT else run.stage,
            error = run.error,
            disconnect = run.disconnect,
            mtu = run.mtu,
            phy = observedPhy,
            timings = GattTimings(run.connectMs, run.mtuMs, run.discoverMs, run.readMs),
            services = run.services,
        )
    }

    /** Returns true when the attempt failed in a way worth one retry. */
    private suspend fun runAttempt(attempt: Int, run: Run): Boolean {
        val link = try {
            linkFactory.connect { event -> inbox.trySend(Tagged(attempt, event)) }
        } catch (error: Exception) {
            run.error = GattError(GattStage.CONNECT, GattStatus.FAILURE, "CALL_REJECTED")
            run.outcome = GattOutcome.FAILED
            return false
        }
        try {
            enter(run, GattStage.CONNECT, attempt)
            val connectStart = nowMs()
            when (val waited = await<Unit>(attempt, timeouts.connectMs) { if (it is GattEvent.Connected) Unit else null }) {
                is Wait.Got -> run.connectMs = elapsedSince(connectStart)
                is Wait.Disconnected -> return failRemote(run, waited.status, attempt)
                Wait.TimedOut -> return failTimeout(run, link, attempt, GattStage.CONNECT)
                Wait.Cancelled -> return cancelled(run, link, attempt)
            }

            enter(run, GattStage.MTU, attempt)
            val mtuStart = nowMs()
            link.readPhy()
            if (link.requestMtu(timeouts.requestedMtu)) {
                when (val waited = await<GattEvent.MtuChanged>(attempt, timeouts.mtuMs) { it as? GattEvent.MtuChanged }) {
                    // A failed or unanswered MTU request only means the default MTU stays.
                    is Wait.Got -> if (waited.value.status == GattStatus.SUCCESS &&
                        waited.value.mtu in MIN_MTU..MAX_MTU
                    ) {
                        run.mtu = waited.value.mtu
                    }
                    is Wait.Disconnected -> return failRemote(run, waited.status, attempt)
                    Wait.TimedOut -> Unit
                    Wait.Cancelled -> return cancelled(run, link, attempt)
                }
            }
            run.mtuMs = elapsedSince(mtuStart)

            enter(run, GattStage.DISCOVER, attempt)
            val discoverStart = nowMs()
            if (!link.discoverServices()) {
                run.error = GattError(GattStage.DISCOVER, GattStatus.FAILURE, GattStatus.name(GattStatus.FAILURE))
                run.outcome = GattOutcome.FAILED
                disconnect(run, link, attempt, DisconnectInitiator.LOCAL)
                return false
            }
            when (val waited = await<GattEvent.ServicesDiscovered>(attempt, timeouts.discoverMs) { it as? GattEvent.ServicesDiscovered }) {
                is Wait.Got -> {
                    run.discoverMs = elapsedSince(discoverStart)
                    if (waited.value.status != GattStatus.SUCCESS) {
                        return failLocal(run, link, attempt, GattStage.DISCOVER, waited.value.status)
                    }
                }
                is Wait.Disconnected -> return failRemote(run, waited.status, attempt)
                Wait.TimedOut -> return failTimeout(run, link, attempt, GattStage.DISCOVER)
                Wait.Cancelled -> return cancelled(run, link, attempt)
            }

            readAll(attempt, run, link)
            return false
        } finally {
            link.close()
        }
    }

    private suspend fun readAll(attempt: Int, run: Run, link: GattLink) {
        val snapshots = link.services()
        val tree = snapshots.map { service ->
            service to service.characteristics.map { characteristic ->
                characteristic to arrayOfNulls<GattReadValue>(characteristic.descriptors.size)
            }
        }
        val characteristicValues = snapshots.map { service -> arrayOfNulls<GattReadValue>(service.characteristics.size) }
        val plan = buildList {
            snapshots.forEachIndexed { s, service ->
                service.characteristics.forEachIndexed { c, characteristic ->
                    if (characteristic.propertyMask and PROPERTY_READ != 0) add(AttributeRef(s, c))
                    characteristic.descriptors.indices.forEach { d -> add(AttributeRef(s, c, d)) }
                }
            }
        }
        fun assemble(): List<GattServiceResult> = tree.mapIndexed { s, (service, characteristics) ->
            GattServiceResult(
                uuid = contractUuid(service.uuid),
                primary = service.primary,
                instance = service.instance.coerceAtLeast(0),
                characteristics = characteristics.mapIndexed { c, (characteristic, descriptorValues) ->
                    GattCharacteristicResult(
                        uuid = contractUuid(characteristic.uuid),
                        instance = characteristic.instance.coerceAtLeast(0),
                        properties = propertyNames(characteristic.propertyMask),
                        value = characteristicValues[s][c] ?: GattReadValue.NOT_READ_VALUE,
                        descriptors = characteristic.descriptors.mapIndexed { d, descriptor ->
                            GattDescriptorResult(
                                contractUuid(descriptor.uuid),
                                descriptorValues[d] ?: GattReadValue.NOT_READ_VALUE,
                            )
                        },
                    )
                },
            )
        }
        fun store(ref: AttributeRef, value: GattReadValue) {
            val descriptor = ref.descriptor
            if (descriptor == null) {
                characteristicValues[ref.service][ref.characteristic] = value
            } else {
                tree[ref.service].second[ref.characteristic].second[descriptor] = value
            }
        }

        enter(run, GattStage.READ, attempt, readsDone = 0, readsTotal = plan.size)
        run.services = assemble()
        val readStart = nowMs()
        val deadline = readStart + timeouts.readTotalMs
        var done = 0
        for (ref in plan) {
            val remaining = deadline - nowMs()
            if (remaining <= 0) {
                run.error = GattError(GattStage.READ, GattStatus.TIMED_OUT, GattStatus.name(GattStatus.TIMED_OUT))
                finishRead(run, assemble(), readStart)
                run.outcome = GattOutcome.PARTIAL
                disconnect(run, link, attempt, DisconnectInitiator.TIMEOUT)
                return
            }
            if (!link.read(ref)) {
                store(ref, GattReadValue.failed(GattStatus.FAILURE))
            } else {
                val waited = await<GattEvent.AttributeRead>(attempt, minOf(timeouts.readEachMs, remaining)) {
                    (it as? GattEvent.AttributeRead)?.takeIf { read -> read.ref == ref }
                }
                when (waited) {
                    is Wait.Got -> store(
                        ref,
                        if (waited.value.status == GattStatus.SUCCESS) {
                            GattReadValue.ok(waited.value.value ?: ByteArray(0))
                        } else {
                            // Insufficient authentication/encryption included: record it and move on.
                            GattReadValue.failed(waited.value.status)
                        },
                    )
                    // A silent read is recorded as such; the total budget bounds the stage.
                    Wait.TimedOut -> store(ref, GattReadValue(null, GattReadValue.TIMEOUT))
                    is Wait.Disconnected -> {
                        run.error = GattError(GattStage.READ, waited.status, GattStatus.name(waited.status))
                        run.disconnect = GattDisconnect(DisconnectInitiator.REMOTE, waited.status)
                        finishRead(run, assemble(), readStart)
                        run.outcome = GattOutcome.PARTIAL
                        return
                    }
                    Wait.Cancelled -> {
                        finishRead(run, assemble(), readStart)
                        run.outcome = GattOutcome.CANCELLED
                        disconnect(run, link, attempt, DisconnectInitiator.LOCAL)
                        return
                    }
                }
            }
            done++
            onProgress(GattProgress(GattStage.READ, done, plan.size, attempt))
        }
        finishRead(run, assemble(), readStart)
        run.outcome = GattOutcome.COMPLETE
        enter(run, GattStage.DISCONNECT, attempt)
        disconnect(run, link, attempt, DisconnectInitiator.LOCAL)
    }

    /** Timings are never negative, even if the wall clock stepped back. */
    private fun elapsedSince(start: Long): Long = (nowMs() - start).coerceAtLeast(0)

    private fun finishRead(run: Run, services: List<GattServiceResult>, readStart: Long) {
        run.services = services
        run.readMs = elapsedSince(readStart)
    }

    private fun enter(run: Run, stage: GattStage, attempt: Int, readsDone: Int = 0, readsTotal: Int? = null) {
        run.stage = stage
        onProgress(GattProgress(stage, readsDone, readsTotal, attempt))
    }

    /** The peer (or stack) ended the connection before any read: failed, retry on 133. */
    private fun failRemote(run: Run, status: Int, attempt: Int): Boolean {
        run.error = GattError(run.stage, status, GattStatus.name(status))
        run.disconnect = GattDisconnect(DisconnectInitiator.REMOTE, status)
        run.outcome = GattOutcome.FAILED
        return status == GattStatus.ERROR && attempt == 1 && !cancelRequested
    }

    /** The connection is up but the stack reported a failure status for the stage. */
    private suspend fun failLocal(run: Run, link: GattLink, attempt: Int, stage: GattStage, status: Int): Boolean {
        run.error = GattError(stage, status, GattStatus.name(status))
        run.outcome = GattOutcome.FAILED
        disconnect(run, link, attempt, DisconnectInitiator.LOCAL)
        return status == GattStatus.ERROR && attempt == 1 && !cancelRequested
    }

    private suspend fun failTimeout(run: Run, link: GattLink, attempt: Int, stage: GattStage): Boolean {
        run.error = GattError(stage, GattStatus.TIMED_OUT, GattStatus.name(GattStatus.TIMED_OUT))
        run.outcome = GattOutcome.FAILED
        disconnect(run, link, attempt, DisconnectInitiator.TIMEOUT)
        return false
    }

    private suspend fun cancelled(run: Run, link: GattLink, attempt: Int): Boolean {
        run.outcome = GattOutcome.CANCELLED
        disconnect(run, link, attempt, DisconnectInitiator.LOCAL)
        return false
    }

    /** Asks the stack to drop the link and waits briefly for the callback; `close()` follows. */
    private suspend fun disconnect(run: Run, link: GattLink, attempt: Int, initiator: DisconnectInitiator) {
        runCatching { link.disconnect() }
        val confirmed = await<Unit>(attempt, timeouts.disconnectMs, honourCancel = false) { null }
        run.disconnect = GattDisconnect(
            initiator,
            (confirmed as? Wait.Disconnected)?.status,
        )
    }

    /**
     * Consumes events of [attempt] until [pick] accepts one, the link disconnects, the
     * operator cancels or [timeoutMs] passes. Events of other attempts are stale.
     */
    private suspend fun <T> await(
        attempt: Int,
        timeoutMs: Long,
        honourCancel: Boolean = true,
        pick: (GattEvent) -> T?,
    ): Wait<T> {
        if (honourCancel && cancelRequested) return Wait.Cancelled
        val result: Wait<T>? = withTimeoutOrNull(timeoutMs) {
            var found: Wait<T>? = null
            while (found == null) {
                val tagged = inbox.receive()
                if (honourCancel && cancelRequested) {
                    found = Wait.Cancelled
                    continue
                }
                if (tagged.attempt != attempt) continue
                val event = tagged.event ?: continue
                if (event is GattEvent.PhyRead && event.status == GattStatus.SUCCESS &&
                    event.tx in PHY_RANGE && event.rx in PHY_RANGE
                ) {
                    observedPhy = GattPhy(event.tx, event.rx)
                }
                found = when (event) {
                    is GattEvent.Disconnected -> Wait.Disconnected(event.status)
                    else -> pick(event)?.let { Wait.Got(it) }
                }
            }
            found
        }
        return result ?: Wait.TimedOut
    }

    @Volatile
    private var observedPhy: GattPhy? = null

    private companion object {
        /** Attempt tag no link ever uses; the retry backoff only listens for cancel. */
        const val RETRY_WAIT = -1
        const val MIN_MTU = 23
        const val MAX_MTU = 517
        val PHY_RANGE = 1..3
    }
}
