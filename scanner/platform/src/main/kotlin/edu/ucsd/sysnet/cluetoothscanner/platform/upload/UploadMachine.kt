package edu.ucsd.sysnet.cluetoothscanner.platform.upload

/**
 * docs/v2-api.md §1.6, §2.8, SPEC O-1 "Pending uploads": one upload pass of one [UploadKind] as an immutable value and
 * a pure reducer; [UploadPassRunner] is its runner. The v3 coordinator's rules, unchanged:
 *
 * - the pass lists the core's pending files of its kind, uploads each once, and lists again until a listing has
 *   nothing new, so a file sealed during the pass goes in the same pass;
 * - resumable sessions of files no longer pending are discarded at every listing;
 * - a file with a saved session resumes it; an expired session is discarded and the file is sent fresh, once;
 * - a send that fails is checked against the store: production rules deny overwrites, so an object an earlier
 *   attempt stored but never acknowledged counts as stored;
 * - every file ends the pass marked succeeded or failed with its diagnostic; an authorization denial is counted apart
 *   ([UploadTally.denied]): it waits for rules or credentials and does not push scans into backoff.
 */
internal object UploadMachine {
    fun start(kind: UploadKind): UploadStep = UploadStep(UploadPass(kind), listOf(UploadEffect.ListPending))

    fun reduce(pass: UploadPass, event: UploadEvent): UploadStep = when (event) {
        is UploadEvent.Listed -> listed(pass, event.pending)
        is UploadEvent.ListFailed -> finish(pass.copy(tally = pass.tally.copy(failed = pass.tally.failed + 1)))
        is UploadEvent.Sent -> step(pass, event.id) { sent(pass, it) }
        is UploadEvent.SendFailed -> step(pass, event.id) { sendFailed(pass, it, event.failure) }
        is UploadEvent.Checked -> step(pass, event.id) { checked(pass, it, event.stored) }
        is UploadEvent.MarkedSucceeded -> step(pass, event.id) { done(pass, it, Outcome.Succeeded) }
        is UploadEvent.MarkSucceededFailed ->
            step(pass, event.id) { markFailed(pass, it, event.message, denied = false) }
        is UploadEvent.MarkedFailed -> step(pass, event.id) { done(pass, it, it.outcome()) }
    }

    private fun listed(pass: UploadPass, pending: List<PendingFile>): UploadStep {
        val ofKind = pending.filter { it.kind == pass.kind }
        val fresh = ofKind.filter { it.id !in pass.tried }
        val discard = UploadEffect.DiscardOrphans(ofKind.map { it.id }.toSet())
        if (fresh.isEmpty()) return finish(pass).let { it.copy(effects = listOf(discard) + it.effects) }
        val next = pass.copy(queue = fresh.drop(1), tried = pass.tried + fresh.map { it.id })
        return begin(next, fresh.first()).let { it.copy(effects = listOf(discard) + it.effects) }
    }

    private fun begin(pass: UploadPass, file: PendingFile): UploadStep {
        val item = Item(file, InFlight.Sending(resumed = true))
        return UploadStep(pass.copy(current = item), listOf(UploadEffect.Send(file, SendMode.ResumeIfSaved)))
    }

    private fun sent(pass: UploadPass, item: Item): UploadStep {
        if (item.state !is InFlight.Sending) return UploadStep(pass, emptyList())
        return marking(pass, item)
    }

    private fun sendFailed(pass: UploadPass, item: Item, failure: SendFailure): UploadStep {
        val sending = item.state as? InFlight.Sending ?: return UploadStep(pass, emptyList())
        return when (failure) {
            // Its session can no longer be resumed: the same bytes go again from the start, once.
            SendFailure.Expired -> if (sending.resumed) {
                UploadStep(
                    pass.copy(current = item.copy(state = InFlight.Sending(resumed = false))),
                    listOf(UploadEffect.Discard(item.file.id), UploadEffect.Send(item.file, SendMode.Fresh)),
                )
            } else {
                askStore(pass, item, "the upload session expired", denied = false)
            }
            is SendFailure.Denied -> askStore(pass, item, failure.message, denied = true)
            is SendFailure.Transient -> askStore(pass, item, failure.message, denied = false)
            // The core prepared another file than asked: never upload it, never ask the store.
            is SendFailure.Mismatch -> markFailed(pass, item, failure.message, denied = false)
        }
    }

    /** A failed send may have stored the object anyway (accepted but not acknowledged): ask the store. */
    private fun askStore(pass: UploadPass, item: Item, message: String, denied: Boolean): UploadStep = UploadStep(
        pass.copy(current = item.copy(state = InFlight.Checking(message, denied))),
        listOf(UploadEffect.CheckStored(item.file)),
    )

    private fun checked(pass: UploadPass, item: Item, stored: Boolean): UploadStep {
        val checking = item.state as? InFlight.Checking ?: return UploadStep(pass, emptyList())
        return if (stored) marking(pass, item) else markFailed(pass, item, checking.message, checking.denied)
    }

    private fun marking(pass: UploadPass, item: Item) = UploadStep(
        pass.copy(current = item.copy(state = InFlight.MarkingSucceeded)),
        listOf(UploadEffect.MarkSucceeded(item.file.id)),
    )

    private fun markFailed(pass: UploadPass, item: Item, message: String, denied: Boolean) = UploadStep(
        pass.copy(current = item.copy(state = InFlight.MarkingFailed(denied))),
        listOf(UploadEffect.MarkFailed(item.file.id, message)),
    )

