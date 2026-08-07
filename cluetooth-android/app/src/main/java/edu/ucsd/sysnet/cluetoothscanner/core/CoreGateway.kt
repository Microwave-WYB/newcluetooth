package edu.ucsd.sysnet.cluetoothscanner.core

import edu.ucsd.sysnet.cluetoothscanner.core.generated.CluetoothCore
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEffect
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreException
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SchemaV2Row
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LegacySessionRows
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.generated.defaultCoreConfig

const val EXPECTED_CORE_API_VERSION: UInt = 6u

interface CoreGateway : AutoCloseable {
    fun apiVersion(): UInt
    fun state(): GatewayCoreState
    fun refresh(): GatewayCoreUpdate
    fun startScanSession(): String = error("Sessions are unavailable")
    fun finishScanSession(): GatewayCoreUpdate = error("Sessions are unavailable")
    fun scanSessions(): List<GatewayScanSession> = emptyList()
    fun prepareSessionExport(sessionId: String, format: GatewayExportFormat): GatewayPreparedExport =
        error("Session export is unavailable")
    fun prepareLegacySessionExport(session: GatewayLegacySessionRows, format: GatewayExportFormat): GatewayPreparedExport =
        error("Legacy session export is unavailable")
    fun prepareFullExport(format: GatewayExportFormat, legacySessions: List<GatewayLegacySessionRows>): GatewayPreparedExport =
        error("Full export is unavailable")
    fun acknowledgeExport(exportId: String) = Unit
    fun deleteScanSession(sessionId: String, allowUnuploadedDataLoss: Boolean): GatewayDeleteResult =
        error("Session deletion is unavailable")
    fun updateLocation(fix: CoreLocationInput): GatewayCoreUpdate
    fun clearLocation(): GatewayCoreUpdate
    fun recordObservations(observations: List<CoreScanInput>): GatewayRecordResult
    fun checkpointActivePayload(): Boolean = false
    fun flushPayload(): Boolean
    fun pendingUploads(): List<GatewayPendingUpload>
    fun prepareUpload(payloadId: String): GatewayPreparedUpload
    fun markUploadSucceeded(payloadId: String): GatewayCoreUpdate
    fun markUploadFailed(payloadId: String, message: String): GatewayCoreUpdate
}

data class CoreLocationInput(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double,
    val observedAtMs: Long,
    val elapsedRealtimeNanos: Long,
)

data class CoreScanInput(
    val addr: String,
    val rssi: Int?,
    val scannedAtMs: Long,
    val elapsedRealtimeNanos: Long,
    val raw: ByteArray,
    val localName: String?,
    val txPower: Int?,
    val isConnectable: Boolean?,
)

data class GatewayCoreState(
    val totalObservations: ULong,
    val observationsWithLocation: ULong,
    val activePayloadRows: ULong,
    val activePayloadEstimatedBytes: ULong,
    val pendingUploadCount: ULong,
    val invalidPendingPayloadCount: ULong,
    val preparedUploadCount: ULong = 0u,
    val failedUploadCount: ULong = 0u,
    val lastUploadError: String? = null,
    val recentLocationFixCount: ULong,
    val hasLocation: Boolean,
    val latestObservationAtMs: Long?,
    val latestLocalName: String?,
)

data class GatewayPreparedUpload(
    val payloadId: String,
    val ciphertextPath: String,
    val objectPath: String,
    val plaintextSizeBytes: ULong,
    val ciphertextSizeBytes: ULong,
)

enum class GatewaySessionStatus { ACTIVE, COMPLETED, INTERRUPTED, LEGACY }
enum class GatewaySessionUploadState { PENDING, UPLOADED, FAILED, EMPTY }
enum class GatewayExportFormat { JSONL, PARQUET }
enum class GatewayDeleteResult { DELETED_UPLOADED_LOCAL_COPY, DELETED_UNUPLOADED_DATA, NOTHING_LOCAL_TO_DELETE }

data class GatewayRoutePoint(
    val observedAtMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double,
)

data class GatewayAdvertisementCluster(
    val clusterId: String,
    val advTypes: ByteArray,
    val fieldLengths: List<Int>,
    val observationCount: ULong,
    val uniqueMacCount: ULong,
    val exactPayloadCount: ULong,
    val observationPoints: List<GatewayRoutePoint>,
)

data class GatewayScanSession(
    val sessionId: String,
    val status: GatewaySessionStatus,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val lastEventAtMs: Long,
    val observationCount: ULong,
    val uniqueMacCount: ULong,
    val exactPayloadCount: ULong,
    val retainedLocalBytes: ULong,
    val routePoints: List<GatewayRoutePoint>,
    val distanceMeters: Double,
    val averageAccuracyMeters: Double?,
    val uploadState: GatewaySessionUploadState,
    val diagnostic: String?,
    val clusters: List<GatewayAdvertisementCluster>,
)

data class GatewayLegacyRow(
    val addr: String,
    val rssi: Int?,
    val scannedAtMs: Long,
    val raw: ByteArray,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
)

data class GatewayLegacySessionRows(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val rows: List<GatewayLegacyRow>,
)

