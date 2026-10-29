package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

/**
 * One manual GATT discovery (§4.3, SPEC D-13) as an immutable [EngineModel] and a pure reducer; [GattEngine] is its
 * runner. Every stage is a sealed [EngineWait] carrying its deadline; every link call is a named [EngineEffect] whose
 * synchronous answer comes back as an [EngineInput], and link callbacks, a timeout or the cancel signal are inputs too.
 *
 * The stages: connect, MTU (optional), discover services, read every readable characteristic and every descriptor,
 * disconnect, always closing the client interface. Anything that goes wrong keeps what was collected as a partial
 * result; status 133 before any read is retried once after a backoff. Callbacks of another attempt are ignored.
 */
internal object GattMachine {
    const val MIN_MTU = 23
    const val MAX_MTU = 517
    val PHY_RANGE = 1..3

    fun start(timeouts: GattTimeouts, request: GattRequest, env: EngineEnv): EngineStep {
        val model = EngineModel(timeouts, request, startedWallMs = env.wallMs, attempt = 1, phy = null, run = RunFacts.FRESH, wait = EngineWait.ConnectRequest)
        return Builder(model, env).apply { beginAttempt() }.build()
    }

    fun reduce(model: EngineModel, input: EngineInput, env: EngineEnv): EngineStep = Builder(model, env).apply { on(input) }.build()

    /** Readable characteristics, then every descriptor, in tree order. */
    fun plan(snapshots: List<GattServiceSnapshot>): List<AttributeRef> = buildList {
        snapshots.forEachIndexed { s, service ->
            service.characteristics.forEachIndexed { c, characteristic ->
                if (characteristic.propertyMask and PROPERTY_READ != 0) add(AttributeRef(s, c))
                characteristic.descriptors.indices.forEach { d -> add(AttributeRef(s, c, d)) }
            }
        }
    }

    fun assemble(snapshots: List<GattServiceSnapshot>, values: Map<AttributeRef, GattReadValue>): List<GattServiceResult> =
        snapshots.mapIndexed { s, service ->
            GattServiceResult(
                uuid = contractUuid(service.uuid),
                primary = service.primary,
                instance = service.instance.coerceAtLeast(0),
                characteristics = service.characteristics.mapIndexed { c, characteristic ->
                    GattCharacteristicResult(
                        uuid = contractUuid(characteristic.uuid),
                        instance = characteristic.instance.coerceAtLeast(0),
                        properties = propertyNames(characteristic.propertyMask),
                        value = values[AttributeRef(s, c)] ?: GattReadValue.NOT_READ_VALUE,
                        descriptors = characteristic.descriptors.mapIndexed { d, descriptor ->
                            GattDescriptorResult(contractUuid(descriptor.uuid), values[AttributeRef(s, c, d)] ?: GattReadValue.NOT_READ_VALUE)
                        },
                    )
                },
            )
        }

    private class Builder(var model: EngineModel, val env: EngineEnv) {
        val effects = mutableListOf<EngineEffect>()
        val t get() = model.timeouts

        fun build() = EngineStep(model, effects.toList())

        private fun run(update: RunFacts.() -> RunFacts) {
            model = model.copy(run = model.run.update())
        }

        private fun waitFor(wait: EngineWait) {
            model = model.copy(wait = wait)
            // A wait that honours cancel ends at once when cancel was asked for before it began.
            if (wait.honoursCancel && env.cancelled) cancelledIn(wait)
        }

        fun beginAttempt() {
            model = model.copy(wait = EngineWait.ConnectRequest)
            effects += EngineEffect.Connect(model.attempt)
        }

