package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase

/**
 * The recording lifecycle (§1.2, §4.4, `FOREGROUND_SERVICE.md` §2–§4) as one immutable value and a pure reducer;
 * [PlatformRecorder] is its runner. Each step of a transition (the foreground service, a core call, the scanner
 * drain) is a named [RecEffect] whose result comes back as a [RecEvent], so the order is decided here:
 *
 * - start: grants, the foreground service and its confirmation, `core.startRecording`, a read of the core's state,
 *   location, the scanner; a missing grant or a refused service leaves no session;
 * - pause and stop drain the scanner before the core call; pause keeps the foreground service;
 * - a failed stop reconciles with what the core says.
 *
 * The invariant every path keeps: a session the core may hold open always has the foreground service. When the
 * core's state cannot be read, the machine assumes the session is still open ([RecPhase.Live] when it knows the
 * session, [RecPhase.Unidentified] when it does not) and a later event or request settles it.
 *
 * A request is answered by exactly one [RecEffect.Reply]. Elapsed time and counts come from the core's state.
 */
internal object RecorderMachine {
    const val MISSING_GRANTS = "Nearby devices and precise location are needed to record"
    const val SERVICE_REFUSED = "Android did not allow the recording service to start"

    fun reduce(state: RecModel, event: RecEvent): RecTransition = Builder(state).apply { on(event) }.build()

    private class Builder(var state: RecModel) {
        val effects = mutableListOf<RecEffect>()

        fun build() = RecTransition(state, effects.toList())

        fun on(event: RecEvent) {
            when (event) {
                is RecEvent.Start -> start(event.missingGrants)
                RecEvent.Pause -> pause()
                RecEvent.Resume -> resume()
                RecEvent.Stop -> stop()
                RecEvent.Restore -> restore()
                is RecEvent.ForegroundEntered -> foreground(event.entered)
                is RecEvent.CoreStartReturned -> startReturned(event.session)
                is RecEvent.CorePaused -> paused(event.core)
                is RecEvent.CoreResumed -> resumed(event.core)
                is RecEvent.CoreStopped -> stopped(event.session)
                is RecEvent.CoreRead -> read(event.core)
                is RecEvent.CoreFailed -> coreFailed(event.cause)
                is RecEvent.ScannerDeferred -> state = state.copy(deferred = event.deferred)
                is RecEvent.Dropped -> state = state.copy(dropped = event.dropped)
                is RecEvent.ScannerError -> state = state.copy(error = event.message)
                is RecEvent.CoreChanged -> coreChanged(event.core)
                is RecEvent.EffectFailed -> effectFailed(event.effect, event.cause, event.remaining)
            }
        }

        // ---- Requests (the runner sends one at a time, after the previous one was answered) ----

        private fun start(missing: List<String>) {
            when (val phase = state.phase) {
                is RecPhase.Live -> return reply(RecOutcome.Session(phase.session))
                // The core records a session the machine could not identify: try to adopt it again.
                RecPhase.Unidentified -> {
                    state = state.copy(phase = RecPhase.AdoptingCore)
                    effects += RecEffect.CoreRead
                    return
                }
                is RecPhase.Idle -> Unit
                RecPhase.AwaitingForeground, RecPhase.StartingCore, is RecPhase.ReadingStarted, RecPhase.AdoptingCore,
                RecPhase.Restoring, is RecPhase.RestoreForeground, is RecPhase.Pausing, is RecPhase.Resuming,
                is RecPhase.Stopping, is RecPhase.Reconciling,
                -> return reply(busy())
            }
            if (missing.isNotEmpty()) {
                return failStart(SecurityExceptionValue("$MISSING_GRANTS: ${missing.joinToString()}"), MISSING_GRANTS)
            }
            state = state.copy(phase = RecPhase.AwaitingForeground, facts = Facts.NONE, error = null)
            effects += RecEffect.EnterForeground
        }

