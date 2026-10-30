// 1:1 mapping between the UniFFI records (edu.ucsd.sysnet.cluetoothscanner.core.generated) and :core-api.
package edu.ucsd.sysnet.cluetoothscanner.corebridge

import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreAssets
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DecodedField
import edu.ucsd.sysnet.cluetoothscanner.api.core.DecoderOutput
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceLive
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.FieldValue
import edu.ucsd.sysnet.cluetoothscanner.api.core.FixSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.IconCredit
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.PageKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.PageRef
import edu.ucsd.sysnet.cluetoothscanner.api.core.PendingUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.Reclaimable
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductCard
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.api.core.StorageStats
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadState
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

// ---- :core-api → bindings ----

internal fun Scope.g() = GScope(
    when (val s = sessions) {
        SessionScope.All -> edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionScope.All
        is SessionScope.Sessions -> edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionScope.Sessions(s.ids)
    },
    filter?.let { GSessionFilter(it.startedFromMs, it.startedToMs) },
)

internal fun Bbox.g() = GBbox(west, south, east, north)

internal fun DeviceFilter.g() = GDeviceFilter(
    area?.g(),
    when (identification) {
        Identification.Any -> GIdentification.ANY
        Identification.Identified -> GIdentification.IDENTIFIED
        Identification.Unidentified -> GIdentification.UNIDENTIFIED
    },
    categories.toList(), readable, trackers, vulnerable, inRangeOnly, page, query,
)

internal fun DisplayFilter.g() = GDisplayFilter(hideUnnamedApple)

internal fun ScanEnvironment.g() = GScanEnvironment(
    deviceModel, androidRelease, androidSdk.toUInt(), appVersion, capExtAdv, capCodedPhy, capMaxAdvLen.toUInt(),
    scanPhy, scanLegacyOnly,
)

internal fun ScanObservationInput.g() = GScanObservationInput(
    addr, rssi?.toShort(), scannedAtMs, elapsedRealtimeNanos.toULong(), raw, localName, txPower?.toShort(),
    isConnectable, isLegacy, primaryPhy, secondaryPhy, advSid, advTxPower, periodicInterval, dataStatus, addrType,
)

internal fun LocationFix.g() = GLocationFix(lat, lon, accuracyMeters, observedAtMs, elapsedRealtimeNanos.toULong())

internal fun CoreConfig.g() = GCoreConfig(
    dataDirectory, recipientPublicKey, locationMaxAgeMs.toULong(), payloadMaxRows.toUInt(),
    payloadMaxEstimatedBytes.toULong(), payloadMaxAgeMs.toULong(), durabilityIntervalMs.toULong(),
    staleAfterMs.toULong(), liveIntervalMs.toULong(),
)

internal fun CoreAssets.g() = GCoreAssets(wikiBundleJson, sigNumbersTsv)

internal fun ExportFormat.g() = when (this) {
    ExportFormat.ParquetRaw -> GExportFormat.PARQUET_RAW
    ExportFormat.Csv -> GExportFormat.CSV
    ExportFormat.SqliteSnapshot -> GExportFormat.SQLITE_SNAPSHOT
}

// ---- bindings → :core-api ----

internal fun GDeviceKeys.api(): DeviceKeys {
    val buffer = ByteBuffer.wrap(leI64).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
    val keys = LongArray(buffer.remaining())
    buffer.get(keys)
    return DeviceKeys(keys, seq.toLong())
}

internal fun GGeoPoint.api() = GeoPoint(lon, lat)
internal fun GBbox.api() = Bbox(west, south, east, north)

internal fun GPageKind.api() = when (this) {
    GPageKind.DEVICE -> PageKind.Device
    GPageKind.PLATFORM -> PageKind.Platform
    GPageKind.IDENTIFIER -> PageKind.Identifier
    GPageKind.VULNERABILITY -> PageKind.Vulnerability
}

internal fun GPageRef.api() = PageRef(id, kind.api(), title)

internal fun GSessionSummary.api() = SessionSummary(
    id,
    when (status) {
        GSessionStatus.RECORDING -> SessionStatus.Recording
        GSessionStatus.PAUSED -> SessionStatus.Paused
        GSessionStatus.COMPLETED -> SessionStatus.Completed
        GSessionStatus.INTERRUPTED -> SessionStatus.Interrupted
    },
    startedMs, endedMs, activeMs, distanceM, deviceCount.toInt(),
    when (upload) {
        GUploadState.PENDING -> UploadState.Pending
        GUploadState.UPLOADED -> UploadState.Uploaded
        GUploadState.FAILED -> UploadState.Failed
        GUploadState.EMPTY -> UploadState.Empty
    },
)

internal fun GAllSessionsSummary.api() = AllSessionsSummary(sessionCount.toInt(), deviceCount.toInt(), bbox?.api())

internal fun GSessionView.api(scope: Scope) = SessionView(
    scope, live, sessionIds, deviceCount.toInt(), frame?.api(), route.map { it.api() }, routesFaint, seq.toLong(),
)

