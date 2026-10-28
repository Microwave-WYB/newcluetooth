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
    private data class VariantKey(val flags: Int, val hash: Int, val size: Int)

    private class Variant(
        val key: VariantKey,
        val raw: ByteArray,
        val legacy: Boolean,
        val primaryPhy: Int?,
        val secondaryPhy: Int?,
        val sid: Int?,
        val connectable: Boolean,
        val firstMs: Long,
    ) {
        /** Parsed once per variant: the table is read every second while a detail page is open. */
        val structures: List<AdStructureRow> by lazy { adStructures(raw) }
        var count = 0
        var lastMs = firstMs
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
    }

    /**
     * The strongest RSSI of each second in primitive arrays. It starts small and doubles up to [MAX_BUCKETS];
     * only a full-size ring wraps, overwriting the oldest second, so before that `head` stays 0.
     */
    private class RssiRing {
        private var seconds = LongArray(INITIAL_BUCKETS)
        private var maxes = IntArray(INITIAL_BUCKETS)
        private var capacity = INITIAL_BUCKETS
        private var head = 0
        private var size = 0

        fun add(second: Long, rssi: Int) {
            if (size > 0) {
                val last = (head + size - 1) % capacity
                if (seconds[last] == second) {
                    if (rssi > maxes[last]) maxes[last] = rssi
                    return
                }
            }
            if (size == capacity && capacity < MAX_BUCKETS) grow()
            val index = if (size < capacity) (head + size++) % capacity else head.also { head = (head + 1) % capacity }
            seconds[index] = second
            maxes[index] = rssi
        }

        private fun grow() {
            capacity = minOf(capacity * 2, MAX_BUCKETS)
            seconds = seconds.copyOf(capacity)
            maxes = maxes.copyOf(capacity)
        }

        inline fun forEach(block: (second: Long, max: Int) -> Unit) {
            for (i in 0 until size) {
                val index = (head + i) % capacity
                block(seconds[index], maxes[index])
            }
        }
    }

    private class Device(val addr: String, val firstSeenMs: Long) {
        var name: String? = null
        var rssi: Int? = null
        var lastSeenMs = 0L
        var connectable = false
        var legacy = true
        var addrType: Int? = null
        val ring = RssiRing()

        /** Access order: the variant least recently heard is the one evicted when full. */
        val variants = LinkedHashMap<VariantKey, Variant>(16, 0.75f, true)
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
            d.ring.add(s.timeMs / 1000, s.rssi)
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
            Variant(key, s.raw, legacy, s.primaryPhy, s.secondaryPhy, s.advSid, connectable, s.timeMs).also {
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

    /** The advertised name, without building a row. */
    fun name(addr: String): String? = devices[addr]?.name

    fun isStale(addr: String, nowMs: Long): Boolean = devices[addr]?.let { nowMs - it.lastSeenMs > STALE_AFTER_MS } ?: true

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
            history = sparkline(d, nowMs),
            stale = nowMs - d.lastSeenMs > STALE_AFTER_MS,
        )
    }

    /** The last 60 seconds in 2 s buckets (the strongest RSSI of each; null = nothing heard). */
    private fun sparkline(d: Device, nowMs: Long): List<Int?> = bucketed(d, nowMs, SPARK_WINDOW_SEC, SPARK_BUCKET_SEC)

    /** The detail chart series: 60 buckets of the window size (1 s, 5 s or 15 s each), ending now. */
    fun chart(addr: String, window: ChartWindow, nowMs: Long): RssiSeries {
        val d = devices[addr] ?: return RssiSeries(List(window.seconds / window.bucketSeconds) { null }, null)
        return RssiSeries(bucketed(d, nowMs, window.seconds, window.bucketSeconds), ((nowMs - d.firstSeenMs) / 1000).toInt().coerceAtLeast(0))
    }

    private fun bucketed(d: Device, nowMs: Long, windowSec: Int, bucketSec: Int): List<Int?> {
        val bucketer = RssiBucketer(nowMs / 1000, windowSec, bucketSec)
        d.ring.forEach { second, max -> bucketer.add(second, max) }
        return bucketer.buckets()
    }

    fun variants(addr: String): List<AdVariantState> {
        val d = devices[addr] ?: return emptyList()
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        // The map is access-ordered (for eviction); the table is shown in the order the variants first appeared.
        return d.variants.values.sortedWith(compareBy<Variant> { it.firstMs }.thenBy { it.key.hash }).map { v ->
            AdVariantState(
                id = "v${v.key.flags}-${v.key.hash}-${v.key.size}",
                pduType = pduType(v.legacy, v.connectable),
                phy = phyLabel(v.legacy, v.primaryPhy, v.secondaryPhy),
                sid = v.sid,
                count = v.count,
                firstSeen = time.format(Instant.ofEpochMilli(v.firstMs)),
                lastSeen = time.format(Instant.ofEpochMilli(v.lastMs)),
                rssiMin = if (v.min == Int.MAX_VALUE) -100 else v.min,
                rssiMax = if (v.max == Int.MIN_VALUE) -100 else v.max,
                structures = v.structures,
            )
        }
    }

    companion object {
        const val MAX_BUCKETS = 900
        const val INITIAL_BUCKETS = 16
        const val MAX_VARIANTS = 24
        const val SPARK_WINDOW_SEC = 60
        const val SPARK_BUCKET_SEC = 2
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