        private fun pause() {
            val phase = state.phase as? RecPhase.Live ?: return reply(RecOutcome.Done)
            if (phase.paused) return reply(RecOutcome.Done)
            state = state.copy(phase = RecPhase.Pausing(phase.session))
            effects += RecEffect.DrainScanner
            effects += RecEffect.CorePause
        }

        private fun resume() {
            val phase = state.phase as? RecPhase.Live ?: return reply(RecOutcome.Done)
            if (!phase.paused) return reply(RecOutcome.Done)
            state = state.copy(phase = RecPhase.Resuming(phase.session))
            effects += RecEffect.CoreResume
        }

        private fun stop() {
            when (val phase = state.phase) {
                is RecPhase.Live -> {
                    state = state.copy(phase = RecPhase.Stopping(phase.session, wasPaused = phase.paused))
                    if (!phase.paused) effects += RecEffect.DrainScanner
                }
                RecPhase.Unidentified -> {
                    state = state.copy(phase = RecPhase.Stopping(session = null, wasPaused = false))
                    effects += RecEffect.DrainScanner
                }
                is RecPhase.Idle, RecPhase.AwaitingForeground, RecPhase.StartingCore, is RecPhase.ReadingStarted,
                RecPhase.AdoptingCore, RecPhase.Restoring, is RecPhase.RestoreForeground, is RecPhase.Pausing,
                is RecPhase.Resuming, is RecPhase.Stopping, is RecPhase.Reconciling,
                -> return reply(RecOutcome.Failed(CoreException.NotRecording()))
            }
            effects += RecEffect.CoreStop
        }

        /** After a process restart: adopt the core's state (`Core::open` has already closed a left-open session). */
        private fun restore() {
            when (state.phase) {
                is RecPhase.Idle -> {
                    state = state.copy(phase = RecPhase.Restoring)
                    effects += RecEffect.CoreRead
                }
                RecPhase.AwaitingForeground, RecPhase.StartingCore, is RecPhase.ReadingStarted, RecPhase.AdoptingCore,
                RecPhase.Unidentified, RecPhase.Restoring, is RecPhase.RestoreForeground, is RecPhase.Live,
                is RecPhase.Pausing, is RecPhase.Resuming, is RecPhase.Stopping, is RecPhase.Reconciling,
                -> reply(RecOutcome.Done)
            }
        }

        // ---- Step results ----

        private fun foreground(entered: Boolean) {
            when (val phase = state.phase) {
                RecPhase.AwaitingForeground -> {
                    if (!entered) return failStart(IllegalStateExceptionValue(SERVICE_REFUSED), SERVICE_REFUSED)
                    state = state.copy(phase = RecPhase.StartingCore)
                    effects += RecEffect.CoreStart
                }
                is RecPhase.RestoreForeground -> {
                    if (entered) return live(phase.session, phase.core, startCollecting = true)
                    // A recording may not run without its service: close the session the core still holds.
                    state = state.copy(phase = RecPhase.Stopping(phase.session, wasPaused = true), error = SERVICE_REFUSED)
                    effects += RecEffect.CoreStop
                }
                is RecPhase.Idle, RecPhase.StartingCore, is RecPhase.ReadingStarted, RecPhase.AdoptingCore, RecPhase.Unidentified,
                RecPhase.Restoring, is RecPhase.Live, is RecPhase.Pausing, is RecPhase.Resuming, is RecPhase.Stopping,
                is RecPhase.Reconciling,
                -> Unit
            }
        }

        /** `startRecording` returned: the session is open in the core; read its state before going live. */
        private fun startReturned(session: SessionId) {
            if (state.phase != RecPhase.StartingCore) return
            state = state.copy(phase = RecPhase.ReadingStarted(session))
            effects += RecEffect.CoreRead
        }

