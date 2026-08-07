package edu.ucsd.sysnet.cluetoothscanner.repository

import edu.ucsd.sysnet.cluetoothscanner.core.CoreGateway
import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanInput
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreEffect
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreState
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayCoreUpdate
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayRecordResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CluetoothRepositoryTest {
    @Test
    fun coreStateMapsToImmutableUiState() {
        val state = GatewayCoreState(
            totalObservations = 12u,
            observationsWithLocation = 10u,
            activePayloadRows = 2u,
            activePayloadEstimatedBytes = 200u,
            pendingUploadCount = 4u,
            invalidPendingPayloadCount = 1u,
            recentLocationFixCount = 3u,
            hasLocation = true,
            latestObservationAtMs = 1234,
            latestLocalName = "sensor",
        ).toAppUiState(apiVersion = 1u)

        assertTrue(state.coreReady)
        assertEquals(1u, state.apiVersion)
        assertEquals(12uL, state.totalObservations)
        assertEquals(10uL, state.observationsWithLocation)
        assertEquals(2uL, state.activePayloadRows)
        assertEquals(200uL, state.activePayloadEstimatedBytes)
        assertEquals(4uL, state.pendingUploadCount)
        assertEquals(1uL, state.invalidPendingPayloadCount)
        assertEquals(3uL, state.recentLocationFixCount)
        assertTrue(state.hasLocation)
        assertEquals(1234L, state.latestObservationAtMs)
        assertEquals("sensor", state.latestLocalName)
        assertNull(state.errorMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun repositoryUsesFakeGatewayBatchesScansAndThrottlesPublication() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchSize = 2,
            scanBatchWindowMs = 100,
            scanPublicationIntervalMs = 500,
        )
        runCurrent()
        assertTrue(repository.uiState.value.coreReady)

        repository.recordObservation(observation(1))
        repository.recordObservation(observation(2))
        runCurrent()
        assertEquals(listOf(2), fake.recordedBatchSizes)
        assertEquals(2uL, repository.uiState.value.totalObservations)

        repository.recordObservation(observation(3))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(2, 1), fake.recordedBatchSizes)
        assertEquals(2uL, repository.uiState.value.totalObservations)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(3uL, repository.uiState.value.totalObservations)
        assertFalse(repository.uiState.value.hasLocation)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun periodicPersistenceCheckpointsWithoutForcingAFlush() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, fake.calls.count { it == "checkpoint" })
        assertFalse(fake.calls.contains("flush"))
        repository.closeAndJoin()
        assertTrue(fake.calls.lastIndexOf("flush") < fake.calls.lastIndexOf("close"))
        assertEquals("close", fake.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun checkpointFailureDegradesAndPausesUntilARepeatedCheckpointSucceeds() = runTest {
        val fake = FakeCoreGateway(failCheckpointAttempts = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(repository.uiState.value.storageDegraded)
        assertTrue(repository.uiState.value.ingressPaused)

        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(repository.uiState.value.storageDegraded)
        assertFalse(repository.uiState.value.ingressPaused)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun acknowledgedFlushReturnsFalseAfterRustSealingFailureAndPreservesOrder() = runTest {
        val fake = FakeCoreGateway(failOnFlush = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        val flush = async { repository.flushPayloadAndWait() }
        runCurrent()

        assertFalse(flush.await())
        assertTrue(fake.calls.indexOf("scan") < fake.calls.indexOf("flush"))
        assertTrue(repository.uiState.value.storageDegraded)
        assertFalse(repository.closeAndJoin())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exportPreparationStopsAfterAcknowledgedFlushFailure() = runTest {
        val fake = FakeCoreGateway(failOnFlush = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        val export = async {
            runCatching {
                repository.prepareSessionExport(
                    "0195c920-7c00-7abc-8def-0123456789ab",
                    edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat.JSONL,
                )
            }
        }
        runCurrent()
        assertTrue(export.await().isFailure)
        assertEquals(listOf("refresh", "flush"), fake.calls)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun successfulExportIsPreparedOnlyAfterFlushAndCanBeAcknowledged() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()
        val export = async {
            repository.prepareSessionExport(
                "0195c920-7c00-7abc-8def-0123456789ab",
                edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat.PARQUET,
            )
        }
        runCurrent()
        val prepared = export.await()
        assertEquals("export-id", prepared.exportId)
        assertTrue(fake.calls.indexOf("flush") < fake.calls.indexOf("prepare-export"))
        repository.acknowledgeExport(prepared.exportId)
        assertEquals("ack-export", fake.calls.last())
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun processOwnedGatewayOpensOnceAndSerializesSamePayloadTransferAndAck() = runTest {
        val ciphertext = File.createTempFile("ciphertext", ".encrypted")
        val fake = FakeCoreGateway(uploadFile = ciphertext)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var opens = 0
        val repository = CluetoothRepository(
            gatewayFactory = {
                opens++
                fake
            },
            parentScope = this,
            nativeDispatcher = dispatcher,
        )
        val transferStarted = CompletableDeferred<Unit>()
        val releaseTransfer = CompletableDeferred<Unit>()
        var activeTransfers = 0
        var maximumActiveTransfers = 0
        val uploader = edu.ucsd.sysnet.cluetoothscanner.service.ObjectFileUploader { _, _ ->
            activeTransfers++
            maximumActiveTransfers = maxOf(maximumActiveTransfers, activeTransfers)
            transferStarted.complete(Unit)
            releaseTransfer.await()
            activeTransfers--
        }
        runCurrent()

        val first = async { repository.uploadPendingV2(uploader) }
        transferStarted.await()
        val second = async { repository.uploadPendingV2(uploader) }
        runCurrent()
        assertEquals(listOf("prepare"), fake.uploadCalls)

        releaseTransfer.complete(Unit)
        runCurrent()
        first.await()
        second.await()

        assertEquals(1, opens)
        assertEquals(1, maximumActiveTransfers)
        assertEquals(listOf("prepare", "success"), fake.uploadCalls)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionStartIsOrderedBeforeScansAndExplicitFinishFollowsDrain() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()
        repository.startScanSession()
        repository.recordObservation(observation(1))
        val finish = async { repository.finishScanSessionAndWait() }
        runCurrent()

        assertTrue(finish.await())
        assertTrue(fake.calls.indexOf("start") < fake.calls.indexOf("scan"))
        assertTrue(fake.calls.indexOf("scan") < fake.calls.indexOf("finish"))
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun repositoryPreservesLocationAndScanIngressOrder() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 100,
        )
        runCurrent()

        repository.updateLocation(
            CoreLocationInput(
                lat = 32.0,
                lon = -117.0,
                accuracyMeters = 4.0,
                observedAtMs = 1,
                elapsedRealtimeNanos = 1,
            ),
        )
        repository.recordObservation(observation(2))
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("refresh", "location", "scan"), fake.calls)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeDrainsPartialScanBatchBeforeGatewayShutdown() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchSize = 64,
            scanBatchWindowMs = 10_000,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        repository.closeAndJoin()

        assertEquals(listOf(1), fake.recordedBatchSizes)
        assertTrue(fake.calls.indexOf("scan") < fake.calls.lastIndexOf("flush"))
        assertEquals("close", fake.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ingressDoesNotEvictQueuedDurableObservations() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchSize = 64,
            scanBatchWindowMs = 0,
        )

        repeat(300) { index -> repository.recordObservation(observation(index.toLong())) }
        runCurrent()
        repository.closeAndJoin()
        assertEquals(300, fake.recordedBatchSizes.sum())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun committedBatchIsNeverReplayedWhenPostCommitUploadSchedulingThrows() = runTest {
        val fake = FakeCoreGateway(
            recordEffects = listOf(GatewayCoreEffect.ScheduleUpload),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
            onUploadNeeded = { error("scheduler unavailable after commit") },
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(1), fake.recordedBatchSizes)
        assertEquals(0, repository.uiState.value.queuedObservationCount)
        assertFalse(repository.uiState.value.actorTerminated)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun committedBatchIsNeverReplayedWhenGatewayThrowsAfterMutation() = runTest {
        val fake = FakeCoreGateway(throwAfterRecordCommit = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(1), fake.recordedBatchSizes)
        assertEquals(1uL, fake.state().totalObservations)
        assertEquals(0, repository.uiState.value.queuedObservationCount)
        assertTrue(repository.uiState.value.storageDegraded)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun observationHotPathDefersSessionSummaryUntilCheckpointBoundary() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()
        val initialSummaries = fake.scanSessionCalls

        repository.recordObservation(observation(1))
        runCurrent()
        assertEquals(initialSummaries, fake.scanSessionCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(fake.scanSessionCalls > initialSummaries)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun typedAutomaticPersistenceFailurePausesWithoutReplayingAcceptedBatch() = runTest {
        val fake = FakeCoreGateway(
            recordEffects = listOf(GatewayCoreEffect.PersistenceDegraded("rotation failed")),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        assertEquals(listOf(1), fake.recordedBatchSizes)
        assertTrue(repository.uiState.value.storageDegraded)
        assertTrue(repository.uiState.value.ingressPaused)

        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(repository.uiState.value.storageDegraded)
        assertFalse(repository.uiState.value.ingressPaused)
        assertEquals(listOf(1), fake.recordedBatchSizes)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedOrTimedOutSessionFinishIsVisibleInUiState() = runTest {
        val fake = FakeCoreGateway()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
        )
        runCurrent()

        assertFalse(repository.finishScanSessionAndWait(timeoutMs = 0))
        assertTrue(repository.uiState.value.storageDegraded)
        assertEquals(
            "Scan session finalization failed or timed out",
            repository.uiState.value.errorMessage,
        )
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finishSchedulesUploadOnlyForSealedRowsAndSchedulingFailureDoesNotReplayFinish() = runTest {
        var schedules = 0
        val withRows = FakeCoreGateway(
            finishEffects = listOf(GatewayCoreEffect.ScheduleUpload),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { withRows },
            parentScope = this,
            nativeDispatcher = dispatcher,
            onUploadNeeded = {
                schedules++
                error("scheduler unavailable after finish commit")
            },
        )
        runCurrent()

        assertTrue(repository.finishScanSessionAndWait())
        assertEquals(1, schedules)
        assertEquals(1, withRows.calls.count { it == "finish" })
        repository.closeAndJoin()

        val noRows = FakeCoreGateway()
        val emptyRepository = CluetoothRepository(
            gatewayFactory = { noRows },
            parentScope = this,
            nativeDispatcher = dispatcher,
            onUploadNeeded = { schedules++ },
        )
        runCurrent()
        assertTrue(emptyRepository.finishScanSessionAndWait())
        assertEquals(1, schedules)
        emptyRepository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun incompatibleCoreApiIsRejectedBeforeObservationsAreAccepted() = runTest {
        val fake = FakeCoreGateway(apiVersion = 5u)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = StandardTestDispatcher(testScheduler),
            scanBatchWindowMs = 0,
        )

        repository.recordObservation(observation(1))
        runCurrent()

        assertTrue(repository.uiState.value.actorTerminated)
        assertTrue(fake.recordedBatchSizes.isEmpty())
        assertTrue("close" in fake.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun durableBatchRetriesWithoutEvictionAfterStorageFailure() = runTest {
        val fake = FakeCoreGateway(failRecordAttempts = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        assertTrue(fake.recordedBatchSizes.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1), fake.recordedBatchSizes)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highWatermarkAdmissionPausesAndResumesOnlyAfterDurableLowWatermark() = runTest {
        val fake = FakeCoreGateway(failRecordAttempts = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchSize = 1,
            scanBatchWindowMs = 0,
            ingressCapacity = 8,
            ingressHighWatermark = 2,
            ingressLowWatermark = 0,
        )
        val pauses = mutableListOf<Boolean>()
        repository.setBackpressureListener { pauses += it }
        runCurrent()

        assertEquals(ObservationAdmission.ACCEPTED, repository.recordObservation(observation(1)))
        assertEquals(
            ObservationAdmission.PAUSE_REQUIRED,
            repository.recordObservation(observation(2)),
        )
        runCurrent()
        assertEquals(listOf(false, true), pauses)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(false, true, false), pauses)
        assertEquals(2, fake.recordedBatchSizes.sum())
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sustainedStorageFailureBoundsRetentionPausesAndDoesNotHangClose() = runTest {
        val fake = FakeCoreGateway(failRecordAttempts = Int.MAX_VALUE)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchSize = 1,
            scanBatchWindowMs = 0,
            ingressCapacity = 8,
            ingressHighWatermark = 4,
            ingressLowWatermark = 1,
        )
        runCurrent()

        val admissions = (0 until 20).map { index ->
            repository.recordObservation(observation(index.toLong()))
        }
        runCurrent()
        assertTrue(admissions.contains(ObservationAdmission.PAUSE_REQUIRED))
        assertTrue(admissions.contains(ObservationAdmission.REJECTED_DEGRADED))
        assertTrue(repository.uiState.value.queuedObservationCount <= 9)
        assertTrue(repository.uiState.value.ingressPaused)
        assertTrue(repository.uiState.value.storageDegraded)

        val close = launch { repository.closeAndJoin() }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(close.isCompleted)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pendingDelayedPublicationCannotOverwriteTerminalState() = runTest {
        val fake = FakeCoreGateway(failOnClear = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { fake },
            parentScope = this,
            nativeDispatcher = dispatcher,
            scanBatchWindowMs = 0,
            scanPublicationIntervalMs = 500,
        )
        runCurrent()

        repository.recordObservation(observation(1))
        runCurrent()
        repository.recordObservation(observation(2))
        runCurrent()
        assertEquals(1uL, repository.uiState.value.totalObservations)

        repository.clearLocation()
        runCurrent()
        assertTrue(repository.uiState.value.actorTerminated)
        assertFalse(repository.uiState.value.coreReady)
        assertEquals("clear failed", repository.uiState.value.errorMessage)

        advanceTimeBy(500)
        runCurrent()
        assertTrue(repository.uiState.value.actorTerminated)
        assertFalse(repository.uiState.value.coreReady)
        assertEquals(1uL, repository.uiState.value.totalObservations)
        repository.closeAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun throwableActorFailureIsTerminalObservableAndClosesIngress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CluetoothRepository(
            gatewayFactory = { ThrowingCoreGateway() },
            parentScope = this,
            nativeDispatcher = dispatcher,
        )
        runCurrent()

        assertTrue(repository.uiState.value.actorTerminated)
        assertFalse(repository.uiState.value.coreReady)
        assertEquals("native linkage failed", repository.uiState.value.errorMessage)

        repository.recordObservation(observation(1))
        repository.closeAndJoin()
    }

    private fun observation(index: Long) = CoreScanInput(
        addr = "AA:BB:CC:DD:EE:FF",
        rssi = -50,
        scannedAtMs = index,
        elapsedRealtimeNanos = index,
        raw = byteArrayOf(index.toByte()),
        localName = null,
        txPower = null,
        isConnectable = null,
    )
}

private class FakeCoreGateway(
    private val failOnClear: Boolean = false,
    private var failRecordAttempts: Int = 0,
    private var failCheckpointAttempts: Int = 0,
    private val failOnFlush: Boolean = false,
    private val uploadFile: File? = null,
    private val recordEffects: List<GatewayCoreEffect> = emptyList(),
    private val finishEffects: List<GatewayCoreEffect> = emptyList(),
    private val throwAfterRecordCommit: Boolean = false,
    private val failOnFinish: Boolean = false,
    private val apiVersion: UInt = 6u,
) : CoreGateway {
    private var state = GatewayCoreState(
        totalObservations = 0u,
        observationsWithLocation = 0u,
        activePayloadRows = 0u,
        activePayloadEstimatedBytes = 0u,
        pendingUploadCount = 0u,
        invalidPendingPayloadCount = 0u,
        recentLocationFixCount = 0u,
        hasLocation = false,
        latestObservationAtMs = null,
        latestLocalName = null,
    )
    val recordedBatchSizes = mutableListOf<Int>()
    val calls = mutableListOf<String>()
    val uploadCalls = mutableListOf<String>()
    var scanSessionCalls = 0
        private set
    private var uploadPending = uploadFile != null
    private val uploadPayloadId = "0195c920-7c00-7abc-8def-0123456789ab"
    private val uploadObjectPath =
        "scans/v2/2025/03/24/$uploadPayloadId.parquet.encrypted"

    override fun apiVersion(): UInt = apiVersion

    override fun state(): GatewayCoreState = state

    override fun refresh(): GatewayCoreUpdate {
        calls += "refresh"
        return update()
    }

    override fun startScanSession(): String {
        calls += "start"
        return "0195c920-7c00-7abc-8def-0123456789ab"
    }

    override fun finishScanSession(): GatewayCoreUpdate {
        calls += "finish"
        if (failOnFinish) error("finish failed")
        return GatewayCoreUpdate(state = state, effects = finishEffects)
    }

    override fun scanSessions(): List<edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession> {
        scanSessionCalls++
        return emptyList()
    }

    override fun updateLocation(fix: CoreLocationInput): GatewayCoreUpdate {
        calls += "location"
        state = state.copy(recentLocationFixCount = 1u, hasLocation = true)
        return update()
    }

    override fun clearLocation(): GatewayCoreUpdate {
        if (failOnClear) error("clear failed")
        calls += "clear"
        state = state.copy(recentLocationFixCount = 0u, hasLocation = false)
        return update()
    }

    override fun recordObservations(observations: List<CoreScanInput>): GatewayRecordResult {
        if (failRecordAttempts > 0) {
            failRecordAttempts--
            return GatewayRecordResult.RetryablePreCommitFailure("storage unavailable")
        }
        calls += "scan"
        recordedBatchSizes += observations.size
        state = state.copy(
            totalObservations = state.totalObservations + observations.size.toULong(),
            latestObservationAtMs = observations.last().scannedAtMs,
        )
        if (throwAfterRecordCommit) {
            throw LinkageError("return mapping failed after native commit")
        }
        return GatewayRecordResult.Committed(
            GatewayCoreUpdate(state = state, effects = recordEffects),
        )
    }

    override fun checkpointActivePayload(): Boolean {
        calls += "checkpoint"
        if (failCheckpointAttempts > 0) {
            failCheckpointAttempts--
            error("checkpoint failed")
        }
        return false
    }

    override fun flushPayload(): Boolean {
        calls += "flush"
        if (failOnFlush) error("seal failed")
        return false
    }

    override fun pendingUploads() = if (uploadPending) {
        listOf(
            edu.ucsd.sysnet.cluetoothscanner.core.GatewayPendingUpload(
                uploadPayloadId,
                "plain",
                uploadObjectPath,
                1u,
                4u,
            ),
        )
    } else {
        emptyList()
    }

    override fun prepareSessionExport(
        sessionId: String,
        format: edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat,
    ): edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport {
        calls += "prepare-export"
        return edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport(
            exportId = "export-id",
            localPath = "export-path",
            suggestedFileName = "session.parquet",
            fileCount = 1u,
            sizeBytes = 10u,
        )
    }

    override fun acknowledgeExport(exportId: String) {
        calls += "ack-export"
    }

    override fun prepareUpload(payloadId: String): edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedUpload {
        require(payloadId == uploadPayloadId)
        uploadCalls += "prepare"
        return edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedUpload(
            uploadPayloadId,
            requireNotNull(uploadFile).path,
            uploadObjectPath,
            4u,
            52u,
        )
    }

    override fun markUploadSucceeded(payloadId: String): GatewayCoreUpdate {
        require(payloadId == uploadPayloadId)
        uploadCalls += "success"
        uploadPending = false
        return update()
    }

    override fun markUploadFailed(payloadId: String, message: String): GatewayCoreUpdate {
        require(payloadId == uploadPayloadId)
        uploadCalls += "failed"
        return update()
    }

    override fun close() {
        calls += "close"
    }

    private fun update() = GatewayCoreUpdate(state = state, effects = emptyList())
}

private class ThrowingCoreGateway : CoreGateway {
    private val state = GatewayCoreState(
        totalObservations = 0u,
        observationsWithLocation = 0u,
        activePayloadRows = 0u,
        activePayloadEstimatedBytes = 0u,
        pendingUploadCount = 0u,
        invalidPendingPayloadCount = 0u,
        recentLocationFixCount = 0u,
        hasLocation = false,
        latestObservationAtMs = null,
        latestLocalName = null,
    )

    override fun apiVersion(): UInt = 6u
    override fun state(): GatewayCoreState = state
    override fun refresh(): GatewayCoreUpdate = throw LinkageError("native linkage failed")
    override fun updateLocation(fix: CoreLocationInput): GatewayCoreUpdate = error("unexpected")
    override fun clearLocation(): GatewayCoreUpdate = error("unexpected")
    override fun recordObservations(observations: List<CoreScanInput>): GatewayRecordResult =
        error("unexpected")
    override fun flushPayload(): Boolean = error("unexpected")
    override fun pendingUploads() =
        emptyList<edu.ucsd.sysnet.cluetoothscanner.core.GatewayPendingUpload>()
    override fun prepareUpload(payloadId: String) = error("unexpected")
    override fun markUploadSucceeded(payloadId: String) = error("unexpected")
    override fun markUploadFailed(payloadId: String, message: String) = error("unexpected")
    override fun close() = Unit
}
