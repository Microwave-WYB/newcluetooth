package edu.ucsd.sysnet.cluetoothscanner.platform.export

/**
 * docs/v2-api.md §4.5: which prepared exports still need `CoreStorage.acknowledgeExport` (which deletes
 * `export-temp/<id>`), as one immutable value and a pure reducer; [AndroidExportShare] is its runner.
 *
 * - A shared export may still be read by the receiving app, so it goes at the next export ([LedgerEvent.Handed]) or
 *   the next app start ([LedgerEvent.AppStarted]); the pending set survives process death ([LedgerEffect.Remember]).
 * - A saved export goes right after its copy ([LedgerEvent.Saved]).
 * - An acknowledgement that fails stays pending and is tried again at the next export or app start; an id is never
 *   acknowledged twice at once.
 */
internal object ExportLedger {
    fun reduce(state: Ledger, event: LedgerEvent): LedgerStep = when (event) {
        is LedgerEvent.Loaded -> LedgerStep(state.copy(pending = state.pending + event.pending), emptyList())
        is LedgerEvent.Handed -> {
            val handed = state.copy(pending = state.pending + event.exportId)
            release(handed, handed.pending - event.exportId)
        }
        is LedgerEvent.Saved -> release(state, setOf(event.exportId) intersect state.pending)
        LedgerEvent.AppStarted -> release(state, state.pending)
        is LedgerEvent.Acknowledged -> {
            val next = Ledger(state.pending - event.exportId, state.acking - event.exportId)
            LedgerStep(next, listOf(LedgerEffect.Remember(next.pending)))
        }
        is LedgerEvent.AckFailed -> LedgerStep(state.copy(acking = state.acking - event.exportId), emptyList())
    }

    /** Acknowledges [ids] that are not already being acknowledged, and remembers the pending set. */
    private fun release(state: Ledger, ids: Set<String>): LedgerStep {
        val now = ids - state.acking
        val next = state.copy(acking = state.acking + now)
        return LedgerStep(next, listOf(LedgerEffect.Remember(next.pending)) + now.sorted().map(LedgerEffect::Acknowledge))
    }
}

/** [pending]: prepared exports not acknowledged yet; [acking]: those whose acknowledgement is running. */
internal data class Ledger(val pending: Set<String> = emptySet(), val acking: Set<String> = emptySet())

internal sealed interface LedgerEvent {
    /** The pending set a previous process left in prefs. */
    data class Loaded(val pending: Set<String>) : LedgerEvent

    /** An export was shared or is being saved: every earlier one may go. */
    data class Handed(val exportId: String) : LedgerEvent

    /** Its copy into Downloads is done: it may go now. */
    data class Saved(val exportId: String) : LedgerEvent

    /** App start, once the core is open: whatever an earlier process left. */
    data object AppStarted : LedgerEvent
    data class Acknowledged(val exportId: String) : LedgerEvent
    data class AckFailed(val exportId: String) : LedgerEvent
}

internal sealed interface LedgerEffect {
    /** `CoreStorage.acknowledgeExport(exportId)`; answered by [LedgerEvent.Acknowledged] or [LedgerEvent.AckFailed]. */
    data class Acknowledge(val exportId: String) : LedgerEffect

    /** Persist [pending] (prefs), so a shared export is acknowledged after process death too. */
    data class Remember(val pending: Set<String>) : LedgerEffect
}

internal data class LedgerStep(val state: Ledger, val effects: List<LedgerEffect>)