        fun on(input: EngineInput) {
            val wait = model.wait
            when (input) {
                EngineInput.Linked -> if (wait == EngineWait.ConnectRequest) {
                    enter(GattStage.CONNECT)
                    waitFor(EngineWait.Connecting(since = env.now, until = env.now + t.connectMs))
                }
                EngineInput.ConnectRejected -> if (wait == EngineWait.ConnectRequest) {
                    run { copy(error = GattError(GattStage.CONNECT, GattStatus.FAILURE, "CALL_REJECTED"), outcome = GattOutcome.FAILED) }
                    endAttempt(retry = false, close = false)
                }
                is EngineInput.MtuRequested -> if (wait is EngineWait.MtuRequest) {
                    if (input.accepted) waitFor(EngineWait.Mtu(wait.since, until = env.now + t.mtuMs)) else mtuDone(wait.since)
                }
                is EngineInput.DiscoverRequested -> if (wait is EngineWait.DiscoverRequest) {
                    if (input.accepted) {
                        waitFor(EngineWait.Discovering(wait.since, until = env.now + t.discoverMs))
                    } else {
                        run { copy(error = GattError(GattStage.DISCOVER, GattStatus.FAILURE, GattStatus.name(GattStatus.FAILURE)), outcome = GattOutcome.FAILED) }
                        disconnect(DisconnectInitiator.LOCAL, retryIf133 = false)
                    }
                }
                is EngineInput.Listed -> if (wait == EngineWait.ListingServices) startReading(input.snapshots)
                is EngineInput.ReadRequested -> if (wait is EngineWait.Reading && wait.step is ReadStep.Requesting) readRequested(wait.read, wait.step, input.accepted)
                is EngineInput.Link, EngineInput.Cancel, EngineInput.TimedOut -> awaited(wait, input)
                EngineInput.LinkFailed -> if (wait !is EngineWait.Finished) linkFailed()
            }
        }

        /**
         * A link call threw something other than a refusal (a stack bug): the discovery is stored as failed at the
         * stage it reached, with nothing else kept, and the link is closed.
         */
        private fun linkFailed() {
            run {
                RunFacts.FRESH.copy(
                    stage = stage,
                    outcome = GattOutcome.FAILED,
                    error = GattError(stage, GattStatus.INTERNAL_ERROR, GattStatus.name(GattStatus.INTERNAL_ERROR)),
                )
            }
            model = model.copy(phy = null)
            effects += EngineEffect.Close
            finish()
        }

        /** An input for a stage that waits: cancel, another attempt's callback, the PHY, a disconnect or its pick. */
        private fun awaited(wait: EngineWait, input: EngineInput) {
            if (!wait.waits) return
            if (input == EngineInput.TimedOut) return timedOut(wait)
            if (wait.honoursCancel && env.cancelled) return cancelledIn(wait)
            val link = input as? EngineInput.Link ?: return
            if (wait is EngineWait.Backoff || link.attempt != model.attempt) return
            val event = link.event
            if (event is GattEvent.PhyRead && event.status == GattStatus.SUCCESS && event.tx in PHY_RANGE && event.rx in PHY_RANGE) {
                model = model.copy(phy = GattPhy(event.tx, event.rx))
            }
            if (event is GattEvent.Disconnected) return disconnectedIn(wait, event.status)
            picked(wait, event)
        }

        private fun picked(wait: EngineWait, event: GattEvent) {
            when (wait) {
                is EngineWait.Connecting -> if (event == GattEvent.Connected) {
                    run { copy(connectMs = elapsedSince(wait.since)) }
                    enter(GattStage.MTU)
                    effects += EngineEffect.ReadPhy
                    effects += EngineEffect.RequestMtu(t.requestedMtu)
                    model = model.copy(wait = EngineWait.MtuRequest(since = env.now))
                }
                is EngineWait.Mtu -> if (event is GattEvent.MtuChanged) {
                    if (event.status == GattStatus.SUCCESS && event.mtu in MIN_MTU..MAX_MTU) run { copy(mtu = event.mtu) }
                    mtuDone(wait.since)
                }
                is EngineWait.Discovering -> if (event is GattEvent.ServicesDiscovered) {
                    run { copy(discoverMs = elapsedSince(wait.since)) }
                    if (event.status != GattStatus.SUCCESS) {
                        run { copy(error = GattError(GattStage.DISCOVER, event.status, GattStatus.name(event.status)), outcome = GattOutcome.FAILED) }
                        disconnect(DisconnectInitiator.LOCAL, retryIf133 = event.status == GattStatus.ERROR)
                    } else {
                        effects += EngineEffect.ListServices
                        model = model.copy(wait = EngineWait.ListingServices)
                    }
                }
                is EngineWait.Reading -> when (val step = wait.step) {
                    is ReadStep.Value, is ReadStep.Grace ->
                        if (event is GattEvent.AttributeRead && event.ref == wait.read.current) {
                            val value = if (event.status == GattStatus.SUCCESS) GattReadValue.ok(event.value ?: ByteArray(0)) else GattReadValue.failed(event.status)
                            readDone(wait.read.store(event.ref, value))
                        }
                    is ReadStep.Requesting, is ReadStep.RefusedPause -> Unit
                }
                EngineWait.ConnectRequest, is EngineWait.MtuRequest, is EngineWait.DiscoverRequest, EngineWait.ListingServices,
                is EngineWait.Disconnecting, is EngineWait.Backoff, is EngineWait.Finished,
                -> Unit
            }
        }

