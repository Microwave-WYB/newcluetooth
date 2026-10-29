package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import kotlinx.coroutines.CompletableDeferred

/**
 * Which discovery runs (one at a time) and which one waits, as one immutable value and a pure reducer;
 * [GattDiscoveryManager] is its runner. A start, a cancel, the engine's creation and a run's end are all events of
 * this one machine, so a cancel is ordered against the start it races with:
 *
 * - a cancel while the discovery is [AdmittedStage.Starting] (before its engine exists) ends it without connecting;
 * - a cancel once it is [AdmittedStage.Running] reaches the engine, and the discovery is stored as `cancelled`;
 * - a superseding start cancels the current discovery and waits as [Admission.Busy.next]; it is launched when the
 *   current one ended, and a later superseding start replaces it before it ever ran.
 */
internal object GattAdmission {
    fun reduce(state: Admission, event: AdmissionEvent): AdmissionStep = when (event) {
        is AdmissionEvent.Start -> start(state, event.ticket, event.supersede)
        is AdmissionEvent.Cancel -> cancel(state, event.ticket)
        is AdmissionEvent.Ready -> ready(state, event.ticket, event.engine)
        is AdmissionEvent.Ended -> ended(state, event.ticket)
    }

    private fun start(state: Admission, ticket: GattTicket, supersede: Boolean): AdmissionStep = when (state) {
        Admission.Free -> AdmissionStep(Admission.Busy(Admitted(ticket), next = null), listOf(AdmissionEffect.Launch(ticket)))
        is Admission.Busy ->
            if (!supersede) {
                AdmissionStep(state, listOf(AdmissionEffect.Refuse(ticket)))
            } else {
                val effects = buildList {
                    add(AdmissionEffect.Superseded(state.current.ticket))
                    addAll(cancelEffects(state.current))
                    state.next?.let { add(AdmissionEffect.Refuse(it)) }
                }
                AdmissionStep(state.copy(current = state.current.copy(cancelled = true), next = ticket), effects)
            }
    }

    /** [ticket] null is the current discovery. */
    private fun cancel(state: Admission, ticket: GattTicket?): AdmissionStep = when (state) {
        Admission.Free -> AdmissionStep(state, emptyList())
        is Admission.Busy -> when (val target = ticket ?: state.current.ticket) {
            state.current.ticket ->
                AdmissionStep(state.copy(current = state.current.copy(cancelled = true)), cancelEffects(state.current))
            state.next -> AdmissionStep(state.copy(next = null), listOf(AdmissionEffect.Refuse(target)))
            else -> AdmissionStep(state, emptyList())
        }
    }

    /** The current discovery built its [engine]: it may connect unless it was cancelled while starting. */
    private fun ready(state: Admission, ticket: GattTicket, engine: GattEngine): AdmissionStep {
        val busy = state as? Admission.Busy ?: return AdmissionStep(state, emptyList())
        val current = busy.current
        if (current.ticket != ticket || current.stage != AdmittedStage.Starting || current.cancelled) {
            return AdmissionStep(state, emptyList())
        }
        return AdmissionStep(busy.copy(current = current.copy(stage = AdmittedStage.Running(engine))), listOf(AdmissionEffect.Proceed(ticket)))
    }

    private fun ended(state: Admission, ticket: GattTicket): AdmissionStep {
        val busy = state as? Admission.Busy ?: return AdmissionStep(state, emptyList())
        if (busy.current.ticket != ticket) return AdmissionStep(state, emptyList())
        val next = busy.next ?: return AdmissionStep(Admission.Free, emptyList())
        return AdmissionStep(Admission.Busy(Admitted(next), next = null), listOf(AdmissionEffect.Launch(next)))
    }

    private fun cancelEffects(current: Admitted): List<AdmissionEffect> = when (val stage = current.stage) {
        AdmittedStage.Starting -> emptyList()
        is AdmittedStage.Running -> listOf(AdmissionEffect.CancelEngine(stage.engine))
    }
}

/** One requested discovery; identity is the instance. The manager completes its signals. */
internal class GattTicket(val request: GattStartRequest) {
    /** True once launched; false when it was refused or replaced before it ran. */
    val admitted = CompletableDeferred<Boolean>()
    val ended = CompletableDeferred<TicketEnd>()

    /** Completed when a newer discovery superseded this one. */
    val superseded = CompletableDeferred<Unit>()
}

internal sealed interface TicketEnd {
    data class Finished(val state: GattUiState.Finished) : TicketEnd

    /** No result: never launched, no discovery identity, cancelled before connecting, or torn down with its scope. */
    data object NoResult : TicketEnd
}

internal sealed interface AdmittedStage {
    /** Launched; the engine does not exist yet, so nothing is connected. */
    data object Starting : AdmittedStage
    data class Running(val engine: GattEngine) : AdmittedStage
}

internal data class Admitted(val ticket: GattTicket, val stage: AdmittedStage = AdmittedStage.Starting, val cancelled: Boolean = false)

internal sealed interface Admission {
    data object Free : Admission
    data class Busy(val current: Admitted, val next: GattTicket?) : Admission
}

internal sealed interface AdmissionEvent {
    /** A refused start ([supersede] false while busy) is answered with [AdmissionEffect.Refuse]. */
    data class Start(val ticket: GattTicket, val supersede: Boolean) : AdmissionEvent
    data class Cancel(val ticket: GattTicket?) : AdmissionEvent
    data class Ready(val ticket: GattTicket, val engine: GattEngine) : AdmissionEvent
    data class Ended(val ticket: GattTicket) : AdmissionEvent
}

internal sealed interface AdmissionEffect {
    data class Launch(val ticket: GattTicket) : AdmissionEffect

    /** The ticket never runs: it ends with [TicketEnd.NoResult]. */
    data class Refuse(val ticket: GattTicket) : AdmissionEffect
    data class Superseded(val ticket: GattTicket) : AdmissionEffect
    data class CancelEngine(val engine: GattEngine) : AdmissionEffect

    /** The answer to [AdmissionEvent.Ready]: the engine may connect. Its absence means: end without connecting. */
    data class Proceed(val ticket: GattTicket) : AdmissionEffect
}

internal data class AdmissionStep(val state: Admission, val effects: List<AdmissionEffect>)
