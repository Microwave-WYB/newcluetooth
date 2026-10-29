package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.util.Log
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.platform.describe
import edu.ucsd.sysnet.cluetoothscanner.platform.failureHandler
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
import kotlinx.coroutines.flow.update
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

    /**
     * [stopAndDrain] with its outcome as a value: a scanner that cannot stop or flush in bounded time says so instead
     * of hanging the recorder. The default trusts [stopAndDrain].
     */
    suspend fun drain(): DrainOutcome {
        stopAndDrain()
        return DrainOutcome.Drained
    }
}

/** How a [RecordingScanner.drain] ended. */
sealed interface DrainOutcome {
    data object Drained : DrainOutcome

    /** The scanner did not stop or flush in time (or had failed); [reason] says which. */
    data class Failed(val reason: String) : DrainOutcome
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
    /** R-10, `CoreEvent.StorageFull { freeBytes }`: the core stopped writing and paused the recording. */
    storageFull: Flow<Long>? = null,
) : Recorder {
    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _storageFull = MutableStateFlow<Long?>(null)

    /** R-10: the free bytes the core reported while the recording is paused for storage, else null. */
    internal val storageFullBytes: StateFlow<Long?> = _storageFull.asStateFlow()

    private sealed interface Message {
        class Request(val event: RecEvent, val reply: CompletableDeferred<RecOutcome>) : Message
        class Signal(val event: RecEvent) : Message
    }

    private val inbox = Channel<Message>(Channel.UNLIMITED)

    /** Every coroutine this runner launches carries it: an escaped exception becomes the recorder's error. */
    private val guard = failureHandler(TAG) { failure -> _state.update { it.copy(error = "The recorder stopped (${failure.describe()})") } }

    init {
        scope.launch(guard) { scanner.deferred.collect { signal(RecEvent.ScannerDeferred(it)) } }
        scope.launch(guard) { scanner.dropped.collect { signal(RecEvent.Dropped(it)) } }
        scope.launch(guard) { scanner.error.collect { error -> error?.let { signal(RecEvent.ScannerError(it)) } } }
        coreStates?.let { flow -> scope.launch(guard) { flow.collect { signal(RecEvent.CoreChanged(it)) } } }
        storageFull?.let { flow -> scope.launch(guard) { flow.collect { signal(RecEvent.StorageFull(it)) } } }
        scope.launch(guard) { actor() }
        // RecordingService reads the process recorder from here (notification actions, foreground confirmation).
        RecordingServiceLink.storageFull = storageFullBytes
        RecordingServiceLink.recorder = this
    }

    /** After a process restart (§4.4): adopt the core's state. `Core::open` has already closed a left-open session. */
    suspend fun restore() {
        ask(RecEvent.Restore)
    }

    override suspend fun start(): PlatformResult<String> = ask(Start).sessionResult()

    override suspend fun pause(): PlatformResult<Unit> = ask(RecEvent.Pause).doneResult()

    override suspend fun resume(): PlatformResult<Unit> = ask(RecEvent.Resume).doneResult()

    override suspend fun stop(): PlatformResult<String> = ask(RecEvent.Stop).sessionResult()

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
                _storageFull.value = model.storageFullBytes()
                // The batch stops at the first failed effect: its failure (with the effects not run) is reduced
                // next, and the machine decides which of them still run.
                val batch = transition.effects
                for ((index, effect) in batch.withIndex()) {
                    val result = when (effect) {
                        is RecEffect.Reply -> {
                            reply?.complete(effect.outcome)
                            EffectResult.Done(null)
                        }
                        RecEffect.EnterForeground, RecEffect.LeaveForeground, RecEffect.CoreStart, RecEffect.CorePause,
                        RecEffect.CoreResume, RecEffect.CoreStop, RecEffect.CoreRead, RecEffect.DrainScanner,
                        RecEffect.StartScanner, RecEffect.StartLocation, RecEffect.StopLocation,
                        -> runEffect(effect)
                    }
                    when (result) {
                        is EffectResult.Done -> result.event?.let(pending::add)
                        is EffectResult.Failed -> {
                            pending.addFirst(RecEvent.EffectFailed(effect, result.cause, batch.drop(index + 1)))
                            break
                        }
                    }
                }
            }
            // A request the machine left unanswered (never expected) must not hang its caller.
            reply?.complete(RecOutcome.Failed(PlatformError.Unavailable("the recorder did not answer")))
        }
    }

    /** An effect's outcome: its result event (if any), or its failure as a value. */
    private sealed interface EffectResult {
        data class Done(val event: RecEvent?) : EffectResult
        data class Failed(val cause: Throwable) : EffectResult
    }

    /** The one wrapper every effect goes through: any exception (or a typed failure) becomes [EffectResult.Failed]. */
    private suspend fun runEffect(effect: RecEffect): EffectResult = try {
        execute(effect)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // android.util.Log is a stub that throws in JVM tests; logging must never turn a failure into a crash.
        runCatching { Log.w(TAG, "recording step $effect failed", failure) }
        EffectResult.Failed(failure)
    }

    /** Runs one effect; its result comes back as the next event. Core calls run on [io]. */
    private suspend fun execute(effect: RecEffect): EffectResult = when (effect) {
        RecEffect.EnterForeground -> EffectResult.Done(RecEvent.ForegroundEntered(host.enterForeground()))
        RecEffect.LeaveForeground -> EffectResult.Done(host.leaveForeground().let { null })
        RecEffect.CoreStart -> EffectResult.Done(
            withContext(io) { RecEvent.CoreStartReturned(SessionId(core.await().startRecording(environment()))) },
        )
        RecEffect.CorePause -> EffectResult.Done(
            withContext(io) {
                val ingest = core.await()
                ingest.pauseRecording()
                RecEvent.CorePaused(ingest.recordingState())
            },
        )
        RecEffect.CoreResume -> EffectResult.Done(
            withContext(io) {
                val ingest = core.await()
                ingest.resumeRecording()
                RecEvent.CoreResumed(ingest.recordingState())
            },
        )
        RecEffect.CoreStop -> EffectResult.Done(withContext(io) { RecEvent.CoreStopped(SessionId(core.await().stopRecording())) })
        RecEffect.CoreRead -> EffectResult.Done(withContext(io) { RecEvent.CoreRead(core.await().recordingState()) })
        RecEffect.DrainScanner -> when (val drained = scanner.drain()) {
            DrainOutcome.Drained -> EffectResult.Done(null)
            is DrainOutcome.Failed -> EffectResult.Failed(IllegalStateExceptionValue(drained.reason))
        }
        RecEffect.StartScanner -> EffectResult.Done(scanner.start().let { null })
        RecEffect.StartLocation -> EffectResult.Done(location.startRecording().let { null })
        RecEffect.StopLocation -> EffectResult.Done(location.stopRecording().let { null })
        is RecEffect.Reply -> EffectResult.Done(null)
    }

    private fun missingGrants(): List<String> = try {
        grants.missing()
    } catch (failure: Exception) {
        listOf(failure.message ?: "grants unavailable")
    }

    private fun RecOutcome.sessionResult(): PlatformResult<String> = when (this) {
        is RecOutcome.Session -> PlatformResult.Ok(session.value)
        is RecOutcome.Failed -> PlatformResult.Err(error)
        RecOutcome.Done -> PlatformResult.Err(PlatformError.Unavailable("the recorder answered without a session"))
    }

    private fun RecOutcome.doneResult(): PlatformResult<Unit> = when (this) {
        is RecOutcome.Session, RecOutcome.Done -> PlatformResult.Ok(Unit)
        is RecOutcome.Failed -> PlatformResult.Err(error)
    }

    companion object {
        private const val TAG = "PlatformRecorder"
        const val MISSING_GRANTS = RecorderMachine.MISSING_GRANTS
        const val SERVICE_REFUSED = RecorderMachine.SERVICE_REFUSED

        /** The start request before the runner samples the grants. */
        private val Start = RecEvent.Start(emptyList())
    }
}