        private fun timedOut(wait: EngineWait) {
            when (wait) {
                is EngineWait.Connecting -> failTimeout(GattStage.CONNECT)
                is EngineWait.Mtu -> mtuDone(wait.since)
                is EngineWait.Discovering -> failTimeout(GattStage.DISCOVER)
                is EngineWait.Reading -> when (val step = wait.step) {
                    is ReadStep.RefusedPause -> request(wait.read, step.tries + 1, step.remaining)
                    is ReadStep.Value -> {
                        // The request is still outstanding inside the stack and no further read can be issued until it
                        // answers; record the timeout but give a late callback a grace period within the read budget.
                        val read = wait.read.store(wait.read.current, GattReadValue(null, GattReadValue.TIMEOUT))
                        val grace = minOf(t.readStallGraceMs, (read.deadline - env.now).coerceAtLeast(0))
                        if (grace > 0) waitFor(EngineWait.Reading(read, ReadStep.Grace(grace, until = env.now + grace))) else stalled(read, grace)
                    }
                    is ReadStep.Grace -> stalled(wait.read, step.graceMs)
                    is ReadStep.Requesting -> Unit
                }
                is EngineWait.Disconnecting -> {
                    run { copy(disconnect = GattDisconnect(wait.initiator, null)) }
                    endAttempt(retry = wait.retryIf133 && !env.cancelled, close = true)
                }
                is EngineWait.Backoff -> {
                    // A fresh attempt after Android's generic status 133.
                    model = model.copy(attempt = model.attempt + 1, run = RunFacts.FRESH, phy = null)
                    beginAttempt()
                }
                EngineWait.ConnectRequest, is EngineWait.MtuRequest, is EngineWait.DiscoverRequest, EngineWait.ListingServices,
                is EngineWait.Finished,
                -> Unit
            }
        }

        private fun cancelledIn(wait: EngineWait) {
            when (wait) {
                is EngineWait.Connecting, is EngineWait.Mtu, is EngineWait.Discovering -> cancelled()
                is EngineWait.Reading -> readCancelled(wait.read)
                is EngineWait.Backoff -> {
                    run { copy(outcome = GattOutcome.CANCELLED) }
                    finish()
                }
                EngineWait.ConnectRequest, is EngineWait.MtuRequest, is EngineWait.DiscoverRequest, EngineWait.ListingServices,
                is EngineWait.Disconnecting, is EngineWait.Finished,
                -> Unit
            }
        }

        private fun disconnectedIn(wait: EngineWait, status: Int) {
            when (wait) {
                is EngineWait.Connecting, is EngineWait.Mtu, is EngineWait.Discovering -> failRemote(status)
                is EngineWait.Reading -> readDisconnected(wait.read, status)
                is EngineWait.Disconnecting -> {
                    run { copy(disconnect = GattDisconnect(wait.initiator, status)) }
                    endAttempt(retry = wait.retryIf133 && !env.cancelled, close = true)
                }
                EngineWait.ConnectRequest, is EngineWait.MtuRequest, is EngineWait.DiscoverRequest, EngineWait.ListingServices,
                is EngineWait.Backoff, is EngineWait.Finished,
                -> Unit
            }
        }

        private fun mtuDone(since: Long) {
            run { copy(mtuMs = elapsedSince(since)) }
            enter(GattStage.DISCOVER)
            effects += EngineEffect.DiscoverServices
            model = model.copy(wait = EngineWait.DiscoverRequest(since = env.now))
        }

