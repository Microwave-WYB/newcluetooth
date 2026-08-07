package edu.ucsd.sysnet.cluetoothscanner.repository

import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreEffect
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreState
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreUpdate
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayDeleteResult
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayLegacySessionRows
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayRecordResult
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.EXPECTED_CORE_API_VERSION
import edu.ucsd.sysnet.cluetoothscanner.service.ObjectFileUploader
import edu.ucsd.sysnet.cluetoothscanner.service.PayloadUploadCoordinator
import edu.ucsd.sysnet.cluetoothscanner.service.PayloadUploadSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

private val LOGGER: Logger = Logger.getLogger("CluetoothRepository")
private const val DEFAULT_SCAN_BATCH_SIZE = 64
private const val DEFAULT_SCAN_BATCH_WINDOW_MS = 250L
private const val DEFAULT_SCAN_PUBLICATION_INTERVAL_MS = 500L
private const val DEFAULT_PAYLOAD_CHECKPOINT_INTERVAL_MS = 30_000L
private const val STORAGE_RETRY_INTERVAL_MS = 1_000L
private const val DEFAULT_INGRESS_CAPACITY = 1_024
private const val DEFAULT_INGRESS_HIGH_WATERMARK = 128
private const val DEFAULT_INGRESS_LOW_WATERMARK = 32

enum class ObservationAdmission {
    ACCEPTED,
    PAUSE_REQUIRED,
    REJECTED_DEGRADED,
}

private sealed interface CoreCommand {
    data class UpdateLocation(val fix: CoreLocationInput) : CoreCommand
    data object ClearLocation : CoreCommand
    data class RecordObservation(val observation: CoreScanInput) : CoreCommand
    data object StartSession : CoreCommand
    data class StartSessionAcknowledged(
        val acknowledgement: kotlinx.coroutines.CompletableDeferred<Boolean>,
    ) : CoreCommand
    data object CheckpointActivePayload : CoreCommand
    data object FlushPayload : CoreCommand
    data class FlushPayloadAcknowledged(
        val acknowledgement: kotlinx.coroutines.CompletableDeferred<Boolean>,
    ) : CoreCommand
    data class FinishSessionAcknowledged(
        val acknowledgement: kotlinx.coroutines.CompletableDeferred<Boolean>,
    ) : CoreCommand
    data object Refresh : CoreCommand
}

data class AppUiState(
    val coreReady: Boolean = false,
    val apiVersion: UInt? = null,
    val totalObservations: ULong = 0u,
    val observationsWithLocation: ULong = 0u,
    val activePayloadRows: ULong = 0u,
    val activePayloadEstimatedBytes: ULong = 0u,
    val pendingUploadCount: ULong = 0u,
    val invalidPendingPayloadCount: ULong = 0u,
    val preparedUploadCount: ULong = 0u,
    val failedUploadCount: ULong = 0u,
    val lastUploadError: String? = null,
    val queuedObservationCount: Int = 0,
    val ingressPaused: Boolean = false,
    val storageDegraded: Boolean = false,
    val recentLocationFixCount: ULong = 0u,
    val hasLocation: Boolean = false,
    val latestObservationAtMs: Long? = null,
    val latestLocalName: String? = null,
    val actorTerminated: Boolean = false,
    val errorMessage: String? = null,
    val sessions: List<GatewayScanSession> = emptyList(),
)

fun GatewayCoreState.toAppUiState(
    apiVersion: UInt,
    errorMessage: String? = null,
) = AppUiState(
    coreReady = true,
    apiVersion = apiVersion,
    totalObservations = totalObservations,
    observationsWithLocation = observationsWithLocation,
    activePayloadRows = activePayloadRows,
    activePayloadEstimatedBytes = activePayloadEstimatedBytes,
    pendingUploadCount = pendingUploadCount,
    invalidPendingPayloadCount = invalidPendingPayloadCount,
    preparedUploadCount = preparedUploadCount,
    failedUploadCount = failedUploadCount,
    lastUploadError = lastUploadError,
    recentLocationFixCount = recentLocationFixCount,
    hasLocation = hasLocation,
    latestObservationAtMs = latestObservationAtMs,
    latestLocalName = latestLocalName,
    errorMessage = errorMessage,
)

