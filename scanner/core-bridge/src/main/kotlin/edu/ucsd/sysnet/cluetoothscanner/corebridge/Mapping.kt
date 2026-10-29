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
import edu.ucsd.sysnet.cluetoothscanner.core.generated.AdStructure as GAdStructure
import edu.ucsd.sysnet.cluetoothscanner.core.generated.AdvFormat as GAdvFormat
import edu.ucsd.sysnet.cluetoothscanner.core.generated.AllSessionsSummary as GAllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.core.generated.Bbox as GBbox
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CategoryCount as GCategoryCount
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CategoryGroup as GCategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreAssets as GCoreAssets
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreConfigV2 as GCoreConfig
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreErrorV2 as GCoreError
import edu.ucsd.sysnet.cluetoothscanner.core.generated.CoreEvent as GCoreEvent
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DecoderOutput as GDecoderOutput
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeleteOutcome as GDeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeviceDetail as GDeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeviceFilter as GDeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeviceKeys as GDeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeviceLive as GDeviceLive
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DeviceRow as GDeviceRow
import edu.ucsd.sysnet.cluetoothscanner.core.generated.DisplayFilter as GDisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ExportFormatV2 as GExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.generated.FieldValue as GFieldValue
import edu.ucsd.sysnet.cluetoothscanner.core.generated.FixSummary as GFixSummary
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattFailure as GGattFailure
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattStep as GGattStep
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattStepKind as GGattStepKind
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattStepState as GGattStepState
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattTarget as GGattTarget
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GattView as GGattView
import edu.ucsd.sysnet.cluetoothscanner.core.generated.GeoPoint as GGeoPoint
import edu.ucsd.sysnet.cluetoothscanner.core.generated.Identification as GIdentification
import edu.ucsd.sysnet.cluetoothscanner.core.generated.IngestAck as GIngestAck
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LiveChart as GLiveChart
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LiveDelta as GLiveDelta
import edu.ucsd.sysnet.cluetoothscanner.core.generated.LocationFix as GLocationFix
import edu.ucsd.sysnet.cluetoothscanner.core.generated.MapData as GMapData
import edu.ucsd.sysnet.cluetoothscanner.core.generated.MapMode as GMapMode
import edu.ucsd.sysnet.cluetoothscanner.core.generated.PageKind as GPageKind
import edu.ucsd.sysnet.cluetoothscanner.core.generated.PageRef as GPageRef
import edu.ucsd.sysnet.cluetoothscanner.core.generated.PendingUpload as GPendingUpload
import edu.ucsd.sysnet.cluetoothscanner.core.generated.PreparedExportV2 as GPreparedExport
import edu.ucsd.sysnet.cluetoothscanner.core.generated.PreparedUpload as GPreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ProductCard as GProductCard
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ProductDetail as GProductDetail
import edu.ucsd.sysnet.cluetoothscanner.core.generated.RecordingPhase as GRecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.core.generated.RecordingState as GRecordingState
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanEnvironment as GScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.core.generated.ScanObservationInput as GScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.core.generated.Scope as GScope
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionFilter as GSessionFilter
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionScope as GSessionScope
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionStatus as GSessionStatus
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionSummary as GSessionSummary
import edu.ucsd.sysnet.cluetoothscanner.core.generated.SessionView as GSessionView
import edu.ucsd.sysnet.cluetoothscanner.core.generated.Sighting as GSighting
import edu.ucsd.sysnet.cluetoothscanner.core.generated.StorageStats as GStorageStats
import edu.ucsd.sysnet.cluetoothscanner.core.generated.UploadState as GUploadState
import edu.ucsd.sysnet.cluetoothscanner.core.generated.UploadStatus as GUploadStatus

// ---- :core-api → bindings ----