        private fun startReading(snapshots: List<GattServiceSnapshot>) {
            val plan = plan(snapshots)
            run { copy(servicesFound = snapshots.size) }
            enter(GattStage.READ, readsDone = 0, readsTotal = plan.size)
            run { copy(services = assemble(snapshots, emptyMap())) }
            nextRead(ReadProgress(snapshots, plan, index = 0, values = emptyMap(), readStart = env.now, deadline = env.now + t.readTotalMs))
        }

        private fun nextRead(read: ReadProgress) {
            if (read.index >= read.plan.size) {
                finishRead(read)
                run { copy(outcome = GattOutcome.COMPLETE) }
                enter(GattStage.DISCONNECT)
                return disconnect(DisconnectInitiator.LOCAL, retryIf133 = false)
            }
            val remaining = read.deadline - env.now
            if (remaining <= 0) {
                run { copy(error = GattError(GattStage.READ, GattStatus.TIMED_OUT, GattStatus.name(GattStatus.TIMED_OUT))) }
                finishRead(read)
                run { copy(outcome = GattOutcome.PARTIAL) }
                return disconnect(DisconnectInitiator.TIMEOUT, retryIf133 = false)
            }
            request(read, tries = 0, remaining = remaining)
        }

        /** BluetoothGatt refuses a request while one is outstanding: a refusal is retried briefly. */
        private fun request(read: ReadProgress, tries: Int, remaining: Long) {
            effects += EngineEffect.Read(read.current)
            model = model.copy(wait = EngineWait.Reading(read, ReadStep.Requesting(tries, remaining)))
        }

        private fun readRequested(read: ReadProgress, step: ReadStep.Requesting, accepted: Boolean) {
            when {
                accepted -> waitFor(EngineWait.Reading(read, ReadStep.Value(until = env.now + minOf(t.readEachMs, step.remaining))))
                step.tries < t.readRefusedTries - 1 ->
                    waitFor(EngineWait.Reading(read, ReadStep.RefusedPause(step.tries, step.remaining, until = env.now + t.readRefusedBackoffMs)))
                else -> {
                    // A standing refusal is a stack problem, not a property of this attribute.
                    run { copy(error = GattError(GattStage.READ, GattStatus.FAILURE, "READ_REFUSED")) }
                    finishRead(read)
                    run { copy(outcome = GattOutcome.PARTIAL) }
                    disconnect(DisconnectInitiator.LOCAL, retryIf133 = false)
                }
            }
        }

        private fun readDone(read: ReadProgress) {
            val next = read.copy(index = read.index + 1)
            effects += EngineEffect.Progress(progress(GattStage.READ, next.index, read.plan.size))
            nextRead(next)
        }

        /** The read budget or the stall grace ended with the request still outstanding. */
        private fun stalled(read: ReadProgress, grace: Long) {
            val budgetEnded = grace < t.readStallGraceMs
            run {
                copy(
                    error = if (budgetEnded) {
                        GattError(GattStage.READ, GattStatus.TIMED_OUT, GattStatus.name(GattStatus.TIMED_OUT))
                    } else {
                        GattError(GattStage.READ, GattStatus.READ_QUEUE_STALLED, "READ_QUEUE_STALLED")
                    },
                )
            }
            finishRead(read)
            run { copy(outcome = GattOutcome.PARTIAL) }
            disconnect(DisconnectInitiator.TIMEOUT, retryIf133 = false)
        }

        private fun readCancelled(read: ReadProgress) {
            finishRead(read)
            run { copy(outcome = GattOutcome.CANCELLED) }
            disconnect(DisconnectInitiator.LOCAL, retryIf133 = false)
        }

        private fun readDisconnected(read: ReadProgress, status: Int) {
            run { copy(error = GattError(GattStage.READ, status, GattStatus.name(status)), disconnect = GattDisconnect(DisconnectInitiator.REMOTE, status)) }
            finishRead(read)
            run { copy(outcome = GattOutcome.PARTIAL) }
            endAttempt(retry = false, close = true)
        }

        private fun finishRead(read: ReadProgress) {
            run { copy(services = assemble(read.snapshots, read.values), readMs = elapsedSince(read.readStart)) }
        }