data class GatewayPreparedExport(
    val exportId: String,
    val localPath: String,
    val suggestedFileName: String,
    val fileCount: ULong,
    val sizeBytes: ULong,
)

data class GatewayPendingUpload(
    val payloadId: String,
    val localPath: String,
    val objectPath: String,
    val rowCount: ULong,
    val sizeBytes: ULong,
)

sealed interface GatewayCoreEffect {
    data object ScheduleUpload : GatewayCoreEffect
    data object CancelUpload : GatewayCoreEffect
    data class StorageWarning(val message: String) : GatewayCoreEffect
    data class PersistenceDegraded(val message: String) : GatewayCoreEffect
}

data class GatewayCoreUpdate(
    val state: GatewayCoreState,
    val effects: List<GatewayCoreEffect>,
)

sealed interface GatewayRecordResult {
    data class Committed(val update: GatewayCoreUpdate) : GatewayRecordResult
    data class RetryablePreCommitFailure(val message: String) : GatewayRecordResult
}

class NativeCoreGateway private constructor(
    private val core: CluetoothCore,
) : CoreGateway {
    override fun apiVersion(): UInt = core.apiVersion()

    override fun state(): GatewayCoreState = core.state().toGatewayState()

    override fun refresh(): GatewayCoreUpdate = core.refresh().toGatewayUpdate()

    override fun startScanSession(): String = core.startScanSession()

    override fun finishScanSession(): GatewayCoreUpdate = core.finishScanSession().toGatewayUpdate()

    override fun scanSessions(): List<GatewayScanSession> = core.scanSessions().map { session ->
        GatewayScanSession(
            sessionId = session.sessionId,
            status = when (session.status) {
                edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanSessionStatus.ACTIVE -> GatewaySessionStatus.ACTIVE
                edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanSessionStatus.COMPLETED -> GatewaySessionStatus.COMPLETED
                edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanSessionStatus.INTERRUPTED -> GatewaySessionStatus.INTERRUPTED
                edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanSessionStatus.LEGACY -> GatewaySessionStatus.LEGACY
            },
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            lastEventAtMs = session.lastEventAtMs,
            observationCount = session.observationCount,
            uniqueMacCount = session.uniqueMacCount,
            exactPayloadCount = session.exactPayloadCount,
            retainedLocalBytes = session.retainedLocalBytes,
            routePoints = session.routePoints.map { GatewayRoutePoint(it.observedAtMs, it.lat, it.lon, it.accuracyMeters) },
            distanceMeters = session.distanceMeters,
            averageAccuracyMeters = session.averageAccuracyMeters,
            uploadState = when (session.uploadState) {
                edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionUploadState.PENDING -> GatewaySessionUploadState.PENDING
                edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionUploadState.UPLOADED -> GatewaySessionUploadState.UPLOADED
                edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionUploadState.FAILED -> GatewaySessionUploadState.FAILED
                edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionUploadState.EMPTY -> GatewaySessionUploadState.EMPTY
            },
            diagnostic = session.diagnostic,
            clusters = session.clusters.map { cluster -> GatewayAdvertisementCluster(
                cluster.clusterId, cluster.advTypes, cluster.fieldLengths,
                cluster.observationCount, cluster.uniqueMacCount, cluster.exactPayloadCount,
                cluster.observationPoints.map { GatewayRoutePoint(it.observedAtMs, it.lat, it.lon, it.accuracyMeters) },
            ) },
        )
    }

    override fun prepareSessionExport(sessionId: String, format: GatewayExportFormat): GatewayPreparedExport =
        core.prepareSessionExport(sessionId, format.toNative()).toGatewayExport()

    override fun prepareLegacySessionExport(session: GatewayLegacySessionRows, format: GatewayExportFormat): GatewayPreparedExport =
        core.prepareLegacySessionExport(session.toNative(), format.toNative()).toGatewayExport()

    override fun prepareFullExport(format: GatewayExportFormat, legacySessions: List<GatewayLegacySessionRows>): GatewayPreparedExport =
        core.prepareFullExport(format.toNative(), legacySessions.map { it.toNative() }).toGatewayExport()

    override fun acknowledgeExport(exportId: String) = core.acknowledgeExport(exportId)

    override fun deleteScanSession(sessionId: String, allowUnuploadedDataLoss: Boolean): GatewayDeleteResult =
        when (core.deleteScanSession(sessionId, allowUnuploadedDataLoss)) {
            edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteSessionResult.DELETED_UPLOADED_LOCAL_COPY -> GatewayDeleteResult.DELETED_UPLOADED_LOCAL_COPY
            edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteSessionResult.DELETED_UNUPLOADED_DATA -> GatewayDeleteResult.DELETED_UNUPLOADED_DATA
            edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteSessionResult.NOTHING_LOCAL_TO_DELETE -> GatewayDeleteResult.NOTHING_LOCAL_TO_DELETE
        }

    override fun updateLocation(fix: CoreLocationInput): GatewayCoreUpdate =
        core.updateLocation(
            LocationFix(
                lat = fix.lat,
                lon = fix.lon,
                accuracyMeters = fix.accuracyMeters,
                observedAtMs = fix.observedAtMs,
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos.toULong(),
            ),
        ).toGatewayUpdate()

    override fun clearLocation(): GatewayCoreUpdate = core.clearLocation().toGatewayUpdate()

    override fun recordObservations(observations: List<CoreScanInput>): GatewayRecordResult {
        // Complete Kotlin-side input mapping before entering Rust. Only typed Rust
        // failures which record_observations guarantees occur before its append are replayable.
        val nativeObservations = observations.map { observation ->
            ScanObservationInput(
                addr = observation.addr,
                rssi = observation.rssi?.toShort(),
                scannedAtMs = observation.scannedAtMs,
                elapsedRealtimeNanos = observation.elapsedRealtimeNanos.toULong(),
                raw = observation.raw,
                localName = observation.localName,
                txPower = observation.txPower?.toShort(),
                isConnectable = observation.isConnectable,
            )
        }
        val update = try {
            core.recordObservations(nativeObservations)
        } catch (error: CoreException) {
            return when (error) {
                is CoreException.Io,
                is CoreException.Parquet,
                is CoreException.Clock,
                -> GatewayRecordResult.RetryablePreCommitFailure(
                    error.message ?: "Native storage unavailable before commit",
                )
                else -> throw error
            }
        }
        // UniFFI lifting and gateway mapping happen after the native commit. Any
        // failure here remains terminal to the caller and is never classified replayable.
        return GatewayRecordResult.Committed(update.toGatewayUpdate())
    }

    override fun checkpointActivePayload(): Boolean =
        core.checkpointActivePayload().sealed != null

    override fun flushPayload(): Boolean = core.flushPayload().sealed != null

    override fun pendingUploads(): List<GatewayPendingUpload> =
        core.pendingUploads().map { pending ->
            GatewayPendingUpload(
                payloadId = pending.payloadId,
                localPath = pending.localPath,
                objectPath = pending.objectPath,
                rowCount = pending.rowCount,
                sizeBytes = pending.sizeBytes,
            )
        }

    override fun prepareUpload(payloadId: String): GatewayPreparedUpload =
        core.prepareUpload(payloadId).let { prepared ->
            GatewayPreparedUpload(
                payloadId = prepared.payloadId,
                ciphertextPath = prepared.ciphertextPath,
                objectPath = prepared.objectPath,
                plaintextSizeBytes = prepared.plaintextSizeBytes,
                ciphertextSizeBytes = prepared.ciphertextSizeBytes,
            )
        }

    override fun markUploadSucceeded(payloadId: String): GatewayCoreUpdate =
        core.markUploadSucceeded(payloadId).toGatewayUpdate()

    override fun markUploadFailed(payloadId: String, message: String): GatewayCoreUpdate =
        core.markUploadFailed(payloadId, message).toGatewayUpdate()

    override fun close() {
        core.flushPayload()
        core.close()
    }

    companion object {
        fun open(dataDirectory: String, config: CoreConfig = defaultCoreConfig()): NativeCoreGateway {
            val gateway = NativeCoreGateway(CluetoothCore.open(dataDirectory, config))
            val version = try {
                gateway.apiVersion()
            } catch (error: Throwable) {
                runCatching { gateway.core.close() }
                throw error
            }
            if (version != EXPECTED_CORE_API_VERSION) {
                runCatching { gateway.core.close() }
                error("Unsupported native core API $version; expected $EXPECTED_CORE_API_VERSION")
            }
            return gateway
        }
    }
}

