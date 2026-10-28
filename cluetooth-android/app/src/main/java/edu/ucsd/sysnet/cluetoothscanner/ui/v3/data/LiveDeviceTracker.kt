package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ble.BleParser
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdStructureRow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdVariantState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AddressType
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One advertisement, reduced to what the tracker keeps (see `LiveScan`). */
class LiveSample(
    val addr: String,
    /** Null when the platform reported none. */
    val rssi: Int?,
    val timeMs: Long,
    val raw: ByteArray,
    val localName: String?,
    val connectable: Boolean?,
    val legacy: Boolean?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advSid: Int?,
    /** 0 public, 1 random, null when the platform did not say. */
    val addrType: Int?,
)

/**
 * Aggregates the live scan stream per MAC: the latest values, one RSSI average per second for
 * the sparkline and the detail chart, and one entry per distinct advertisement variant. Not
 * thread safe on its own; the owner serialises access.
 *
 * Memory is bounded: 15 minutes of per-second buckets per device, at most [MAX_VARIANTS]
 * variants per device, and devices unheard for [FORGET_AFTER_MS] are dropped by [prune].
 */
class LiveDeviceTracker {
    private class Bucket(val second: Long, var sum: Int, var count: Int)

    private data class VariantKey(val flags: Int, val hash: Int, val size: Int)

    private class Variant(val raw: ByteArray, val legacy: Boolean, val primaryPhy: Int?, val secondaryPhy: Int?, val sid: Int?, val connectable: Boolean, val firstMs: Long) {
        var count = 0
        var lastMs = firstMs
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
    }

    private class Device(val addr: String, val firstSeenMs: Long) {
        var name: String? = null
        var rssi: Int? = null
        var lastSeenMs = 0L
        var connectable = false
        var legacy = true
        var addrType: Int? = null
        val buckets = ArrayDeque<Bucket>()
        val variants = LinkedHashMap<VariantKey, Variant>()
    }

    private val devices = LinkedHashMap<String, Device>()

    val size: Int get() = devices.size

    fun contains(addr: String) = devices.containsKey(addr)

    /** Returns true when the address was not known before. */
    fun onSample(s: LiveSample): Boolean {
        val isNew = !devices.containsKey(s.addr)
        val d = devices.getOrPut(s.addr) { Device(s.addr, s.timeMs) }
        d.lastSeenMs = s.timeMs
        if (s.rssi != null) {
            d.rssi = s.rssi
            val sec = s.timeMs / 1000
            val last = d.buckets.lastOrNull()
            if (last != null && last.second == sec) {
                last.sum += s.rssi
                last.count++
            } else {
                d.buckets.addLast(Bucket(sec, s.rssi, 1))
                while (d.buckets.size > MAX_BUCKETS) d.buckets.removeFirst()
            }
        }
        s.connectable?.let { d.connectable = it }
        s.legacy?.let { d.legacy = it }
        s.addrType?.let { d.addrType = it }
        val legacy = s.legacy ?: true
        val connectable = s.connectable ?: false
        val flags = (if (legacy) 1 else 0) or (if (connectable) 2 else 0) or ((s.primaryPhy ?: 0) shl 2) or ((s.secondaryPhy ?: 0) shl 5) or (((s.advSid ?: -1) + 1) shl 8)
        val key = VariantKey(flags, s.raw.contentHashCode(), s.raw.size)
        val v = d.variants.getOrPut(key) {
            if (d.variants.size >= MAX_VARIANTS) d.variants.remove(d.variants.keys.first())
            Variant(s.raw, legacy, s.primaryPhy, s.secondaryPhy, s.advSid, connectable, s.timeMs).also {
                (s.localName ?: advertisedName(s.raw))?.let { name -> d.name = name }
            }
        }
        v.count++
        v.lastMs = s.timeMs
        if (s.rssi != null) {
            if (s.rssi < v.min) v.min = s.rssi
            if (s.rssi > v.max) v.max = s.rssi
        }
        return isNew
    }

    fun rssi(addr: String): Int? = devices[addr]?.rssi

    /** The raw bytes of the most recently heard variant, for matching against the wiki. */
    fun latestAdvertisement(addr: String): ByteArray? = devices[addr]?.variants?.values?.maxByOrNull { it.lastMs }?.raw

    /** Drops devices unheard for [FORGET_AFTER_MS]; returns their addresses. */
    fun prune(nowMs: Long): List<String> {
        val gone = devices.values.filter { nowMs - it.lastSeenMs > FORGET_AFTER_MS }.map { it.addr }
        gone.forEach(devices::remove)
        return gone
    }

