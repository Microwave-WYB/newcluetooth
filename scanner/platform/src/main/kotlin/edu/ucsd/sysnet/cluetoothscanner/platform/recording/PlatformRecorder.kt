package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.util.Log
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the recorder needs of the BLE scanner (§4.1). */
interface RecordingScanner {
    /** Bluetooth off while recording: the scan waits and restarts by itself on `STATE_ON`. */
    val deferred: StateFlow<Boolean>

    /** Observations the inbound queue dropped (§4.1). */
    val dropped: StateFlow<Long>

    /** A scanner problem the user should see (for example scan settings that could not change); null when fine. */
    val error: StateFlow<String?>

    /** Starts the scan (or defers it while Bluetooth is off). */
    fun start()

    /**
     * Stops the scan, waits until every queued scan callback has run (the main-looper fence), then flushes the
     * batcher into `ingestScans`, so no observation of the running session reaches the core after it pauses or stops.
     */
    suspend fun stopAndDrain()
}

/** The RECORDING location request (§4.2). */
interface RecordingLocation {
    fun startRecording()
    fun stopRecording()
}

/** The foreground service (§1.2.3). */
interface ForegroundHost {
    /** Starts `RecordingService` and waits for its `startForeground`; false when Android refused or it timed out. */
    suspend fun enterForeground(): Boolean

    fun leaveForeground()
}

/** The grants a `location|connectedDevice` foreground service needs on Android 14 (§1.2.3). */
fun interface RecordingGrants {
    /** Missing permission names; empty when recording may start. */
    fun missing(): List<String>
}

/**
 * The process-wide [Recorder] (§4.4, docs `scanner/platform/FOREGROUND_SERVICE.md` §2–§4).
 *
 * The decisions are in [RecorderMachine]; this class is its runner. One actor in [scope] owns the [RecModel]: the
 * public calls are requests with a one-shot reply, the scanner and core flows are signals, and each [RecEffect]
 * (the foreground service, a core call on [io], the scanner, location) runs here and comes back as an event. Requests
 * run one at a time; cancelling a caller only stops its wait, so a transition never stays half done. Any exception
 * from a platform or core call becomes a failure event.
 */