        /**
         * The core holds [session]: unless it is paused, location and the scanner follow; the start is answered with
         * it.
         */
        private fun live(session: SessionId, core: CoreRecordingState?, startCollecting: Boolean) {
            val paused = core?.phase == RecordingPhase.Paused
            state = state.copy(phase = RecPhase.Live(session, paused), facts = core?.let(Facts::of) ?: Facts.NONE, error = null)
            if (startCollecting && !paused) {
                effects += RecEffect.StartLocation
                effects += RecEffect.StartScanner
            }
            reply(RecOutcome.Session(session))
        }

        private fun paused(core: CoreRecordingState) {
            val phase = state.phase as? RecPhase.Pausing ?: return
            state = state.copy(phase = RecPhase.Live(phase.session, paused = true), facts = Facts.of(core), error = null)
            // The service stays in the foreground (owner decision "Pause"); it only drops the wake lock.
            effects += RecEffect.StopLocation
            reply(RecOutcome.Done)
        }

        private fun resumed(core: CoreRecordingState) {
            val phase = state.phase as? RecPhase.Resuming ?: return
            state = state.copy(phase = RecPhase.Live(phase.session, paused = false), facts = Facts.of(core), error = null)
            effects += RecEffect.StartLocation
            effects += RecEffect.StartScanner
            reply(RecOutcome.Done)
        }

        private fun stopped(session: SessionId) {
            if (state.phase !is RecPhase.Stopping) return
            // V-3: the UI stays on the session that just ended.
            state = state.copy(
                phase = RecPhase.Idle(session),
                facts = state.facts.copy(activeBeforeMs = 0, runningSinceMs = null),
                error = state.error.takeIf { it == SERVICE_REFUSED },
            )
            effects += RecEffect.StopLocation
            reply(RecOutcome.Session(session))
            effects += RecEffect.LeaveForeground
        }

        private fun read(core: CoreRecordingState) {
            val session = core.sessionId?.let(::SessionId)
            val open = core.phase != RecordingPhase.Idle
            when (val phase = state.phase) {
                is RecPhase.ReadingStarted -> live(phase.session, core, startCollecting = true)
                RecPhase.AdoptingCore -> when {
                    open && session != null -> live(session, core, startCollecting = true)
                    open -> unidentified(IllegalStateExceptionValue("the core is recording without a session"))
                    // Nothing is open after all: the start failed cleanly.
                    else -> failStartAfterForeground(CoreException.NotRecording())
                }
                RecPhase.Restoring ->
                    if (open && session != null) {
                        // Never expected (Core::open closes a left-open session); if so, its service comes first.
                        state = state.copy(phase = RecPhase.RestoreForeground(session, core))
                        effects += RecEffect.EnterForeground
                    } else {
                        state = state.copy(phase = RecPhase.Idle(lastSession = null))
                        reply(RecOutcome.Done)
                    }
                is RecPhase.Reconciling -> when {
                    !open -> endAfterFailedStop(phase)
                    // Still open: the user can try again; collect again if it records.
                    session != null -> {
                        state = state.copy(
                            phase = RecPhase.Live(session, paused = core.phase == RecordingPhase.Paused),
                            facts = Facts.of(core),
                            error = phase.message,
                        )
                        if (core.phase == RecordingPhase.Recording) effects += RecEffect.StartScanner
                        reply(RecOutcome.Failed(phase.cause))
                    }
                    else -> {
                        state = state.copy(phase = RecPhase.Unidentified, error = phase.message)
                        effects += RecEffect.StartScanner
                        reply(RecOutcome.Failed(phase.cause))
                    }
                }
                is RecPhase.Idle, RecPhase.AwaitingForeground, RecPhase.StartingCore, RecPhase.Unidentified,
                is RecPhase.RestoreForeground, is RecPhase.Live, is RecPhase.Pausing, is RecPhase.Resuming,
                is RecPhase.Stopping,
                -> Unit
            }
        }