class CluetoothRepository(
    gatewayFactory: () -> CoreGateway,
    parentScope: CoroutineScope,
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scanBatchSize: Int = DEFAULT_SCAN_BATCH_SIZE,
    private val scanBatchWindowMs: Long = DEFAULT_SCAN_BATCH_WINDOW_MS,
    private val scanPublicationIntervalMs: Long = DEFAULT_SCAN_PUBLICATION_INTERVAL_MS,
    private val ingressCapacity: Int = DEFAULT_INGRESS_CAPACITY,
    private val ingressHighWatermark: Int = DEFAULT_INGRESS_HIGH_WATERMARK,
    private val ingressLowWatermark: Int = DEFAULT_INGRESS_LOW_WATERMARK,
    private val onUploadNeeded: () -> Unit = {},
) : AutoCloseable {
    private val repositoryJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + repositoryJob)
    private val gateway = scope.async(nativeDispatcher) {
        val openedGateway = gatewayFactory()
        val version = try {
            openedGateway.apiVersion()
        } catch (error: Throwable) {
            runCatching { openedGateway.close() }
            throw error
        }
        if (version != EXPECTED_CORE_API_VERSION) {
            runCatching { openedGateway.close() }
            error("Unsupported native core API $version; expected $EXPECTED_CORE_API_VERSION")
        }
        openedGateway
    }
    private val commands: Channel<CoreCommand>
    private val publicationLock = Any()
    private var lastScanPublicationNanos = 0L
    private var pendingScanState: AppUiState? = null
    private var scanPublicationJob: Job? = null
    private var terminalStatePublished = false
    private val queuedObservations = AtomicInteger(0)
    private val closing = AtomicBoolean(false)
    private val backpressurePaused = AtomicBoolean(false)
    // This repository is the process-wide core owner. All v2 reconciliation and
    // transfer sessions share this mutex, including periodic and one-time workers.
    private val uploadCoordinatorMutex = Mutex()
    @Volatile
    private var backpressureListener: ((Boolean) -> Unit)? = null

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val actorJob: Job
    private val closeFlushResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    init {
        require(scanBatchSize > 0) { "scanBatchSize must be greater than zero" }
        require(scanBatchWindowMs >= 0) { "scanBatchWindowMs must not be negative" }
        require(scanPublicationIntervalMs >= 0) {
            "scanPublicationIntervalMs must not be negative"
        }
        require(ingressCapacity > 0) { "ingressCapacity must be greater than zero" }
        require(ingressHighWatermark in 1 until ingressCapacity) {
            "ingressHighWatermark must be below ingressCapacity"
        }
        require(ingressLowWatermark in 0 until ingressHighWatermark) {
            "ingressLowWatermark must be below ingressHighWatermark"
        }
        commands = Channel(ingressCapacity)
        actorJob = scope.launch(nativeDispatcher) { processCoreCommands() }
        scope.launch(nativeDispatcher) {
            while (true) {
                delay(DEFAULT_PAYLOAD_CHECKPOINT_INTERVAL_MS)
                submit(CoreCommand.CheckpointActivePayload)
            }
        }
        submit(CoreCommand.Refresh)
    }

    fun updateLocation(fix: CoreLocationInput) {
        submit(CoreCommand.UpdateLocation(fix))
    }

    fun clearLocation() {
        submit(CoreCommand.ClearLocation)
    }

    fun recordObservation(observation: CoreScanInput): ObservationAdmission {
        if (closing.get()) return ObservationAdmission.REJECTED_DEGRADED
        val retained = queuedObservations.incrementAndGet()
        if (commands.trySend(CoreCommand.RecordObservation(observation)).isFailure) {
            queuedObservations.decrementAndGet()
            publishIngressState(degraded = true, error = "Durable observation ingress is full")
            pauseIngress()
            return ObservationAdmission.REJECTED_DEGRADED
        }
        if (retained >= ingressHighWatermark) {
            pauseIngress()
            return ObservationAdmission.PAUSE_REQUIRED
        }
        return ObservationAdmission.ACCEPTED
    }

    fun setBackpressureListener(listener: ((Boolean) -> Unit)?) {
        backpressureListener = listener
        listener?.invoke(backpressurePaused.get())
    }

    fun startScanSession() {
        submit(CoreCommand.StartSession)
    }

    suspend fun startScanSessionAndWait(timeoutMs: Long = 5_000L): Boolean {
        val acknowledged = withTimeoutOrNull(timeoutMs) {
            val acknowledgement = kotlinx.coroutines.CompletableDeferred<Boolean>()
            commands.send(CoreCommand.StartSessionAcknowledged(acknowledgement))
            acknowledgement.await()
        } ?: false
        if (!acknowledged) {
            publishIngressState(
                degraded = true,
                error = "Scan session start failed or timed out",
            )
        }
        return acknowledged
    }

    fun flushPayload() {
        submit(CoreCommand.FlushPayload)
    }

    suspend fun flushPayloadAndWait(timeoutMs: Long = 5_000L): Boolean {
        val acknowledged = withTimeoutOrNull(timeoutMs) {
            val acknowledgement = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val command = CoreCommand.FlushPayloadAcknowledged(acknowledgement)
            commands.send(command)
            acknowledgement.await()
        } ?: false
        if (!acknowledged) {
            publishIngressState(
                degraded = true,
                error = "Timed out waiting for durable payload flush acknowledgement",
            )
        }
        return acknowledged
    }

    suspend fun finishScanSessionAndWait(timeoutMs: Long = 5_000L): Boolean {
        val acknowledged = withTimeoutOrNull(timeoutMs) {
            val acknowledgement = kotlinx.coroutines.CompletableDeferred<Boolean>()
            commands.send(CoreCommand.FinishSessionAcknowledged(acknowledgement))
            acknowledgement.await()
        } ?: false
        if (!acknowledged) {
            publishIngressState(
                degraded = true,
                error = "Scan session finalization failed or timed out",
            )
        }
        return acknowledged
    }

    suspend fun prepareSessionExport(
        sessionId: String,
        format: GatewayExportFormat,
    ): GatewayPreparedExport {
        check(flushPayloadAndWait()) { "Unable to save active observations before export" }
        return uploadCoordinatorMutex.withLock {
            gateway.await().prepareSessionExport(sessionId, format).also { publishGatewaySnapshot() }
        }
    }

    suspend fun prepareLegacySessionExport(
        session: GatewayLegacySessionRows,
        format: GatewayExportFormat,
    ): GatewayPreparedExport {
        check(flushPayloadAndWait()) { "Unable to save active observations before export" }
        return uploadCoordinatorMutex.withLock {
            gateway.await().prepareLegacySessionExport(session, format)
        }
    }

    suspend fun prepareFullExport(
        format: GatewayExportFormat,
        legacySessions: List<GatewayLegacySessionRows> = emptyList(),
    ): GatewayPreparedExport {
        check(flushPayloadAndWait()) { "Unable to save active observations before export" }
        return uploadCoordinatorMutex.withLock {
            gateway.await().prepareFullExport(format, legacySessions).also { publishGatewaySnapshot() }
        }
    }

    suspend fun acknowledgeExport(exportId: String) = uploadCoordinatorMutex.withLock {
        gateway.await().acknowledgeExport(exportId)
    }

    suspend fun deleteScanSession(
        sessionId: String,
        allowUnuploadedDataLoss: Boolean,
    ): GatewayDeleteResult = uploadCoordinatorMutex.withLock {
        gateway.await().deleteScanSession(sessionId, allowUnuploadedDataLoss).also {
            publishGatewaySnapshot()
        }
    }

    internal suspend fun uploadPendingV2(
        uploader: ObjectFileUploader,
    ): PayloadUploadSummary = uploadCoordinatorMutex.withLock {
        check(!closing.get()) { "Core repository is closing" }
        val summary = PayloadUploadCoordinator(gateway.await(), uploader).uploadPending()
        callCore(publishScanState = false) { it.refresh() }
        summary
    }

    fun refresh() {
        submit(CoreCommand.Refresh)
    }

    private fun submit(command: CoreCommand) {
        if (commands.trySend(command).isFailure) {
            LOGGER.warning("Rejected core command ${command::class.java.simpleName}; ingress is closed")
        }
    }

    private suspend fun processCoreCommands() {
        var pendingCommand: CoreCommand? = null
        var closeFlushSucceeded = false
        try {
            commandLoop@ while (true) {
                val command = pendingCommand
                    ?: commands.receiveCatching().getOrNull()
                    ?: break
                pendingCommand = null
                when (command) {
                    CoreCommand.StartSession -> {
                        gateway.await().startScanSession()
                        publishGatewaySnapshot()
                    }
                    is CoreCommand.StartSessionAcknowledged -> {
                        val succeeded = try {
                            gateway.await().startScanSession()
                            publishGatewaySnapshot()
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            publishFlushFailure(error)
                            false
                        }
                        command.acknowledgement.complete(succeeded)
                    }
                    is CoreCommand.UpdateLocation -> callCore(publishScanState = false) {
                        it.updateLocation(command.fix)
                    }
                    CoreCommand.ClearLocation -> callCore(publishScanState = false) {
                        it.clearLocation()
                    }
                    CoreCommand.CheckpointActivePayload ->
                        tryCheckpointCoreWithoutTerminatingActor()
                    CoreCommand.FlushPayload -> tryFlushCoreWithoutTerminatingActor()
                    is CoreCommand.FlushPayloadAcknowledged -> {
                        val succeeded = try {
                            flushCoreOrThrow()
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            publishFlushFailure(error)
                            false
                        }
                        command.acknowledgement.complete(succeeded)
                    }
                    is CoreCommand.FinishSessionAcknowledged -> {
                        val succeeded = try {
                            val openedGateway = gateway.await()
                            val apiVersion = openedGateway.apiVersion()
                            val committed = openedGateway.finishScanSession()
                            runCatching {
                                publishCoreUpdate(
                                    openedGateway,
                                    apiVersion,
                                    committed,
                                    publishScanState = false,
                                    refreshSessions = true,
                                )
                            }.onFailure { error ->
                                LOGGER.log(Level.WARNING, "Post-finish session refresh failed", error)
                            }
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            publishFlushFailure(error)
                            false
                        }
                        command.acknowledgement.complete(succeeded)
                    }
                    CoreCommand.Refresh -> callCore(publishScanState = false) { it.refresh() }
                    is CoreCommand.RecordObservation -> {
                        val batch = ArrayList<CoreScanInput>(scanBatchSize)
                        batch += command.observation
                        if (batch.size < scanBatchSize && scanBatchWindowMs > 0) {
                            withTimeoutOrNull(scanBatchWindowMs) {
                                while (batch.size < scanBatchSize) {
                                    val next = commands.receiveCatching().getOrNull() ?: break
                                    when (next) {
                                        is CoreCommand.RecordObservation -> batch += next.observation
                                        else -> {
                                            pendingCommand = next
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        // A normally closed ingress still submits the partial batch collected above.
                        if (!recordBatchWithStorageRetry(batch)) break@commandLoop
                    }
                }
            }
            closeFlushSucceeded = tryFlushCoreWithoutTerminatingActor()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val actorFailure = CancellationException("Native core actor failed")
            actorFailure.initCause(error)
            commands.cancel(actorFailure)
            publishTerminalState(error)
            LOGGER.log(Level.SEVERE, "Native core actor terminated", error)
        } finally {
            closeFlushResult.complete(closeFlushSucceeded)
            commands.close()
            scanPublicationJob?.cancel()
            try {
                gateway.await().close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                LOGGER.log(Level.SEVERE, "Failed to close native core", error)
            } finally {
                repositoryJob.cancel()
            }
        }
    }

    private suspend fun recordBatchWithStorageRetry(batch: List<CoreScanInput>): Boolean {
        var precommitDegraded = false
        while (true) {
            val openedGateway: CoreGateway
            val apiVersion: UInt
            val recordResult: GatewayRecordResult
            try {
                openedGateway = gateway.await()
                apiVersion = openedGateway.apiVersion()
                recordResult = openedGateway.recordObservations(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Unknown, linkage, UniFFI lifting, and post-commit mapping failures
                // are ambiguous or terminal. Never replay this batch.
                queuedObservations.addAndGet(-batch.size)
                publishIngressState(
                    degraded = true,
                    error = error.message ?: "Native observation boundary failed",
                )
                pauseIngress()
                LOGGER.log(Level.SEVERE, "Terminal observation boundary failure; batch will not replay", error)
                return false
            }

            if (recordResult is GatewayRecordResult.RetryablePreCommitFailure) {
                publishIngressState(degraded = true, error = recordResult.message)
                pauseIngress()
                precommitDegraded = true
                LOGGER.warning("Retrying typed pre-commit observation failure: ${recordResult.message}")
                if (closing.get()) {
                    val retained = queuedObservations.get()
                    val message = "Closing with $retained observations not durably accepted"
                    publishIngressState(degraded = true, error = message)
                    LOGGER.severe(message)
                    return false
                }
                delay(STORAGE_RETRY_INTERVAL_MS)
                continue
            }

            val committed = (recordResult as GatewayRecordResult.Committed).update
            val retained = queuedObservations.addAndGet(-batch.size)
            val persistenceFailure = committed.effects
                .filterIsInstance<GatewayCoreEffect.PersistenceDegraded>()
                .lastOrNull()
            try {
                publishCoreUpdate(
                    openedGateway = openedGateway,
                    apiVersion = apiVersion,
                    update = committed,
                    publishScanState = true,
                    refreshSessions = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Presentation, summary, and scheduling are after acceptance and
                // must never replay the committed batch.
                LOGGER.log(Level.WARNING, "Post-commit observation publication failed", error)
            }
            if (persistenceFailure != null) {
                publishIngressState(degraded = true, error = persistenceFailure.message)
                pauseIngress()
            } else {
                if (precommitDegraded) publishIngressState(degraded = false, error = null)
                if (!_uiState.value.storageDegraded &&
                    backpressurePaused.get() && retained <= ingressLowWatermark
                ) {
                    resumeIngress()
                }
            }
            return true
        }
    }

    private suspend fun checkpointCoreOrThrow() {
        val openedGateway = gateway.await()
        val apiVersion = openedGateway.apiVersion()
        val sealed = openedGateway.checkpointActivePayload()
        publishBoundarySnapshotBestEffort(openedGateway, apiVersion, sealed, "checkpoint")
    }

    private suspend fun tryCheckpointCoreWithoutTerminatingActor(): Boolean = try {
        checkpointCoreOrThrow()
        publishIngressState(degraded = false, error = null)
        if (backpressurePaused.get() && queuedObservations.get() <= ingressLowWatermark) {
            resumeIngress()
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        publishFlushFailure(error)
        false
    }

    private suspend fun flushCoreOrThrow() {
        val openedGateway = gateway.await()
        val apiVersion = openedGateway.apiVersion()
        val sealed = openedGateway.flushPayload()
        publishBoundarySnapshotBestEffort(openedGateway, apiVersion, sealed, "flush")
    }

    private fun publishBoundarySnapshotBestEffort(
        openedGateway: CoreGateway,
        apiVersion: UInt,
        sealed: Boolean,
        boundary: String,
    ) {
        if (sealed) {
            runCatching(onUploadNeeded).onFailure { error ->
                LOGGER.log(Level.WARNING, "Post-$boundary upload scheduling failed", error)
            }
        }
        runCatching {
            publishCoreUpdate(
                openedGateway,
                apiVersion,
                openedGateway.refresh(),
                publishScanState = false,
                refreshSessions = true,
            )
        }.onFailure { error ->
            LOGGER.log(Level.WARNING, "Post-$boundary UI/session refresh failed", error)
        }
    }

    private suspend fun tryFlushCoreWithoutTerminatingActor(): Boolean = try {
        flushCoreOrThrow()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        publishFlushFailure(error)
        false
    }

    private fun publishFlushFailure(error: Throwable) {
        synchronized(publicationLock) {
            if (!terminalStatePublished) {
                _uiState.value = _uiState.value.copy(
                    storageDegraded = true,
                    errorMessage = error.message ?: error::class.java.simpleName,
                )
            }
        }
        pauseIngress()
        LOGGER.log(Level.WARNING, "Durable payload persistence failed; retained rows will retry", error)
    }

    private suspend fun callCore(
        publishScanState: Boolean,
        refreshSessions: Boolean = true,
        operation: (CoreGateway) -> GatewayCoreUpdate,
    ) {
        val openedGateway = gateway.await()
        val apiVersion = openedGateway.apiVersion()
        val update = operation(openedGateway)
        publishCoreUpdate(openedGateway, apiVersion, update, publishScanState, refreshSessions)
    }

    private fun publishCoreUpdate(
        openedGateway: CoreGateway,
        apiVersion: UInt,
        update: GatewayCoreUpdate,
        publishScanState: Boolean,
        refreshSessions: Boolean,
    ) {
        if (update.effects.any { it is GatewayCoreEffect.ScheduleUpload }) {
            runCatching(onUploadNeeded).onFailure { error ->
                LOGGER.log(Level.WARNING, "Post-commit upload scheduling failed", error)
            }
        }
        val warning = update.effects.filterIsInstance<GatewayCoreEffect.StorageWarning>()
            .lastOrNull()
            ?.message
        val persistenceFailure = update.effects
            .filterIsInstance<GatewayCoreEffect.PersistenceDegraded>()
            .lastOrNull()
            ?.message
        val current = _uiState.value
        val sessions = if (refreshSessions) openedGateway.scanSessions() else current.sessions
        val mapped = update.state.toAppUiState(
            apiVersion = apiVersion,
            errorMessage = persistenceFailure ?: warning ?: current.errorMessage,
        ).copy(
            queuedObservationCount = queuedObservations.get(),
            ingressPaused = backpressurePaused.get(),
            storageDegraded = current.storageDegraded || persistenceFailure != null,
            sessions = sessions,
        )
        if (!_uiState.value.coreReady) {
            LOGGER.info("Native core ready (API $apiVersion)")
        }
        if (publishScanState) {
            publishScanState(mapped)
        } else {
            publishImmediately(mapped)
        }
    }

    private suspend fun publishGatewaySnapshot() {
        val openedGateway = gateway.await()
        callCore(publishScanState = false) { openedGateway.refresh() }
    }

    private fun pauseIngress() {
        if (backpressurePaused.compareAndSet(false, true)) {
            publishIngressState(degraded = _uiState.value.storageDegraded, error = null)
            backpressureListener?.invoke(true)
        }
    }

    private fun resumeIngress() {
        if (backpressurePaused.compareAndSet(true, false)) {
            publishIngressState(degraded = false, error = null)
            backpressureListener?.invoke(false)
        }
    }

    private fun publishIngressState(degraded: Boolean, error: String?) {
        synchronized(publicationLock) {
            if (terminalStatePublished) return
            _uiState.value = _uiState.value.copy(
                queuedObservationCount = queuedObservations.get(),
                ingressPaused = backpressurePaused.get(),
                storageDegraded = degraded,
                errorMessage = error ?: if (degraded) _uiState.value.errorMessage else null,
            )
        }
    }

    private fun publishTerminalState(error: Throwable) {
        synchronized(publicationLock) {
            terminalStatePublished = true
            pendingScanState = null
            scanPublicationJob?.cancel()
            scanPublicationJob = null
            _uiState.value = _uiState.value.copy(
                coreReady = false,
                actorTerminated = true,
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun publishImmediately(state: AppUiState) {
        synchronized(publicationLock) {
            if (terminalStatePublished) return
            pendingScanState = null
            scanPublicationJob?.cancel()
            scanPublicationJob = null
            _uiState.value = state
        }
    }

    private fun publishScanState(state: AppUiState) {
        synchronized(publicationLock) {
            if (terminalStatePublished) return
            val elapsedMs = (System.nanoTime() - lastScanPublicationNanos) / 1_000_000
            if (lastScanPublicationNanos == 0L || elapsedMs >= scanPublicationIntervalMs) {
                lastScanPublicationNanos = System.nanoTime()
                _uiState.value = state
                return
            }
            pendingScanState = state
            if (scanPublicationJob == null) {
                val remainingMs = scanPublicationIntervalMs - elapsedMs
                scanPublicationJob = scope.launch(nativeDispatcher) {
                    delay(remainingMs)
                    synchronized(publicationLock) {
                        if (terminalStatePublished) return@synchronized
                        pendingScanState?.let { pending ->
                            lastScanPublicationNanos = System.nanoTime()
                            _uiState.value = pending
                        }
                        pendingScanState = null
                        scanPublicationJob = null
                    }
                }
            }
        }
    }

    suspend fun closeAndJoin(timeoutMs: Long = 5_000L): Boolean {
        closing.set(true)
        commands.close()
        val closed = withTimeoutOrNull(timeoutMs) {
            actorJob.join()
            closeFlushResult.await()
        } ?: false
        if (!closed) {
            actorJob.cancel(CancellationException("Timed out closing native core repository"))
            LOGGER.severe("Timed out closing native core repository")
        }
        return closed
    }

    override fun close() {
        check(runBlocking { closeAndJoin() }) {
            "Native core repository closed without a successful final payload flush"
        }
    }
}
