package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import edu.ucsd.sysnet.cluetoothscanner.ble.ScanV3Fields
import edu.ucsd.sysnet.cluetoothscanner.service.BleScanService
import edu.ucsd.sysnet.cluetoothscanner.service.LiveScan
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only (this is the debug source set; a release build has no such class): about 300
 * synthetic advertisers pushed into the live scan stream, for reproducing a crowded place on an
 * emulator or a phone. Start it with `am start ... --ez fake_scan true`.
 *
 * Names are missing, ordinary or very long; RSSI runs from -30 to -105 and drifts; some devices
 * leave for good (they go stale), some leave and come back, and new ones keep arriving.
 */
object DebugFakeScan {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Fake(val index: Int, val arriveAtMs: Long, val leaveAtMs: Long?, val returnAtMs: Long?, var rssi: Int, val raw: ByteArray, val name: String?)

    @JvmStatic
    fun start(service: BleScanService) {
        if (!started.compareAndSet(false, true)) return
        val rnd = Random(20261028)
        val devices = List(300) { i ->
            val name = when (i % 5) {
                0, 1 -> null
                2 -> "Govee_H%04d".format(i)
                3 -> "LE-Long-Device-Name-%03d-wrapping".format(i).take(29)
                else -> "S%08x".format(rnd.nextInt())
            }
            val arrive = if (i < 140) rnd.nextLong(0, 1_500) else rnd.nextLong(2_000, 50_000)
            val leaves = rnd.nextInt(100) < 18
            val leave = if (leaves) arrive + rnd.nextLong(8_000, 40_000) else null
            val returns = if (leaves && rnd.nextInt(100) < 30) leave!! + 40_000 else null
            Fake(i, arrive, leave, returns, rnd.nextInt(-105, -29), raw(i, name), name)
        }
        scope.launch {
            val t0 = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - t0
                // The phone's fix, spread a little per device (up to about 150 m) so a map has something to show.
                val fix = service.locationService.getLastKnownLocation()
                for (d in devices) {
                    val here = elapsed >= d.arriveAtMs && (d.leaveAtMs == null || elapsed < d.leaveAtMs || (d.returnAtMs != null && elapsed >= d.returnAtMs))
                    if (!here || rnd.nextInt(100) >= 60) continue
                    d.rssi = (d.rssi + rnd.nextInt(-3, 4)).coerceIn(-105, -29)
                    service.liveSink.tryEmit(
                        LiveScan(
                            addr = "%02X:%02X:%02X:%02X:%02X:%02X".format(0xD0 + d.index % 16, d.index / 256, d.index % 256, 0x5A, 0x11, d.index % 199),
                            rssi = d.rssi,
                            scannedAtMs = now,
                            elapsedRealtimeNanos = now * 1_000_000,
                            raw = d.raw,
                            localName = d.name,
                            txPower = null,
                            isConnectable = d.index % 3 != 0,
                            v3 = ScanV3Fields.from(true, 1, 0, 255, 127, 0, 0, 1),
                            lat = fix?.let { it.latitude + ((d.index * 37) % 27 - 13) * 0.0001 },
                            lon = fix?.let { it.longitude + ((d.index * 53) % 31 - 15) * 0.0001 },
                            accuracyMeters = fix?.accuracy,
                        ),
                    )
                }
                delay(500)
            }
        }
    }

    private fun raw(i: Int, name: String?): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(2, 1, 6).map { it.toByte() }
        if (name != null) {
            val bytes = name.toByteArray()
            out += (bytes.size + 1).toByte(); out += 9.toByte(); out += bytes.toList()
        }
        out += listOf(3, 0xFF, i % 250, 0).map { it.toByte() }
        return out.toByteArray()
    }
}