        private fun coreFailed(cause: Throwable) {
            val detail = cause.message
            when (val phase = state.phase) {
                RecPhase.StartingCore ->
                    if (cause is CoreException.AlreadyRecording) {
                        state = state.copy(phase = RecPhase.AdoptingCore)
                        effects += RecEffect.CoreRead
                    } else {
                        failStartAfterForeground(cause)
                    }
                // The session is open (startRecording returned it) but its state could not be read: it records.
                is RecPhase.ReadingStarted -> {
                    live(phase.session, core = null, startCollecting = true)
                    state = state.copy(error = "The recording state could not be read ($detail)")
                }
                // The core records a session it would not describe: keep its service and collect.
                RecPhase.AdoptingCore -> unidentified(cause)
                is RecPhase.Pausing -> {
                    // The core still records: collect again.
                    state = state.copy(phase = RecPhase.Live(phase.session, paused = false), error = "The recording could not pause ($detail)")
                    effects += RecEffect.StartScanner
                    reply(RecOutcome.Done)
                }
                is RecPhase.Resuming -> {
                    state = state.copy(phase = RecPhase.Live(phase.session, paused = true), error = "The recording could not resume ($detail)")
                    reply(RecOutcome.Done)
                }
                is RecPhase.Stopping -> {
                    state = state.copy(phase = RecPhase.Reconciling(phase.session, phase.wasPaused, cause, "The recording could not stop ($detail)"))
                    effects += RecEffect.CoreRead
                }
                // The core's state is unknown: assume the session is still open, so it never runs without its service.
                // A later stop settles it either way.
                is RecPhase.Reconciling -> {
                    val session = phase.session
                    state = if (session != null) {
                        state.copy(phase = RecPhase.Live(session, paused = phase.wasPaused), error = phase.message)
                    } else {
                        state.copy(phase = RecPhase.Unidentified, error = phase.message)
                    }
                    if (!phase.wasPaused) effects += RecEffect.StartScanner
                    reply(RecOutcome.Failed(phase.cause))
                }
                RecPhase.Restoring -> {
                    state = state.copy(phase = RecPhase.Idle(lastSession = null))
                    reply(RecOutcome.Done)
                }
                is RecPhase.Idle, RecPhase.AwaitingForeground, RecPhase.Unidentified, is RecPhase.RestoreForeground,
                is RecPhase.Live,
                -> Unit
            }
        }

        /**
         * The runner could not execute [effect] and stopped its batch there; [remaining] are the effects it did not
         * run. A failed foreground entry is a refusal, a failed core call is a core failure (with its
         * reconciliation), a failed drain still lets the core step run, and a failed scanner or location step is
         * reported. The remaining effects then run.
         */
        private fun effectFailed(effect: RecEffect, cause: Throwable, remaining: List<RecEffect>) {
            when (effect) {
                RecEffect.EnterForeground -> foreground(entered = false)
                RecEffect.CoreStart, RecEffect.CorePause, RecEffect.CoreResume, RecEffect.CoreStop, RecEffect.CoreRead ->
                    coreFailed(cause)
                RecEffect.LeaveForeground, RecEffect.DrainScanner, RecEffect.StartScanner, RecEffect.StartLocation,
                RecEffect.StopLocation,
                -> state = state.copy(error = "A recording step failed (${cause.message ?: cause::class.java.simpleName})")
                is RecEffect.Reply -> Unit
            }
            effects += remaining
        }

        private fun coreChanged(core: CoreRecordingState) {
            val session = when (val phase = state.phase) {
                is RecPhase.Live -> phase.session
                is RecPhase.Pausing -> phase.session
                is RecPhase.Resuming -> phase.session
                is RecPhase.Stopping -> phase.session
                is RecPhase.Reconciling -> phase.session
                is RecPhase.ReadingStarted -> phase.session
                RecPhase.Unidentified -> {
                    // The core names the session it records: adopt it.
                    val named = core.sessionId?.let(::SessionId)
                    if (named != null && core.phase != RecordingPhase.Idle) {
                        state = state.copy(phase = RecPhase.Live(named, paused = core.phase == RecordingPhase.Paused), facts = Facts.of(core))
                    }
                    return
                }
                is RecPhase.Idle, RecPhase.AwaitingForeground, RecPhase.StartingCore, RecPhase.AdoptingCore, RecPhase.Restoring,
                is RecPhase.RestoreForeground,
                -> null
            }
            if (session != null && core.sessionId == session.value) state = state.copy(facts = state.facts.copy(deviceCount = core.deviceCount))
        }