private fun GatewayLegacySessionRows.toNative() = LegacySessionRows(
    sessionId = sessionId,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    rows = rows.map { row -> SchemaV2Row(
        addr = row.addr, rssi = row.rssi, scannedAtMs = row.scannedAtMs, raw = row.raw,
        localName = null, txPower = null, isConnectable = null,
        lat = row.lat, lon = row.lon, accuracy = row.accuracy,
    ) },
)

private fun GatewayExportFormat.toNative() = when (this) {
    GatewayExportFormat.JSONL -> ExportFormat.JSONL
    GatewayExportFormat.PARQUET -> ExportFormat.PARQUET
}

private fun edu.ucsd.sysnet.cluetoothscanner.core.generated.PreparedExport.toGatewayExport() =
    GatewayPreparedExport(exportId, localPath, suggestedFileName, fileCount, sizeBytes)

private fun edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreState.toGatewayState() =
    GatewayCoreState(
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
    )

private fun edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreUpdate.toGatewayUpdate() =
    GatewayCoreUpdate(
        state = state.toGatewayState(),
        effects = effects.map { effect ->
            when (effect) {
                CoreEffect.ScheduleUpload -> GatewayCoreEffect.ScheduleUpload
                CoreEffect.CancelUpload -> GatewayCoreEffect.CancelUpload
                is CoreEffect.StorageWarning -> GatewayCoreEffect.StorageWarning(effect.message)
                is CoreEffect.PersistenceDegraded ->
                    GatewayCoreEffect.PersistenceDegraded(effect.message)
            }
        },
    )