    private fun done(pass: UploadPass, item: Item, outcome: Outcome): UploadStep {
        val tally = when (outcome) {
            Outcome.Succeeded -> pass.tally.copy(succeeded = pass.tally.succeeded + 1)
            Outcome.Failed -> pass.tally.copy(failed = pass.tally.failed + 1)
            Outcome.Denied -> pass.tally.copy(denied = pass.tally.denied + 1)
        }
        // A stored file drops its cached ciphertext and session; a failed one keeps them to resume next time.
        val cleanup = if (outcome == Outcome.Succeeded) listOf(UploadEffect.Discard(item.file.id)) else emptyList()
        val next = pass.copy(current = null, tally = tally)
        val following = next.queue.firstOrNull()
            ?: return UploadStep(next, cleanup + UploadEffect.ListPending)
        val begun = begin(next.copy(queue = next.queue.drop(1)), following)
        return begun.copy(effects = cleanup + begun.effects)
    }

    private fun finish(pass: UploadPass) = UploadStep(pass.copy(current = null, finished = true), listOf(UploadEffect.Finish(pass.tally)))

    /** An answer for the file in flight; any other (late or unknown) answer changes nothing. */
    private inline fun step(pass: UploadPass, id: String, on: (Item) -> UploadStep): UploadStep {
        val item = pass.current?.takeIf { it.file.id == id && !pass.finished } ?: return UploadStep(pass, emptyList())
        return on(item)
    }

    private fun Item.outcome(): Outcome = when (val state = state) {
        is InFlight.MarkingFailed -> if (state.denied) Outcome.Denied else Outcome.Failed
        InFlight.MarkingSucceeded, is InFlight.Sending, is InFlight.Checking -> Outcome.Failed
    }

    private enum class Outcome { Succeeded, Failed, Denied }
}

/** Scan payloads and GATT objects go in separate passes with separate retry policies (as v3). */
enum class UploadKind {
    SCANS,
    GATT,
    ;

    companion object {
        fun of(objectPath: String) = if (objectPath.startsWith("gatt/")) GATT else SCANS
    }
}

/** A file the core has pending: `pendingUploads()`. */
internal data class PendingFile(val id: String, val objectPath: String) {
    val kind: UploadKind get() = UploadKind.of(objectPath)
}

/** What one pass did. [failed] files are worth a retry with backoff; [denied] ones wait for rules or credentials. */
data class UploadTally(val succeeded: Int = 0, val failed: Int = 0, val denied: Int = 0)

internal data class UploadPass(
    val kind: UploadKind,
    /** Listed, not started yet. */
    val queue: List<PendingFile> = emptyList(),
    /** Every file this pass has taken up (each goes once per pass). */
    val tried: Set<String> = emptySet(),
    val current: Item? = null,
    val tally: UploadTally = UploadTally(),
    val finished: Boolean = false,
)

internal data class Item(val file: PendingFile, val state: InFlight)

internal sealed interface InFlight {
    /** [resumed]: this send may still resume a saved session (a fresh send after an expiry may not). */
    data class Sending(val resumed: Boolean) : InFlight

    /** The send failed with [message]; asking the store whether the object is there anyway. */
    data class Checking(val message: String, val denied: Boolean) : InFlight
    data object MarkingSucceeded : InFlight
    data class MarkingFailed(val denied: Boolean) : InFlight
}

internal enum class SendMode {
    /** Continue the saved session when its cached ciphertext still exists, else prepare and send fresh. */
    ResumeIfSaved,

    /** Prepare (encrypt) again and start a new session. */
    Fresh,
}

internal sealed interface SendFailure {
    /** The saved session can no longer be resumed (a client error other than authorization). */
    data object Expired : SendFailure

    /** HTTP 401/403 or not authorized/authenticated. */
    data class Denied(val message: String) : SendFailure
    data class Transient(val message: String) : SendFailure

    /** `prepareUpload` answered for another payload or object path. */
    data class Mismatch(val message: String) : SendFailure
}

internal sealed interface UploadEvent {
    data class Listed(val pending: List<PendingFile>) : UploadEvent
    data class ListFailed(val message: String) : UploadEvent
    data class Sent(val id: String) : UploadEvent
    data class SendFailed(val id: String, val failure: SendFailure) : UploadEvent

    /** Whether the store already holds the object; unknown (the check failed) counts as not stored. */
    data class Checked(val id: String, val stored: Boolean) : UploadEvent
    data class MarkedSucceeded(val id: String) : UploadEvent

    /** `markUploadSucceeded` failed: the file is marked failed instead (as v3). */
    data class MarkSucceededFailed(val id: String, val message: String) : UploadEvent

    /** `markUploadFailed` ran (or failed; the send's failure is still the useful one). */
    data class MarkedFailed(val id: String) : UploadEvent
}

internal sealed interface UploadEffect {
    data object ListPending : UploadEffect

    /** Drop resumable sessions and cached ciphertext of every file not in [pending]. */
    data class DiscardOrphans(val pending: Set<String>) : UploadEffect
    data class Send(val file: PendingFile, val mode: SendMode) : UploadEffect
    data class CheckStored(val file: PendingFile) : UploadEffect
    data class MarkSucceeded(val id: String) : UploadEffect
    data class MarkFailed(val id: String, val message: String) : UploadEffect

    /** Drop [id]'s session and cached ciphertext. */
    data class Discard(val id: String) : UploadEffect
    data class Finish(val tally: UploadTally) : UploadEffect
}

internal data class UploadStep(val pass: UploadPass, val effects: List<UploadEffect>)