        // ---- Helpers ----

        private fun unidentified(cause: Throwable) {
            state = state.copy(phase = RecPhase.Unidentified, facts = Facts.NONE, error = "The recording could not be identified (${cause.message})")
            effects += RecEffect.StartLocation
            effects += RecEffect.StartScanner
            reply(RecOutcome.Failed(cause))
        }

        private fun endAfterFailedStop(phase: RecPhase.Reconciling) {
            state = state.copy(phase = RecPhase.Idle(phase.session), facts = Facts.NONE, error = phase.message)
            effects += RecEffect.StopLocation
            effects += RecEffect.LeaveForeground
            reply(RecOutcome.Failed(phase.cause))
        }

        private fun failStart(cause: Throwable, message: String) {
            state = state.copy(phase = RecPhase.Idle(lastSession = null), facts = Facts.NONE, error = message)
            reply(RecOutcome.Failed(cause))
        }

        private fun failStartAfterForeground(cause: Throwable) {
            effects += RecEffect.LeaveForeground
            failStart(cause, "The recording could not start (${cause.message ?: cause::class.java.simpleName})")
        }

        private fun reply(outcome: RecOutcome) {
            effects += RecEffect.Reply(outcome)
        }

        private fun busy() = RecOutcome.Failed(IllegalStateExceptionValue("a recording transition is in progress"))
    }
}

/** A recording session's UUIDv7. */
@JvmInline
internal value class SessionId(val value: String)

/** Exceptions as values: the reducer builds them and hands them to the caller, it never throws. */
internal class SecurityExceptionValue(message: String) : SecurityException(message)
internal class IllegalStateExceptionValue(message: String) : IllegalStateException(message)

/** What the core says about the recording, in the units the UI ticks with. */
internal data class Facts(val activeBeforeMs: Long, val runningSinceMs: Long?, val deviceCount: Int) {
    companion object {
        val NONE = Facts(0, null, 0)
        fun of(core: CoreRecordingState) = Facts(core.activeBeforeMs, core.runningSinceMs, core.deviceCount)
    }
}

internal sealed interface RecPhase {
    /** No recording; [lastSession] is the one that just ended (V-3), if any. */
    data class Idle(val lastSession: SessionId?) : RecPhase
    data object AwaitingForeground : RecPhase
    data object StartingCore : RecPhase

    /** `startRecording` returned [session]: reading the core's state before going live. */
    data class ReadingStarted(val session: SessionId) : RecPhase

    /** `startRecording` said AlreadyRecording: reading the core's state to adopt its session. */
    data object AdoptingCore : RecPhase

    /** The core records a session the machine could not identify: the service and collection go on until it does. */
    data object Unidentified : RecPhase
    data object Restoring : RecPhase

    /** A restore found the core recording [session]: entering the foreground service before going live. */
    data class RestoreForeground(val session: SessionId, val core: CoreRecordingState) : RecPhase
    data class Live(val session: SessionId, val paused: Boolean) : RecPhase
    data class Pausing(val session: SessionId) : RecPhase
    data class Resuming(val session: SessionId) : RecPhase

    /** Stopping [session] (null when it was [Unidentified]). */
    data class Stopping(val session: SessionId?, val wasPaused: Boolean) : RecPhase

    /** `stopRecording` failed: reading the core's state to see whether the session is still open. */
    data class Reconciling(val session: SessionId?, val wasPaused: Boolean, val cause: Throwable, val message: String) : RecPhase
}