internal fun GDeviceRow.api() = DeviceRow(
    key, addr, name, page?.api(), categories, traits, readable, vulnerable, icon, rssi?.toInt(), inRange, spark,
    best?.api(), lastSeenMs,
)

internal fun GCategoryCount.api() = CategoryCount(category, label, deviceCount.toInt())

internal fun GProductCard.api() = ProductCard(
    id, title, kind?.api(), icon, live, count.toInt(), inRange.toInt(), lastSeenMs,
)

internal fun GCategoryGroup.api() = CategoryGroup(category, label, products.map { it.api() })

internal fun GProductDetail.api() = ProductDetail(
    page?.api(), title, oneLine, categories, traits, readable, vulnerabilities.map { it.api() }, wikiUrl, icon,
    iconCredit?.let { IconCredit(it.source, it.license, it.author) }, places.toInt(), lastSeenMs, deviceCount.toInt(),
)

internal fun GDecoderOutput.api() = DecoderOutput(fields.map { f ->
    DecodedField(
        f.name,
        when (val v = f.value) {
            is edu.ucsd.sysnet.cluetoothscanner.core.generated.FieldValue.Bool -> FieldValue.Bool(v.v)
            is edu.ucsd.sysnet.cluetoothscanner.core.generated.FieldValue.Number -> FieldValue.Number(v.v)
            is edu.ucsd.sysnet.cluetoothscanner.core.generated.FieldValue.Text -> FieldValue.Text(v.v)
        },
        f.unit, f.lookup,
    )
})

internal fun GAdStructure.api() = AdStructure(len.toInt(), adType.toInt(), data)

internal fun GDeviceDetail.api() = DeviceDetail(
    key, addr, name, page?.api(), categories, traits, readable, vulnerable, vulnerabilities.map { it.api() }, wikiUrl,
    icon, rssi?.toInt(), inRange, live, connectable,
    when (format) {
        GAdvFormat.LEGACY -> AdvFormat.Legacy
        GAdvFormat.EXTENDED -> AdvFormat.Extended
        GAdvFormat.UNKNOWN -> AdvFormat.Unknown
    },
    companyId?.toInt(), companyName, serviceUuid, txPower?.toInt(), structures.map { it.api() }, tail,
    decoded?.api(), lastSeenMs,
)

internal fun GSighting.api() = Sighting(at.api(), rssi.toInt(), seenMs)

internal fun GLiveChart.api() = LiveChart(key, endMs, samples, rssi?.toInt())

internal fun GMapData.api() = MapData(
    when (mode) {
        GMapMode.POINTS -> MapMode.Points
        GMapMode.CELLS -> MapMode.Cells
    },
    count.toInt(), geojson, seq.toLong(),
)

internal fun GGattFailure.api(): GattFailure = when (this) {
    edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure.TimedOut -> GattFailure.TimedOut
    edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure.DisconnectedUnexpectedly -> GattFailure.DisconnectedUnexpectedly
    edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure.ConnectionFailed -> GattFailure.ConnectionFailed
    edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure.Cancelled -> GattFailure.Cancelled
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure.Other -> GattFailure.Other(status)
}

internal fun GGattStep.api() = GattStep(
    when (kind) {
        GGattStepKind.CONNECTING -> GattStepKind.Connecting
        GGattStepKind.DISCOVERING_SERVICES -> GattStepKind.DiscoveringServices
        GGattStepKind.READING_CHARACTERISTICS -> GattStepKind.ReadingCharacteristics
        GGattStepKind.DISCONNECTED -> GattStepKind.Disconnected
    },
    when (state) {
        GGattStepState.DONE -> GattStepState.Done
        GGattStepState.RUNNING -> GattStepState.Running
        GGattStepState.FAILED -> GattStepState.Failed
    },
    durationMs?.toInt(), services?.toInt(), read?.toInt(), readable?.toInt(), failure?.api(),
)

internal fun GGattView.api() = GattView(
    discoveryId, key, startedMs, ok, steps.map { it.api() },
    services.map { s ->
        GattServiceView(s.uuid, s.name, s.characteristics.map { GattCharView(it.uuid, it.name, it.properties, it.valueText) })
    },
)

internal fun GGattTarget.api() = GattTarget(
    addr, addrType, name, triggerRaw, triggerRssi?.toInt(), triggerScannedAtMs, triggerIsLegacy, triggerPrimaryPhy,
    triggerSecondaryPhy, triggerAdvSid, sessionId,
)

internal fun GIngestAck.api() = IngestAck(accepted.toInt(), rejected.toInt(), ignoredNotRecording.toInt())

internal fun GRecordingState.api() = CoreRecordingState(
    when (phase) {
        GRecordingPhase.IDLE -> RecordingPhase.Idle
        GRecordingPhase.RECORDING -> RecordingPhase.Recording
        GRecordingPhase.PAUSED -> RecordingPhase.Paused
    },
    sessionId, startedMs, activeBeforeMs, runningSinceMs, deviceCount.toInt(), distanceM, pausedForStorage,
    storageFailure,
)