        /** The peer (or stack) ended the connection before any read: failed, retry on 133. */
        private fun failRemote(status: Int) {
            run { copy(error = GattError(stage, status, GattStatus.name(status)), disconnect = GattDisconnect(DisconnectInitiator.REMOTE, status), outcome = GattOutcome.FAILED) }
            endAttempt(retry = status == GattStatus.ERROR && model.attempt == 1 && !env.cancelled, close = true)
        }

        private fun failTimeout(stage: GattStage) {
            run { copy(error = GattError(stage, GattStatus.TIMED_OUT, GattStatus.name(GattStatus.TIMED_OUT)), outcome = GattOutcome.FAILED) }
            disconnect(DisconnectInitiator.TIMEOUT, retryIf133 = false)
        }

        private fun cancelled() {
            run { copy(outcome = GattOutcome.CANCELLED) }
            disconnect(DisconnectInitiator.LOCAL, retryIf133 = false)
        }

        /** Asks the stack to drop the link and waits briefly for the callback (cancel is not honoured here). */
        private fun disconnect(initiator: DisconnectInitiator, retryIf133: Boolean) {
            effects += EngineEffect.Disconnect
            model = model.copy(wait = EngineWait.Disconnecting(initiator, retryIf133 && model.attempt == 1, until = env.now + t.disconnectMs))
        }

        private fun endAttempt(retry: Boolean, close: Boolean) {
            if (close) effects += EngineEffect.Close
            if (retry) waitFor(EngineWait.Backoff(until = env.now + t.retryBackoffMs)) else finish()
        }

        private fun finish() {
            val run = model.run
            val result = GattDiscoveryResult(
                request = model.request,
                startedAtMs = model.startedWallMs,
                // The wall clock may step back mid-run; the contract needs ended >= started.
                endedAtMs = maxOf(env.wallMs, model.startedWallMs),
                outcome = run.outcome,
                stageReached = if (run.outcome == GattOutcome.COMPLETE) GattStage.DISCONNECT else run.stage,
                error = run.error,
                disconnect = run.disconnect,
                mtu = run.mtu,
                phy = model.phy,
                timings = GattTimings(run.connectMs, run.mtuMs, run.discoverMs, run.readMs),
                services = run.services,
            )
            model = model.copy(wait = EngineWait.Finished(result))
            effects += EngineEffect.Finish(result)
        }

        private val stage get() = model.run.stage

        private fun enter(stage: GattStage, readsDone: Int = 0, readsTotal: Int? = null) {
            run { copy(stage = stage) }
            effects += EngineEffect.Progress(progress(stage, readsDone, readsTotal))
        }

        private fun progress(stage: GattStage, readsDone: Int, readsTotal: Int?) = GattProgress(
            stage, readsDone, readsTotal, model.attempt,
            connectMs = model.run.connectMs?.let { it + (model.run.mtuMs ?: 0) },
            services = model.run.servicesFound,
        )

        /** Timings are never negative, even if a clock stepped back. */
        private fun elapsedSince(start: Long): Long = (env.now - start).coerceAtLeast(0)
    }
}

/** What one attempt collected; reset for the 133 retry. */
internal data class RunFacts(
    val stage: GattStage,
    val outcome: GattOutcome,
    val error: GattError?,
    val disconnect: GattDisconnect?,
    val mtu: Int?,
    val connectMs: Long?,
    val mtuMs: Long?,
    val discoverMs: Long?,
    val readMs: Long?,
    val services: List<GattServiceResult>,
    val servicesFound: Int?,
) {
    companion object {
        val FRESH = RunFacts(GattStage.CONNECT, GattOutcome.FAILED, null, null, null, null, null, null, null, emptyList(), null)
    }
}

/** The read stage: the plan, the next attribute and what was read, within the total read budget. */
internal data class ReadProgress(
    val snapshots: List<GattServiceSnapshot>,
    val plan: List<AttributeRef>,
    val index: Int,
    val values: Map<AttributeRef, GattReadValue>,
    val readStart: Long,
    val deadline: Long,
) {
    /** Valid while `index < plan.size`; the reducer only reads it inside a read step. */
    val current: AttributeRef get() = plan.getOrElse(index) { AttributeRef(-1, -1) }

    fun store(ref: AttributeRef, value: GattReadValue) = copy(values = values + (ref to value))
}