internal data class RecModel(
    val phase: RecPhase = RecPhase.Idle(lastSession = null),
    val facts: Facts = Facts.NONE,
    val deferred: Boolean = false,
    val dropped: Long = 0,
    val error: String? = null,
) {
    /** The public state (§4.4). */
    fun public(): RecorderState {
        val (publicPhase, session) = when (val phase = phase) {
            is RecPhase.Idle -> Phase.Idle to phase.lastSession
            RecPhase.AwaitingForeground, RecPhase.StartingCore, is RecPhase.ReadingStarted, RecPhase.AdoptingCore,
            is RecPhase.RestoreForeground,
            -> Phase.Starting to null
            RecPhase.Unidentified -> Phase.Recording to null
            RecPhase.Restoring -> Phase.Idle to null
            is RecPhase.Live -> (if (phase.paused) Phase.Paused else Phase.Recording) to phase.session
            is RecPhase.Pausing -> Phase.Recording to phase.session
            is RecPhase.Resuming -> Phase.Paused to phase.session
            is RecPhase.Stopping -> Phase.Stopping to phase.session
            is RecPhase.Reconciling -> Phase.Stopping to phase.session
        }
        val stopping = publicPhase == Phase.Stopping
        return RecorderState(
            phase = publicPhase,
            sessionId = session?.value,
            activeBeforeMs = facts.activeBeforeMs,
            runningSinceMs = if (stopping) null else facts.runningSinceMs,
            deviceCount = facts.deviceCount,
            scannerDeferred = deferred,
            dropped = dropped,
            error = error,
        )
    }
}

internal sealed interface RecEvent {
    // Requests
    data class Start(val missingGrants: List<String>) : RecEvent
    data object Pause : RecEvent
    data object Resume : RecEvent
    data object Stop : RecEvent
    data object Restore : RecEvent

    // Step results
    data class ForegroundEntered(val entered: Boolean) : RecEvent

    /** `startRecording` returned the new session's id. */
    data class CoreStartReturned(val session: SessionId) : RecEvent
    data class CorePaused(val core: CoreRecordingState) : RecEvent
    data class CoreResumed(val core: CoreRecordingState) : RecEvent
    data class CoreStopped(val session: SessionId) : RecEvent
    data class CoreRead(val core: CoreRecordingState) : RecEvent
    data class CoreFailed(val cause: Throwable) : RecEvent

    // Signals
    data class ScannerDeferred(val deferred: Boolean) : RecEvent
    data class Dropped(val dropped: Long) : RecEvent
    data class ScannerError(val message: String) : RecEvent
    data class CoreChanged(val core: CoreRecordingState) : RecEvent

    /**
     * The runner could not execute [effect] (any exception, or a typed failure such as a drain timeout, as a value);
     * it stopped its batch there and [remaining] are the effects it did not run.
     */
    data class EffectFailed(val effect: RecEffect, val cause: Throwable, val remaining: List<RecEffect> = emptyList()) : RecEvent
}

internal sealed interface RecEffect {
    data object EnterForeground : RecEffect
    data object LeaveForeground : RecEffect

    /** `core.startRecording(environment())`; the session id comes back as [RecEvent.CoreStartReturned]. */
    data object CoreStart : RecEffect
    data object CorePause : RecEffect
    data object CoreResume : RecEffect
    data object CoreStop : RecEffect
    data object CoreRead : RecEffect
    data object DrainScanner : RecEffect
    data object StartScanner : RecEffect
    data object StartLocation : RecEffect
    data object StopLocation : RecEffect
    data class Reply(val outcome: RecOutcome) : RecEffect
}

internal sealed interface RecOutcome {
    data class Session(val session: SessionId) : RecOutcome
    data object Done : RecOutcome
    data class Failed(val cause: Throwable) : RecOutcome
}

internal data class RecTransition(val state: RecModel, val effects: List<RecEffect>)