internal fun Scope.g() = GScope(
    when (val s = sessions) {
        SessionScope.All -> GSessionScope.All
        is SessionScope.Sessions -> GSessionScope.Sessions(s.ids)
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
            is GFieldValue.Bool -> FieldValue.Bool(v.v)
            is GFieldValue.Number -> FieldValue.Number(v.v)
            is GFieldValue.Text -> FieldValue.Text(v.v)
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
    GGattFailure.TimedOut -> GattFailure.TimedOut
    GGattFailure.DisconnectedUnexpectedly -> GattFailure.DisconnectedUnexpectedly
    GGattFailure.ConnectionFailed -> GattFailure.ConnectionFailed
    GGattFailure.Cancelled -> GattFailure.Cancelled
    is GGattFailure.Other -> GattFailure.Other(status)
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
    sessionId, startedMs, activeBeforeMs, runningSinceMs, deviceCount.toInt(), distanceM,
)

internal fun GFixSummary.api() = FixSummary(lat, lon, accuracyM, ageMs)

internal fun GDeviceLive.api() = DeviceLive(key, rssi?.toInt(), inRange, spark, name, page?.api(), best?.api())

internal fun GLiveDelta.api() = LiveDelta(
    sessionId, seq.toLong(), atMs, state.api(), changed.map { it.api() }, appended.map { it.api() },
    products.map { it.api() }, routeAppended.map { it.api() }, fix?.api(), chart?.api(),
)

internal fun GCoreEvent.api(): CoreEvent = when (this) {
    is GCoreEvent.RecordingChanged -> CoreEvent.RecordingChanged(state.api())
    GCoreEvent.SessionsChanged -> CoreEvent.SessionsChanged
    is GCoreEvent.DataInvalidated -> CoreEvent.DataInvalidated(reason)
    GCoreEvent.UploadNeeded -> CoreEvent.UploadNeeded
    is GCoreEvent.StorageWarning -> CoreEvent.StorageWarning(message)
}

internal fun GStorageStats.api() = StorageStats(
    databaseBytes.toLong(), rawFilesBytes.toLong(), pendingCount.toInt(), pendingBytes.toLong(), sessionCount.toInt(),
)

internal fun GPreparedExport.api() = PreparedExport(exportId, localPath, suggestedFileName, fileCount.toLong(), sizeBytes.toLong())

internal fun GDeleteOutcome.api(): DeleteOutcome = when (this) {
    is GDeleteOutcome.Deleted -> DeleteOutcome.Deleted(sessions.toInt())
    is GDeleteOutcome.DeletedPendingKept -> DeleteOutcome.DeletedPendingKept(pending.toInt())
    is GDeleteOutcome.Refused -> DeleteOutcome.Refused(reason)
}

internal fun GUploadStatus.api() = UploadStatus(pendingCount.toInt(), pendingBytes.toLong(), failedCount.toInt(), lastError)

internal fun GPendingUpload.api() = PendingUpload(payloadId, localPath, objectPath, rowCount.toLong(), sizeBytes.toLong())

internal fun GPreparedUpload.api() = PreparedUpload(
    payloadId, ciphertextPath, objectPath, plaintextSizeBytes.toLong(), ciphertextSizeBytes.toLong(),
)

/** §2.9 → [CoreException]; `Cancelled` and `Superseded` become [CancellationException] (§2). */
internal fun GCoreError.api(): Exception = when (this) {
    is GCoreError.InvalidPayload -> CoreException.InvalidPayload(detail)
    is GCoreError.InvalidConfig -> CoreException.InvalidConfig(detail)
    is GCoreError.InputBatchTooLarge -> CoreException.InputBatchTooLarge(detail)
    is GCoreError.UploadState -> CoreException.UploadState(detail)
    is GCoreError.Encryption -> CoreException.Encryption(detail)
    is GCoreError.Io -> CoreException.Io(detail)
    is GCoreError.Parquet -> CoreException.Parquet(detail)
    is GCoreError.Clock -> CoreException.Clock(detail)
    is GCoreError.Db -> CoreException.Db(detail)
    is GCoreError.SchemaTooNew -> CoreException.SchemaTooNew(found.toInt(), supported.toInt())
    is GCoreError.InvalidBundle -> CoreException.InvalidBundle(detail)
    is GCoreError.Backpressure -> CoreException.Backpressure()
    is GCoreError.NotRecording -> CoreException.NotRecording()
    is GCoreError.AlreadyRecording -> CoreException.AlreadyRecording()
    is GCoreError.Closed -> CoreException.Closed()
    is GCoreError.Cancelled -> CancellationException("query cancelled")
    is GCoreError.Superseded -> CancellationException("query superseded by a newer one of its kind")
}

/** Runs a binding call, rethrowing its error as :core-api's. */
internal inline fun <T> call(block: () -> T): T = try {
    block()
} catch (e: GCoreError) {
    throw e.api()
}
