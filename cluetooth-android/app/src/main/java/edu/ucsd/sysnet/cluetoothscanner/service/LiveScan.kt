package edu.ucsd.sysnet.cluetoothscanner.service

import edu.ucsd.sysnet.cluetoothscanner.ble.ScanV3Fields
import edu.ucsd.sysnet.cluetoothscanner.data.BleRecord
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One advertisement as the scanner saw it. Every scan result is emitted as a [LiveScan]
 * whenever the scanner runs (the app is in the foreground, or a recording is active); whether it
 * is also written to Parquet is a separate matter (only while a recording is active, see
 * docs/recording-model.md).
 */
class LiveScan(
    val addr: String,
    /** `null` when the platform reports its "absent" value (127). */
    val rssi: Int?,
    /** Wall-clock milliseconds of the advertisement. */
    val scannedAtMs: Long,
    /** `ScanResult.getTimestampNanos`, the monotonic time of the advertisement. */
    val elapsedRealtimeNanos: Long,
    val raw: ByteArray,
    val localName: String?,
    val txPower: Int?,
    val isConnectable: Boolean?,
    /** The scan payload v3 fields (null where the platform reports nothing). */
    val v3: ScanV3Fields,
    /** The latest location fix at the time of the advertisement, if any. */
    val lat: Double?,
    val lon: Double?,
    val accuracyMeters: Float?,
)

internal fun LiveScan.toBleRecord() = BleRecord(
    mac = addr,
    rssi = rssi,
    timestamp = Instant.ofEpochMilli(scannedAtMs).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
    lat = lat,
    lon = lon,
    accuracy = accuracyMeters,
    raw = raw,
)