class PlatformRecorder(
    private val core: Deferred<CoreIngest>,
    private val scanner: RecordingScanner,
    private val location: RecordingLocation,
    private val host: ForegroundHost,
    private val grants: RecordingGrants,
    private val environment: () -> ScanEnvironment,
    private val io: CoroutineDispatcher,
    /** The process scope: the actor runs here, so a cancelled caller never leaves a transition half done. */
    private val scope: CoroutineScope,
    /** `CoreQueries.events` `RecordingChanged` states, for the device count between transitions. */
    coreStates: Flow<CoreRecordingState>? = null,
) : Recorder {
    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private sealed interface Message {
        class Request(val event: RecEvent, val reply: CompletableDeferred<RecOutcome>) : Message
        class Signal(val event: RecEvent) : Message
    }

    private val inbox = Channel<Message>(Channel.UNLIMITED)

    init {
        scope.launch { scanner.deferred.collect { signal(RecEvent.ScannerDeferred(it)) } }
        scope.launch { scanner.dropped.collect { signal(RecEvent.Dropped(it)) } }
        scope.launch { scanner.error.collect { error -> error?.let { signal(RecEvent.ScannerError(it)) } } }
        coreStates?.let { flow -> scope.launch { flow.collect { signal(RecEvent.CoreChanged(it)) } } }
        scope.launch { actor() }
        // RecordingService reads the process recorder from here (notification actions, foreground confirmation).
        RecordingServiceLink.recorder = this
    }

    /** After a process restart (§4.4): adopt the core's state. `Core::open` has already closed a left-open session. */
    suspend fun restore() {
        ask(RecEvent.Restore)
    }

    override suspend fun start(): Result<String> = ask(Start).sessionResult()

    override suspend fun pause() {
        ask(RecEvent.Pause)
    }

    override suspend fun resume() {
        ask(RecEvent.Resume)
    }

    override suspend fun stop(): Result<String> = ask(RecEvent.Stop).sessionResult()

    private fun signal(event: RecEvent) {
        inbox.trySend(Message.Signal(event))
    }

    private suspend fun ask(event: RecEvent): RecOutcome {
        val reply = CompletableDeferred<RecOutcome>()
        inbox.send(Message.Request(event, reply))
        return reply.await()
    }

    private suspend fun actor() {
        var model = RecModel()
        for (message in inbox) {
            val (first, reply) = when (message) {
                is Message.Request -> (if (message.event === Start) RecEvent.Start(missingGrants()) else message.event) to message.reply
                is Message.Signal -> message.event to null
            }
            val pending = ArrayDeque<RecEvent>().apply { add(first) }
            while (pending.isNotEmpty()) {
                val transition = RecorderMachine.reduce(model, pending.removeFirst())
                model = transition.state
                _state.value = model.public()
                for (effect in transition.effects) {
                    when (effect) {
                        is RecEffect.Reply -> reply?.complete(effect.outcome)
                        RecEffect.EnterForeground, RecEffect.LeaveForeground, RecEffect.CoreStart, RecEffect.CorePause,
                        RecEffect.CoreResume, RecEffect.CoreStop, RecEffect.CoreRead, RecEffect.DrainScanner,
                        RecEffect.StartScanner, RecEffect.StartLocation, RecEffect.StopLocation,
                        -> execute(effect)?.let(pending::add)
                    }
                }
            }
            // A request the machine left unanswered (never expected) must not hang its caller.
            reply?.complete(RecOutcome.Failed(IllegalStateExceptionValue("the recorder did not answer")))
        }
    }

    /** Runs one effect; a result (or any failure, as a value) comes back as the next event. */
    private suspend fun execute(effect: RecEffect): RecEvent? = when (effect) {
        RecEffect.EnterForeground -> RecEvent.ForegroundEntered(attempt { host.enterForeground() } ?: false)
        RecEffect.LeaveForeground -> attempt { host.leaveForeground() }.let { null }
        RecEffect.CoreStart -> coreCall {
            val ingest = core.await()
            ingest.startRecording(environment())
            RecEvent.CoreStarted(ingest.recordingState())
        }
        RecEffect.CorePause -> coreCall {
            val ingest = core.await()
            ingest.pauseRecording()
            RecEvent.CorePaused(ingest.recordingState())
        }
        RecEffect.CoreResume -> coreCall {
            val ingest = core.await()
            ingest.resumeRecording()
            RecEvent.CoreResumed(ingest.recordingState())
        }
        RecEffect.CoreStop -> coreCall { RecEvent.CoreStopped(SessionId(core.await().stopRecording())) }
        RecEffect.CoreRead -> coreCall { RecEvent.CoreRead(core.await().recordingState()) }
        RecEffect.DrainScanner -> attempt { scanner.stopAndDrain() }.let { null }
        RecEffect.StartScanner -> attempt { scanner.start() }.let { null }
        RecEffect.StartLocation -> attempt { location.startRecording() }.let { null }
        RecEffect.StopLocation -> attempt { location.stopRecording() }.let { null }
        is RecEffect.Reply -> null
    }

    private suspend fun coreCall(call: suspend () -> RecEvent): RecEvent = try {
        withContext(io) { call() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RecEvent.CoreFailed(failure)
    }

    /** A platform call whose failure is logged and treated as "did not happen". */
    private suspend fun <T> attempt(call: suspend () -> T): T? = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // android.util.Log is a stub that throws in JVM tests; logging must never turn a failure into a crash.
        runCatching { Log.w(TAG, "recording step failed", failure) }
        null
    }

    private fun missingGrants(): List<String> = try {
        grants.missing()
    } catch (failure: Exception) {
        listOf(failure.message ?: "grants unavailable")
    }

    private fun RecOutcome.sessionResult(): Result<String> = when (this) {
        is RecOutcome.Session -> Result.success(session.value)
        is RecOutcome.Failed -> Result.failure(cause)
        RecOutcome.Done -> Result.failure(IllegalStateExceptionValue("no session"))
    }

    companion object {
        private const val TAG = "PlatformRecorder"
        const val MISSING_GRANTS = RecorderMachine.MISSING_GRANTS
        const val SERVICE_REFUSED = RecorderMachine.SERVICE_REFUSED

        /** The start request before the runner samples the grants. */
        private val Start = RecEvent.Start(emptyList())
    }
}