    fun row(addr: String, nowMs: Long): DeviceRowState? {
        val d = devices[addr] ?: return null
        return DeviceRowState(
            id = d.addr,
            name = d.name,
            mac = d.addr,
            addressType = classifyAddress(d.addr, d.addrType),
            advKind = if (d.legacy) AdvKind.Legacy else AdvKind.Extended,
            connectable = d.connectable,
            rssi = d.rssi ?: -100,
            history = withCurrent(sparkline(d, nowMs), d.rssi),
            stale = nowMs - d.lastSeenMs > STALE_AFTER_MS,
        )
    }

    /**
     * The last point of every series is the value shown as the number, so the number's colour and
     * the last point's colour always agree (bins are averages and may lag by a second or two).
     */
    private fun withCurrent(series: List<Int>, current: Int?): List<Int> =
        if (current == null || series.isEmpty()) series else series.dropLast(1) + current

    /** About 24 points covering the last 60 seconds, averaged per bin; empty bins are skipped. */
    private fun sparkline(d: Device, nowMs: Long): List<Int> = binned(d, nowMs / 1000, 60, 24)

    /** RSSI over the window for the detail chart, at most [CHART_POINTS] points, oldest first. */
    fun chart(addr: String, window: ChartWindow, nowMs: Long): List<Int> {
        val d = devices[addr] ?: return emptyList()
        val seconds = when (window) {
            ChartWindow.OneMinute -> 60
            ChartWindow.FiveMinutes -> 300
            ChartWindow.FifteenMinutes -> 900
        }
        return withCurrent(binned(d, nowMs / 1000, seconds, minOf(seconds, CHART_POINTS)), d.rssi)
    }

    private fun binned(d: Device, nowSec: Long, windowSec: Int, bins: Int): List<Int> {
        val sums = IntArray(bins)
        val counts = IntArray(bins)
        val start = nowSec - windowSec + 1
        for (b in d.buckets) {
            if (b.second < start || b.second > nowSec) continue
            val idx = (((b.second - start) * bins) / windowSec).toInt().coerceIn(0, bins - 1)
            sums[idx] += b.sum
            counts[idx] += b.count
        }
        return (0 until bins).filter { counts[it] > 0 }.map { sums[it] / counts[it] }
    }

    fun variants(addr: String): List<AdVariantState> {
        val d = devices[addr] ?: return emptyList()
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        return d.variants.values.mapIndexed { index, v ->
            AdVariantState(
                id = "v$index-${v.raw.contentHashCode()}",
                pduType = pduType(v.legacy, v.connectable),
                phy = phyLabel(v.legacy, v.primaryPhy, v.secondaryPhy),
                sid = v.sid,
                count = v.count,
                firstSeen = time.format(Instant.ofEpochMilli(v.firstMs)),
                lastSeen = time.format(Instant.ofEpochMilli(v.lastMs)),
                rssiMin = if (v.min == Int.MAX_VALUE) -100 else v.min,
                rssiMax = if (v.max == Int.MIN_VALUE) -100 else v.max,
                structures = adStructures(v.raw),
            )
        }
    }

    companion object {
        const val MAX_BUCKETS = 900
        const val MAX_VARIANTS = 24
        const val CHART_POINTS = 120
        const val STALE_AFTER_MS = 20_000L
        const val FORGET_AFTER_MS = 30 * 60_000L

        fun advertisedName(raw: ByteArray): String? =
            BleParser.extractDeviceName(BleParser.parseAdvertisementData(raw))?.takeIf { it.isNotBlank() }

        fun adStructures(raw: ByteArray): List<AdStructureRow> =
            BleParser.parseAdvertisementData(raw).structures.map { s ->
                AdStructureRow(
                    type = s.type.toString(16).padStart(2, '0').uppercase(),
                    typeName = BleParser.getAdTypeDescription(s.type),
                    length = s.length,
                    data = s.data.joinToString("") { "%02x".format(it) },
                )
            }

        /** Android does not report the PDU type; this is what the flags imply. */
        fun pduType(legacy: Boolean, connectable: Boolean) = when {
            !legacy -> "ADV_EXT_IND"
            connectable -> "ADV_IND"
            else -> "ADV_NONCONN_IND"
        }

        fun phyLabel(legacy: Boolean, primary: Int?, secondary: Int?): String {
            fun name(phy: Int?) = when (phy) {
                1 -> "1M"
                2 -> "2M"
                3 -> "Coded"
                else -> null
            }
            return if (legacy) "1M" else name(secondary) ?: name(primary) ?: "—"
        }

        /** Public/random from the platform when it says; random subtypes from the address's top bits. */
        fun classifyAddress(mac: String, platformType: Int?): AddressType {
            if (platformType == 0) return AddressType.Public
            if (platformType != 1) return AddressType.Unknown
            val top = mac.take(2).toIntOrNull(16)?.let { it shr 6 } ?: return AddressType.Unknown
            return when (top) {
                3 -> AddressType.RandomStatic
                1 -> AddressType.ResolvablePrivate
                0 -> AddressType.NonResolvablePrivate
                else -> AddressType.Unknown
            }
        }
    }
}
