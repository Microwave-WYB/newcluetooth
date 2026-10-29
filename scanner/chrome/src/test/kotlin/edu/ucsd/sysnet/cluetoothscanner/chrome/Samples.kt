package edu.ucsd.sysnet.cluetoothscanner.chrome

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadState
import java.util.Calendar
import java.util.TimeZone

/** data.js's sessions (2026-10-28, UTC) and a few devices. */
object Samples {
    val utc: TimeZone = TimeZone.getTimeZone("UTC")
    fun at(d: Int, h: Int, m: Int) = Calendar.getInstance(utc).apply { clear(); set(2026, 9, d, h, m) }.timeInMillis
    val now = at(28, 19, 24)
    private fun s(id: String, d: Int, h: Int, m: Int, min: Long, meters: Double, devices: Int, status: SessionStatus = SessionStatus.Completed) =
        SessionSummary(id, status, at(d, h, m), at(d, h, m) + min * 60_000, min * 60_000, meters, devices, UploadState.Uploaded)

    val current = SessionSummary("now", SessionStatus.Recording, at(28, 19, 12), null, 12 * 60_000, 640.0, 26, UploadState.Pending)
    val finished = listOf(
        s("r1", 28, 18, 5, 23, 1800.0, 214), s("r2", 28, 12, 10, 8, 0.0, 41), s("r3", 27, 17, 32, 41, 3200.0, 388),
        s("r4", 27, 15, 10, 2, 0.0, 0), s("r5", 27, 9, 2, 15, 950.0, 122), s("r6", 25, 14, 14, 65, 4600.0, 512),
    )
    val all = AllSessionsSummary(5, 1277, null)

    private fun row(key: Long, name: String?, rssi: Int, inRange: Boolean = true) = DeviceRow(
        key, edu.ucsd.sysnet.cluetoothscanner.designsystem.Format.mac(key), name, null, emptyList(), emptyList(), false, false, null,
        rssi, inRange, ByteArray(30) { (-rssi + (it % 5) - 2).toByte() }, null, now,
    )
    val rows = listOf(row(0x40E77D3F7ACDL, "AirPods Pro", -52), row(0x72CD4C4B4B6FL, null, -63), row(0xD8A6D23130FEL, "Hue color lamp", -67, false))
}