internal fun GFixSummary.api() = FixSummary(lat, lon, accuracyM, ageMs)

internal fun GDeviceLive.api() = DeviceLive(key, rssi?.toInt(), inRange, spark, name, page?.api(), best?.api())

internal fun GLiveDelta.api() = LiveDelta(
    sessionId, seq.toLong(), atMs, state.api(), changed.map { it.api() }, appended.map { it.api() },
    products.map { it.api() }, routeAppended.map { it.api() }, fix?.api(), chart?.api(),
)

internal fun GCoreEvent.api(): CoreEvent = when (this) {
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.RecordingChanged -> CoreEvent.RecordingChanged(state.api())
    edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.SessionsChanged -> CoreEvent.SessionsChanged
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.DataInvalidated -> CoreEvent.DataInvalidated(reason)
    edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.UploadNeeded -> CoreEvent.UploadNeeded
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.StorageWarning -> CoreEvent.StorageWarning(message)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.StorageFull ->
        CoreEvent.StorageFull(freeBytes.toLong(), droppedScans.toLong())
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent.StorageFailed ->
        CoreEvent.StorageFailed(message, droppedScans.toLong())
}

internal fun GReclaimable.api() = Reclaimable(freeBytes.toLong(), uploadedRawBytes.toLong(), localDatabaseBytes.toLong())

internal fun GStorageStats.api() = StorageStats(
    databaseBytes.toLong(), rawFilesBytes.toLong(), pendingCount.toInt(), pendingBytes.toLong(), sessionCount.toInt(),
)

internal fun GPreparedExport.api() = PreparedExport(exportId, localPath, suggestedFileName, fileCount.toLong(), sizeBytes.toLong())

internal fun GDeleteOutcome.api(): DeleteOutcome = when (this) {
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteOutcome.Deleted -> DeleteOutcome.Deleted(sessions.toInt())
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteOutcome.DeletedPendingKept -> DeleteOutcome.DeletedPendingKept(pending.toInt())
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteOutcome.Refused -> DeleteOutcome.Refused(reason)
}

internal fun GUploadStatus.api() = UploadStatus(pendingCount.toInt(), pendingBytes.toLong(), failedCount.toInt(), lastError)

internal fun GPendingUpload.api() = PendingUpload(payloadId, localPath, objectPath, rowCount.toLong(), sizeBytes.toLong())

internal fun GPreparedUpload.api() = PreparedUpload(
    payloadId, ciphertextPath, objectPath, plaintextSizeBytes.toLong(), ciphertextSizeBytes.toLong(),
)

/** §2.9 → [CoreException]; `Cancelled` becomes [CancellationException] (§2). */
internal fun GCoreError.api(): Exception = when (this) {
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.InvalidPayload -> CoreException.InvalidPayload(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.InvalidConfig -> CoreException.InvalidConfig(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.InputBatchTooLarge -> CoreException.InputBatchTooLarge(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.UploadState -> CoreException.UploadState(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Encryption -> CoreException.Encryption(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Io -> CoreException.Io(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Parquet -> CoreException.Parquet(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Clock -> CoreException.Clock(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Db -> CoreException.Db(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.SchemaTooNew -> CoreException.SchemaTooNew(found.toInt(), supported.toInt())
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.InvalidBundle -> CoreException.InvalidBundle(detail)
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Backpressure -> CoreException.Backpressure()
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.StorageFull -> CoreException.StorageFull(freeBytes.toLong())
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.NotRecording -> CoreException.NotRecording()
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.AlreadyRecording -> CoreException.AlreadyRecording()
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Closed -> CoreException.Closed()
    is edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2.Cancelled -> CancellationException("query cancelled")
}

/**
 * Every binding failure in :core-api's types: a core error maps to its [CoreException] (`Cancelled` to
 * [CancellationException]); a Rust panic UniFFI caught (`InternalException`) or any other binding fault becomes
 * [CoreException.Internal]. A coroutine cancellation passes through.
 */
internal fun bindingFailure(e: Exception): Exception = when (e) {
    is GCoreError -> e.api()
    is CancellationException -> e
    is CoreException -> e
    is GInternalException -> CoreException.Internal(e.message ?: "core panic")
    else -> CoreException.Internal(e.toString())
}

/** Runs a binding call as a [CoreResult]; a coroutine cancellation still propagates. */
internal inline fun <T> result(block: () -> T): CoreResult<T> = try {
    CoreResult.Ok(block())
} catch (e: Exception) {
    when (val failure = bindingFailure(e)) {
        is CoreException -> CoreResult.Err(failure)
        else -> throw failure
    }
}

/** Runs a binding call; any failure is rethrown in :core-api's types ([bindingFailure]). */
internal inline fun <T> call(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    throw bindingFailure(e)
}