internal sealed interface ReadStep {
    /** `read()` was issued; its synchronous answer comes back as [EngineInput.ReadRequested]. */
    data class Requesting(val tries: Int, val remaining: Long) : ReadStep
    data class RefusedPause(val tries: Int, val remaining: Long, val until: Long) : ReadStep
    data class Value(val until: Long) : ReadStep
    data class Grace(val graceMs: Long, val until: Long) : ReadStep
}

internal sealed interface EngineWait {
    /** `connect()` was called; its answer is [EngineInput.Linked] or [EngineInput.ConnectRejected]. */
    data object ConnectRequest : EngineWait
    data class Connecting(val since: Long, val until: Long) : EngineWait
    data class MtuRequest(val since: Long) : EngineWait
    data class Mtu(val since: Long, val until: Long) : EngineWait
    data class DiscoverRequest(val since: Long) : EngineWait
    data class Discovering(val since: Long, val until: Long) : EngineWait
    data object ListingServices : EngineWait
    data class Reading(val read: ReadProgress, val step: ReadStep) : EngineWait
    data class Disconnecting(val initiator: DisconnectInitiator, val retryIf133: Boolean, val until: Long) : EngineWait

    /** The pause before the 133 retry; it listens only for cancel. */
    data class Backoff(val until: Long) : EngineWait
    data class Finished(val result: GattDiscoveryResult) : EngineWait
}

/** The deadline of a stage that waits; null while a link call's synchronous answer is pending, or when finished. */
internal val EngineWait.until: Long?
    get() = when (this) {
        is EngineWait.Connecting -> until
        is EngineWait.Mtu -> until
        is EngineWait.Discovering -> until
        is EngineWait.Reading -> when (val step = step) {
            is ReadStep.Requesting -> null
            is ReadStep.RefusedPause -> step.until
            is ReadStep.Value -> step.until
            is ReadStep.Grace -> step.until
        }
        is EngineWait.Disconnecting -> until
        is EngineWait.Backoff -> until
        EngineWait.ConnectRequest, is EngineWait.MtuRequest, is EngineWait.DiscoverRequest, EngineWait.ListingServices,
        is EngineWait.Finished,
        -> null
    }

internal val EngineWait.waits: Boolean get() = until != null

/** Every wait honours cancel except the disconnect, which always completes. */
internal val EngineWait.honoursCancel: Boolean get() = waits && this !is EngineWait.Disconnecting

internal data class EngineModel(
    val timeouts: GattTimeouts,
    val request: GattRequest,
    val startedWallMs: Long,
    val attempt: Int,
    val phy: GattPhy?,
    val run: RunFacts,
    val wait: EngineWait,
)

/** The facts the runner samples when it reduces: the monotonic and wall clocks and the cancel signal. */
internal data class EngineEnv(val now: Long, val wallMs: Long, val cancelled: Boolean)

internal sealed interface EngineInput {
    data object Linked : EngineInput
    data object ConnectRejected : EngineInput
    data class MtuRequested(val accepted: Boolean) : EngineInput
    data class DiscoverRequested(val accepted: Boolean) : EngineInput
    data class Listed(val snapshots: List<GattServiceSnapshot>) : EngineInput
    data class ReadRequested(val accepted: Boolean) : EngineInput
    data class Link(val attempt: Int, val event: GattEvent) : EngineInput
    data object Cancel : EngineInput
    data object TimedOut : EngineInput

    /** A link call threw something other than a refusal. */
    data object LinkFailed : EngineInput
}

internal sealed interface EngineEffect {
    data class Connect(val attempt: Int) : EngineEffect
    data object ReadPhy : EngineEffect
    data class RequestMtu(val mtu: Int) : EngineEffect
    data object DiscoverServices : EngineEffect
    data object ListServices : EngineEffect
    data class Read(val ref: AttributeRef) : EngineEffect
    data object Disconnect : EngineEffect
    data object Close : EngineEffect
    data class Progress(val progress: GattProgress) : EngineEffect
    data class Finish(val result: GattDiscoveryResult) : EngineEffect
}

internal data class EngineStep(val model: EngineModel, val effects: List<EngineEffect>)
